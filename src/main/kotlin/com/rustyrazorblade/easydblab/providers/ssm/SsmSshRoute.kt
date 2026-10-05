package com.rustyrazorblade.easydblab.providers.ssm

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.SshEndpoint
import com.rustyrazorblade.easydblab.providers.ssh.SshRoute
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * The `ssm` SSH transport: every SSH connection travels through AWS Systems Manager Session
 * Manager, so no inbound port on the node has to be reachable from this machine.
 *
 * OpenSSH gets an `AWS-StartSSHSession` `ProxyCommand` per host. The in-process client (Apache
 * MINA SSHD) cannot run a `ProxyCommand`, so it dials a loopback port that an
 * `AWS-StartPortForwardingSession` process forwards to the node's sshd. There is one forwarding
 * process per instance, kept for the life of the JVM. A forward whose process has died, for
 * example after an SSM idle timeout, is replaced on the next request, so the SSH provider's
 * existing stale-session reconnect recovers without help.
 *
 * A forward that fails to start throws [IOException]. `up`'s SSH-readiness retry retries that
 * like any other node not yet accepting SSH, which covers the window before a fresh node's SSM
 * agent registers.
 *
 * Forwarding processes are stopped by [close] and by a JVM shutdown hook, because nothing stops
 * the SSH provider explicitly when a command ends. Stopping a forward also stops its descendants:
 * the AWS CLI hands each session to a `session-manager-plugin` child, which would otherwise
 * outlive it.
 *
 * @param commands builds the `start-session` command lines
 * @param sshPort the port sshd listens on, on every node
 * @param readyTimeout how long a new forwarding session gets to report ready
 * @param freeLocalPort picks the loopback port for a new forwarding session
 */
class SsmSshRoute(
    private val commands: SsmSessionCommandBuilder,
    private val sshPort: Int,
    private val readyTimeout: Duration = Duration.ofSeconds(Constants.Ssm.PORT_FORWARD_READY_TIMEOUT_SECONDS),
    private val freeLocalPort: () -> Int = ::ephemeralLocalPort,
) : SshRoute {
    private class Forward(
        val process: Process,
        val localPort: Int,
    )

    /** Live forwards, keyed by instance ID. */
    private val forwards = ConcurrentHashMap<String, Forward>()

    // Per-instance locks let forwards to different nodes start concurrently, while two threads
    // asking for the same node share one session.
    private val startLocks = ConcurrentHashMap<String, Any>()

    private val shutdownHook = thread(start = false, name = "ssm-ssh-route-shutdown") { stopAll() }

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    override fun endpoint(host: Host): SshEndpoint = SshEndpoint(Constants.Ssm.LOCAL_FORWARD_ADDRESS, localPortFor(instanceIdOf(host)))

    override fun proxyCommand(host: Host): String = commands.sshSession(instanceIdOf(host)).toShellCommand()

    override fun close() {
        stopAll()
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // The JVM is already shutting down, and the hook is running or has run.
        }
    }

    /**
     * A host without an instance ID cannot be targeted. This fails rather than falling back to a
     * direct connection, which on a network that needs SSM would only fail later and less clearly.
     */
    private fun instanceIdOf(host: Host): String {
        require(host.instanceId.isNotBlank()) {
            "Host ${host.alias} has no EC2 instance ID, so it cannot be reached over SSM Session Manager"
        }
        return host.instanceId
    }

    private fun localPortFor(instanceId: String): Int {
        synchronized(startLocks.computeIfAbsent(instanceId) { Any() }) {
            forwards[instanceId]?.takeIf { it.process.isAlive }?.let { return it.localPort }
            return start(instanceId).also { forwards[instanceId] = it }.localPort
        }
    }

    private fun start(instanceId: String): Forward {
        val localPort = freeLocalPort()
        val command = commands.portForwardSession(instanceId, sshPort, localPort)
        log.info { "Starting SSM port forward ${Constants.Ssm.LOCAL_FORWARD_ADDRESS}:$localPort -> $instanceId:$sshPort" }

        // stdin stays an open, unwritten pipe: a port-forwarding session must not see EOF on it.
        val process =
            ProcessBuilder(command.argv)
                .redirectErrorStream(true)
                .apply { environment().putAll(command.environment) }
                .start()

        val transcript = Transcript(Constants.Ssm.TRANSCRIPT_MAX_LINES)
        val ready = CompletableFuture<Unit>()
        drainOutput(process, instanceId, transcript, ready)

        try {
            ready.get(readyTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            stop(listOf(process))
            throw IOException(
                "SSM port forward to $instanceId:$sshPort did not become ready within ${readyTimeout.toMillis()} ms. " +
                    "Session Manager plugin output:\n${transcript.text()}",
                e,
            )
        } catch (e: ExecutionException) {
            stop(listOf(process))
            throw e.cause as? IOException ?: IOException(e.cause)
        }
        return Forward(process, localPort)
    }

    /**
     * Reads the session's output for its whole life, completing [ready] when the plugin reports it
     * is listening, or failing it if the process exits first. Draining continues after the session
     * is ready, so the plugin never blocks writing a line about an accepted connection.
     */
    private fun drainOutput(
        process: Process,
        instanceId: String,
        transcript: Transcript,
        ready: CompletableFuture<Unit>,
    ) {
        thread(isDaemon = true, name = "ssm-port-forward-$instanceId") {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        transcript.add(line)
                        log.debug { "[ssm $instanceId] $line" }
                        if (line.contains(Constants.Ssm.PORT_FORWARD_READY_MARKER)) ready.complete(Unit)
                    }
                }
            } catch (e: IOException) {
                log.debug(e) { "Stopped reading SSM port forward output for $instanceId" }
            }
            val exitCode = runCatching { process.waitFor() }.getOrNull()
            ready.completeExceptionally(
                IOException(
                    "SSM port forward to $instanceId:$sshPort exited (exit code $exitCode) before it was ready. " +
                        "Session Manager plugin output:\n${transcript.text()}",
                ),
            )
        }
    }

    private fun stopAll() {
        stop(forwards.values.map { it.process })
        forwards.clear()
    }

    /** Signals every process and its descendants at once, then waits on one shared deadline before killing stragglers. */
    private fun stop(processes: List<Process>) {
        val handles = processes.flatMap { it.descendants().toList() + it.toHandle() }
        handles.forEach { it.destroy() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Constants.Ssm.PROCESS_STOP_GRACE_SECONDS)
        handles.forEach { handle ->
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
            try {
                handle.onExit().get(remaining, TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                handle.destroyForcibly()
            } catch (_: ExecutionException) {
                handle.destroyForcibly()
            }
        }
    }

    /** The most recent lines of a session's output, kept for error messages. */
    private class Transcript(
        private val maxLines: Int,
    ) {
        private val lines = ArrayDeque<String>()

        @Synchronized
        fun add(line: String) {
            lines.addLast(line)
            if (lines.size > maxLines) lines.removeFirst()
        }

        @Synchronized
        fun text(): String = lines.joinToString("\n").ifEmpty { "(no output)" }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}

/** An OS-assigned free port on the loopback interface. Another process could take it before it is used, which is an accepted risk. */
private fun ephemeralLocalPort(): Int = ServerSocket(0).use { it.localPort }

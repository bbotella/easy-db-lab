package com.rustyrazorblade.easydblab.providers.ssm

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.SshEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [SsmSshRoute] against a stub `aws` script, so the real port-forward handling
 * (readiness detection, failure surfacing, reuse, teardown) runs without AWS or the Session
 * Manager plugin.
 *
 * The stub records each invocation's PID and arguments next to itself and behaves according to a
 * `mode` file: `ready` prints the plugin's real readiness output then idles, `fail` prints a
 * Session Manager error and exits 254, `silent` idles without ever reporting ready.
 */
internal class SsmSshRouteTest {
    @TempDir
    lateinit var stubDir: File

    private val routes = mutableListOf<SsmSshRoute>()
    private val nextPort = AtomicInteger(41000)

    @BeforeEach
    fun writeStub() {
        File(stubDir, "aws").apply {
            writeText(
                """
                |#!/bin/sh
                |dir=${'$'}(dirname "${'$'}0")
                |echo "${'$'}${'$'}" >> "${'$'}dir/pids"
                |echo "${'$'}@" >> "${'$'}dir/args"
                |case "${'$'}(cat "${'$'}dir/mode")" in
                |  ready)
                |    echo "Starting session with SessionId: test-0123"
                |    echo "Port opened for sessionId test-0123."
                |    echo "Waiting for connections..."
                |    exec sleep 300 ;;
                |  fail)
                |    echo "An error occurred (TargetNotConnected) when calling the StartSession operation: i-test is not connected."
                |    exit 254 ;;
                |  silent)
                |    exec sleep 300 ;;
                |esac
                |
                """.trimMargin(),
            )
            setExecutable(true)
        }
        mode("ready")
    }

    @AfterEach
    fun closeRoutes() {
        routes.forEach { it.close() }
    }

    @Test
    fun `dials the loopback port once the plugin reports it is listening`() {
        val endpoint = route().endpoint(host("i-0abc"))

        assertThat(endpoint).isEqualTo(SshEndpoint("127.0.0.1", 41000))
        assertThat(File(stubDir, "args").readText()).contains("--target i-0abc", "portNumber=22,localPortNumber=41000")
    }

    @Test
    fun `reuses the live forward for the same instance`() {
        val route = route()

        val first = route.endpoint(host("i-0abc"))
        val second = route.endpoint(host("i-0abc"))

        assertThat(second).isEqualTo(first)
        assertThat(pids()).hasSize(1)
    }

    @Test
    fun `starts a separate forward for each instance`() {
        val route = route()

        val first = route.endpoint(host("i-0abc"))
        val second = route.endpoint(host("i-0def"))

        assertThat(second.port).isNotEqualTo(first.port)
        assertThat(pids()).hasSize(2)
    }

    @Test
    fun `replaces a forward whose process has died`() {
        val route = route()
        route.endpoint(host("i-0abc"))
        ProcessHandle
            .of(pids().single())
            .get()
            .also { it.destroy() }
            .onExit()
            .get(5, TimeUnit.SECONDS)

        val endpoint = route.endpoint(host("i-0abc"))

        assertThat(endpoint.port).isEqualTo(41001)
        assertThat(pids()).hasSize(2)
    }

    @Test
    fun `a session that exits before it is ready fails with the plugin output`() {
        mode("fail")

        assertThatThrownBy { route().endpoint(host("i-0abc")) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("exit code 254")
            .hasMessageContaining("TargetNotConnected")
    }

    @Test
    fun `a session that never reports ready is killed after the timeout`() {
        mode("silent")

        assertThatThrownBy { route(readyTimeout = Duration.ofMillis(500)).endpoint(host("i-0abc")) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("did not become ready")
        assertThat(isAlive(pids().single())).isFalse()
    }

    @Test
    fun `a failed start is not cached, so the next request tries again`() {
        val route = route()
        mode("fail")
        assertThatThrownBy { route.endpoint(host("i-0abc")) }.isInstanceOf(IOException::class.java)

        mode("ready")
        val endpoint = route.endpoint(host("i-0abc"))

        assertThat(endpoint.port).isEqualTo(41001)
    }

    @Test
    fun `close terminates every forwarding process`() {
        val route = route()
        route.endpoint(host("i-0abc"))
        route.endpoint(host("i-0def"))

        route.close()

        assertThat(pids()).hasSize(2).noneMatch { isAlive(it) }
    }

    @Test
    fun `a host with no instance ID is refused instead of being dialed directly`() {
        val route = route()

        assertThatThrownBy { route.endpoint(host("")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("db0")
        assertThatThrownBy { route.proxyCommand(host("")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("db0")
        assertThat(pids()).isEmpty()
    }

    private fun route(readyTimeout: Duration = Duration.ofSeconds(10)): SsmSshRoute {
        val commands =
            SsmSessionCommandBuilder(
                "us-west-2",
                { SsmCliCredentials.NamedProfile("lab") },
                awsExecutable = File(stubDir, "aws").absolutePath,
            )
        return SsmSshRoute(commands, sshPort = 22, readyTimeout = readyTimeout) { nextPort.getAndIncrement() }.also { routes.add(it) }
    }

    private fun host(instanceId: String) =
        Host(public = "54.1.2.3", private = "10.0.0.7", alias = "db0", availabilityZone = "a", instanceId = instanceId)

    private fun mode(value: String) = File(stubDir, "mode").writeText(value)

    private fun pids(): List<Long> =
        File(stubDir, "pids")
            .takeIf { it.exists() }
            ?.readLines()
            ?.filter { it.isNotBlank() }
            ?.map { it.trim().toLong() }
            .orEmpty()

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
}

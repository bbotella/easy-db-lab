package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.Host

/** The address and port the in-process SSH client dials to reach a host's sshd. */
data class SshEndpoint(
    val address: String,
    val port: Int,
)

/**
 * How this machine reaches a cluster node's sshd: one implementation per SSH transport.
 *
 * Both SSH clients take their route from here, so the transport is decided once, in Koin, rather
 * than separately by each client. The in-process client dials [endpoint]. The OpenSSH paths (the
 * SOCKS tunnel and the `env.sh` helpers) use the generated `sshConfig`, which carries
 * [proxyCommand] for each host.
 */
interface SshRoute : AutoCloseable {
    /** Where the in-process client connects for [host]. */
    fun endpoint(host: Host): SshEndpoint

    /** The ssh_config `ProxyCommand` for [host], or null when ssh should dial the host's `Hostname` itself. */
    fun proxyCommand(host: Host): String?

    /** Releases anything the route started to make hosts reachable. */
    override fun close() = Unit
}

/** The `direct` transport: dial the node's public IP. */
class DirectSshRoute(
    private val sshPort: Int,
) : SshRoute {
    override fun endpoint(host: Host): SshEndpoint = SshEndpoint(host.public, sshPort)

    override fun proxyCommand(host: Host): String? = null
}

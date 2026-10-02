package com.shilapi.xcertplay.network

import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Binds the AirPlay control listener, falling back when the preferred port is already taken.
 *
 * Some head units ship a factory CarPlay daemon that permanently listens on the default AirPlay
 * port (7000) on every interface, so binding DiPlay's listener fails with EADDRINUSE. The bound
 * port is advertised to the iPhone through Bonjour and iAP2, so any free port works.
 */
object AirPlayPortSelector {
    /** Ports tried, in order, after the preferred port; an ephemeral port is the last resort. */
    val FALLBACK_PORTS: IntRange = 7001..7010

    fun bind(
        address: InetAddress,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        onFallback: (busyPort: Int, boundPort: Int) -> Unit = { _, _ -> },
    ): ServerSocket {
        tryBind(address, preferredPort)?.let { return it }
        for (port in fallbackPorts) {
            if (port == preferredPort) continue
            tryBind(address, port)?.let { server ->
                onFallback(preferredPort, server.localPort)
                return server
            }
        }
        val server = ServerSocket()
        try {
            server.bind(InetSocketAddress(address, 0))
        } catch (error: Exception) {
            server.close()
            throw error
        }
        onFallback(preferredPort, server.localPort)
        return server
    }

    private fun tryBind(address: InetAddress, port: Int): ServerSocket? {
        val server = ServerSocket()
        return try {
            server.bind(InetSocketAddress(address, port))
            server
        } catch (_: BindException) {
            server.close()
            null
        }
    }
}

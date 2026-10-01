package com.shilapi.xcertplay.network

import java.net.Socket
import jdk.net.ExtendedSocketOptions

internal object TcpLiveness {
    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        socket.keepAlive = true
        try {
            socket.setOption(ExtendedSocketOptions.TCP_KEEPIDLE,10)
            socket.setOption(ExtendedSocketOptions.TCP_KEEPINTERVAL,3)
            socket.setOption(ExtendedSocketOptions.TCP_KEEPCOUNT,3)
            diagnostic("Desktop TCP keepalive configured")
        } catch (_: Exception) { diagnostic("Desktop TCP keepalive uses system defaults") }
    }
}

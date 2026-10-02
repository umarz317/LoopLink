package com.shilapi.xcertplay.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test

class AirPlayPortSelectorTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun bindsPreferredPortWhenFree() {
        val preferred = freePort()
        var fallback: Pair<Int, Int>? = null
        AirPlayPortSelector.bind(loopback, preferred, emptyList()) { busy, bound -> fallback = busy to bound }.use {
            assertEquals(preferred, it.localPort)
        }
        assertNull(fallback)
    }

    @Test fun fallsBackWhenAnotherListenerOwnsTheWildcardPort() {
        // A factory daemon listening on 0.0.0.0 blocks a later bind to a specific address on that port.
        ServerSocket().use { factory ->
            factory.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            val busy = factory.localPort
            val alternative = freePort()
            var fallback: Pair<Int, Int>? = null
            AirPlayPortSelector.bind(loopback, busy, listOf(busy, alternative)) { b, bound -> fallback = b to bound }.use {
                assertEquals(alternative, it.localPort)
                assertEquals(busy to alternative, fallback)
            }
        }
    }

    @Test fun usesEphemeralPortWhenAllCandidatesAreBusy() {
        ServerSocket(0, 50, loopback).use { first ->
            ServerSocket(0, 50, loopback).use { second ->
                AirPlayPortSelector.bind(loopback, first.localPort, listOf(second.localPort)).use {
                    assertNotEquals(first.localPort, it.localPort)
                    assertNotEquals(second.localPort, it.localPort)
                    assertTrue(it.localPort in 1..65535)
                }
            }
        }
    }

    private fun freePort(): Int = ServerSocket(0, 50, loopback).use { it.localPort }
}

package com.shilapi.xcertplay.transport

/** An unlimited drive still has a bounded authentication handshake and bounded I/O polls. */
internal class Iap2ControlDeadline(
    private val timeoutMillis: Long,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val unlimited = timeoutMillis == Long.MAX_VALUE
    private val startedNanos = clockNanos()
    private var authenticated = false
    fun authenticated() { authenticated = true }
    fun remainingMillis(): Long {
        if (unlimited && authenticated) return MAX_POLL_MILLIS
        val budget = if (unlimited) HANDSHAKE_MILLIS else timeoutMillis
        val elapsedNanos = (clockNanos() - startedNanos).coerceAtLeast(0)
        val remainingNanos = budget * 1_000_000 - elapsedNanos
        if (remainingNanos <= 0) return 0
        return ((remainingNanos + 999_999) / 1_000_000).coerceAtMost(MAX_POLL_MILLIS)
    }
    companion object {
        const val HANDSHAKE_MILLIS = 60_000L
        const val MAX_POLL_MILLIS = 30_000L
    }
}

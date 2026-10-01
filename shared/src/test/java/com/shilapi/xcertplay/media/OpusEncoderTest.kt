package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class OpusEncoderTest {
    @Test fun missingSystemEncoderFallsBackToRealSoftwareOpusAtEachNegotiatedRate() {
        for (rate in listOf(16_000, 24_000, 48_000)) {
            OpusEncoder(rate, 48_000, systemFactory = { _, _ -> error("No hardware encoder") }).use { encoder ->
                assertEquals("software", encoder.backendName)
                val packets = encoder.encode(ByteArray(rate / 50 * 2))
                assertEquals(1, packets.size)
                assertTrue(packets.single().isNotEmpty())
            }
        }
    }

    @Test fun stalledSystemEncoderReplaysPendingFramesThroughSoftware() {
        var closed = false
        val stalled = object : OpusBackend {
            override val name = "system"
            override fun encode(pcm: ByteArray) = emptyList<ByteArray>()
            override fun close() { closed = true }
        }
        OpusEncoder(16_000, 48_000, systemFactory = { _, _ -> stalled }).use { encoder ->
            repeat(3) { assertTrue(encoder.encode(ByteArray(640)).isEmpty()) }
            assertEquals(4, encoder.encode(ByteArray(640)).size)
            assertTrue(closed)
            assertEquals("software", encoder.backendName)
        }
    }
}

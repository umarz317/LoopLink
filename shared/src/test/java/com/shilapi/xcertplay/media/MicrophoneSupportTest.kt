package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.microphoneBindAddress
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class MicrophoneSupportTest {
    @Test fun overlappingRecordersRestoreAudioModeOnlyAfterTheLastRecorderCloses() {
        var mode = 0
        val leases = AudioModeLeaseManager({ mode }, { mode = it }, 3)
        val first = leases.acquire()
        val second = leases.acquire()
        assertEquals(3, mode)
        first.close()
        first.close()
        assertEquals(3, mode)
        second.close()
        assertEquals(0, mode)
    }

    @Test fun closingARecorderDoesNotUndoAnotherAppsAudioModeChange() {
        var mode = 0
        val lease = AudioModeLeaseManager({ mode }, { mode = it }, 3).acquire()
        mode = 2
        lease.close()
        assertEquals(2, mode)
    }

    @Test fun resamplingIsIndependentOfReadBoundariesIncludingPartialSamples() {
        val pcm = ByteArray(640) { (it * 17).toByte() }
        for (rate in listOf(16_000, 24_000, 48_000)) {
            val whole = PcmMonoResampler(16_000, rate).convert(pcm, 0, pcm.size)
            val resampler = PcmMonoResampler(16_000, rate)
            var chunked = byteArrayOf()
            var offset = 0
            while (offset < pcm.size) {
                val count = minOf(13, pcm.size - offset)
                chunked += resampler.convert(pcm, offset, count)
                offset += count
            }
            assertArrayEquals(whole, chunked)
            if (rate == 16_000) assertArrayEquals(pcm, whole)
        }
    }

    @Test fun microphoneSocketUsesThePhonesAddressFamily() {
        assertEquals("0.0.0.0", microphoneBindAddress(InetAddress.getByName("192.168.2.2")).hostAddress)
        assertTrue(microphoneBindAddress(InetAddress.getByName("fe80::1")).isAnyLocalAddress)
    }
}

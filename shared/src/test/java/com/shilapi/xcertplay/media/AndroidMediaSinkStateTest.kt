package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidMediaSinkStateTest {
    @Test fun framesWithoutCodecConfigurationRequestAKeyframe() {
        val requested = CountDownLatch(1)
        val sink = AndroidMediaSink()
        sink.setVideoRecoveryHandler(110) { requested.countDown() }
        try {
            sink.onVideoFrame(110, byteArrayOf(0, 0, 0, 1, 0x26, 1))
            assertTrue("Missing codec config must request recovery", requested.await(2, TimeUnit.SECONDS))
        } finally {
            sink.close()
        }
    }
    @Test fun recreatingTheScreenRestoresItsActiveVideoState() {
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        sink.onScreenStreamActive(111, false)
        val events = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> events.add(type to active) }
        assertEquals(listOf(110 to true), events)
        sink.close()
        assertEquals(listOf(110 to true, 110 to false), events)
        val afterClose = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> afterClose.add(type to active) }
        assertTrue(afterClose.isEmpty())
    }
}

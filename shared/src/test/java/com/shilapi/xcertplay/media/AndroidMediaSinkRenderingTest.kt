package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AndroidMediaSinkRenderingTest {
    @Test fun onlyRenderedVideoIsReplayedAndDecoderRecoveryClearsIt() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = AndroidMediaSink(onScreenRenderingChanged = { type, rendering -> events += type to rendering })
        try {
            sink.onScreenStreamActive(110, true)
            assertTrue(events.isEmpty())
            val decoder = decoder(sink)
            rendered(sink, decoder, true)
            assertEquals(listOf(110 to true), events)
            val adopted = mutableListOf<Pair<Int, Boolean>>()
            sink.setScreenRenderingChangedListener { type, rendering -> adopted += type to rendering }
            assertEquals(listOf(110 to true), adopted)

            // Exercise the decoder's actual recovery notification without depending on vendor codecs.
            decoder.javaClass.getDeclaredField("renderedFrameLogged").apply { isAccessible = true }.set(decoder, true)
            decoder.javaClass.getDeclaredMethod("releaseDecoder").apply { isAccessible = true }.invoke(decoder)
            assertEquals(listOf(110 to true, 110 to false), adopted)
            val afterRecovery = mutableListOf<Pair<Int, Boolean>>()
            sink.setScreenRenderingChangedListener { type, rendering -> afterRecovery += type to rendering }
            assertTrue(afterRecovery.isEmpty())
        } finally { sink.close() }
    }

    @Test fun replacedDecoderAndClosedSinkCannotReplayStaleRendering() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = AndroidMediaSink(onScreenRenderingChanged = { type, rendering -> events += type to rendering })
        try {
            sink.onScreenStreamActive(110, true)
            val old = decoder(sink)
            rendered(sink, old, true)
            sink.onScreenStreamActive(110, false)
            sink.onScreenStreamActive(110, true)
            val next = decoder(sink)
            rendered(sink, next, true)
            rendered(sink, old, false)
            assertEquals(listOf(110 to true, 110 to false, 110 to true), events)
            sink.close()
            assertEquals(110 to false, events.last())
            rendered(sink, next, true)
            val afterClose = mutableListOf<Pair<Int, Boolean>>()
            sink.setScreenRenderingChangedListener { type, rendering -> afterClose += type to rendering }
            assertTrue(afterClose.isEmpty())
        } finally { sink.close() }
    }

    private fun decoder(sink: AndroidMediaSink): Any = sink.javaClass.getDeclaredMethod("videoDecoder", Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(sink, 110)!!

    private fun rendered(sink: AndroidMediaSink, decoder: Any, rendering: Boolean) {
        sink.javaClass.getDeclaredMethod("onVideoRenderingChanged", Int::class.javaPrimitiveType, decoder.javaClass, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(sink, 110, decoder, rendering)
    }
}

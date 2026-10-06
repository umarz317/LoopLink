package com.shilapi.xcertplay.airplay

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMediaButtonTest {
    @Test
    fun steeringWheelKeysMapToCarPlayMediaPresses() {
        assertEquals(CarPlayMediaButton.NEXT, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        // A head unit may rewrite its play/pause key into PLAY or PAUSE; both toggle.
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_HEADSETHOOK))
    }

    @Test
    fun bydSteeringWheelKeysAreHandled() {
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE))
        assertTrue(CarPlayMediaButton.opensSiri(CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE))
        assertTrue(CarPlayMediaButton.opensSiri(CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG))
    }

    @Test
    fun theVoiceKeyOpensSiri() {
        assertTrue(CarPlayMediaButton.opensSiri(KeyEvent.KEYCODE_VOICE_ASSIST))
        assertFalse(CarPlayMediaButton.opensSiri(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_VOICE_ASSIST))
    }

    @Test
    fun otherKeysAreLeftToTheSystem() {
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_STOP))
    }

    @Test
    fun indicesMatchTheAdvertisedMediaHidReport() {
        // Media report usages: 0 none, 1 play, 2 pause, 3 play/pause, 4 next, 5 previous.
        assertEquals(3, CarPlayMediaButton.PLAY_PAUSE)
        assertEquals(4, CarPlayMediaButton.NEXT)
        assertEquals(5, CarPlayMediaButton.PREVIOUS)
    }
}

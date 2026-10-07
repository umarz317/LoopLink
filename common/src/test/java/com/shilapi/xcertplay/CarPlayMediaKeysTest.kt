package com.shilapi.xcertplay

import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayMediaKeysTest {
    @Test fun disablingAudioFocusKeepsMediaKeysWithoutClaimingFocus() = withController { controller ->
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveAudioFocusEnabled(context, false)
        CarPlayMediaKeys.attach(context, controller, AirPlayPersistence.loadAudioFocusEnabled(context))
        CarPlayMediaKeys.onMediaAudioChanged(true)
        idle()
        assertNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest)
        val session = CarPlayMediaKeys::class.java.getDeclaredField("session")
            .apply { isAccessible = true }.get(CarPlayMediaKeys) as MediaSession
        assertTrue(session.isActive)

        CarPlayMediaKeys.onMediaAudioChanged(false)
        CarPlayMediaKeys.onIphonePlaying(true)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        idle()
        assertNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest)
        assertTrue(session.isActive)
    }

    @Test fun enabledAudioFocusCanBeRegainedAndDisabledOnReconnect() = withController { controller ->
        val context = RuntimeEnvironment.getApplication()
        val audio = shadowOf(context.getSystemService(AudioManager::class.java))
        CarPlayMediaKeys.attach(context, controller, true)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        idle()
        val first = audio.lastAudioFocusRequest
        assertNotNull(first)
        first.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        CarPlayMediaKeys.onIphonePlaying(true)
        idle()
        assertNotSame(first, audio.lastAudioFocusRequest)

        CarPlayMediaKeys.attach(context, controller, false)
        assertNotNull(audio.lastAbandonedAudioFocusRequest)
        val last = audio.lastAudioFocusRequest
        CarPlayMediaKeys.onMediaAudioChanged(true)
        CarPlayMediaKeys.onIphonePlaying(true)
        idle()
        assertSame(last, audio.lastAudioFocusRequest)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun withController(test: (CarPlayController) -> Unit) {
        val controller = CarPlayController(
            RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig("test", "unit", "unit", "123", "1", "1", 3),
            ),
            AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01", sourceVersion = "1",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
        )
        try { test(controller) } finally {
            CarPlayMediaKeys.detach(controller)
            controller.close()
            controller.awaitClosed(2000)
            idle()
        }
    }
}

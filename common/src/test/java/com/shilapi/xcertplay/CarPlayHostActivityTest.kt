package com.shilapi.xcertplay

import android.os.Looper
import android.view.View
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayHostActivityTest {
    // Attach without onCreate: connection UI can be tested without starting USB/Bluetooth.
    private fun host(): CarPlayHostActivity =
        Robolectric.buildActivity(CarPlayHostActivity::class.java).get()

    @Test fun returningFromSettingsReloadsBothSystemBarPreferences() {
        val activity = host()
        AirPlayPersistence.saveHideTopBar(activity, true)
        AirPlayPersistence.saveHideBottomBar(activity, true)
        invoke(activity, "loadPersistedSettings")
        for (hidden in listOf(false, true)) {
            AirPlayPersistence.saveHideTopBar(activity, hidden)
            AirPlayPersistence.saveHideBottomBar(activity, hidden)
            invoke(activity, "onResume")
            assertEquals(hidden, field(activity, "hideTopBar"))
            assertEquals(hidden, field(activity, "hideBottomBar"))
        }
    }

    @Test fun normalHotspotProgressNeverShowsAFailure() {
        val activity = host()
        val expected = activity.getString(R.string.getting_carplay_ready)
        assertEquals(expected, stage(activity, CarPlayStatus.StartingHotspot))
        assertEquals(expected, stage(activity, CarPlayStatus.HotspotReady("test", "5 GHz", 36, "", "", "Wi-Fi Direct")))
        assertEquals(
            activity.getString(R.string.the_head_unit_couldn_t_start_carplay_wi_fi_check_wi_fi_and),
            stage(activity, CarPlayStatus.Failed("createGroup failed")),
        )
    }

    @Test @Config(qualifiers = "es") fun translatedStagesKeepTheirInstructions() {
        val activity = host()
        assertEquals(activity.getString(R.string.connect_your_iphone_with_a_usb_cable), stage(activity, CarPlayStatus.WaitingForIphone))
        assertEquals(activity.getString(R.string.connect_your_iphone_with_a_usb_cable), stage(activity, CarPlayStatus.DiscoveringIphone))
        assertEquals(activity.getString(R.string.looking_for_your_paired_iphone), stage(activity, CarPlayStatus.WaitingForPairedIphone))
        assertEquals(activity.getString(R.string.allow_the_connection_permission_to_continue), stage(activity, CarPlayStatus.RequestingIphonePermission))
        assertEquals(activity.getString(R.string.reconnecting_to_your_iphone), stage(activity, CarPlayStatus.ControlEnded))
        assertEquals(activity.getString(R.string.getting_carplay_ready), stage(activity, CarPlayStatus.StartingHotspot))
        assertEquals(
            activity.getString(R.string.a_previous_wi_fi_direct_connection_is_still_running_reset),
            stage(activity, CarPlayStatus.Failed("reset required", wifiResetRequired = true)),
        )
    }

    @Test fun connectionPanelStaysUntilRenderingAndReturnsOnRecovery() {
        val activity = host()
        activity.setContentView(invoke(activity, "buildContentView") as View)
        val panel = field(activity, "connectionPanel") as View
        val sink = activity.javaClass.getDeclaredMethod(
            "createMediaSink", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(activity, 800, 480, 0) as AndroidMediaSink
        try {
            sink.onScreenStreamActive(110, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.VISIBLE, panel.visibility)
            rendering(activity, 0, true)
            assertEquals(View.GONE, panel.visibility)
            rendering(activity, 0, false)
            assertEquals(View.VISIBLE, panel.visibility)
            rendering(activity, -1, true)
            assertEquals("An old session must not hide the current status", View.VISIBLE, panel.visibility)
        } finally {
            sink.close()
        }
    }

    private fun stage(activity: CarPlayHostActivity, status: CarPlayStatus): String {
        activity.setContentView(invoke(activity, "buildContentView") as View)
        @Suppress("UNCHECKED_CAST")
        val report = activity.javaClass.getDeclaredMethod("createStatusReporter", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 0) as (CarPlayStatus) -> Unit
        report(status)
        return (field(activity, "stageStatusView") as TextView).text.toString()
    }

    private fun rendering(activity: CarPlayHostActivity, generation: Int, rendering: Boolean) {
        activity.javaClass.getDeclaredMethod(
            "onScreenRenderingChanged", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(activity, generation, 110, rendering)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun invoke(activity: CarPlayHostActivity, name: String): Any? =
        activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun field(activity: CarPlayHostActivity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
}

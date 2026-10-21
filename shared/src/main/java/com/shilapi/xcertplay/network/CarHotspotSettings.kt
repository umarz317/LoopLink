package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** The driver's opt-in for [CarHotspotTethering]. */
object CarHotspotSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay_car_hotspot", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("auto_enable", false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("auto_enable", enabled).apply()

    /** Whether the head unit's ADB last accepted LoopLink's key; only Android 11+ need it. */
    fun adbApproved(context: Context): Boolean = prefs(context).getBoolean("adb_approved", false)

    fun setAdbApproved(context: Context, approved: Boolean) =
        prefs(context).edit().putBoolean("adb_approved", approved).apply()

    fun shouldEnable(context: Context, mode: WirelessHotspotMode): Boolean =
        mode == WirelessHotspotMode.MANUAL && enabled(context) && CarHotspotTethering.supported()

    /** Whether LoopLink will turn the hotspot on itself, so an "off" report needs no warning. */
    fun willEnable(context: Context, mode: WirelessHotspotMode): Boolean =
        shouldEnable(context, mode) &&
            if (CarHotspotTethering.appStartSupported()) CarHotspotTethering.permitted(context) else adbApproved(context)
}

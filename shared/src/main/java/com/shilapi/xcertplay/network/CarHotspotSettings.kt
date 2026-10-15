package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** The driver's opt-in for [CarHotspotTethering]. */
object CarHotspotSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay_car_hotspot", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("auto_enable", false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("auto_enable", enabled).apply()

    fun shouldEnable(context: Context, mode: WirelessHotspotMode): Boolean =
        mode == WirelessHotspotMode.MANUAL && enabled(context) && CarHotspotTethering.appStartSupported()

    /** Whether LoopLink will turn the hotspot on itself, so an "off" report needs no warning. */
    fun willEnable(context: Context, mode: WirelessHotspotMode): Boolean =
        shouldEnable(context, mode) && CarHotspotTethering.permitted(context)
}

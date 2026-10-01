package com.shilapi.xcertplay.airplay

/**
 * Display scaling uses tenths so the UI can expose only the supported 0.3x..1.0x steps.
 */
object CarPlayDisplayScale {
    const val MIN_TENTHS = 3
    const val MAX_TENTHS = 10
    const val DEFAULT_TENTHS = MAX_TENTHS

    fun sanitize(tenths: Int): Int = tenths.coerceIn(MIN_TENTHS, MAX_TENTHS)

    fun label(tenths: Int): String {
        val value = sanitize(tenths)
        return "${value / 10}.${value % 10}x"
    }

    fun apply(display: AirPlayDisplayConfig, tenths: Int): AirPlayDisplayConfig {
        val value = sanitize(tenths)
        return display.copy(
            widthPixels = scalePixels(display.widthPixels, value),
            heightPixels = scalePixels(display.heightPixels, value),
        )
    }

    private fun scalePixels(pixels: Int, tenths: Int): Int {
        require(pixels > 0) { "pixels must be positive" }
        val scaled = ((pixels.toLong() * tenths + 5L) / 10L).toInt().coerceAtLeast(1)
        return if (scaled % 2 == 0) scaled else scaled + 1
    }
}

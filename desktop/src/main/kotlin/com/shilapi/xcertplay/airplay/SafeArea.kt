package com.shilapi.xcertplay.airplay

/** A safe-area rectangle in the activity's current pixel coordinate space. */
data class SafeAreaRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top) {
            "Safe area coordinates must describe a non-empty rectangle"
        }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun clampTo(widthPixels: Int, heightPixels: Int): SafeAreaRect {
        require(widthPixels > 0 && heightPixels > 0) { "Activity dimensions must be positive" }
        val clampedLeft = left.coerceIn(0, widthPixels - 1)
        val clampedTop = top.coerceIn(0, heightPixels - 1)
        val clampedRight = right.coerceIn(clampedLeft + 1, widthPixels)
        val clampedBottom = bottom.coerceIn(clampedTop + 1, heightPixels)
        return SafeAreaRect(clampedLeft, clampedTop, clampedRight, clampedBottom)
    }
}

/** Resolves activity-space safe areas into the scaled AirPlay display coordinate space. */
object AirPlaySafeArea {
    fun full(widthPixels: Int, heightPixels: Int): SafeAreaRect {
        require(widthPixels > 0 && heightPixels > 0) { "Activity dimensions must be positive" }
        return SafeAreaRect(0, 0, widthPixels, heightPixels)
    }

    /**
     * Default editor boundaries are one quarter of the activity from each edge, leaving the middle
     * half of the activity as the safe area.
     */
    fun default(widthPixels: Int, heightPixels: Int): SafeAreaRect {
        require(widthPixels > 0 && heightPixels > 0) { "Activity dimensions must be positive" }
        val left = roundFraction(widthPixels, 1, 4).coerceIn(0, widthPixels - 1)
        val top = roundFraction(heightPixels, 1, 4).coerceIn(0, heightPixels - 1)
        val right = roundFraction(widthPixels, 3, 4)
            .coerceIn(left + 1, widthPixels)
        val bottom = roundFraction(heightPixels, 3, 4)
            .coerceIn(top + 1, heightPixels)
        return SafeAreaRect(left, top, right, bottom)
    }

    fun resolve(
        mapping: SafeAreaRect?,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
    ): SafeAreaRect = mapping?.clampTo(activityWidthPixels, activityHeightPixels)
        ?: full(activityWidthPixels, activityHeightPixels)

    fun scaleRect(
        rect: SafeAreaRect,
        sourceWidthPixels: Int,
        sourceHeightPixels: Int,
        targetWidthPixels: Int,
        targetHeightPixels: Int,
    ): SafeAreaRect {
        require(sourceWidthPixels > 0 && sourceHeightPixels > 0) {
            "Source dimensions must be positive"
        }
        require(targetWidthPixels > 0 && targetHeightPixels > 0) {
            "Target dimensions must be positive"
        }
        val clamped = rect.clampTo(sourceWidthPixels, sourceHeightPixels)
        val left = scaleCoordinate(clamped.left, sourceWidthPixels, targetWidthPixels)
            .coerceIn(0, targetWidthPixels - 1)
        val top = scaleCoordinate(clamped.top, sourceHeightPixels, targetHeightPixels)
            .coerceIn(0, targetHeightPixels - 1)
        val right = scaleCoordinate(clamped.right, sourceWidthPixels, targetWidthPixels)
            .coerceIn(left + 1, targetWidthPixels)
        val bottom = scaleCoordinate(clamped.bottom, sourceHeightPixels, targetHeightPixels)
            .coerceIn(top + 1, targetHeightPixels)
        return SafeAreaRect(left, top, right, bottom)
    }

    /**
     * Converts a safe area stored against the activity size into insets for the scaled AirPlay
     * display. Coordinates are normalized by axis, so a stored width of 1900/1920 remains 1900/1920
     * of the negotiated display after the user's display scale has been applied.
     */
    fun toInsets(
        mapping: SafeAreaRect?,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        displayWidthPixels: Int,
        displayHeightPixels: Int,
    ): AirPlayInsets {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        require(displayWidthPixels > 0 && displayHeightPixels > 0) {
            "Display dimensions must be positive"
        }
        val rect = resolve(mapping, activityWidthPixels, activityHeightPixels)
        val left = scaleCoordinate(rect.left, activityWidthPixels, displayWidthPixels)
            .coerceIn(0, displayWidthPixels - 1)
        val top = scaleCoordinate(rect.top, activityHeightPixels, displayHeightPixels)
            .coerceIn(0, displayHeightPixels - 1)
        val right = scaleCoordinate(rect.right, activityWidthPixels, displayWidthPixels)
            .coerceIn(left + 1, displayWidthPixels)
        val bottom = scaleCoordinate(rect.bottom, activityHeightPixels, displayHeightPixels)
            .coerceIn(top + 1, displayHeightPixels)
        val aligned = alignDimensionsToEven(
            SafeAreaRect(left, top, right, bottom),
            displayWidthPixels,
            displayHeightPixels,
        )
        return AirPlayInsets(
            top = aligned.top,
            bottom = displayHeightPixels - aligned.bottom,
            left = aligned.left,
            right = displayWidthPixels - aligned.right,
        )
    }

    private fun alignDimensionsToEven(
        rect: SafeAreaRect,
        widthPixels: Int,
        heightPixels: Int,
    ): SafeAreaRect {
        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom
        if ((right - left) % 2 != 0) {
            if (right < widthPixels) right += 1 else left -= 1
        }
        if ((bottom - top) % 2 != 0) {
            if (bottom < heightPixels) bottom += 1 else top -= 1
        }
        return SafeAreaRect(
            left = left.coerceIn(0, widthPixels - 1),
            top = top.coerceIn(0, heightPixels - 1),
            right = right.coerceIn(left + 1, widthPixels),
            bottom = bottom.coerceIn(top + 1, heightPixels),
        )
    }

    private fun scaleCoordinate(coordinate: Int, source: Int, target: Int): Int =
        ((coordinate.toLong() * target + source / 2L) / source).toInt()

    private fun roundFraction(value: Int, numerator: Int, denominator: Int): Int =
        ((value.toLong() * numerator + denominator / 2L) / denominator).toInt()
}

/** Stable encoding used by the settings store for one activity-size mapping. */
object SafeAreaCodec {
    fun encode(rect: SafeAreaRect): String =
        listOf(rect.left, rect.top, rect.right, rect.bottom).joinToString(",")

    fun decode(value: String?): SafeAreaRect? {
        val parts = value?.split(',') ?: return null
        if (parts.size != 4) return null
        return try {
            SafeAreaRect(
                left = parts[0].toInt(),
                top = parts[1].toInt(),
                right = parts[2].toInt(),
                bottom = parts[3].toInt(),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

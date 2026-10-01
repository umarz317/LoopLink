package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlaySizeTest {
    @Test
    fun `presets map back to themselves`() {
        CarPlaySize.entries.forEach { assertEquals(it, CarPlaySize.fromWidthMillimeters(it.widthMillimeters)) }
    }

    @Test
    fun `older widths snap to the nearest preset`() {
        assertEquals(CarPlaySize.LARGE, CarPlaySize.fromWidthMillimeters(200))
        assertEquals(CarPlaySize.SMALL, CarPlaySize.fromWidthMillimeters(400))
    }

    @Test
    fun `presets fit the reported width range`() {
        CarPlaySize.entries.forEach {
            assertEquals(it.widthMillimeters, AirPlayDisplaySettings.sanitizeWidthPhysicalMm(it.widthMillimeters))
        }
    }
}

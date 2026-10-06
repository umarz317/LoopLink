package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayFailureTest {
    @Test
    fun controllerMessagesBecomeActionableFailures() {
        val expected = mapOf(
            "Failed: Bluetooth is not enabled" to CarPlayFailure.BLUETOOTH_OFF,
            "Failed: Bluetooth adapter is unavailable" to CarPlayFailure.BLUETOOTH_UNAVAILABLE,
            "Failed: Need android.permission.BLUETOOTH_CONNECT permission" to CarPlayFailure.BLUETOOTH_PERMISSION,
            "Failed: The selected iPhone is no longer paired. Choose it again in DiPlay." to CarPlayFailure.IPHONE_UNPAIRED,
            "Failed: Multiple bonded iPhones found and none is currently connected" to CarPlayFailure.IPHONE_AMBIGUOUS,
            "Failed: No unambiguous bonded iPhone found; pair one iPhone and retry" to CarPlayFailure.IPHONE_AMBIGUOUS,
            "Failed: Timed out after 20000ms connecting RFCOMM to AA:BB" to CarPlayFailure.IPHONE_UNREACHABLE,
            "Failed: The car hotspot is off. Turn it on in the car settings and connect again." to CarPlayFailure.CAR_HOTSPOT_OFF,
            "Failed: Manual hotspot SSID is not configured" to CarPlayFailure.HOTSPOT_DETAILS_MISSING,
            "Failed: Could not establish WIFI_P2P hotspot: busy" to CarPlayFailure.WIFI_LINK_FAILED,
            "Failed: iPhone USB permission was denied" to CarPlayFailure.USB_DENIED,
            "Failed: Wireless iAP2 tunnel closed" to CarPlayFailure.LINK_DROPPED,
            "Failed: MFi coprocessor client is unavailable" to CarPlayFailure.SETUP_UNAVAILABLE,
            "Failed: Could not bind the CarPlay AirPlay service" to CarPlayFailure.SERVICE_UNAVAILABLE,
        )
        expected.forEach { (message, failure) -> assertEquals(message, failure, CarPlayFailure.classify(message)) }
    }

    @Test
    fun unknownMessagesAreLeftToTheGenericRetryNotice() {
        assertNull(CarPlayFailure.classify("Failed: something unexpected"))
    }
}

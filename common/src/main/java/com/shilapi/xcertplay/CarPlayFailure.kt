package com.shilapi.xcertplay

/**
 * Connection failures a driver can act on. The controller reports raw technical messages; each
 * recognised one becomes a short instruction instead of "Connection interrupted".
 */
internal enum class CarPlayFailure {
    BLUETOOTH_OFF,
    BLUETOOTH_UNAVAILABLE,
    BLUETOOTH_PERMISSION,
    IPHONE_UNPAIRED,
    IPHONE_AMBIGUOUS,
    IPHONE_UNREACHABLE,
    CAR_HOTSPOT_OFF,
    HOTSPOT_DETAILS_MISSING,
    WIFI_LINK_FAILED,
    LINK_DROPPED,
    USB_DENIED,
    SETUP_UNAVAILABLE,
    SERVICE_UNAVAILABLE,
    ;

    companion object {
        fun classify(message: String): CarPlayFailure? {
            val text = message.lowercase()
            fun has(vararg parts: String) = parts.any { it in text }
            return when {
                has("bluetooth is not enabled", "bluetooth is off", "bluetooth_adapter_off") -> BLUETOOTH_OFF
                has("bluetooth adapter is unavailable") -> BLUETOOTH_UNAVAILABLE
                has("bluetooth_connect") -> BLUETOOTH_PERMISSION
                has("no longer paired") -> IPHONE_UNPAIRED
                has("multiple connected iphones", "multiple bonded iphones", "no unambiguous bonded iphone") -> IPHONE_AMBIGUOUS
                has("rfcomm") -> IPHONE_UNREACHABLE
                has("car hotspot is off") -> CAR_HOTSPOT_OFF
                has("hotspot ssid is not configured") -> HOTSPOT_DETAILS_MISSING
                has("hotspot", "link-local") -> WIFI_LINK_FAILED
                has("usb permission") -> USB_DENIED
                has("control channel closed", "tunnel closed", "tunnel iap2 ready") -> LINK_DROPPED
                has("mfi coprocessor", "mfi usb", "ch341") -> SETUP_UNAVAILABLE
                has("airplay service", "already attached") -> SERVICE_UNAVAILABLE
                else -> null
            }
        }
    }
}

package com.shilapi.xcertplay.airplay

/** Only product/version tokens, never the phone's name, addresses or pairing identifiers. */
object AirPlayPeerDiagnostics {
    private val modelToken = Regex("(?:iPhone|iPad|iPod)[0-9]{1,3},[0-9]{1,3}")
    private val versionToken = Regex("[0-9][0-9A-Za-z._-]{0,39}")

    fun summary(model: String, osVersion: String, sourceVersion: String): String {
        fun version(value: String) = value.takeIf(versionToken::matches) ?: "not_reported"
        return "Phone display peer model=${model.takeIf(modelToken::matches) ?: "not_reported"} " +
            "ios=${version(osVersion)} airplay=${version(sourceVersion)}"
    }
}

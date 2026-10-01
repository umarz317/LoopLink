package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AirPlayPeerDiagnosticsTest {
    @Test fun productAndVersionDetailsAreAvailableWithoutIdentity() {
        assertEquals("Phone display peer model=iPhone11,2 ios=18.7.10 airplay=950.7.1",
            AirPlayPeerDiagnostics.summary("iPhone11,2", "18.7.10", "950.7.1"))
    }

    @Test fun absentOrUnexpectedFieldsCannotLeakPersonalOrMultilineValues() {
        val summary = AirPlayPeerDiagnostics.summary("Jane's iPhone", "26.0\npassword=secret", "")
        assertEquals("Phone display peer model=not_reported ios=not_reported airplay=not_reported", summary)
    }
}

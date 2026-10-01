package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class Iap2ControlDeadlineTest {
    @Test fun anAuthenticatedDriveSurvivesFiveMinutesAndAFullDay() {
        var now = 0L
        val deadline = Iap2ControlDeadline(Long.MAX_VALUE) { now }
        deadline.authenticated()
        for (minutes in listOf(6L, 90L, 25L * 60L)) {
            now = minutes * 60_000_000_000L
            assertEquals(30_000L, deadline.remainingMillis())
        }
    }
    @Test fun unlimitedDriveDoesNotLeaveAnUnauthenticatedHandshakeHanging() {
        var now = 0L
        val deadline = Iap2ControlDeadline(Long.MAX_VALUE) { now }
        now = 59_999_000_000L
        assertEquals(1L, deadline.remainingMillis())
        now = 60_000_000_000L
        assertEquals(0L, deadline.remainingMillis())
    }
    @Test fun explicitFiniteWindowsStillExpireAfterAuthentication() {
        var now = -1_000_000_000L
        val deadline = Iap2ControlDeadline(100) { now }
        deadline.authenticated()
        now += 100_000_000L
        assertEquals(0L, deadline.remainingMillis())
    }
}

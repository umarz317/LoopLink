package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessSessionParameters
import com.shilapi.xcertplay.iap2.trace.Iap2FrameFormatter
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2ProtocolTest {
    @Test
    fun catalogRegistersEveryReferenceEndpointOnce() {
        assertEquals(151, Iap2Endpoints.ALL.size)
        assertEquals(151, Iap2Endpoints.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun framerAcceptsFragmentedAndConcatenatedFrames() {
        val first = Iap2Frame(0x4300, byteArrayOf(0, 4, 0, 0))
        val second = Iap2Frame(0x4301, byteArrayOf(0, 5, 0, 0, 1))
        val bytes = first.encodedFrame() + second.encodedFrame()
        val framer = Iap2CsmFramer()

        assertTrue(framer.offer(bytes.copyOfRange(0, 3)).isEmpty())
        assertEquals(listOf(first, second), framer.offer(bytes.copyOfRange(3, bytes.size)))
    }

    @Test
    fun orderedParameterListPreservesRepeatedAndUnknownIds() {
        val original = listOf(
            Iap2Parameter(0, byteArrayOf(1)),
            Iap2Parameter(0, byteArrayOf(2)),
            Iap2Parameter(0x7fff, byteArrayOf(3, 4)),
        )
        val decoded = Iap2ParameterList.parse(Iap2ParameterList.of(original).encode())

        assertEquals(listOf(0, 0, 0x7fff), decoded.asList().map { it.id })
        assertArrayEquals(byteArrayOf(1), decoded.all(0)[0].payload)
        assertArrayEquals(byteArrayOf(2), decoded.all(0)[1].payload)
        assertArrayEquals(byteArrayOf(3, 4), decoded.first(0x7fff)?.payload)
    }

    @Test
    fun genericBuilderCanConstructNestedUnmodeledEndpointBody() {
        val frame = Iap2Messages.buildRaw(0x0d01) {
            u16(0, 1)
            group(4) {
                u32(0, 7)
                u8(1, 2)
            }
        }
        val body = Iap2BodyReader.of(frame)
        val roadSign = body.group(4)

        assertEquals(1, body.u16(0))
        assertEquals(7L, roadSign.u32(0))
        assertEquals(2, roadSign.u8(1))
    }

    @Test
    fun carPlayStartSessionSupportsReferenceOptionalFields() {
        val frame = Iap2CarPlayMessages.startSession(
            wiredIpv6Addresses = listOf("fe80::2"),
            wiredReserved = 3L,
            wireless = Iap2WirelessSessionParameters(
                ssid = "LIVI",
                passphrase = "secret",
                channel = 36,
                ipAddresses = listOf("192.168.1.1", "192.168.1.2"),
                securityType = 3,
            ),
            airPlayPort = 7000,
            deviceIdentifier = "dev-1",
            publicKey = "pub",
            sourceVersion = "1.0",
            sdkVersion = "27.0",
            clusterAsset = Iap2ClusterAsset("cluster", 4),
            mutualAuth = true,
        )
        val body = Iap2BodyReader.of(frame)
        val wired = body.group(0)
        val wireless = body.group(1)

        assertEquals("fe80::2", wired.string(0))
        assertEquals(3L, wired.u32(1))
        assertEquals("LIVI", wireless.string(0))
        assertEquals(2, wireless.all(3).size)
        assertEquals("192.168.1.2", wireless.all(3)[1].payload?.let { String(it).trimEnd('\u0000') })
        assertEquals(7000L, body.u32(2))
        assertEquals("27.0", body.string(6))
        assertEquals("cluster", body.group(7).string(0))
        assertEquals(4L, body.group(7).u32(1))
        assertEquals(1, body.u8(8))
    }

    @Test
    fun wirelessCarPlayAvailabilityUsesBooleanReferenceSemantics() {
        assertFalse(
            Iap2WirelessMessages.wirelessCarPlayAvailability(
                Iap2Messages.buildRaw(0x4e0d) { u8(0, 0) },
            ),
        )
        assertTrue(
            Iap2WirelessMessages.wirelessCarPlayAvailability(
                Iap2Messages.buildRaw(0x4e0d) { u8(0, 1) },
            ),
        )
        val invalid = Iap2Messages.buildRaw(0x4e0d) { u8(0, 2) }
        try {
            Iap2WirelessMessages.wirelessCarPlayAvailability(invalid)
            throw AssertionError("Expected an invalid boolean availability to be rejected")
        } catch (_: Iap2ProtocolException) {
            // Expected.
        }
    }

    @Test
    fun accessoryWifiConfigurationOmitsOrIncludesOptionalBssid() {
        val withoutBssid = Iap2WirelessMessages.accessoryWiFiConfiguration(
            ssid = "LIVI",
            passphrase = "secret",
            channel = 36,
            securityType = 3,
        )
        val withBssid = Iap2WirelessMessages.accessoryWiFiConfiguration(
            ssid = "LIVI",
            passphrase = "secret",
            channel = 36,
            securityType = 3,
            bssid = byteArrayOf(1, 2, 3, 4, 5, 6),
        )

        assertNull(Iap2BodyReader.of(withoutBssid).first(0))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), Iap2BodyReader.of(withBssid).bytes(0))
    }

    @Test
    fun formatterShowsEndpointNamesFieldsAndNestedGroups() {
        val frame = Iap2CarPlayMessages.startSession(
            wiredIpv6Addresses = listOf("fe80::2"),
            airPlayPort = 7000,
            publicKey = "pub",
            sourceVersion = "1.0",
        )

        val formatted = Iap2FrameFormatter.format(Iap2TraceDirection.TX, "wired", frame)

        assertTrue(formatted.startsWith("IAP2 TX [wired] 0x4301 CarPlayStartSession"))
        assertTrue("wired:" in formatted)
        assertTrue("wiredIP: \"fe80::2\"" in formatted)
        assertTrue("airPlayPort: u32=7000" in formatted)
        assertTrue("raw-body=" in formatted)
    }

    @Test
    fun formatterFallsBackForMalformedBodies() {
        val formatted = Iap2FrameFormatter.format(
            Iap2TraceDirection.RX,
            "wired",
            Iap2Frame(0x4300, byteArrayOf(0, 1, 2)),
        )

        assertTrue(formatted.contains("body=malformed TLV"))
        assertTrue(formatted.contains("raw-body=00 01 02"))
    }

    @Test
    fun formatterEscapesLineBreaksInNmeaStrings() {
        val frame = Iap2ControlMessages.locationInformation("\$GPGGA,1\r\n")
        val formatted = Iap2FrameFormatter.format(Iap2TraceDirection.TX, "wired", frame)

        assertTrue(formatted.contains("\\r\\n"))
    }
}

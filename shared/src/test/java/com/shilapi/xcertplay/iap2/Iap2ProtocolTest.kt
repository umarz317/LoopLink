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
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingAccumulator
import com.shilapi.xcertplay.iap2.message.Iap2PlaybackStatus
import com.shilapi.xcertplay.iap2.message.Iap2HidMessages
import com.shilapi.xcertplay.iap2.message.Iap2MediaRemoteCommand
import com.shilapi.xcertplay.iap2.session.Iap2FileTransferHandler
import com.shilapi.xcertplay.transport.Iap2LinkEngine
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
    }    @Test
    fun nowPlayingUpdatesMergeIncrementallyAndFreezeElapsedTimeWhenPaused() {
        var now = 1_000L
        val accumulator = Iap2NowPlayingAccumulator { now }
        val initial = accumulator.update(
            Iap2Messages.buildRaw(0x5001) {
                group(0) {
                    string(1, "Track")
                    u32(4, 180_000)
                    string(12, "Artist")
                }
                group(1) {
                    u8(0, 1)
                    u32(1, 12_000)
                    u32(2, 2)
                    u32(3, 10)
                    string(7, "Music")
                }
            },
        )
        now = 4_000L
        val paused = accumulator.update(
            Iap2Messages.buildRaw(0x5001) {
                group(1) { u8(0, 2) }
            },
        )

        assertEquals("Track", initial.title)
        assertEquals(Iap2PlaybackStatus.PLAYING, initial.status)
        assertEquals(2L, paused.queueIndex)
        assertEquals("Music", paused.appName)
        assertEquals(Iap2PlaybackStatus.PAUSED, paused.status)
        assertEquals(15_000L, paused.elapsedMillis)
        assertEquals(4_000L, paused.positionUpdateRealtimeMillis)
    }

    @Test
    fun aNewSessionDoesNotInheritThePreviousPhonesTrack() {
        val accumulator = Iap2NowPlayingAccumulator()
        accumulator.update(Iap2Messages.buildRaw(0x5001) { group(0) { string(1, "Previous track") } })
        accumulator.clear()
        val next = accumulator.update(Iap2Messages.buildRaw(0x5001) { group(1) { u8(0, 1) } })
        assertNull(next.title)
        assertNull(next.artworkFileTransferId)
        assertEquals(Iap2PlaybackStatus.PLAYING, next.status)
    }

    @Test
    fun nowPlayingUpdateParsesArtworkFileTransferIdentifier() {
        val accumulator = Iap2NowPlayingAccumulator { 0L }
        val withArtwork = accumulator.update(
            Iap2Messages.buildRaw(0x5001) {
                group(0) {
                    string(1, "Track")
                    u8(26, 7)
                }
            },
        )

        assertEquals(7, withArtwork.artworkFileTransferId)
    }

    @Test
    fun linkAndNowPlayingSubscriptionDeclareArtworkFileTransferCapability() {
        val sessions = Iap2LinkEngine().peerSynchronization().sessions
        val nowPlaying = Iap2ControlMessages.subscriptions().first()

        assertTrue(
            sessions.any {
                it.id == Iap2LinkEngine.FILE_TRANSFER_SESSION_ID && it.kind == 1 && it.version == 2
            },
        )
        assertTrue(Iap2BodyReader.of(nowPlaying).group(0).has(26))
    }

    @Test
    fun fileTransferAssemblesArtworkAndReusesCachedIdentifier() {
        val handler = Iap2FileTransferHandler()
        val setup = byteArrayOf(
            7, 0x04,
            0, 0, 0, 0, 0, 0, 0, 5,
            0, 2,
        )

        assertArrayEquals(byteArrayOf(7, 0x01), handler.handle(setup).response)
        assertNull(handler.handle(byteArrayOf(7, 0x80.toByte(), 1, 2)).response)
        assertNull(handler.handle(byteArrayOf(7, 0x00, 3)).response)
        val completed = handler.handle(byteArrayOf(7, 0x40, 4, 5))
        assertArrayEquals(byteArrayOf(7, 0x05), completed.response)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), completed.artwork?.bytes)

        val cached = handler.handle(setup) { it == 7 }
        assertArrayEquals(byteArrayOf(7, 0x05), cached.response)
        assertNull(cached.artwork)
    }

    @Test
    fun hidMediaRemoteUsesAdvertisedDescriptorAndPressReleaseReports() {
        val start = Iap2HidMessages.startMediaPlaybackRemote()
        val next = Iap2HidMessages.mediaReport(Iap2MediaRemoteCommand.NEXT)
        val release = Iap2HidMessages.releaseMediaButtons()

        assertEquals(0x6800, start.messageId)
        assertEquals(Iap2HidMessages.MEDIA_PLAYBACK_COMPONENT_ID, Iap2BodyReader.of(start).u16(0))
        assertArrayEquals(
            byteArrayOf(
                0x05, 0x0c, 0x09, 0x01, 0xa1.toByte(), 0x01, 0x15, 0x00,
                0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x04, 0x09, 0xb0.toByte(),
                0x09, 0xb1.toByte(), 0x09, 0xb5.toByte(), 0x09, 0xb6.toByte(),
                0x81.toByte(), 0x02, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(),
                0x03, 0xc0.toByte(),
            ),
            Iap2BodyReader.of(start).bytes(4),
        )
        assertEquals(0x6802, next.messageId)
        assertArrayEquals(byteArrayOf(4), Iap2BodyReader.of(next).bytes(1))
        assertArrayEquals(byteArrayOf(0), Iap2BodyReader.of(release).bytes(1))
        assertEquals(0x6803, Iap2HidMessages.stopMediaPlaybackRemote().messageId)
    }
}

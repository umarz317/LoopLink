package io.loopbreak.carlink.desktop

import com.shilapi.xcertplay.airplay.*
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class DesktopTest {
    @Test fun displaySettingsRoundTripAndRejectUnsupportedValues() {
        val store = LocalStore(Files.createTempDirectory("carlink-store"))
        try {
            val config = store.parseConfig(json.readTree("{\"width\":1920,\"height\":1080,\"fps\":60,\"microphone\":true}"))
            config.validate()
            assertEquals(DesktopConfig(1920,1080,60,true),config)
            assertFailsWith<IllegalArgumentException> { DesktopConfig(fps=45).validate() }
        } finally { store.directory.toFile().deleteRecursively() }
    }
    @Test fun identityAndPairingsSurviveRestartWithPrivatePermissions() {
        val folder = Files.createTempDirectory("carlink-identity")
        try {
            val store = LocalStore(folder)
            val first=store.identity(); val second=LocalStore(folder).identity()
            assertContentEquals(first.privateKey,second.privateKey)
            assertContentEquals(first.publicKey,second.publicKey)
            assertEquals(first.pairingId,second.pairingId)
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(folder.resolve("identity.json")))
            val pairings=store.pairings(); val key=ByteArray(32){it.toByte()}; pairings.save("test-phone",key)
            assertContentEquals(key,LocalStore(folder).pairings().get("test-phone"))
        } finally { folder.toFile().deleteRecursively() }
    }
    @Test fun localControlsRejectRebindingAndCrossOriginWrites() {
        val access=LocalAccess(8765,"private-token")
        assertTrue(access.acceptsHost("127.0.0.1:8765"))
        assertTrue(access.acceptsHost("localhost:8765"))
        assertFalse(access.acceptsHost("attacker.example:8765"))
        assertFalse(access.acceptsWrite("https://attacker.example","private-token"))
        assertFalse(access.acceptsWrite("http://localhost:8765",null))
        assertTrue(access.acceptsWrite("http://localhost:8765","private-token"))
    }
    @Test fun encryptedVideoAndMicrophoneUseTheSharedProtocol() {
        val key=ByteArray(32){it.toByte()}; val header=ByteArray(128)
        val nalu=byteArrayOf(0,0,0,3,0x65,0x20,0x30)
        val sealed=AirPlayCrypto.chachaSeal(key,AirPlayCrypto.nonce64(0),nalu,header)
        assertContentEquals(nalu,ScreenCodec.decryptFrame(key,0,header,sealed))
        assertContentEquals(byteArrayOf(0,0,0,1,0x65,0x20,0x30),ScreenCodec.lengthPrefixedToAnnexB(nalu.copyOf()))
        val counters=MicrophoneCounters(); val pcm=byteArrayOf(0x12,0x34,0x56,0x78)
        val packet=MicrophonePacketizer.sealPacket(key,110,counters,pcm,960)
        assertEquals(1,counters.sequence); assertEquals(960,counters.timestamp); assertEquals(1L,counters.nonce)
        assertContentEquals(pcm,AirPlayCrypto.chachaOpen(key,ByteArray(12),packet.copyOfRange(12,packet.size-8),packet.copyOfRange(4,12)))
    }
}

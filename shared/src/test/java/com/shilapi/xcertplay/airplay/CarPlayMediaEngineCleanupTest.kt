package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayMediaEngineCleanupTest {
    @Test fun sessionCloseStopsItsAudioAndMicrophoneAndPreservesOtherSessionMetadata() {
        val stopped = mutableListOf<AudioStreamId>()
        val microphonesStopped = mutableListOf<AudioStreamId>()
        val microphoneStarted = CountDownLatch(1)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped.add(id) }
            override fun onMicrophoneStopped(id: AudioStreamId) { microphonesStopped.add(id) }
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { microphoneStarted.countDown() }
        }, microphoneEnabled = true)
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val peer = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val session = session(server.accept(), engine)
        val other = session(Socket(), engine)
        try {
            engine.onAudio(session, 100, stream("media", 41))
            val response = engine.onAudio(session, 100, stream("telephony", 42) + ("dataPort" to 12345L))
            assertNotNull(response)
            engine.onAudio(other, 100, stream("navigation", 43))
            val key = AirPlayCrypto.hkdfSha512(
                ByteArray(32), "DataStream-Salt42".toByteArray(), "DataStream-Output-Encryption-Key".toByteArray(), 32,
            )
            val packet = MicrophonePacketizer.sealPacket(key, 100, MicrophoneCounters(), ByteArray(640), 320)
            DatagramSocket().use {
                it.send(DatagramPacket(packet, packet.size, InetAddress.getLoopbackAddress(), (response!!["dataPort"] as Number).toInt()))
            }
            assertTrue("Telephony must start its microphone before closing", microphoneStarted.await(3, TimeUnit.SECONDS))
            stopped.clear()
            microphonesStopped.clear()

            session.close()
            assertEquals(setOf(AudioStreamId(100, "media"), AudioStreamId(100, "telephony")), stopped.toSet())
            assertEquals(listOf(AudioStreamId(100, "telephony")), microphonesStopped)
            assertEquals(1, (engine.onFeedback(other)?.get("streams") as List<*>).size)

            session.close()
            assertEquals("Closing twice must not stop audio twice", 2, stopped.size)
            assertEquals(1, microphonesStopped.size)
        } finally {
            session.close()
            other.close()
            peer.close()
            server.close()
        }
    }

    private fun stream(audioType: String, connectionId: Long): Map<String, Any?> = mapOf(
        "audioType" to audioType, "audioFormat" to 0x10L, "streamConnectionID" to connectionId,
    )

    private fun session(socket: Socket, engine: CarPlayMediaEngine): AirPlaySession = AirPlaySession(
        socket,
        AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01", sourceVersion = "1",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, engine,
    ).also {
        it.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(it.pairVerify, ByteArray(32))
    }
}

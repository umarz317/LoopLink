package io.loopbreak.carlink.desktop

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.*
import com.shilapi.xcertplay.transport.*
import java.io.Closeable
import java.io.File
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class Receiver(private val store: LocalStore, private val usbHelper: String, private val authRoot: File,
    private val report: (String) -> Unit,
) : Closeable {
    lateinit var browser: BrowserHub
    @Volatile var phase = "idle"; private set
    @Volatile var message = "Connect your iPhone with a USB cable."; private set
    @Volatile private var run: SessionRun? = null
    val startedAt = AtomicLong()
    fun authReady(): Boolean = File(authRoot,"offline-mfi/identity.pk8").isFile && File(authRoot,"offline-mfi/certificate.p7b").isFile
    fun status(): Map<String, Any?> = mapOf("event" to "status", "phase" to phase, "message" to message,
        "frames" to browser.frames.get(), "audioPackets" to browser.audioPackets.get(),
        "identityAvailable" to authReady(), "startedAt" to startedAt.get())
    private fun state(next: String, text: String) { phase = next; message = text; report(text); browser.event(status()) }
    @Synchronized fun start(config: DesktopConfig) {
        check(run == null) { "Disconnect before changing the connection." }
        config.validate()
        require(authReady()) { "Real accessory identity files are missing. Set DIPLAY_AUTH_ASSETS_DIR; synthetic identities cannot connect to an iPhone." }
        browser.reset(); startedAt.set(System.currentTimeMillis())
        val next = SessionRun(config)
        run = next; state("starting", "Starting the local receiver…")
        thread(name="carlink-receiver", isDaemon=true) { next.start() }
    }
    @Synchronized override fun close() {
        val previous = run; run = null
        previous?.close(); browser.reset(); state("idle", "Disconnected. Ready for another test.")
    }
    fun input(text: String) {
        val node = json.readTree(text)
        val current = run ?: return
        val session = current.active
        when (node.text("action")) {
            "touch" -> session?.sendTouch(listOf(AirPlayContact(0,
                node.path("x").asDouble().coerceIn(0.0,1.0), node.path("y").asDouble().coerceIn(0.0,1.0), node.path("down").asBoolean())))
            "home" -> session?.sendKnob(AirPlayKnobState(home=true))
            "back" -> session?.sendKnob(AirPlayKnobState(back=true))
            "siri" -> session?.invokeSiri()
            "play" -> session?.sendMedia(3)
            "next" -> session?.sendMedia(4)
            "previous" -> session?.sendMedia(5)
            "keyframe" -> browser.keyframe()
            "rendered" -> if (session != null && browser.frames.get() > 0) {
                browser.rendered(); current.state("streaming", "CarPlay is rendering from your iPhone.")
            }
            "night" -> session?.setNightMode(node.path("enabled").asBoolean())
            "release" -> session?.sendTouch(emptyList())
        }
    }
    fun microphone(packet: ByteBuffer) {
        if (packet.remaining() < 5 || packet.get().toInt() != 4) return
        val type = packet.int
        run?.microphones?.get(type)?.send(ByteArray(packet.remaining()).also(packet::get))
    }
    private inner class SessionRun(val config: DesktopConfig) : Closeable {
        private val closed = AtomicBoolean()
        private val resources = CopyOnWriteArrayList<AutoCloseable>()
        val microphones = ConcurrentHashMap<Int, MicrophoneSender>()
        @Volatile var active: AirPlaySession? = null
        @Volatile private var channel: Iap2Session? = null
        private fun <T: AutoCloseable> own(resource: T): T {
            synchronized(resources) {
                if (closed.get()) { resource.close(); throw java.io.IOException("Connection cancelled") }
                resources.add(resource)
            }
            return resource
        }
        fun state(next: String, text: String) { if (run === this && !closed.get()) this@Receiver.state(next,text) }
        fun start() {
            try {
                val mfi = LocalMfiAuthenticationClient.load(File(authRoot, "offline-mfi"))
                val identity = store.identity()
                val pairings = store.pairings()
                val media = CarPlayMediaEngine(browser, microphoneEnabled=config.microphone)
                browser.microphoneStarted = { type, mic ->
                    if (!closed.get()) {
                        val sender = own(MicrophoneSender(mic))
                        microphones.put(type, sender)?.close()
                        browser.event(mapOf("event" to "microphoneConfig", "stream" to type,
                            "codec" to mic.codec.name,"sampleRate" to mic.sampleRate,"channels" to mic.channels,
                            "samples" to mic.samplesPerPacket,"bitrate" to mic.bitrate))
                    }
                }
                browser.microphoneStopped = { type ->
                    microphones.remove(type)?.close(); browser.event(mapOf("event" to "microphoneStop", "stream" to type))
                }
                startUsb(mfi, identity, pairings, media)
            } catch (error: Exception) { fail(error) }
        }
        private fun airPlayConfig(deviceId: String, btMac: String, port: Int) = AirPlayConfig("CarLink Desk", deviceId, btMac,"950.7.1",
            AirPlayDisplayConfig(config.width, config.height, fps=config.fps), port=port,
            microphone=config.microphone, manufacturer="CarLink",model="CarLink Desk",oemLabel="CarLink")
        private fun startUsb(mfi: LocalMfiAuthenticationClient, identity: AirPlayIdentity, pairings: PairingStore, media: CarPlayMediaEngine) {
            state("pairing","Looking for an iPhone connected by USB…")
            val phone = usbPhones(usbHelper).firstOrNull()
                ?: throw java.io.IOException("No iPhone found over USB. Connect it with a data cable and unlock it.")
            require(phone.trusted) { "Unlock ${phone.name} and tap Trust This Computer, then connect again." }
            val network = phone.networkInterface.takeIf { it.isNotEmpty() && phone.interfaceNumber >= 0 }?.let(NetworkInterface::getByName)
                ?: throw java.io.IOException("${phone.name} has no CarPlay USB network interface. Reconnect the cable and unlock the iPhone.")
            val address = network.inetAddresses.toList().filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }
                ?: throw java.io.IOException("The iPhone USB network (${network.name}) has no IPv6 address yet. Unlock the iPhone and try again.")
            val deviceId = network.hardwareAddress.joinToString(":") { "%02X".format(it.toInt() and 255) }
            // macOS AirPlay Receiver may own *:7000; iAP2 tells the iPhone this listener's actual port.
            val server = own(ServerSocket().apply { reuseAddress=true; bind(InetSocketAddress(address,0)) })
            val airplay = airPlayConfig(deviceId, deviceId, server.localPort)
            serveAirPlay(server, airplay, identity, pairings, mfi, media)
            report("USB iPhone ${phone.name} on ${network.name} ${address.hostAddress}; AirPlay port ${server.localPort}")
            val identification = Iap2IdentificationConfig("CarLink Desk","CarLink Desk","CarLink",
                identity.pairingId,"1.0","1.0", carPlayUsbInterfaceNumber=phone.interfaceNumber)
            val endpoint = Iap2WiredCarPlayEndpoint(listOf(address.hostAddress.substringBefore('%')), server.localPort,
                identity.publicKeyHex, airplay.sourceVersion, deviceId)
            val stream = own(HelperStream(listOf(usbHelper,"connect",phone.udid),report))
            val usbChannel = own(Iap2Session.open(stream))
            channel = usbChannel
            state("waiting","Authenticating with the iPhone over USB. Accept any CarPlay prompt on the iPhone.")
            val result = Iap2WiredControlClient(usbChannel,Iap2MfiAuthenticationClient(mfi)).run(identification,endpoint,
                availableCurrentMilliAmps=0, timeoutMillis=Iap2WiredControlClient.NO_TIMEOUT_MILLIS, onProgress=report)
            if (!closed.get()) throw java.io.IOException("USB CarPlay control ended: ${result.terminal}")
        }
        private fun serveAirPlay(server: ServerSocket, airplay: AirPlayConfig, identity: AirPlayIdentity, pairings: PairingStore,
            mfi: LocalMfiAuthenticationClient, media: CarPlayMediaEngine) {
            thread(name="carlink-airplay-listen",isDaemon=true) {
                try {
                    while (!closed.get()) {
                        val socket = server.accept()
                        if (closed.get()) { socket.close(); break }
                        val session = own(AirPlaySession(socket,airplay,identity,pairings,mfi,
                            object: AirPlaySessionListener {
                                override fun onSessionActive(session: AirPlaySession) { active=session; state("connected","iPhone connected. Waiting for video…") }
                                override fun onSessionEnded(session: AirPlaySession) {
                                    if (active === session) { active=null; state("waiting","iPhone disconnected. Reconnect or restart the test.") }
                                    resources.remove(session)
                                }
                                override fun onVideoFrameRendered(session: AirPlaySession) { state("streaming","CarPlay is rendering from your iPhone.") }
                                override fun onTransportError(message: String) { report(message) }
                                override fun onDebugLog(message: String) {
                                    if (!message.contains("bodyHex") && !message.contains("headers=") && !message.contains("key=")) report(message)
                                }
                            },media))
                        session.start()
                    }
                } catch (error: Exception) { fail(error) }
            }
        }
        private fun fail(error: Exception) {
            if (run === this && !closed.get()) {
                close(); stateAfterClose(error)
            }
        }
        private fun stateAfterClose(error: Exception) = synchronized(this@Receiver) {
            if (run === this) {
                run=null; browser.reset(); this@Receiver.state("error",error.message ?: error.javaClass.simpleName)
            }
        }
        override fun close() {
            if (!closed.compareAndSet(false,true)) return
            active=null
            synchronized(resources) { resources.reversed().forEach { runCatching { it.close() } }; resources.clear() }
            microphones.clear()
            browser.microphoneStarted={_,_->}; browser.microphoneStopped={}
        }
    }
}

private class MicrophoneSender(private val config: MicrophoneConfig) : Closeable {
    private val socket = DatagramSocket()
    private val counters = MicrophoneCounters()
    @Synchronized fun send(body: ByteArray) {
        if (body.isEmpty() || body.size > 8192 || config.codec == AudioCodecKind.AAC_LC) return
        if (config.codec == AudioCodecKind.LPCM && body.size != config.frameBytes) return
        val packet = MicrophonePacketizer.sealPacket(config.key,config.payloadType,counters,body,config.samplesPerPacket)
        socket.send(DatagramPacket(packet,packet.size,config.host,config.port))
    }
    override fun close() { socket.close() }
}

package io.loopbreak.carlink.desktop

import com.shilapi.xcertplay.airplay.*
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Private localhost media channel. No accessory keys are sent to the browser. */
class BrowserHub(private val port: Int, private val token: String,
    private val input: (String) -> Unit, private val micInput: (ByteBuffer) -> Unit,
    private val ready: () -> Map<String, Any?>,
) : WebSocketServer(InetSocketAddress("127.0.0.1", port)), MediaSink {
    private val configs = ConcurrentHashMap<String, String>()
    private val recovery = ConcurrentHashMap<Int, () -> Unit>()
    private val diagnostics = ConcurrentHashMap<Int, (String) -> Unit>()
    private val authorized = ConcurrentHashMap.newKeySet<WebSocket>()
    private val droppedVideo = ConcurrentHashMap.newKeySet<WebSocket>()
    val frames = AtomicLong()
    val audioPackets = AtomicLong()
    @Volatile var microphoneStarted: (Int, MicrophoneConfig) -> Unit = { _, _ -> }
    @Volatile var microphoneStopped: (Int) -> Unit = {}
    override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
        val origin = handshake.getFieldValue("Origin")
        if (handshake.resourceDescriptor != "/media?token=$token" ||
            origin !in setOf("http://127.0.0.1:${port-1}", "http://localhost:${port-1}")) {
            connection.close(1008, "Local session required"); return
        }
        authorized.add(connection)
        connection.send(encode(ready()))
        configs.values.forEach(connection::send)
        recovery.values.forEach { it() }
    }
    override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) { authorized.remove(connection); droppedVideo.remove(connection) }
    override fun onMessage(connection: WebSocket, message: String) {
        if (connection in authorized && message.length < 8192) runCatching { input(message) }
            .onFailure { connection.send(encode(mapOf("event" to "inputError", "message" to it.message))) }
    }
    override fun onMessage(connection: WebSocket, message: ByteBuffer) {
        if (connection in authorized && message.remaining() <= 65536) runCatching { micInput(message) }
    }
    override fun onError(connection: WebSocket?, error: Exception) {
        System.err.println("Browser socket: ${error.message}")
        // No connection means the media server itself failed (e.g. port in use); without it no video reaches the page.
        if (connection == null) {
            System.err.println("Media port $port is unavailable. Stop any other CarLink Desk receiver and start again.")
            Runtime.getRuntime().halt(1)
        }
    }
    override fun onStart() { connectionLostTimeout = 20 }
    fun event(value: Any) { val text = encode(value); authorized.forEach { if (it.isOpen) it.send(text) } }
    private fun remember(key: String, value: Any) { val text = encode(value); configs[key] = text; authorized.forEach { if (it.isOpen) it.send(text) } }
    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        require(codec == VideoCodec.H264) { "Desktop receiver requests H.264" }
    }
    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        remember("video:$type", mapOf("event" to "videoConfig", "stream" to type,
            "config" to Base64.getEncoder().encodeToString(codecData)))
    }
    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        if (type != 110) return
        frames.incrementAndGet(); packet(2, type, System.nanoTime()/1000, naluBytes)
    }
    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { recovery[type] = handler }
    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) { diagnostics[type] = handler }
    fun rendered() { diagnostics[110]?.invoke("first frame rendered") }
    fun keyframe() { recovery[110]?.invoke() }
    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) { configs.remove("video:$type"); recovery.remove(type); diagnostics.remove(type) }
        event(mapOf("event" to "screen", "stream" to type, "active" to active))
    }
    // One iPhone stream type can carry several audio types; the browser addresses each by its own number.
    private val audioIds = ConcurrentHashMap<AudioStreamId, Int>()
    private val nextAudioId = AtomicInteger(1000)
    private fun audioId(id: AudioStreamId) = audioIds.computeIfAbsent(id) { nextAudioId.getAndIncrement() }
    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        val stream = audioId(id)
        remember("audio:$stream", mapOf("event" to "audioConfig", "stream" to stream,
            "codec" to format.codec.name, "sampleRate" to format.sampleRate, "channels" to format.channels))
    }
    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        if (rtp.size <= 12) return
        audioPackets.incrementAndGet(); packet(3, audioId(id), sample.toLong() and 0xffffffffL, rtp.copyOfRange(12,rtp.size))
    }
    override fun onAudioStopped(id: AudioStreamId) {
        val stream = audioIds.remove(id) ?: return
        configs.remove("audio:$stream"); event(mapOf("event" to "audioStop", "stream" to stream))
    }
    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { microphoneStarted(audioId(id), config) }
    override fun onMicrophoneStopped(id: AudioStreamId) { audioIds.remove(id)?.let(microphoneStopped) }
    private fun packet(kind: Int, stream: Int, timestamp: Long, payload: ByteArray) {
        val packet = ByteBuffer.allocate(13+payload.size).put(kind.toByte()).putInt(stream).putLong(timestamp).put(payload).array()
        authorized.forEach { connection ->
            // A slow browser must not hold up the iPhone receive loop or grow memory indefinitely.
            if (connection.isOpen) {
                if (connection.hasBufferedData()) {
                    if (kind == 2) droppedVideo.add(connection)
                } else {
                    if (kind == 2 && droppedVideo.remove(connection)) {
                        connection.send(encode(mapOf("event" to "recover")))
                        recovery[stream]?.invoke()
                    }
                    connection.send(packet)
                }
            }
        }
    }
    fun reset() { configs.clear(); audioIds.clear(); recovery.clear(); diagnostics.clear(); droppedVideo.clear(); frames.set(0); audioPackets.set(0) }
}

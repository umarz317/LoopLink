package io.loopbreak.carlink.desktop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

val json = ObjectMapper()
fun encode(value: Any): String = json.writeValueAsString(value)
fun JsonNode.text(key: String, fallback: String = ""): String = get(key)?.asText() ?: fallback

data class DesktopConfig(
    val width: Int = 1280, val height: Int = 720, val fps: Int = 30,
    val microphone: Boolean = false,
) {
    fun validate() {
        require(width in 640..2560 && height in 360..1440 && fps in setOf(24,30,60)) { "Unsupported display size or frame rate." }
    }
    fun publicView() = mapOf("width" to width, "height" to height, "fps" to fps, "microphone" to microphone)
}

class LocalStore(val directory: Path) {
    init {
        Files.createDirectories(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
    }
    @Synchronized fun save(name: String, value: Any) {
        val temporary = Files.createTempFile(directory, ".write-", ".json")
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
            Files.writeString(temporary, encode(value))
            Files.move(temporary, directory.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }
    fun read(name: String): JsonNode? = directory.resolve(name).takeIf(Files::exists)?.let { json.readTree(it.toFile()) }
    fun config(): DesktopConfig = read("config.json")?.let(::parseConfig) ?: DesktopConfig()
    fun parseConfig(node: JsonNode) = DesktopConfig(node.get("width")?.asInt() ?: 1280,
        node.get("height")?.asInt() ?: 720, node.get("fps")?.asInt() ?: 30, node.get("microphone")?.asBoolean() ?: false)
    fun identity(): AirPlayIdentity {
        val decoder = Base64.getDecoder()
        return read("identity.json")?.let { AirPlayIdentity(decoder.decode(it.text("privateKey")),
            decoder.decode(it.text("publicKey")), it.text("pairingId")) } ?: AirPlayIdentity.generate().also {
            val encoder = Base64.getEncoder()
            save("identity.json", mapOf("privateKey" to encoder.encodeToString(it.privateKey),
                "publicKey" to encoder.encodeToString(it.publicKey), "pairingId" to it.pairingId))
        }
    }
    fun pairings(): PairingStore {
        val saved = mutableMapOf<String, String>()
        read("pairings.json")?.fields()?.forEach { saved[it.key] = it.value.asText() }
        val store = PairingStore { name, key -> synchronized(saved) {
            saved[name] = Base64.getEncoder().encodeToString(key); save("pairings.json", saved)
        } }
        saved.toMap().forEach { (name, key) -> store.save(name, Base64.getDecoder().decode(key)) }
        return store
    }
}

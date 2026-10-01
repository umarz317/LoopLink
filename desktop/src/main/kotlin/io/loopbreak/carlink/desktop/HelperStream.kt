package io.loopbreak.carlink.desktop

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Byte stream relayed by a native helper's stdin/stdout; its stderr lines are status reports. */
class HelperStream(command: List<String>, report: (String) -> Unit) : BlockingDuplexByteStream {
    private val process = ProcessBuilder(command).start()
    private val incoming = LinkedBlockingQueue<ByteArray>(256)
    @Volatile private var closed = false
    @Volatile private var failure: String? = null
    private var remainder = ByteArray(0)
    private val writeLock = Any()
    init {
        thread(name = "helper-read", isDaemon = true) {
            try { process.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (!closed) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) incoming.put(buffer.copyOf(count))
                }
            } } catch (_: Exception) {} finally {
                process.waitFor()
                if (process.exitValue() != 0 && failure == null) failure = "Connection helper exited (${process.exitValue()})"
                closed = true
            }
        }
        thread(name = "helper-status", isDaemon = true) {
            process.errorStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                if (listOf("failed", "timed out", "unavailable", "Could not").any { line.contains(it) }) failure = line
                report(line)
            } }
        }
    }
    override fun send(data: ByteArray): Unit = synchronized(writeLock) {
        if (closed) throw IOException(failure ?: "iPhone connection is closed")
        process.outputStream.write(data); process.outputStream.flush()
    }
    @Synchronized override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0 && timeoutMillis >= 0)
        if (remainder.isEmpty()) {
            val end = System.nanoTime() + timeoutMillis.coerceAtMost(60_000) * 1_000_000
            do {
                incoming.poll(minOf(100, timeoutMillis), TimeUnit.MILLISECONDS)?.let { remainder = it }
                if (remainder.isNotEmpty()) break
                if (closed) { failure?.let { throw IOException(it) }; return ByteArray(0) }
            } while (System.nanoTime() < end)
            if (remainder.isEmpty()) return null
        }
        val result = remainder.copyOfRange(0, minOf(maxBytes, remainder.size))
        remainder = remainder.copyOfRange(result.size, remainder.size)
        return result
    }
    // Closing must interrupt a blocked reader; do not take the recv/send monitor here.
    override fun close() { closed = true; process.destroy(); process.destroyForcibly() }
}

data class UsbPhone(val udid: String, val name: String, val trusted: Boolean, val networkInterface: String, val interfaceNumber: Int)

fun usbPhones(helper: String): List<UsbPhone> {
    require(File(helper).canExecute()) { "The USB helper is missing. Install libimobiledevice (brew install libimobiledevice) and start with scripts/desktop.sh." }
    val process = ProcessBuilder(helper, "devices").redirectError(ProcessBuilder.Redirect.DISCARD).start()
    if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw IOException("USB iPhone lookup timed out.") }
    require(process.exitValue() == 0) { "USB iPhone lookup failed." }
    return json.readTree(process.inputStream.readBytes()).path("devices").map {
        UsbPhone(it.text("udid"), it.text("name"), it.path("trusted").asBoolean(), it.text("interface"), it.path("interfaceNumber").asInt(-1))
    }
}

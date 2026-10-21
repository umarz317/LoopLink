package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.shilapi.xcertplay.host.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test builds only: streams this process's logcat to the URL baked in with
 * -Plooplink.logStreamUrl. Release builds leave it empty and never start. Lines are redacted first.
 */
internal object RemoteLogStream {
    private const val TAG = "LoopLinkLogStream"
    private const val BATCH_LINES = 500
    private val started = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>(20_000)

    fun start(context: Context) {
        val url = BuildConfig.LOG_STREAM_URL
        if (url.isEmpty() || !started.compareAndSet(false, true)) return
        val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        Thread({ read() }, "log-stream-read").apply { isDaemon = true; start() }
        Thread({ send(url, device) }, "log-stream-send").apply { isDaemon = true; start() }
        Log.i(TAG, "streaming logs from $device")
        BluetoothDiagnostics.log(context.applicationContext, "app start")
    }

    private fun read() {
        try {
            val process = ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}")
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().forEachLine { line ->
                DiagnosticRedactor.redact(line)?.let { queue.offer(it) }
            }
        } catch (error: Exception) {
            queue.offer("log stream: logcat stopped ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun send(url: String, device: String) {
        val batch = ArrayList<String>(BATCH_LINES)
        while (true) {
            if (batch.isEmpty()) {
                batch.add(queue.take())
                queue.drainTo(batch, BATCH_LINES - 1)
            }
            if (post(url, device, batch)) batch.clear()
            else TimeUnit.SECONDS.sleep(5)
            TimeUnit.MILLISECONDS.sleep(500)
        }
    }

    private fun post(url: String, device: String, lines: List<String>): Boolean = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        connection.setRequestProperty("X-LoopLink-Token", BuildConfig.LOG_STREAM_TOKEN)
        connection.setRequestProperty("X-LoopLink-Device", device)
        connection.outputStream.use { it.write(lines.joinToString("\n", postfix = "\n").toByteArray()) }
        val ok = connection.responseCode in 200..299
        connection.disconnect()
        ok
    } catch (_: Exception) {
        false
    }
}

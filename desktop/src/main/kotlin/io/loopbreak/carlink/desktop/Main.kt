package io.loopbreak.carlink.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch

/** Restricts browser controls to this local app; blocks DNS rebinding and cross-site POSTs. */
class LocalAccess(private val port: Int, val token: String) {
    fun acceptsHost(host: String?) = host in setOf("localhost:$port", "127.0.0.1:$port")
    fun acceptsWrite(origin: String?, suppliedToken: String?) =
        (origin == null || origin in setOf("http://localhost:$port", "http://127.0.0.1:$port")) && suppliedToken == token
}

fun main() {
    val root = Path.of(System.getenv("CARLINK_DESKTOP_ROOT") ?: "desktop").toAbsolutePath()
    val port = (System.getenv("CARLINK_DESKTOP_PORT") ?: "8765").toInt().also { require(it in 1024..65534) }
    val usbHelper = System.getenv("CARLINK_USB_HELPER") ?: root.resolve("build/carlink-usb").toString()
    val auth = File(System.getenv("DIPLAY_AUTH_ASSETS_DIR") ?: root.parent.resolve(".private/runtime-assets").toString())
    val store = LocalStore(root.parent.resolve(".private/desktop"))
    val access = LocalAccess(port,Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)))
    val logs = java.util.ArrayDeque<Map<String, String>>()
    var saved = store.config()
    lateinit var browser: BrowserHub
    var eventBrowser: BrowserHub? = null
    val report: (String) -> Unit = { raw ->
        // Payload dumps can include Wi-Fi credentials or protocol keys. Never record them.
        if (!raw.contains("bodyHex=") && !raw.contains("payload=") && !raw.contains("headers=")) {
            val entry = mapOf("time" to Instant.now().toString(), "message" to raw.take(1200))
            synchronized(logs) { logs.addLast(entry); while (logs.size>200) logs.removeFirst() }
            System.err.println(raw)
            eventBrowser?.event(mapOf("event" to "log") + entry)
        }
    }
    android.util.Log.report = report
    val receiver = Receiver(store,usbHelper,auth,report)
    browser = BrowserHub(port+1,access.token,receiver::input,receiver::microphone,receiver::status)
    eventBrowser = browser
    receiver.browser = browser
    val server = HttpServer.create(InetSocketAddress("127.0.0.1",port),0)
    server.executor = Executors.newFixedThreadPool(8) { runnable -> Thread(runnable,"browser-http").apply { isDaemon=true } }
    server.createContext("/") { exchange ->
        try {
            require(access.acceptsHost(exchange.requestHeaders.getFirst("Host"))) { "Localhost access required" }
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            if (method == "POST") {
                require(access.acceptsWrite(exchange.requestHeaders.getFirst("Origin"),exchange.requestHeaders.getFirst("X-CarLink-Token"))) { "Local session token required" }
            }
            when {
                method == "GET" && path == "/api/bootstrap" -> exchange.reply(200,encode(mapOf(
                    "token" to access.token,"mediaPort" to port+1,"config" to saved.publicView(),
                    "status" to receiver.status(),
                    "logs" to synchronized(logs) { logs.toList() })))
                method == "GET" && path == "/api/status" -> exchange.reply(200,encode(receiver.status()))
                method == "GET" && path == "/api/logs" -> {
                    exchange.responseHeaders.set("Content-Disposition","attachment; filename=carlink-desktop-log.json")
                    exchange.reply(200,encode(synchronized(logs) { logs.toList() }))
                }
                method == "POST" && path == "/api/connect" -> {
                    val body = exchange.requestBody.readNBytes(16385)
                    require(body.size <= 16384) { "Settings are too large" }
                    val next = store.parseConfig(json.readTree(body))
                    next.validate()
                    check(receiver.phase in setOf("idle","error")) { "Disconnect the current session first" }
                    saved = next; store.save("config.json",next)
                    receiver.start(next)
                    exchange.reply(202,encode(receiver.status()))
                }
                method == "POST" && path == "/api/disconnect" -> { receiver.close(); exchange.reply(200,encode(receiver.status())) }
                method == "GET" && path in setOf("/","/index.html","/app.js","/app.css","/microphone.js","/logo.svg") -> {
                    val name = if(path == "/") "index.html" else path.drop(1)
                    val mime = when(name.substringAfterLast('.')) { "html" -> "text/html"; "css" -> "text/css"; "svg" -> "image/svg+xml"; else -> "text/javascript" }
                    exchange.reply(200,root.resolve("web/$name").toFile().readText(),"$mime; charset=utf-8")
                }
                else -> exchange.reply(404,encode(mapOf("error" to "Not found")))
            }
        } catch (error: Exception) {
            exchange.reply(400,encode(mapOf("error" to (error.message ?: "Request failed"))))
        } finally { exchange.close() }
    }
    Runtime.getRuntime().addShutdownHook(Thread { receiver.close(); server.stop(0); browser.stop(1000) })
    browser.start(); server.start()
    println("CarLink Desk is running at http://127.0.0.1:$port")
    println("Open in Chrome and connect your iPhone with a USB cable.")
    CountDownLatch(1).await()
}

private fun HttpExchange.reply(status: Int, text: String, type: String = "application/json; charset=utf-8") {
    responseHeaders.set("Content-Type",type)
    responseHeaders.set("Cache-Control","no-store")
    responseHeaders.set("X-Content-Type-Options","nosniff")
    responseHeaders.set("Referrer-Policy","no-referrer")
    responseHeaders.set("Content-Security-Policy", "default-src 'self'; connect-src 'self' ws://127.0.0.1:* ws://localhost:*; style-src 'self'; script-src 'self'; img-src 'self' data:; frame-ancestors 'none'")
    val bytes = text.toByteArray(Charsets.UTF_8)
    sendResponseHeaders(status,bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

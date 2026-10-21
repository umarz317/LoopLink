package com.shilapi.xcertplay.network

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.system.exitProcess

/**
 * Shell-UID entry point that [CarHotspotAdbFallback] starts through the head unit's adb with app_process. An app can no
 * longer start tethering itself ("No field mService" on Android 10, "NoSuchMethodException … startTethering" on 12),
 * while the shell may, and holds the tethering permission it checks. Starts Wi-Fi tethering with the car's saved
 * hotspot configuration, never a new one, and prints only the protocol line. Android 7.0–10 call
 * IConnectivityManager; Android 11 moved tethering to TetheringManager. Ported from upstream DiPlay.
 */
object CarHotspotStartMain {
    internal const val HEADER = "LOOPLINK_HOTSPOT_START_V1"
    private const val DEADLINE_MS = 10_000L
    private const val RESULT_WAIT_MS = 5_000L
    private const val TETHERING_WIFI = 0
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val NO_RESULT = Int.MIN_VALUE

    @JvmStatic fun main(args: Array<String>) {
        // A stuck Binder call must not leave a privileged helper running.
        Thread({ Thread.sleep(DEADLINE_MS); exitProcess(2) }, "hotspot-start-helper-deadline")
            .apply { isDaemon = true; start() }
        val result = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) startWithTetheringManager() else startWithConnectivity()
        } catch (error: Throwable) {
            "error ${CarHotspotTethering.describe(error)}"
        }
        println("$HEADER|$result")
        exitProcess(0)
    }

    private fun service(name: String): IBinder? = Class.forName("android.os.ServiceManager")
        .getMethod("getService", String::class.java).invoke(null, name) as? IBinder

    private fun startWithConnectivity(): String {
        val binder = service("connectivity") ?: return "unsupported"
        val service = Class.forName("android.net.IConnectivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return "unsupported"
        val method = service.javaClass.methods.firstOrNull {
            it.name == "startTethering" && it.parameterTypes.contentEquals(arrayOf(
                Int::class.javaPrimitiveType, ResultReceiver::class.java, Boolean::class.javaPrimitiveType, String::class.java))
        } ?: return "unsupported"
        val result = Answer()
        method.invoke(service, TETHERING_WIFI, object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) = result.set(resultCode)
        }, false, SHELL_PACKAGE)
        return result.await()
    }

    private fun startWithTetheringManager(): String {
        val connector = service("tethering") ?: return "unsupported"
        val managerType = Class.forName("android.net.TetheringManager")
        val manager = managerType.getDeclaredConstructor(Context::class.java, Supplier::class.java)
            .apply { isAccessible = true }
            .newInstance(ShellContext(), Supplier<IBinder> { connector })
        val builder = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
        val request = builder.javaClass.getMethod("build").invoke(builder)
        val callbackType = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val result = Answer()
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
            when (method.name) {
                "onTetheringStarted" -> { result.set(0); null }
                "onTetheringFailed" -> { result.set(args?.firstOrNull() as? Int ?: -1); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "LoopLinkStartTetheringCallback"
                else -> null
            }
        }
        managerType.getMethod("startTethering", request.javaClass, Executor::class.java, callbackType)
            .invoke(manager, request, Executor { it.run() }, callback)
        return result.await()
    }

    /** The calls the tethering service checks: the shell's package for the shell's uid, no attribution tag. */
    private class ShellContext : ContextWrapper(null) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getAttributionTag(): String? = null
    }

    /** "requested" when no result arrived in time, else "result N" (0 is TETHER_ERROR_NO_ERROR). */
    private class Answer {
        private val code = AtomicInteger(NO_RESULT)
        private val answered = CountDownLatch(1)

        fun set(value: Int) {
            code.set(value)
            answered.countDown()
        }

        fun await(): String {
            answered.await(RESULT_WAIT_MS, TimeUnit.MILLISECONDS)
            return if (code.get() == NO_RESULT) "requested" else "result ${code.get()}"
        }
    }

    fun command(apk: String): String {
        val quoted = "'" + apk.replace("'", "'\"'\"'") + "'"
        return "CLASSPATH=$quoted app_process /system/bin ${CarHotspotStartMain::class.java.name}"
    }

    /** The helper's reply, or null when its protocol line is missing; other output is ignored. */
    fun parse(output: String?): String? {
        val prefix = "$HEADER|"
        return output?.lineSequence()?.map(String::trim)?.lastOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    /** "requested" when the call returned without a result yet; "result 0" is TETHER_ERROR_NO_ERROR. */
    fun accepted(reply: String?): Boolean = reply == "requested" || reply == "result 0"
}

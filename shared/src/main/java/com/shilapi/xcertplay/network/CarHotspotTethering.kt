package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.Settings
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * Turns on the car's saved hotspot: on Android 8.0 to 10 with Android's "Modify system settings" access
 * ([PlatformTetheringStart]), and on Android 11+ through the head unit's own ADB once the driver approved
 * LoopLink ([CarHotspotAdbFallback]). Uses the car's saved hotspot configuration; this never stops or
 * reconfigures the hotspot.
 */
object CarHotspotTethering {
    const val READY_TIMEOUT_MILLIS = 15_000L
    private val startupLock = ReentrantLock()

    enum class Result(val diagnostic: String) {
        READY("Car hotspot is on"),
        PERMISSION_REQUIRED("Hotspot control permission is missing"),
        UNSUPPORTED("This head unit does not support automatic hotspot startup"),
        FAILED("The car could not start its hotspot"),
        TIMED_OUT("Timed out waiting for the car hotspot"),
        CANCELLED("Hotspot startup was cancelled"),
    }

    fun permitted(context: Context): Boolean = Settings.System.canWrite(context)

    /** Whether this Android version lets an app with [permitted] access start tethering itself. */
    fun appStartSupported(): Boolean = PlatformTetheringStart.supported()

    /** Android 11+ blocks the app's own start; the head unit's ADB may still start the saved hotspot. */
    fun adbStartSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun supported(): Boolean = appStartSupported() || adbStartSupported()

    /**
     * Blocking; serializes startup requests. Android 8.0 to 10 use the app's own start and fall back to ADB when
     * the firmware blocks it; Android 11+ use ADB only, and never show the approval dialog from here.
     */
    fun enable(
        context: Context,
        isCancelled: () -> Boolean,
        timeoutMillis: Long = READY_TIMEOUT_MILLIS,
        log: (String) -> Unit,
    ): Result {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val observedAdbState = AtomicReference<Boolean?>()
        val adbAccess = AtomicReference<LocalAdb.Access?>()
        val startAdb: () -> Boolean = {
            val client = AtomicReference<LocalAdb?>()
            CarHotspotAdbFallback.bounded(deadline, isCancelled,
                abort = { client.get()?.cancelPendingOperations() }) {
                val adb = LocalAdb(AdbKeys.load(context))
                client.set(adb)
                adb.use {
                    val access = if (isCancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) null
                        else adb.connect(mayAsk = false)
                    adbAccess.set(access)
                    if (access != null) log("car hotspot: ADB access=$access")
                    if (access == LocalAdb.Access.READY || access == LocalAdb.Access.NOT_APPROVED)
                        CarHotspotSettings.setAdbApproved(context, access == LocalAdb.Access.READY)
                    if (access != LocalAdb.Access.READY) false
                    else CarHotspotAdbFallback.start(deadline, isCancelled,
                        state = { CarHotspotStatus.isEnabled(context) }, shell = adb::shell, log = log,
                        helper = CarHotspotStartMain.command(context.applicationInfo.sourceDir))
                        .also { if (it) observedAdbState.set(true) }
                }
            }
        }
        val appStart = appStartSupported()
        return enableUntil(
            deadline,
            isCancelled,
            canWrite = { !appStart || permitted(context) },
            isEnabled = stateWithAdbObservation({ CarHotspotStatus.isEnabled(context) }, observedAdbState),
            startFallback = startAdb,
            start = if (!appStart) null else { receiver ->
                try {
                    PlatformTetheringStart.start(context, receiver, log)
                } catch (error: Exception) {
                    log("car hotspot: Android start request failed ${describe(error)}")
                    throw error
                }
            },
        ).let {
            // Without ADB approval on Android 11+, the driver has to allow LoopLink again in settings.
            val access = adbAccess.get()
            if (!appStart && it != Result.READY && it != Result.CANCELLED &&
                access != null && access != LocalAdb.Access.READY) Result.PERMISSION_REQUIRED else it
        }.also {
            if (it != Result.READY && it != Result.CANCELLED)
                log("car hotspot: Android state=${CarHotspotStatus.isEnabled(context) ?: "unknown"} writeSettings=${permitted(context)}")
            log("car hotspot auto-enable: ${it.diagnostic}")
        }
    }

    internal fun describe(error: Throwable): String {
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        val reason = cause.javaClass.simpleName + (cause.message?.let { ": ${it.take(100)}" } ?: "")
        return if (cause === error) reason else "${error.javaClass.simpleName} caused by $reason"
    }

    /** An ADB observation supplements hidden platform status, but never overrides a current off state. */
    internal fun stateWithAdbObservation(
        platformState: () -> Boolean?,
        observation: AtomicReference<Boolean?>,
    ): () -> Boolean? = { platformState() ?: observation.get() }

    internal fun enable(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        start: (ResultReceiver) -> Unit,
    ): Result = enable(timeoutMillis, isCancelled, canWrite, isEnabled, null, start)

    internal fun enable(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        startFallback: (() -> Boolean)?,
        start: ((ResultReceiver) -> Unit)?,
    ): Result = enableUntil(System.nanoTime() + timeoutMillis * 1_000_000L,
        isCancelled, canWrite, isEnabled, startFallback, start)

    /** [start] is the app's own request; null when only [startFallback] (ADB) can start the hotspot. */
    private fun enableUntil(
        deadline: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        startFallback: (() -> Boolean)?,
        start: ((ResultReceiver) -> Unit)?,
    ): Result {
        fun stopped(): Result? = when {
            isCancelled() || Thread.currentThread().isInterrupted -> Result.CANCELLED
            System.nanoTime() >= deadline -> Result.TIMED_OUT
            else -> null
        }
        while (true) {
            stopped()?.let { return it }
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return Result.TIMED_OUT
            try {
                if (startupLock.tryLock(minOf(250L, remaining), TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.CANCELLED
            }
        }
        try {
            stopped()?.let { return it }
            val initialState = isEnabled()
            stopped()?.let { return it }
            if (initialState == true) return Result.READY
            val permission = canWrite()
            stopped()?.let { return it }
            if (!permission) return Result.PERMISSION_REQUIRED
            fun fallback(): Boolean {
                stopped()?.let { return false }
                return try { startFallback?.invoke() == true } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    false
                }
            }
            // Without a readable state only ADB, which reads the AP state itself, can confirm a start.
            if (initialState == null || start == null) {
                if (!fallback()) return stopped() ?: if (start == null) Result.FAILED else Result.UNSUPPORTED
            }
            val response = AtomicInteger(-1)
            try {
                stopped()?.let { return it }
                if (initialState != null && start != null) {
                    start(object : ResultReceiver(null) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                            response.set(resultCode)
                        }
                    })
                }
            } catch (error: Exception) {
                // Firmware that blocks the app's own call may still let the shell start the hotspot.
                val adbRecovered = ((error is ReflectiveOperationException && error !is InvocationTargetException) ||
                    error is SecurityException ||
                    (error is InvocationTargetException && error.targetException is SecurityException)) &&
                    fallback()
                if (!adbRecovered) {
                    stopped()?.let { return it }
                    return when {
                        error is InvocationTargetException ->
                            if (error.targetException is SecurityException) Result.PERMISSION_REQUIRED else Result.FAILED
                        error is ReflectiveOperationException -> Result.UNSUPPORTED
                        error is SecurityException -> Result.PERMISSION_REQUIRED
                        else -> Result.FAILED
                    }
                }
            }
            while (true) {
                stopped()?.let { return it }
                val enabled = isEnabled()
                stopped()?.let { return it }
                if (enabled == true) return Result.READY
                if (response.get() > 0) return Result.FAILED
                val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMillis <= 0) return Result.TIMED_OUT
                try {
                    Thread.sleep(minOf(250L, remainingMillis))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return Result.CANCELLED
                }
            }
        } finally {
            startupLock.unlock()
        }
    }
}

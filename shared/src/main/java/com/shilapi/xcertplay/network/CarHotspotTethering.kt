package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Turns on the car's saved hotspot with Android's "Modify system settings" access ([PlatformTetheringStart]).
 * Uses the car's saved hotspot configuration; this never stops or reconfigures the hotspot.
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

    /** Blocking; concurrent calls share one start and recheck the state after waiting. */
    fun enable(
        context: Context,
        isCancelled: () -> Boolean,
        timeoutMillis: Long = READY_TIMEOUT_MILLIS,
        log: (String) -> Unit,
    ): Result = enable(
        timeoutMillis,
        isCancelled,
        canWrite = { permitted(context) },
        isEnabled = { CarHotspotStatus.isEnabled(context) },
    ) { receiver ->
        try {
            PlatformTetheringStart.start(context, receiver, log)
        } catch (error: Exception) {
            log("car hotspot: Android start request failed ${describe(error)}")
            throw error
        }
    }.also {
        if (it != Result.READY && it != Result.CANCELLED)
            log("car hotspot: Android state=${CarHotspotStatus.isEnabled(context) ?: "unknown"} writeSettings=${permitted(context)}")
        log("car hotspot auto-enable: ${it.diagnostic}")
    }

    internal fun describe(error: Throwable): String {
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        val reason = cause.javaClass.simpleName + (cause.message?.let { ": ${it.take(100)}" } ?: "")
        return if (cause === error) reason else "${error.javaClass.simpleName} caused by $reason"
    }

    internal fun enable(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        start: (ResultReceiver) -> Unit,
    ): Result {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
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
            // Without a readable state, a start could never be confirmed; the car settings stay the way to turn it on.
            if (initialState == null) return Result.UNSUPPORTED
            val response = AtomicInteger(-1)
            try {
                start(object : ResultReceiver(null) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        response.set(resultCode)
                    }
                })
            } catch (error: Exception) {
                stopped()?.let { return it }
                return when {
                    error is InvocationTargetException ->
                        if (error.targetException is SecurityException) Result.PERMISSION_REQUIRED else Result.FAILED
                    error is ReflectiveOperationException -> Result.UNSUPPORTED
                    error is SecurityException -> Result.PERMISSION_REQUIRED
                    else -> Result.FAILED
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

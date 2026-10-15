package com.shilapi.xcertplay.network

import android.os.ResultReceiver
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.shilapi.xcertplay.network.CarHotspotTethering.Result

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotTetheringTest {
    @Test fun startFailuresNameTheUnderlyingCause() {
        assertEquals("NoSuchFieldException: mService", CarHotspotTethering.describe(NoSuchFieldException("mService")))
        assertEquals("InvocationTargetException caused by SecurityException: uid 10123",
            CarHotspotTethering.describe(InvocationTargetException(SecurityException("uid 10123"))))
    }

    @Test fun existingHotspotNeedsNoGrantOrStartCall() {
        assertEquals(Result.READY, enable(canWrite = false, state = { true }))
    }

    @Test fun missingPermissionDoesNotTouchTheHotspot() {
        assertEquals(Result.PERMISSION_REQUIRED, enable(canWrite = false))
    }

    @Test fun unreadableStateIsUnsupportedWithoutStartingBlindly() {
        assertEquals(Result.UNSUPPORTED, enable(state = { null }))
    }

    @Test fun successfulRequestStillWaitsForActualHotspotState() {
        assertEquals(Result.TIMED_OUT, enable(start = { it.send(0, null) }))
    }

    @Test fun actualEnabledStateCompletesStartup() {
        var on = false
        assertEquals(Result.READY, enable(state = { on }, start = { on = true }))
    }

    @Test fun asynchronousEnabledStateIsObserved() {
        val on = AtomicBoolean(false)
        var completion: Thread? = null
        val result = CarHotspotTethering.enable(1_000, { false }, { true }, { on.get() }) {
            completion = thread { on.set(true) }
        }
        completion!!.join(1_000)
        assertEquals(Result.READY, result)
    }

    @Test fun platformRejectionIsReported() {
        assertEquals(Result.FAILED, enable(start = { it.send(1, null) }))
    }

    @Test fun missingBinderMethodIsUnsupported() {
        assertEquals(Result.UNSUPPORTED, enable(start = { throw NoSuchMethodException() }))
    }

    @Test fun binderPermissionFailureIsReportedSeparately() {
        assertEquals(Result.PERMISSION_REQUIRED,
            enable(start = { throw InvocationTargetException(SecurityException()) }))
    }

    @Test fun otherBinderFailureIsNotReportedAsPermissionReady() {
        assertEquals(Result.FAILED,
            enable(start = { throw InvocationTargetException(IllegalStateException()) }))
    }

    @Test fun cancellationWhileReadingEnabledStateCannotReportReady() {
        var cancelled = false
        assertEquals(Result.CANCELLED, enable(cancelled = { cancelled }, state = {
            cancelled = true
            true
        }))
    }

    @Test fun anExpiredStatusReadCannotReportReadyOrStartAnything() {
        val result = CarHotspotTethering.enable(20, { false }, { true }, {
            Thread.sleep(30)
            true
        }, start = { fail("No reflection after expiry") })
        assertEquals(Result.TIMED_OUT, result)
    }

    @Test fun cancelledRequestDoesNotStartTheHotspot() {
        assertEquals(Result.CANCELLED, enable(cancelled = { true }))
    }

    @Test fun cancellationDuringStartupStopsWaitingWithoutAnotherRequest() {
        var cancelled = false
        var calls = 0
        assertEquals(Result.CANCELLED, enable(cancelled = { cancelled }, start = {
            calls++
            cancelled = true
        }))
        assertEquals(1, calls)
    }

    @Test fun concurrentRequestsShareOneStartAndRecheckStateAfterWaiting() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val on = AtomicBoolean(false)
        val calls = AtomicInteger(0)
        var first: Result? = null
        var second: Result? = null
        val one = thread {
            first = CarHotspotTethering.enable(1_000, { false }, { true }, { on.get() }) {
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                on.set(true)
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val two = thread {
            second = CarHotspotTethering.enable(1_000, { false }, { true }, { on.get() }) {
                calls.incrementAndGet()
            }
        }
        release.countDown()
        one.join(2_000); two.join(2_000)
        assertFalse(one.isAlive); assertFalse(two.isAlive)
        assertEquals(Result.READY, first); assertEquals(Result.READY, second)
        assertEquals(1, calls.get())
    }

    private fun enable(
        canWrite: Boolean = true,
        state: () -> Boolean? = { false },
        cancelled: () -> Boolean = { false },
        start: (ResultReceiver) -> Unit = { fail("Unexpected hotspot mutation") },
    ): Result = CarHotspotTethering.enable(20, cancelled, { canWrite }, state, start)
}

package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlaySurfaceLifecycleTest {
    @Test @Config(sdk = [28, 34]) fun bothRenderersKeepTheTouchAndConnectionOverlaysAboveVideo() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        for (efficient in listOf(false, true)) {
            AirPlayPersistence.saveSurfaceViewEnabled(activity, efficient)
            val root = callInstanceMethod<FrameLayout>(activity, "buildContentView")
            val video = root.getChildAt(0)
            assertTrue(if (efficient) video is SurfaceView else video is TextureView)
            assertSame(video, getField<View>(activity, "videoView"))
            assertSame(root.getChildAt(1), getField<View>(activity, "gestureOverlay"))
            assertSame(root.getChildAt(2), getField<View>(activity, "connectionPanel"))
        }
    }

    @Test fun holderSurfaceDetachesWithoutBeingReleasedAndCanReattach() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val sink = AndroidMediaSink(context = activity)
        setField(activity, "sink", sink)
        val callback = getField<SurfaceHolder.Callback>(activity, "surfaceListener")
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val holder = holderFor(surface)
        val surfaces = getField<Map<Int, Surface>>(sink, "surfaces")
        try {
            callback.surfaceCreated(holder)
            assertSame(surface, surfaces[110])
            assertSame(surface, surfaces[111])
            callback.surfaceDestroyed(holder)
            assertTrue(surfaces.isEmpty())
            assertNull(getField<Surface?>(activity, "currentSurface"))
            assertTrue(surface.isValid)

            callback.surfaceCreated(holder)
            assertSame(surface, surfaces[110])
            assertSame(surface, surfaces[111])
            callInstanceMethod<Void>(activity, "detachCurrentSurface")
            assertTrue(surfaces.isEmpty())
            assertTrue(surface.isValid)
        } finally {
            sink.close()
            surface.release()
            texture.release()
        }
    }

    @Test fun lateHolderDestructionDoesNotDetachAReplacementSurface() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val callback = getField<SurfaceHolder.Callback>(activity, "surfaceListener")
        val oldTexture = SurfaceTexture(0)
        val replacementTexture = SurfaceTexture(0)
        val old = Surface(oldTexture)
        val replacement = Surface(replacementTexture)
        try {
            callback.surfaceCreated(holderFor(replacement))
            callback.surfaceDestroyed(holderFor(old))
            assertSame(replacement, getField<Surface>(activity, "currentSurface"))
            assertTrue(replacement.isValid)
        } finally {
            callInstanceMethod<Void>(activity, "detachCurrentSurface")
            old.release()
            replacement.release()
            oldTexture.release()
            replacementTexture.release()
        }
    }

    @Test fun textureSurfaceWrapperIsReleasedWhenDetached() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val callback = getField<TextureView.SurfaceTextureListener>(activity, "textureListener")
        val texture = SurfaceTexture(0)
        try {
            callback.onSurfaceTextureAvailable(texture, 1280, 720)
            val surface = getField<Surface>(activity, "currentSurface")
            assertTrue(surface.isValid)
            callback.onSurfaceTextureDestroyed(texture)
            assertFalse(surface.isValid)
            assertNull(getField<Surface?>(activity, "currentSurface"))
        } finally {
            texture.release()
        }
    }

    private fun holderFor(surface: Surface): SurfaceHolder = Proxy.newProxyInstance(
        SurfaceHolder::class.java.classLoader, arrayOf(SurfaceHolder::class.java),
    ) { _, method, _ ->
        check(method.name == "getSurface") { "Unexpected holder call: ${method.name}" }
        surface
    } as SurfaceHolder
}

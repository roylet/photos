package com.roylet.photos.editor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.roylet.photos.editor.cpu.ReferenceRenderer
import com.roylet.photos.editor.gl.toArgbImage
import com.roylet.photos.editor.recipe.GeometryMath
import com.roylet.photos.editor.recipe.Recipe
import kotlin.math.roundToInt

/**
 * The live GPU preview. It uses a [TextureView] driven by its own EGL thread rather than the
 * engine's `GLSurfaceView`-based `EditorCanvas`: a TextureView composites like any other view, so
 * it fades, clips and slides correctly inside Compose animations and sheets.
 */
@Composable
fun GlPreview(session: EditorSession, modifier: Modifier = Modifier) {
    val renderer = session.renderer ?: return StaticPreview(session, modifier)
    LaunchedEffect(session) {
        renderer.imageBounds.collect { session.bounds.value = it }
    }
    AndroidView(
        factory = { ctx ->
            // `adb shell setprop log.tag.EditGl DEBUG` logs draft vs full frame times (see EditRenderer.measureFrames).
            renderer.measureFrames = Log.isLoggable("EditGl", Log.DEBUG)
            GlTextureView(ctx, renderer).also { v -> session.invalidate = v::requestRender }
        },
        modifier = modifier,
        onRelease = { v -> if (session.invalidate == v::requestRender) session.invalidate = {} },
    )
}

/**
 * A CPU-rendered stand-in for [GlPreview] where GL is unavailable (Robolectric screenshots,
 * previews). Same fit, crop-mode margin, bounds reporting and compare crossfade as the GPU path.
 */
@Composable
fun StaticPreview(session: EditorSession, modifier: Modifier = Modifier, cropMode: Boolean = false, compareMix: Float = 0f) {
    val recipe by session.history.current.collectAsState()
    val small = remember(session) { downscale(session.proxy, 560).toArgbImage() }
    val edited = remember(recipe, cropMode) { ReferenceRenderer.render(small, recipe, ignoreCrop = cropMode).toBitmap().asImageBitmap() }
    val original = remember(small) { ReferenceRenderer.render(small, Recipe()).toBitmap().asImageBitmap() }
    Canvas(modifier.fillMaxSize()) {
        fun place(r: Recipe, ignoreCrop: Boolean): RectF {
            val aspect = GeometryMath.outputAspect(r.geometry, session.fullWidth, session.fullHeight, ignoreCrop)
            val zoom = if (cropMode) EditorSession.CROP_ZOOM else 1f
            val viewAspect = size.width / size.height
            val fw = (if (aspect > viewAspect) size.width else size.height * aspect) * zoom
            val fh = (if (aspect > viewAspect) size.width / aspect else size.height) * zoom
            val l = (size.width - fw) / 2f
            val t = (size.height - fh) / 2f
            return RectF(l, t, l + fw, t + fh)
        }
        val mix = compareMix.coerceIn(0f, 1f)
        val editRect = place(recipe, cropMode)
        val originalRect = place(Recipe(), false)
        val b = if (mix < 0.5f) editRect else originalRect
        if (session.bounds.value != b) session.bounds.value = b
        drawIntoCanvas {
            // Each layer fades over the letterbox, so differently cropped frames crossfade cleanly.
            if (mix < 1f) drawImage(edited, dstOffset = IntOffset(editRect.left.roundToInt(), editRect.top.roundToInt()),
                dstSize = IntSize(editRect.width().roundToInt(), editRect.height().roundToInt()), alpha = 1f - mix, filterQuality = FilterQuality.High)
            if (mix > 0f) drawImage(original, dstOffset = IntOffset(originalRect.left.roundToInt(), originalRect.top.roundToInt()),
                dstSize = IntSize(originalRect.width().roundToInt(), originalRect.height().roundToInt()), alpha = mix, filterQuality = FilterQuality.High)
        }
    }
}

/** A TextureView that renders a [GLSurfaceView.Renderer] on demand on its own EGL (ES 3) thread. */
@SuppressLint("ViewConstructor")
internal class GlTextureView(context: Context, private val renderer: GLSurfaceView.Renderer) :
    TextureView(context), TextureView.SurfaceTextureListener {
    private var thread: GlThread? = null

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    fun requestRender() { thread?.requestRender() }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        thread = GlThread(surface, renderer).also { it.resize(width, height); it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        thread?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        thread?.finish()
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}

private class GlThread(private val texture: SurfaceTexture, private val renderer: GLSurfaceView.Renderer) : Thread("editor-gl") {
    private val lock = Object()
    private var dirty = true
    private var quit = false
    private var width = 0
    private var height = 0
    private var sizeChanged = true

    fun requestRender() = synchronized(lock) { dirty = true; lock.notifyAll() }

    fun resize(w: Int, h: Int) = synchronized(lock) { width = w; height = h; sizeChanged = true; dirty = true; lock.notifyAll() }

    fun finish() {
        synchronized(lock) { quit = true; lock.notifyAll() }
        runCatching { join(1500) }
    }

    override fun run() {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No ES3 config" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            surface = EGL14.eglCreateWindowSurface(display, configs[0], texture, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
            renderer.onSurfaceCreated(null, null)
            while (true) {
                var w: Int
                var h: Int
                var resized: Boolean
                synchronized(lock) {
                    while (!dirty && !quit) lock.wait()
                    if (quit) return
                    dirty = false
                    w = width; h = height; resized = sizeChanged; sizeChanged = false
                }
                if (resized) renderer.onSurfaceChanged(null, w, h)
                renderer.onDrawFrame(null)
                EGL14.eglSwapBuffers(display, surface)
            }
        } catch (e: Exception) {
            Log.e("GlPreview", "Preview rendering stopped", e)
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            }
        }
    }
}

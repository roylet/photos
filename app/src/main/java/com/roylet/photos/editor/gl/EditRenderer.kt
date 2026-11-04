package com.roylet.photos.editor.gl

import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.roylet.photos.editor.cpu.BlurMath
import com.roylet.photos.editor.recipe.GeometryMath
import com.roylet.photos.editor.recipe.LutResolver
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.recipe.RenderParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.roundToInt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Live GPU preview of a [Recipe] over a proxy bitmap, as a [GLSurfaceView.Renderer] (ES 3.0).
 * Drop it into Compose with [EditorCanvas], or attach it to your own `GLSurfaceView` and call
 * [attach].
 *
 * All setters are thread-safe and cheap (they store the value and request a frame), so
 * `setRecipe` can be called on every slider move. Full-resolution export is the static
 * [exportFullRes].
 */
class EditRenderer(private val lutResolver: LutResolver = LutResolver.Default) : GLSurfaceView.Renderer {

    private class State(
        val source: Bitmap? = null,
        val sourceVersion: Int = 0,
        val fullWidth: Int = 0,
        val fullHeight: Int = 0,
        val recipe: Recipe = Recipe(),
        val showOriginal: Boolean = false,
        val cropMode: Boolean = false,
        val zoom: Float = 1f,
        val panX: Float = 0f,
        val panY: Float = 0f,
        val background: Int = 0xFF000000.toInt(),
        /** 0 = the edit, 1 = the original; in between, a crossfade (hold-to-compare). */
        val compareMix: Float = 0f,
        /** Render at reduced resolution (a gesture is in flight). */
        val draft: Boolean = false,
    )

    @Volatile private var state = State()
    private val lock = Any()
    private var view: WeakReference<GLSurfaceView>? = null

    // GL-thread only.
    private var pipeline: GlPipeline? = null
    private var uploadedVersion = -1
    private var blurVersion = -1
    private var viewW = 0
    private var viewH = 0
    private var lastRecipe: Recipe? = null
    private var lastParams: RenderParams? = null
    private var compositor: PreviewCompositor? = null
    private val originalParams by lazy { RenderParams.from(Recipe(), lutResolver) }

    /**
     * Debug aid: when true each frame ends with `glFinish()` and the average draft / full frame
     * times are logged (tag `EditGl`) every 60 frames. `GlPreview` turns it on when
     * `adb shell setprop log.tag.EditGl DEBUG` is set.
     */
    @Volatile var measureFrames = false
    private val frameTimes = FrameTimes()

    private val _imageBounds = MutableStateFlow<RectF?>(null)

    /** Where the image is currently drawn, in view pixels (null before the first frame). */
    val imageBounds: StateFlow<RectF?> = _imageBounds.asStateFlow()

    private inline fun update(f: (State) -> State) {
        synchronized(lock) { state = f(state) }
        view?.get()?.requestRender()
    }

    /** Sets the view to draw into; [EditorCanvas] does this for you. */
    fun attach(surfaceView: GLSurfaceView) { view = WeakReference(surfaceView) }

    fun detach(surfaceView: GLSurfaceView) { if (view?.get() === surfaceView) view = null }

    /**
     * Sets the preview source (a ~2048 px proxy). [fullWidth]/[fullHeight] are the full-res
     * oriented dimensions, used for exact aspect ratios. The bitmap must stay valid (not
     * recycled) while it is the source.
     */
    fun setSource(bitmap: Bitmap, fullWidth: Int = bitmap.width, fullHeight: Int = bitmap.height) {
        val soft = bitmap.toSoftwareArgb()
        update { State(soft, it.sourceVersion + 1, fullWidth, fullHeight, it.recipe, it.showOriginal,
            it.cropMode, it.zoom, it.panX, it.panY, it.background, it.compareMix, it.draft) }
    }

    /**
     * Sets the recipe. [draft] renders this frame at reduced resolution (long edge ≤ [DRAFT_EDGE]);
     * pass it while a gesture is in flight and call [setDraft] `(false)` shortly after it ends.
     */
    fun setRecipe(recipe: Recipe, draft: Boolean = false) = update { it.copy(recipe = recipe, draft = draft) }

    /** Leaves (or enters) draft quality. Returns false (and asks for no frame) when unchanged. */
    fun setDraft(draft: Boolean): Boolean {
        if (state.draft == draft) return false
        update { it.copy(draft = draft) }
        return true
    }

    internal val isDraft: Boolean get() = state.draft
    internal val compareMix: Float get() = state.compareMix

    /** Hold-to-compare: draw the untouched source (no geometry, no colour). */
    fun setShowOriginal(show: Boolean) = setCompareMix(if (show) 1f else 0f)

    /** Crossfade between the edit (0) and the untouched original (1); animate it for hold-to-compare. */
    fun setCompareMix(mix: Float) {
        val m = mix.coerceIn(0f, 1f)
        if (state.compareMix == m) return
        update { it.copy(compareMix = m, showOriginal = m >= 1f) }
    }

    /**
     * Crop tool mode: draw the whole frame (rotation, flips, straighten and perspective applied,
     * crop ignored) so the UI can draw a crop overlay over [imageBounds].
     */
    fun setCropMode(enabled: Boolean) = update { it.copy(cropMode = enabled) }

    /** Preview zoom (1 = fit) and pan in view pixels from centre. */
    fun setViewTransform(zoom: Float, panX: Float, panY: Float) =
        update { it.copy(zoom = zoom.coerceAtLeast(0.05f), panX = panX, panY = panY) }

    /** Letterbox colour (ARGB). */
    fun setBackgroundColor(argb: Int) = update { it.copy(background = argb) }

    fun requestRender() { view?.get()?.requestRender() }

    private fun State.copy(
        recipe: Recipe = this.recipe, showOriginal: Boolean = this.showOriginal, cropMode: Boolean = this.cropMode,
        zoom: Float = this.zoom, panX: Float = this.panX, panY: Float = this.panY, background: Int = this.background,
        compareMix: Float = this.compareMix, draft: Boolean = this.draft,
    ) = State(source, sourceVersion, fullWidth, fullHeight, recipe, showOriginal, cropMode, zoom, panX, panY, background, compareMix, draft)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: everything from the old one is gone.
        pipeline = GlPipeline()
        compositor = null
        uploadedVersion = -1
        blurVersion = -1
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width; viewH = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val s = state
        val bg = s.background
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        clear(bg)
        val p = pipeline ?: return
        val src = s.source ?: return
        if (src.isRecycled || viewW == 0 || viewH == 0) return
        if (uploadedVersion != s.sourceVersion) {
            p.uploadSource(src)
            uploadedVersion = s.sourceVersion
        }
        val started = if (measureFrames) System.nanoTime() else 0L
        val mix = s.compareMix
        val scale = if (s.draft) draftScale(viewW, viewH) else 1f
        if (scale >= 1f && (mix <= 0f || mix >= 1f)) {
            // The common case: straight to the screen.
            drawScene(p, s, original = mix >= 1f, report = true)
        } else {
            val c = compositor ?: PreviewCompositor().also { compositor = it }
            val w = max(1, (viewW * scale).roundToInt())
            val h = max(1, (viewH * scale).roundToInt())
            if (mix < 1f) {
                c.edit.bind(w, h); clear(bg)
                drawScene(p, s, original = false, report = mix < 0.5f)
            }
            if (mix > 0f) {
                // The original is cheap (no colour work), so it is never drafted.
                c.original.bind(w, h); clear(bg)
                drawScene(p, s, original = true, report = mix >= 0.5f)
            }
            c.present(viewW, viewH, mix)
        }
        if (measureFrames) {
            GLES30.glFinish()
            frameTimes.add(draft = scale < 1f, nanos = System.nanoTime() - started)
        }
    }

    private fun clear(bg: Int) {
        GLES30.glClearColor((bg shr 16 and 0xFF) / 255f, (bg shr 8 and 0xFF) / 255f, (bg and 0xFF) / 255f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    /**
     * Draws the edit (or the untouched [original]) into the bound framebuffer, which may be the
     * screen or a smaller offscreen target: the placement is computed in view pixels and only the
     * viewport changes, so a draft frame shows exactly the same picture, just softer.
     */
    private fun drawScene(p: GlPipeline, s: State, original: Boolean, report: Boolean) {
        val src = s.source ?: return
        val recipe = if (original) Recipe() else s.recipe
        val params = when {
            original -> originalParams
            recipe == lastRecipe -> lastParams!!
            else -> RenderParams.from(recipe, lutResolver).also { lastRecipe = recipe; lastParams = it }
        }
        if ((params.clarity != 0f || params.dehaze != 0f) && blurVersion != s.sourceVersion) {
            p.uploadBlur(BlurMath.blurredCopy(src.toArgbImage()))
            blurVersion = s.sourceVersion
        }

        val fw = if (s.fullWidth > 0) s.fullWidth else src.width
        val fh = if (s.fullHeight > 0) s.fullHeight else src.height
        val g = recipe.geometry.normalized()
        val ignoreCrop = s.cropMode && !original
        val aspect = GeometryMath.outputAspect(g, fw, fh, ignoreCrop)
        val m = GeometryMath.outputToSource(g, fw, fh, ignoreCrop)

        // Fit, then zoom/pan.
        val viewAspect = viewW.toFloat() / viewH
        val fitW = if (aspect > viewAspect) viewW.toFloat() else viewH * aspect
        val fitH = if (aspect > viewAspect) viewW / aspect else viewH.toFloat()
        val dw = fitW * s.zoom
        val dh = fitH * s.zoom
        val clip = floatArrayOf(dw / viewW, -dh / viewH, 2f * s.panX / viewW, -2f * s.panY / viewH)
        GLES30.glDisable(GLES30.GL_BLEND)
        p.draw(params, m.toColumnMajor(), floatArrayOf(0f, 0f, 1f, 1f), clip, floatArrayOf(0f, 0f, 1f, 1f),
            RenderParams.grainScale(aspect))

        if (!report) return
        val cx = viewW / 2f + s.panX
        val cy = viewH / 2f + s.panY
        val bounds = RectF(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)
        if (_imageBounds.value != bounds) _imageBounds.value = bounds
    }

    /** Rolling averages of draft and full frames, for [measureFrames]. */
    private class FrameTimes {
        private var draftSum = 0L; private var draftN = 0
        private var fullSum = 0L; private var fullN = 0

        fun add(draft: Boolean, nanos: Long) {
            if (draft) { draftSum += nanos; draftN++ } else { fullSum += nanos; fullN++ }
            if (draftN + fullN >= 60) {
                Log.d("EditGl", "preview frame: draft %.2f ms (%d), full %.2f ms (%d)".format(
                    if (draftN > 0) draftSum / 1e6 / draftN else 0.0, draftN, if (fullN > 0) fullSum / 1e6 / fullN else 0.0, fullN))
                draftSum = 0; draftN = 0; fullSum = 0; fullN = 0
            }
        }
    }

    companion object {
        /** Long edge, in pixels, of the preview while a gesture is in flight. */
        const val DRAFT_EDGE = 1024

        /** Scale (≤ 1) of a draft frame for a [viewW]×[viewH] view: its long edge becomes at most [edge]. */
        fun draftScale(viewW: Int, viewH: Int, edge: Int = DRAFT_EDGE): Float {
            val long = max(viewW, viewH)
            return if (long <= edge) 1f else edge.toFloat() / long
        }

        /**
         * Renders [recipe] over the full-resolution [source] offscreen, in FBO tiles of at most
         * [maxTileSize] (and `GL_MAX_TEXTURE_SIZE`) so neither the source nor the output has to
         * fit in one texture. Blocking and GPU-heavy: call from a background thread (it creates
         * and makes current its own EGL context, restoring the previous one afterwards). Never
         * call it on a `GLSurfaceView`'s render thread.
         *
         * @param maxOutputPixels optional memory budget; the output is scaled down to fit.
         * @param blurBasis optional downscaled copy (e.g. the preview proxy) for clarity/dehaze,
         *   saving a decode.
         */
        fun exportFullRes(
            source: ExportSource,
            recipe: Recipe,
            lutResolver: LutResolver = LutResolver.Default,
            maxTileSize: Int = 2048,
            maxOutputPixels: Long? = null,
            blurBasis: Bitmap? = null,
        ): Bitmap = GlExporter.export(source, recipe, lutResolver, maxTileSize, maxOutputPixels, blurBasis)

        /** [exportFullRes] over an in-memory full-resolution bitmap. */
        fun exportFullRes(
            source: Bitmap,
            recipe: Recipe,
            lutResolver: LutResolver = LutResolver.Default,
            maxTileSize: Int = 2048,
        ): Bitmap = exportFullRes(BitmapExportSource(source), recipe, lutResolver, maxTileSize)
    }
}

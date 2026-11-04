package com.roylet.photos.editor.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.roylet.photos.editor.cpu.ArgbImage
import com.roylet.photos.editor.cpu.ReferenceRenderer
import com.roylet.photos.editor.gl.EditRenderer
import com.roylet.photos.editor.gl.toArgbImage
import com.roylet.photos.editor.recipe.GeometryMath
import com.roylet.photos.editor.recipe.History
import com.roylet.photos.editor.recipe.Presets
import com.roylet.photos.editor.recipe.Recipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One open photo in the editor: the preview proxy, the undo [history], and the GPU [renderer]
 * (null where GL can't run, e.g. screenshot tests, which draw [StaticPreview] instead).
 *
 * Recipe changes go straight to the renderer through [push]; Compose only reads the recipe in
 * the leaf widgets that show a value.
 */
@Stable
class EditorSession(
    val proxy: Bitmap,
    /** Full-resolution oriented size. */
    val fullWidth: Int = proxy.width,
    val fullHeight: Int = proxy.height,
    /** The recipe the session opened with; anything else is unsaved. */
    val initial: Recipe = Recipe(),
    val renderer: EditRenderer? = EditRenderer(),
) {
    val history = History(initial)

    /** Where the image is drawn inside the canvas, in canvas pixels. */
    val bounds = MutableStateFlow<RectF?>(null)

    /** Crop aspect lock (UI state shared by the crop panel and overlay). */
    var cropAspect by mutableStateOf(AspectPreset.FREE)

    /** Small previews of each built-in filter, keyed by preset id ("" = none). */
    val filterThumbs = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    /** Asks the preview view for a frame (set by [GlPreview]). */
    @Volatile internal var invalidate: () -> Unit = {}

    private var cropMode = false
    private var showOriginal = false
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    val isDirty: Boolean get() = history.recipe != initial

    init {
        renderer?.setSource(proxy, fullWidth, fullHeight)
        renderer?.setRecipe(initial)
        renderer?.setBackgroundColor(0xFF111111.toInt())
    }

    fun push(recipe: Recipe) { renderer?.setRecipe(recipe); invalidate() }

    fun setCropMode(enabled: Boolean) {
        if (cropMode == enabled) return
        cropMode = enabled
        renderer?.setCropMode(enabled)
        // A margin round the full frame so the crop handles never sit on the screen edge.
        if (enabled) setView(CROP_ZOOM, 0f, 0f) else setView(1f, 0f, 0f)
        invalidate()
    }

    fun setShowOriginal(show: Boolean) {
        if (showOriginal == show) return
        showOriginal = show
        renderer?.setShowOriginal(show)
        invalidate()
    }

    fun setView(zoom: Float, panX: Float, panY: Float) {
        this.zoom = zoom; this.panX = panX; this.panY = panY
        renderer?.setViewTransform(zoom, panX, panY)
        invalidate()
    }

    val viewZoom get() = zoom

    /** Aspect (w/h) of the frame the crop lives in: the oriented source after quarter turns. */
    fun frameAspect(recipe: Recipe): Float {
        val (w, h) = GeometryMath.frameSize(recipe.geometry, fullWidth, fullHeight)
        return w.toFloat() / h
    }

    /** Renders [filterThumbs] on the CPU. Blocking; call off the main thread. */
    fun renderFilterThumbs(edge: Int = 168) {
        val small = scaled(edge)
        val src = small.toArgbImage()
        val out = LinkedHashMap<String, ImageBitmap>()
        out[""] = small.asImageBitmap()
        for (p in Presets.all) {
            out[p.id] = ReferenceRenderer.render(src, Recipe(filter = p.filter())).toBitmap().asImageBitmap()
        }
        filterThumbs.value = out
    }

    /** A centre square of the proxy, [edge] px, for thumbnails. */
    private fun scaled(edge: Int): Bitmap {
        val side = minOf(proxy.width, proxy.height)
        val sq = Bitmap.createBitmap(proxy, (proxy.width - side) / 2, (proxy.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(sq, edge, edge, true)
    }

    companion object {
        const val CROP_ZOOM = 0.86f
    }
}

internal fun ArgbImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

/** [bitmap] scaled down so its long edge is at most [edge]. */
internal fun downscale(bitmap: Bitmap, edge: Int): Bitmap {
    val long = max(bitmap.width, bitmap.height)
    if (long <= edge) return bitmap
    val s = edge.toFloat() / long
    return Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * s).roundToInt()), max(1, (bitmap.height * s).roundToInt()), true)
}

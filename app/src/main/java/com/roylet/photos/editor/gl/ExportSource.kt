package com.roylet.photos.editor.gl

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.Closeable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A full-resolution, already EXIF-oriented image that can be read in pieces, so export never
 * needs the whole source in one texture. See `io/SourceLoader` for the file-backed version.
 */
interface ExportSource : Closeable {
    /** Oriented full-resolution size. */
    val width: Int
    val height: Int

    /**
     * Decodes the oriented pixel rect [rect] as a software ARGB_8888 bitmap whose long edge is at
     * most [maxEdge] (it may be subsampled; callers map by the requested rect, not the bitmap
     * size).
     */
    fun decodeRegion(rect: Rect, maxEdge: Int): Bitmap

    /** The whole image with its long edge at most [maxEdge]. */
    fun decodePreview(maxEdge: Int): Bitmap = decodeRegion(Rect(0, 0, width, height), maxEdge)

    override fun close() {}
}

/** An [ExportSource] over an in-memory bitmap (not recycled on close). */
class BitmapExportSource(private val bitmap: Bitmap) : ExportSource {
    private val soft = bitmap.toSoftwareArgb()
    override val width: Int get() = soft.width
    override val height: Int get() = soft.height

    override fun decodeRegion(rect: Rect, maxEdge: Int): Bitmap {
        val r = Rect(rect).apply { intersect(0, 0, width, height) }
        if (r.isEmpty) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val region = if (r.left == 0 && r.top == 0 && r.width() == width && r.height() == height) soft
        else Bitmap.createBitmap(soft, r.left, r.top, r.width(), r.height())
        return region.scaledToFit(maxEdge)
    }
}

internal fun Bitmap.toSoftwareArgb(): Bitmap =
    if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)

internal fun Bitmap.scaledToFit(maxEdge: Int): Bitmap {
    val long = max(width, height)
    if (long <= maxEdge) return this
    val s = maxEdge.toFloat() / long
    return Bitmap.createScaledBitmap(this, max(1, (width * s).roundToInt()), max(1, (height * s).roundToInt()), true)
}

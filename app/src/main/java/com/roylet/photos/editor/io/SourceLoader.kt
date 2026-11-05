package com.roylet.photos.editor.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.roylet.photos.editor.gl.BitmapExportSource
import com.roylet.photos.editor.gl.ExportSource
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A photo opened for editing: a preview [proxy] (EXIF orientation applied, software
 * ARGB_8888, sRGB) plus everything needed to re-read the full resolution for export.
 */
class LoadedEdit internal constructor(
    val file: File,
    /** e.g. `image/jpeg`, `image/heic`; null if the decoder could not tell. */
    val mimeType: String?,
    /** Full-resolution oriented size. */
    val width: Int,
    val height: Int,
    /** The file's EXIF orientation (1–8), already applied to [proxy] and [openFullRes]. */
    val exifOrientation: Int,
    val proxy: Bitmap,
) {
    /**
     * Opens the full-resolution image for [com.roylet.photos.editor.gl.EditRenderer.exportFullRes].
     * Uses `BitmapRegionDecoder` (tiles; bounded memory) where the format allows, falling back to
     * one full `ImageDecoder` decode. Close it when done.
     */
    fun openFullRes(): ExportSource =
        runCatching { RegionDecoderSource(file, exifOrientation) }
            .onFailure { Log.i(TAG, "Region decoding unavailable for ${file.name}: ${it.message}") }
            .getOrNull()
            ?: BitmapExportSource(SourceLoader.decodeFull(file))

    private companion object { const val TAG = "LoadedEdit" }
}

/** Decodes photos for the editor. All methods block; call them off the main thread. */
object SourceLoader {
    const val DEFAULT_PROXY_EDGE = 2048

    /** Opens [file] (JPEG, HEIC/HEIF, PNG, WebP, AVIF where supported, DNG previews…). */
    fun load(file: File, proxyLongEdge: Int = DEFAULT_PROXY_EDGE): LoadedEdit {
        val orientation = readOrientation(file)
        var mime: String? = null
        var headerW = 0
        var headerH = 0
        val proxy = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            mime = info.mimeType
            headerW = info.size.width
            headerH = info.size.height
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val long = max(headerW, headerH)
            var sample = 1
            while (long / (sample * 2) >= proxyLongEdge) sample *= 2
            // Sample size keeps the aspect ratio whatever orientation the header reports.
            decoder.setTargetSampleSize(sample)
        }.let { scaleToFit(it.toArgb8888(), proxyLongEdge) }

        // Full oriented size: raw bounds rotated by EXIF; fall back to the decoder's header size
        // matched to the proxy's aspect.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val (fullW, fullH) = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            Orientation.orientedSize(orientation, bounds.outWidth, bounds.outHeight)
        } else {
            val landscape = proxy.width >= proxy.height
            if ((headerW >= headerH) == landscape) headerW to headerH else headerH to headerW
        }
        return LoadedEdit(file, mime, fullW, fullH, orientation, proxy)
    }

    fun readOrientation(file: File): Int = runCatching {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL).let { if (it in 1..8) it else 1 }

    /** One full-resolution oriented decode (fallback when regions are unsupported). */
    internal fun decodeFull(file: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }.toArgb8888()

    internal fun scaleToFit(b: Bitmap, maxEdge: Int): Bitmap {
        val long = max(b.width, b.height)
        if (long <= maxEdge) return b
        val s = maxEdge.toFloat() / long
        val out = Bitmap.createScaledBitmap(b, max(1, (b.width * s).roundToInt()), max(1, (b.height * s).roundToInt()), true)
        if (out !== b) b.recycle()
        return out
    }

    internal fun Bitmap.toArgb8888(): Bitmap =
        if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false).also { recycle() }
}

/** Reads oriented regions of a file through [BitmapRegionDecoder] (JPEG, PNG, WebP, HEIF). */
internal class RegionDecoderSource(file: File, private val orientation: Int) : ExportSource {
    private val decoder: BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file.path)
        else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(file.path, false)
            ?: error("No region decoder")
    private val rawW = decoder.width
    private val rawH = decoder.height
    override val width: Int
    override val height: Int

    init {
        val (w, h) = Orientation.orientedSize(orientation, rawW, rawH)
        width = w; height = h
    }

    override fun decodeRegion(rect: Rect, maxEdge: Int): Bitmap {
        val r = Orientation.orientedRectToRaw(orientation, rect.left, rect.top, rect.right, rect.bottom, rawW, rawH)
        val raw = Rect(r[0], r[1], r[2], r[3]).apply { intersect(0, 0, rawW, rawH) }
        val long = max(raw.width(), raw.height())
        var sample = 1
        while (long / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val decoded = synchronized(decoder) { decoder.decodeRegion(raw, opts) }
            ?: error("decodeRegion failed for $raw")
        val oriented = applyOrientation(decoded, orientation)
        return SourceLoader.scaleToFit(oriented, maxEdge)
    }

    override fun close() = decoder.recycle()

    companion object {
        /** Rotates/mirrors a raw bitmap into display orientation (ExifInterface conventions). */
        fun applyOrientation(b: Bitmap, orientation: Int): Bitmap {
            val m = Matrix()
            when (orientation) {
                2 -> m.setScale(-1f, 1f)
                3 -> m.setRotate(180f)
                4 -> { m.setRotate(180f); m.postScale(-1f, 1f) }
                5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
                6 -> m.setRotate(90f)
                7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
                8 -> m.setRotate(-90f)
                else -> return b
            }
            val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (out !== b) b.recycle()
            return out
        }
    }
}

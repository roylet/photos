package com.roylet.photos.cast

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Turns any image the phone can decode (HEIC, PNG, big JPEGs, ...) into a TV-sized JPEG. */
fun interface JpegTranscoder {
    fun transcode(image: ByteArray, maxEdge: Int): ByteArray
}

/** What [PhoneMediaProxy] does with a phone photo before the TV gets it. */
object PhotoPlan {
    /**
     * The receiver shows at most 1280×720 (1080p on newer models), so 1920 px on the long edge
     * is plenty and keeps phone → TV transfers quick.
     */
    const val MAX_EDGE = 1920

    /** A JPEG already that small goes as is; anything else (HEIC, PNG, a 12 MP JPEG) is transcoded. */
    fun passThrough(mime: String?, width: Int, height: Int): Boolean =
        mime.equals("image/jpeg", ignoreCase = true) && width > 0 && height > 0 && max(width, height) <= MAX_EDGE

    /** The decoded size for a [width]×[height] source: the long edge capped at [maxEdge], aspect kept. */
    fun target(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= maxEdge || long <= 0) return width to height
        val s = maxEdge.toDouble() / long
        return (width * s).roundToInt().coerceAtLeast(1) to (height * s).roundToInt().coerceAtLeast(1)
    }
}

/**
 * ImageDecoder (HEIF/AVIF/WebP/PNG/JPEG, EXIF orientation applied, downsampled while decoding),
 * falling back to BitmapFactory + ExifInterface when ImageDecoder can't (old codecs, tests).
 */
class AndroidJpegTranscoder(private val quality: Int = 88) : JpegTranscoder {
    override fun transcode(image: ByteArray, maxEdge: Int): ByteArray {
        val bitmap = runCatching { decodeWithImageDecoder(image, maxEdge) }.getOrNull() ?: decodeWithBitmapFactory(image, maxEdge)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun decodeWithImageDecoder(image: ByteArray, maxEdge: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(image))) { decoder, info, _ ->
            val (w, h) = PhotoPlan.target(info.size.width, info.size.height, maxEdge)
            decoder.setTargetSize(w, h)
            // Software, so compress() can read it.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun decodeWithBitmapFactory(image: ByteArray, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw java.io.IOException("Couldn't decode this photo")
        val (w, h) = PhotoPlan.target(decoded.width, decoded.height, maxEdge)
        val scaled = if (w != decoded.width) Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() } else decoded
        val degrees = runCatching { ExifInterface(ByteArrayInputStream(image)).rotationDegrees }.getOrDefault(0)
        if (degrees == 0) return scaled
        val rotated = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (rotated !== scaled) scaled.recycle()
        return rotated
    }
}

package com.roylet.photos.editor.cpu

import com.roylet.photos.editor.recipe.PipelineConstants
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An opaque image as packed `0xAARRGGBB` ints (the layout of `Bitmap.getPixels`). */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init { require(pixels.size == width * height) { "pixels must hold width*height ints" } }

    operator fun get(x: Int, y: Int) = pixels[y * width + x]
    operator fun set(x: Int, y: Int, argb: Int) { pixels[y * width + x] = argb }

    /**
     * Bilinear sample at uv (`0..1`) with clamp-to-edge, matching `texture()` on a GL_LINEAR,
     * CLAMP_TO_EDGE texture. Writes `0..1` RGB into [out].
     */
    fun sample(u: Float, v: Float, out: FloatArray): FloatArray {
        val fx = u * width - 0.5f
        val fy = v * height - 0.5f
        val x0f = kotlin.math.floor(fx)
        val y0f = kotlin.math.floor(fy)
        val tx = fx - x0f
        val ty = fy - y0f
        val x0 = x0f.toInt().coerceIn(0, width - 1)
        val y0 = y0f.toInt().coerceIn(0, height - 1)
        val x1 = (x0f.toInt() + 1).coerceIn(0, width - 1)
        val y1 = (y0f.toInt() + 1).coerceIn(0, height - 1)
        val p00 = pixels[y0 * width + x0]
        val p10 = pixels[y0 * width + x1]
        val p01 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]
        for (c in 0..2) {
            val shift = 16 - c * 8
            val a = (p00 shr shift and 0xFF) + ((p10 shr shift and 0xFF) - (p00 shr shift and 0xFF)) * tx
            val b = (p01 shr shift and 0xFF) + ((p11 shr shift and 0xFF) - (p01 shr shift and 0xFF)) * tx
            out[c] = (a + (b - a) * ty) / 255f
        }
        return out
    }

    companion object {
        fun pack(r: Float, g: Float, b: Float): Int {
            val ri = (r.coerceIn(0f, 1f) * 255f).roundToInt()
            val gi = (g.coerceIn(0f, 1f) * 255f).roundToInt()
            val bi = (b.coerceIn(0f, 1f) * 255f).roundToInt()
            return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        fun filled(width: Int, height: Int, argb: Int) = ArgbImage(width, height, IntArray(width * height) { argb })
    }
}

/**
 * The blurred copy clarity and dehaze compare against: an area-averaged downsample to
 * [PipelineConstants.BLUR_LONG_EDGE] followed by three box-blur passes. The GL renderer uploads
 * exactly this image, so preview, export and the CPU reference share one blur.
 */
object BlurMath {
    fun blurredCopy(src: ArgbImage, longEdge: Int = PipelineConstants.BLUR_LONG_EDGE): ArgbImage {
        val scale = min(1f, longEdge.toFloat() / max(src.width, src.height))
        val w = max(1, (src.width * scale).roundToInt())
        val h = max(1, (src.height * scale).roundToInt())
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        downsample(src, w, h, r, g, b)
        val radius = max(1, (max(w, h) * PipelineConstants.BLUR_RADIUS_FRACTION).roundToInt())
        val tmp = FloatArray(w * h)
        for (ch in arrayOf(r, g, b)) repeat(3) {
            boxH(ch, tmp, w, h, radius)
            boxV(tmp, ch, w, h, radius)
        }
        return ArgbImage(w, h, IntArray(w * h) { ArgbImage.pack(r[it], g[it], b[it]) })
    }

    private fun downsample(src: ArgbImage, w: Int, h: Int, r: FloatArray, g: FloatArray, b: FloatArray) {
        val count = FloatArray(w * h)
        for (y in 0 until src.height) {
            val ty = (y.toLong() * h / src.height).toInt()
            for (x in 0 until src.width) {
                val tx = (x.toLong() * w / src.width).toInt()
                val i = ty * w + tx
                val p = src.pixels[y * src.width + x]
                r[i] += (p shr 16 and 0xFF) / 255f
                g[i] += (p shr 8 and 0xFF) / 255f
                b[i] += (p and 0xFF) / 255f
                count[i] += 1f
            }
        }
        for (i in count.indices) if (count[i] > 0f) { r[i] /= count[i]; g[i] /= count[i]; b[i] /= count[i] }
    }

    private fun boxH(src: FloatArray, dst: FloatArray, w: Int, h: Int, radius: Int) {
        val norm = 1f / (2 * radius + 1)
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (k in -radius..radius) acc += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = acc * norm
                acc += src[row + (x + radius + 1).coerceAtMost(w - 1)] - src[row + (x - radius).coerceAtLeast(0)]
            }
        }
    }

    private fun boxV(src: FloatArray, dst: FloatArray, w: Int, h: Int, radius: Int) {
        val norm = 1f / (2 * radius + 1)
        for (x in 0 until w) {
            var acc = 0f
            for (k in -radius..radius) acc += src[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = acc * norm
                acc += src[(y + radius + 1).coerceAtMost(h - 1) * w + x] - src[(y - radius).coerceAtLeast(0) * w + x]
            }
        }
    }
}

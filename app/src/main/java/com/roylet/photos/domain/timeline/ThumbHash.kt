package com.roylet.photos.domain.timeline

import java.util.Base64
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decoder for ThumbHash (https://evanw.github.io/thumbhash/), the ~25-byte blurry previews Immich
 * returns per asset. A Kotlin port of the reference `thumbHashToRGBA`; pure JVM so it can be unit
 * tested and moved to shared code. The UI turns [Image.argb] into a Bitmap.
 */
object ThumbHash {
    /** A decoded preview, at most 32px on its long side. [argb] is packed 0xAARRGGBB, row-major. */
    class Image(val width: Int, val height: Int, val argb: IntArray)

    fun decode(base64: String): Image? = runCatching { decode(Base64.getDecoder().decode(base64)) }.getOrNull()

    fun decode(hash: ByteArray): Image {
        require(hash.size >= 5) { "ThumbHash too short" }
        fun u(i: Int) = if (i < hash.size) hash[i].toInt() and 0xFF else 0
        val header24 = u(0) or (u(1) shl 8) or (u(2) shl 16)
        val header16 = u(3) or (u(4) shl 8)
        val lDc = (header24 and 63) / 63f
        val pDc = ((header24 shr 6) and 63) / 31.5f - 1f
        val qDc = ((header24 shr 12) and 63) / 31.5f - 1f
        val lScale = ((header24 shr 18) and 31) / 31f
        val hasAlpha = (header24 shr 23) != 0
        val pScale = ((header16 shr 3) and 63) / 63f
        val qScale = ((header16 shr 9) and 63) / 63f
        val isLandscape = (header16 shr 15) != 0
        val lx = max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else header16 and 7)
        val ly = max(3, if (isLandscape) header16 and 7 else (if (hasAlpha) 5 else 7))
        val aDc = if (hasAlpha) (u(5) and 15) / 15f else 1f
        val aScale = (u(5) shr 4) / 15f

        val acStart = if (hasAlpha) 6 else 5
        var acIndex = 0
        fun channel(nx: Int, ny: Int, scale: Float): FloatArray {
            val ac = ArrayList<Float>()
            for (cy in 0 until ny) {
                var cx = if (cy > 0) 0 else 1
                while (cx * ny < nx * (ny - cy)) {
                    val nibble = (u(acStart + (acIndex shr 1)) shr ((acIndex and 1) shl 2)) and 15
                    ac += (nibble / 7.5f - 1f) * scale
                    acIndex++
                    cx++
                }
            }
            return ac.toFloatArray()
        }
        val lAc = channel(lx, ly, lScale)
        // Saturation is boosted 1.25x to compensate for quantisation, as in the reference.
        val pAc = channel(3, 3, pScale * 1.25f)
        val qAc = channel(3, 3, qScale * 1.25f)
        val aAc = if (hasAlpha) channel(5, 5, aScale) else FloatArray(0)

        val ratio = aspectRatio(hash)
        val w = (if (ratio > 1f) 32f else 32f * ratio).roundToInt().coerceAtLeast(1)
        val h = (if (ratio > 1f) 32f / ratio else 32f).roundToInt().coerceAtLeast(1)
        val out = IntArray(w * h)
        val nFx = max(lx, if (hasAlpha) 5 else 3)
        val nFy = max(ly, if (hasAlpha) 5 else 3)
        val fx = FloatArray(nFx)
        val fy = FloatArray(nFy)
        var i = 0
        for (y in 0 until h) {
            for (cy in 0 until nFy) fy[cy] = cos(PI / h * (y + 0.5) * cy).toFloat()
            for (x in 0 until w) {
                var l = lDc
                var p = pDc
                var q = qDc
                var a = aDc
                for (cx in 0 until nFx) fx[cx] = cos(PI / w * (x + 0.5) * cx).toFloat()

                var j = 0
                for (cy in 0 until ly) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fy[cy] * 2f
                    while (cx * ly < lx * (ly - cy)) { l += lAc[j++] * fx[cx] * fy2; cx++ }
                }
                j = 0
                for (cy in 0 until 3) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fy[cy] * 2f
                    while (cx < 3 - cy) {
                        val f = fx[cx] * fy2
                        p += pAc[j] * f
                        q += qAc[j] * f
                        j++; cx++
                    }
                }
                if (hasAlpha) {
                    j = 0
                    for (cy in 0 until 5) {
                        var cx = if (cy > 0) 0 else 1
                        val fy2 = fy[cy] * 2f
                        while (cx < 5 - cy) { a += aAc[j++] * fx[cx] * fy2; cx++ }
                    }
                }
                val b = l - 2f / 3f * p
                val r = (3f * l - b + q) / 2f
                val g = r - q
                out[i++] = (channel8(a) shl 24) or (channel8(r) shl 16) or (channel8(g) shl 8) or channel8(b)
            }
        }
        return Image(w, h, out)
    }

    /** Width / height of the original image, as approximated by the hash. */
    fun aspectRatio(hash: ByteArray): Float {
        val header = hash[3].toInt() and 0xFF
        val hasAlpha = (hash[2].toInt() and 0x80) != 0
        val isLandscape = (hash[4].toInt() and 0x80) != 0
        val lx = if (isLandscape) (if (hasAlpha) 5 else 7) else header and 7
        val ly = if (isLandscape) header and 7 else (if (hasAlpha) 5 else 7)
        return if (ly == 0) 1f else lx.toFloat() / ly
    }

    private fun channel8(v: Float): Int = (255f * min(1f, max(0f, v))).toInt()
}

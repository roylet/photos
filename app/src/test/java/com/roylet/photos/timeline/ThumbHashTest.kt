package com.roylet.photos.timeline

import com.roylet.photos.domain.timeline.ThumbHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

class ThumbHashTest {
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun a(c: Int) = (c ushr 24) and 0xFF

    @Test fun solidColourRoundTrips() {
        val img = image(40, 30) { _, _ -> intArrayOf(200, 80, 40, 255) }
        val out = ThumbHash.decode(ThumbHashEncoder.encode(40, 30, img))
        // Landscape: 32 wide, ~24 tall.
        assertEquals(32, out.width)
        assertTrue(out.height in 20..26)
        val mid = out.argb[out.width * (out.height / 2) + out.width / 2]
        assertEquals(255, a(mid))
        assertTrue("red ${r(mid)}", abs(r(mid) - 200) <= 16)
        assertTrue("green ${g(mid)}", abs(g(mid) - 80) <= 16)
        assertTrue("blue ${b(mid)}", abs(b(mid) - 40) <= 16)
    }

    @Test fun gradientKeepsDirectionAndPortraitShape() {
        // Dark at the top, bright at the bottom, portrait.
        val img = image(30, 60) { _, y -> val v = (y * 255 / 59); intArrayOf(v, v, v, 255) }
        val out = ThumbHash.decode(ThumbHashEncoder.encode(30, 60, img))
        assertEquals(32, out.height)
        assertTrue(out.width in 14..18)
        val top = out.argb[out.width / 2]
        val bottom = out.argb[out.width * (out.height - 1) + out.width / 2]
        assertTrue("top ${r(top)} < bottom ${r(bottom)}", r(top) + 120 < r(bottom))
    }

    @Test fun transparencyIsDecoded() {
        val img = image(32, 32) { x, _ -> intArrayOf(10, 200, 10, if (x < 16) 0 else 255) }
        val out = ThumbHash.decode(ThumbHashEncoder.encode(32, 32, img))
        val left = out.argb[out.width * 16 + 2]
        val right = out.argb[out.width * 16 + out.width - 3]
        assertTrue("left alpha ${a(left)} right ${a(right)}", a(left) < 80 && a(right) > 180)
    }

    @Test fun decodesBase64AndRejectsGarbage() {
        val hash = Base64.getEncoder().encodeToString(ThumbHashEncoder.encode(8, 8, image(8, 8) { _, _ -> intArrayOf(1, 2, 3, 255) }))
        val out = ThumbHash.decode(hash)!!
        assertEquals(32, out.width)
        assertNull(ThumbHash.decode("not base64 !!"))
        assertNull(ThumbHash.decode("AAE="))
    }

    private fun image(w: Int, h: Int, px: (Int, Int) -> IntArray): ByteArray {
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) px(x, y).forEachIndexed { i, v -> out[(y * w + x) * 4 + i] = v.toByte() }
        return out
    }
}

/** Port of the reference `rgbaToThumbHash`, used only to produce test inputs. */
object ThumbHashEncoder {
    fun encode(w: Int, h: Int, rgba: ByteArray): ByteArray {
        fun at(i: Int) = rgba[i].toInt() and 0xFF
        var avgR = 0.0; var avgG = 0.0; var avgB = 0.0; var avgA = 0.0
        for (i in 0 until w * h) {
            val j = i * 4
            val alpha = at(j + 3) / 255.0
            avgR += alpha / 255.0 * at(j); avgG += alpha / 255.0 * at(j + 1); avgB += alpha / 255.0 * at(j + 2); avgA += alpha
        }
        if (avgA > 0) { avgR /= avgA; avgG /= avgA; avgB /= avgA }
        val hasAlpha = avgA < w * h
        val lLimit = if (hasAlpha) 5 else 7
        val lx = max(1, (lLimit.toDouble() * w / max(w, h)).roundToInt())
        val ly = max(1, (lLimit.toDouble() * h / max(w, h)).roundToInt())
        val l = DoubleArray(w * h); val p = DoubleArray(w * h); val q = DoubleArray(w * h); val a = DoubleArray(w * h)
        for (i in 0 until w * h) {
            val j = i * 4
            val alpha = at(j + 3) / 255.0
            val r = avgR * (1 - alpha) + alpha / 255.0 * at(j)
            val g = avgG * (1 - alpha) + alpha / 255.0 * at(j + 1)
            val b = avgB * (1 - alpha) + alpha / 255.0 * at(j + 2)
            l[i] = (r + g + b) / 3; p[i] = (r + g) / 2 - b; q[i] = r - g; a[i] = alpha
        }
        class Ch(val dc: Double, val ac: DoubleArray, val scale: Double)
        fun channel(c: DoubleArray, nx: Int, ny: Int): Ch {
            var dc = 0.0; val ac = ArrayList<Double>(); var scale = 0.0
            for (cy in 0 until ny) {
                var cx = 0
                while (cx * ny < nx * (ny - cy)) {
                    var f = 0.0
                    for (y in 0 until h) for (x in 0 until w) f += c[x + y * w] * cos(PI / w * cx * (x + 0.5)) * cos(PI / h * cy * (y + 0.5))
                    f /= (w * h)
                    if (cx > 0 || cy > 0) { ac += f; scale = max(scale, abs(f)) } else dc = f
                    cx++
                }
            }
            val norm = if (scale > 0) ac.map { 0.5 + 0.5 / scale * it } else ac
            return Ch(dc, norm.toDoubleArray(), scale)
        }
        val lc = channel(l, max(3, lx), max(3, ly))
        val pc = channel(p, 3, 3)
        val qc = channel(q, 3, 3)
        val ac = if (hasAlpha) channel(a, 5, 5) else null
        val isLandscape = w > h
        val header24 = (63 * lc.dc).roundToInt() or ((31.5 + 31.5 * pc.dc).roundToInt() shl 6) or ((31.5 + 31.5 * qc.dc).roundToInt() shl 12) or
            ((31 * lc.scale).roundToInt() shl 18) or ((if (hasAlpha) 1 else 0) shl 23)
        val header16 = (if (isLandscape) ly else lx) or ((63 * pc.scale).roundToInt() shl 3) or ((63 * qc.scale).roundToInt() shl 9) or ((if (isLandscape) 1 else 0) shl 15)
        val hash = ArrayList<Int>(listOf(header24 and 255, (header24 shr 8) and 255, header24 shr 16, header16 and 255, header16 shr 8))
        if (hasAlpha) hash += (15 * ac!!.dc).roundToInt() or ((15 * ac.scale).roundToInt() shl 4)
        val acStart = if (hasAlpha) 6 else 5
        var idx = 0
        for (ch in listOfNotNull(lc, pc, qc, ac)) for (f in ch.ac) {
            val pos = acStart + (idx shr 1)
            while (hash.size <= pos) hash += 0
            hash[pos] = hash[pos] or ((15 * f).roundToInt() shl ((idx and 1) shl 2))
            idx++
        }
        return ByteArray(hash.size) { hash[it].toByte() }
    }
}

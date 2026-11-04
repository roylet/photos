package com.roylet.photos.editor.recipe

import kotlin.math.abs
import kotlin.math.sqrt

/** Monotone cubic (Fritsch–Carlson) tone-curve interpolation and LUT baking. */
object CurveMath {
    const val LUT_SIZE = 256

    fun isIdentity(points: List<CurvePoint>): Boolean {
        val p = normalize(points)
        return p.all { abs(it.x - it.y) < 1e-6f }
    }

    /**
     * Sorts by x, clamps into `0..1`, drops points closer than 1/255 in x, and makes sure there
     * are at least two points (falling back to the identity line).
     */
    fun normalize(points: List<CurvePoint>): List<CurvePoint> {
        val sorted = points
            .map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
            .sortedBy { it.x }
        val out = ArrayList<CurvePoint>(sorted.size)
        for (p in sorted) {
            if (out.isEmpty() || p.x - out.last().x >= 1f / 255f) out += p else out[out.lastIndex] = p
        }
        return when (out.size) {
            0 -> Curves.IDENTITY
            1 -> listOf(CurvePoint(0f, out[0].y), CurvePoint(1f, out[0].y))
            else -> out
        }
    }

    /**
     * Bakes [points] into [size] samples at `x = i / (size - 1)`. Outside the first/last point the
     * curve is flat. With non-decreasing control points the result is non-decreasing.
     */
    fun bake(points: List<CurvePoint>, size: Int = LUT_SIZE): FloatArray {
        val p = normalize(points)
        val n = p.size
        val xs = DoubleArray(n) { p[it].x.toDouble() }
        val ys = DoubleArray(n) { p[it].y.toDouble() }
        val d = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        val m = DoubleArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0.0) 0.0 else (d[i - 1] + d[i]) / 2.0
        for (i in 0 until n - 1) {
            if (d[i] == 0.0) {
                m[i] = 0.0; m[i + 1] = 0.0
            } else {
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9.0) {
                    val t = 3.0 / sqrt(s)
                    m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]
                }
            }
        }
        val out = FloatArray(size)
        var seg = 0
        for (i in 0 until size) {
            val x = i.toDouble() / (size - 1)
            val y = when {
                x <= xs[0] -> ys[0]
                x >= xs[n - 1] -> ys[n - 1]
                else -> {
                    while (seg < n - 2 && x > xs[seg + 1]) seg++
                    val h = xs[seg + 1] - xs[seg]
                    val t = (x - xs[seg]) / h
                    val t2 = t * t
                    val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * ys[seg] + (t3 - 2 * t2 + t) * h * m[seg] +
                        (-2 * t3 + 3 * t2) * ys[seg + 1] + (t3 - t2) * h * m[seg + 1]
                }
            }
            out[i] = y.coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    /**
     * The composite per-channel LUT the renderers sample: `out[c][i] = channel_c(rgb(i/255))`,
     * interleaved as `[r0, g0, b0, r1, g1, b1, ...]` (256 × 3 floats).
     */
    fun compositeLut(curves: Curves): FloatArray {
        val master = bake(curves.rgb)
        val channels = arrayOf(bake(curves.red), bake(curves.green), bake(curves.blue))
        val out = FloatArray(LUT_SIZE * 3)
        for (i in 0 until LUT_SIZE) {
            for (c in 0..2) out[i * 3 + c] = sampleLinear(channels[c], master[i])
        }
        return out
    }

    /** Linear interpolation into a baked LUT at `x` in `0..1` (what GL's linear filter does). */
    fun sampleLinear(lut: FloatArray, x: Float): Float {
        val f = x.coerceIn(0f, 1f) * (lut.size - 1)
        val i = f.toInt().coerceAtMost(lut.size - 2)
        val t = f - i
        return lut[i] + (lut[i + 1] - lut[i]) * t
    }
}

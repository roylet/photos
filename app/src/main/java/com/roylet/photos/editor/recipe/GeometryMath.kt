package com.roylet.photos.editor.recipe

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Row-major 3×3 matrix (homography) in double precision. */
class Mat3(val m: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)) {
    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            r[i * 3 + j] = m[i * 3] * o.m[j] + m[i * 3 + 1] * o.m[3 + j] + m[i * 3 + 2] * o.m[6 + j]
        }
        return Mat3(r)
    }

    /** Maps a point with the perspective divide. */
    fun map(x: Double, y: Double): DoubleArray {
        val w = m[6] * x + m[7] * y + m[8]
        return doubleArrayOf((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    fun inverse(): Mat3 {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        require(det != 0.0) { "Singular matrix" }
        val inv = doubleArrayOf(
            c00, a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            c01, a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            c02, a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        return Mat3(DoubleArray(9) { inv[it] / det })
    }

    /** Column-major floats, as `glUniformMatrix3fv(..., transpose = false, ...)` expects. */
    fun toColumnMajor(): FloatArray = FloatArray(9) { m[(it % 3) * 3 + it / 3].toFloat() }

    companion object {
        fun affine(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) =
            Mat3(doubleArrayOf(a, b, c, d, e, f, 0.0, 0.0, 1.0))
        fun translate(x: Double, y: Double) = affine(1.0, 0.0, x, 0.0, 1.0, y)
        fun scale(x: Double, y: Double) = affine(x, 0.0, 0.0, 0.0, y, 0.0)
    }
}

/**
 * Geometry maths shared by the GL renderer and the CPU reference.
 *
 * Coordinate spaces: *source uv* (`0..1` over the decoded, EXIF-oriented bitmap), *frame uv*
 * (`0..1` over the image after [Geometry.rotate90] and flips — the space [CropRect] lives in) and
 * *output uv* (`0..1` over the cropped result). Renderers evaluate [outputToSource] per pixel.
 */
object GeometryMath {
    /** Strength of ±100 perspective, as the homography's projective coefficient. */
    const val PERSPECTIVE_STRENGTH = 0.3

    /** Size of the frame (the image after quarter turns). */
    fun frameSize(geometry: Geometry, srcW: Int, srcH: Int): Pair<Int, Int> =
        if (geometry.rotate90.mod(2) == 1) srcH to srcW else srcW to srcH

    /** Pixel size of the rendered output at the source's resolution. */
    fun outputSize(geometry: Geometry, srcW: Int, srcH: Int, ignoreCrop: Boolean = false): Pair<Int, Int> {
        val (fw, fh) = frameSize(geometry, srcW, srcH)
        val crop = if (ignoreCrop) CropRect.FULL else geometry.crop.normalized()
        return max(1, (fw * crop.width).roundToInt()) to max(1, (fh * crop.height).roundToInt())
    }

    /** Output aspect ratio (width / height). */
    fun outputAspect(geometry: Geometry, srcW: Int, srcH: Int, ignoreCrop: Boolean = false): Float {
        val (fw, fh) = frameSize(geometry, srcW, srcH)
        val crop = if (ignoreCrop) CropRect.FULL else geometry.crop.normalized()
        return (fw * crop.width) / (fh * crop.height)
    }

    /**
     * Homography from output uv to source uv. Points mapping outside `0..1` have no source pixel
     * (renderers draw black); straighten/perspective are auto-scaled so this never happens
     * inside the frame.
     */
    fun outputToSource(geometry: Geometry, srcW: Int, srcH: Int, ignoreCrop: Boolean = false): Mat3 {
        val g = geometry
        val crop = if (ignoreCrop) CropRect.FULL else g.crop.normalized()
        val (fw, fh) = frameSize(g, srcW, srcH)
        val hl = max(fw, fh) / 2.0
        // output uv -> frame uv
        val a1 = Mat3.affine(crop.width.toDouble(), 0.0, crop.left.toDouble(), 0.0, crop.height.toDouble(), crop.top.toDouble())
        // frame uv -> centred units (half the long edge = 1)
        val a2 = Mat3.scale(fw / hl, fh / hl) * Mat3.translate(-0.5, -0.5)
        val warpInv = inverseWarp(g, fw, fh, hl)
        // centred units -> frame uv of the unwarped image
        val a3 = Mat3.translate(0.5, 0.5) * Mat3.scale(hl / fw, hl / fh)
        val a4 = Mat3.affine(if (g.flipH) -1.0 else 1.0, 0.0, if (g.flipH) 1.0 else 0.0,
            0.0, if (g.flipV) -1.0 else 1.0, if (g.flipV) 1.0 else 0.0)
        // oriented uv -> source uv (undo quarter turns clockwise)
        val a5 = when (g.rotate90.mod(4)) {
            1 -> Mat3.affine(0.0, 1.0, 0.0, -1.0, 0.0, 1.0)
            2 -> Mat3.affine(-1.0, 0.0, 1.0, 0.0, -1.0, 1.0)
            3 -> Mat3.affine(0.0, -1.0, 1.0, 1.0, 0.0, 0.0)
            else -> Mat3()
        }
        return a5 * a4 * a3 * warpInv * a2 * a1
    }

    /** The auto-zoom applied with straighten/perspective so the frame has no empty corners. */
    fun coverScale(geometry: Geometry, srcW: Int, srcH: Int): Double {
        val (fw, fh) = frameSize(geometry, srcW, srcH)
        return coverScale(geometry, fw, fh, max(fw, fh) / 2.0)
    }

    /** The inverse of `scale(s) · perspective · rotate(straighten)` in centred units. */
    private fun inverseWarp(g: Geometry, fw: Int, fh: Int, hl: Double): Mat3 {
        if (g.straighten == 0f && g.perspectiveVertical == 0f && g.perspectiveHorizontal == 0f) return Mat3()
        val s = coverScale(g, fw, fh, hl)
        return unscaledInverseWarp(g) * Mat3.scale(1.0 / s, 1.0 / s)
    }

    private fun unscaledInverseWarp(g: Geometry): Mat3 {
        val t = -g.straighten * PI / 180.0
        val rotInv = Mat3.affine(cos(t), -sin(t), 0.0, sin(t), cos(t), 0.0)
        val px = g.perspectiveHorizontal / 100.0 * PERSPECTIVE_STRENGTH
        val py = g.perspectiveVertical / 100.0 * PERSPECTIVE_STRENGTH
        val persp = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, px, py, 1.0))
        return rotInv * persp.inverse()
    }

    private fun coverScale(g: Geometry, fw: Int, fh: Int, hl: Double): Double {
        if (g.straighten == 0f && g.perspectiveVertical == 0f && g.perspectiveHorizontal == 0f) return 1.0
        val inv = unscaledInverseWarp(g)
        val ex = fw / hl
        val ey = fh / hl
        val eps = 1e-9
        fun covered(s: Double): Boolean {
            for (cx in doubleArrayOf(-ex, ex)) for (cy in doubleArrayOf(-ey, ey)) {
                val p = inv.map(cx / s, cy / s)
                if (p[0] < -ex - eps || p[0] > ex + eps || p[1] < -ey - eps || p[1] > ey + eps) return false
            }
            return true
        }
        if (covered(1.0)) return 1.0
        var lo = 1.0
        var hi = 2.0
        while (!covered(hi) && hi < 64.0) { lo = hi; hi *= 2.0 }
        repeat(40) {
            val mid = (lo + hi) / 2
            if (covered(mid)) hi = mid else lo = mid
        }
        return hi
    }
}

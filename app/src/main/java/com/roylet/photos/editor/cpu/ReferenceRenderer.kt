package com.roylet.photos.editor.cpu

import com.roylet.photos.editor.recipe.CurveMath
import com.roylet.photos.editor.recipe.GeometryMath
import com.roylet.photos.editor.recipe.LutResolver
import com.roylet.photos.editor.recipe.PipelineConstants as K
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.recipe.RenderParams
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The CPU reference of the editor's per-pixel pipeline — the executable spec that
 * `gl/EditShaders` mirrors line for line. Slow (plain Kotlin), meant for tests and as the
 * documentation of the maths.
 *
 * Pipeline, per output pixel at output uv `(u, v)`:
 * 1. **Geometry**: source uv = [GeometryMath.outputToSource] · (u, v); outside the source → black.
 * 2. **Local contrast** (display-referred): sharpen against the 4 neighbouring texels, clarity
 *    against the [BlurMath] copy weighted to mid-tones, dehaze as a dark-channel-prior style
 *    transmission estimated from the blurred copy (negative adds haze).
 * 3. **Linear light**: sRGB → linear, white balance gains (temperature/tint), exposure `× 2^EV`.
 * 4. **Tone** on luminance (hue preserving): blacks/whites levels, shadows/highlights smooth
 *    bumps, pivoted power-curve contrast, all on the sRGB-encoded luminance; then back to sRGB.
 * 5. **Colour**: vibrance (weighted by inverse chroma), saturation, 8-band HSL with smoothstep
 *    hue weighting between neighbouring band centres.
 * 6. **Curves** (256-entry composite LUT, linear filtered) and the **3D LUT filter** (trilinear)
 *    mixed by its amount.
 * 7. **Vignette** (radial, in output space) and **grain** (value noise over an integer hash
 *    lattice, so it is deterministic and resolution independent).
 */
object ReferenceRenderer {

    /** Renders [recipe] over [src]. [blur] defaults to [BlurMath.blurredCopy] of [src]. */
    fun render(
        src: ArgbImage,
        recipe: Recipe,
        lutResolver: LutResolver = LutResolver.Default,
        blur: ArgbImage? = null,
        ignoreCrop: Boolean = false,
        outputSize: Pair<Int, Int>? = null,
    ): ArgbImage {
        val g = recipe.geometry.normalized()
        val (ow, oh) = outputSize ?: GeometryMath.outputSize(g, src.width, src.height, ignoreCrop)
        val m = GeometryMath.outputToSource(g, src.width, src.height, ignoreCrop)
        val p = RenderParams.from(recipe, lutResolver)
        val needsBlur = p.clarity != 0f || p.dehaze != 0f
        val blurImg = if (needsBlur) blur ?: BlurMath.blurredCopy(src) else null
        val (gsx, gsy) = RenderParams.grainScale(ow.toFloat() / oh)
        val out = ArgbImage(ow, oh)
        val c = FloatArray(3)
        val bl = FloatArray(3)
        val nb = FloatArray(3)
        val tmp = FloatArray(3)
        val texX = 1f / src.width
        val texY = 1f / src.height
        for (y in 0 until oh) for (x in 0 until ow) {
            val u = (x + 0.5f) / ow
            val v = (y + 0.5f) / oh
            val s = m.map(u.toDouble(), v.toDouble())
            val su = s[0].toFloat()
            val sv = s[1].toFloat()
            if (su < 0f || su > 1f || sv < 0f || sv > 1f) { out[x, y] = 0xFF000000.toInt(); continue }
            src.sample(su, sv, c)
            if (p.sharpen > 0f) {
                nb.fill(0f)
                for ((dx, dy) in NEIGHBOURS) {
                    src.sample(su + dx * texX, sv + dy * texY, tmp)
                    for (i in 0..2) nb[i] += tmp[i] * 0.25f
                }
            }
            if (blurImg != null) blurImg.sample(su, sv, bl)
            shade(c, bl, nb, u, v, p, gsx, gsy)
            out[x, y] = ArgbImage.pack(c[0], c[1], c[2])
        }
        return out
    }

    private val NEIGHBOURS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /** Steps 2–7 for one pixel: [c] (display-referred rgb `0..1`) is updated in place. */
    fun shade(c: FloatArray, blur: FloatArray, neighbours: FloatArray, u: Float, v: Float, p: RenderParams,
              grainScaleX: Float, grainScaleY: Float) {
        localContrast(c, blur, neighbours, p)
        // --- linear light ---
        for (i in 0..2) c[i] = srgbToLinear(c[i])
        c[0] *= 1f + K.TEMP_GAIN * p.temperature
        c[1] *= 1f - K.TINT_GAIN * p.tint
        c[2] *= 1f - K.TEMP_GAIN * p.temperature
        val gain = 2f.pow(p.exposure)
        for (i in 0..2) c[i] *= gain
        toneMap(c, p)
        for (i in 0..2) c[i] = linearToSrgb(c[i].coerceIn(0f, 1f))
        // --- colour ---
        val yp = luma(c)
        if (p.vibrance != 0f) {
            val chroma = max3(c) - min3(c)
            val k = 1f + p.vibrance * (1f - chroma)
            for (i in 0..2) c[i] = yp + (c[i] - yp) * k
        }
        if (p.saturation != 0f) {
            val k = 1f + p.saturation
            for (i in 0..2) c[i] = yp + (c[i] - yp) * k
        }
        clamp01(c)
        if (p.hslOn) hsl(c, p.hsl)
        p.curveLut?.let { lut ->
            for (i in 0..2) c[i] = sampleCurve(lut, i, c[i])
        }
        val lut = p.lut
        if (lut != null && p.lutAmount > 0f) {
            val l = lut.sample(c[0], c[1], c[2])
            for (i in 0..2) c[i] += (l[i] - c[i]) * p.lutAmount
        }
        // --- finishing ---
        if (p.vignette != 0f) {
            val px = (u - 0.5f) * 2f
            val py = (v - 0.5f) * 2f
            val w = smoothstep(K.VIGNETTE_START, 1f, (px * px + py * py) * 0.5f)
            if (p.vignette < 0f) {
                val k = 1f + p.vignette * K.VIGNETTE_DARKEN * w
                for (i in 0..2) c[i] *= k
            } else {
                val k = p.vignette * K.VIGNETTE_LIGHTEN * w
                for (i in 0..2) c[i] += (1f - c[i]) * k
            }
        }
        if (p.grain > 0f) {
            val n = valueNoise(u * grainScaleX, v * grainScaleY) - 0.5f
            val l = luma(c) * 2f - 1f
            val amt = p.grain * K.GRAIN_GAIN * (1f - 0.6f * l * l)
            for (i in 0..2) c[i] += n * amt
        }
        clamp01(c)
    }

    private fun localContrast(c: FloatArray, blur: FloatArray, nb: FloatArray, p: RenderParams) {
        val l = luma(c)
        if (p.sharpen > 0f) {
            val d = (l - luma(nb)) * p.sharpen * K.SHARPEN_GAIN
            for (i in 0..2) c[i] += d
        }
        if (p.clarity != 0f) {
            val m = 2f * l - 1f
            val mid = max(0f, 1f - m * m)
            val d = (l - luma(blur)) * mid * p.clarity * K.CLARITY_GAIN
            for (i in 0..2) c[i] += d
        }
        if (p.dehaze > 0f) {
            val dark = min3(blur)
            val t = max(1f - K.DEHAZE_GAIN * p.dehaze * dark, K.DEHAZE_MIN_T)
            for (i in 0..2) c[i] = (c[i] - 1f) / t + 1f
        } else if (p.dehaze < 0f) {
            val haze = luma(blur) * 0.5f + 0.5f
            val k = -p.dehaze * K.HAZE_GAIN
            for (i in 0..2) c[i] += (haze - c[i]) * k
        }
        clamp01(c)
    }

    /** Luminance tone mapping in linear light; [c] is linear rgb, modified in place. */
    private fun toneMap(c: FloatArray, p: RenderParams) {
        val y = luma(c)
        val l = linearToSrgb(min(y, 1f))
        val y2 = srgbToLinear(toneCurve(l, p))
        if (y < K.TONE_EPSILON) {
            val d = y2 - y
            for (i in 0..2) c[i] += d
        } else {
            val k = y2 / y
            for (i in 0..2) c[i] *= k
        }
    }

    /** The tone curve on sRGB-encoded luminance; identity when all light sliders are 0. */
    fun toneCurve(l0: Float, p: RenderParams): Float {
        val b0 = -K.BLACKS_GAIN * p.blacks
        val w0 = 1f - K.WHITES_GAIN * p.whites
        var l = ((l0 - b0) / (w0 - b0)).coerceIn(0f, 1f)
        val sa = if (p.shadows > 0f) p.shadows * K.SHADOWS_UP else p.shadows * K.SHADOWS_DOWN
        val ha = if (p.highlights > 0f) p.highlights * K.HIGHLIGHTS_UP else p.highlights * K.HIGHLIGHTS_DOWN
        val inv = 1f - l
        l += sa * l * inv * inv + ha * l * l * inv
        l = l.coerceIn(0f, 1f)
        val k = 1f + K.CONTRAST_GAIN * p.contrast
        l = if (l < 0.5f) 0.5f * (2f * l).pow(k) else 1f - 0.5f * (2f - 2f * l).pow(k)
        return l.coerceIn(0f, 1f)
    }

    private fun hsl(c: FloatArray, bands: FloatArray) {
        val mx = max3(c)
        val mn = min3(c)
        val d = mx - mn
        if (d < 1e-6f) return
        var l = (mx + mn) * 0.5f
        var s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        var h = when (mx) {
            c[0] -> (c[1] - c[2]) / d + (if (c[1] < c[2]) 6f else 0f)
            c[1] -> (c[2] - c[0]) / d + 2f
            else -> (c[0] - c[1]) / d + 4f
        } / 6f
        val deg = h * 360f
        var ah = bands[0]; var asat = bands[1]; var al = bands[2]
        val centres = RenderParams.HSL_CENTRES
        for (i in 0 until 8) {
            val c0 = centres[i]
            val c1 = if (i == 7) 360f else centres[i + 1]
            if (deg >= c0 && deg < c1) {
                val t = smoothstep(0f, 1f, (deg - c0) / (c1 - c0))
                val j = (i + 1) % 8
                ah = bands[i * 3] + (bands[j * 3] - bands[i * 3]) * t
                asat = bands[i * 3 + 1] + (bands[j * 3 + 1] - bands[i * 3 + 1]) * t
                al = bands[i * 3 + 2] + (bands[j * 3 + 2] - bands[i * 3 + 2]) * t
            }
        }
        h += ah * K.HSL_HUE_DEGREES / 360f
        h -= floor(h)
        s = (s * (1f + asat)).coerceIn(0f, 1f)
        l = (l + al * K.HSL_LUM_GAIN * d).coerceIn(0f, 1f)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val pp = 2f * l - q
        c[0] = hue2rgb(pp, q, h + 1f / 3f)
        c[1] = hue2rgb(pp, q, h)
        c[2] = hue2rgb(pp, q, h - 1f / 3f)
    }

    private fun hue2rgb(p: Float, q: Float, t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    /** Linear-filtered lookup into the interleaved composite curve LUT. */
    fun sampleCurve(lut: FloatArray, channel: Int, x: Float): Float {
        val n = CurveMath.LUT_SIZE
        val f = x.coerceIn(0f, 1f) * (n - 1)
        val i = f.toInt().coerceAtMost(n - 2)
        val t = f - i
        val a = lut[i * 3 + channel]
        return a + (lut[(i + 1) * 3 + channel] - a) * t
    }

    // --- shared helpers (mirrored in GLSL) ---

    fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    fun linearToSrgb(c: Float): Float = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
    fun luma(c: FloatArray) = K.LUMA_R * c[0] + K.LUMA_G * c[1] + K.LUMA_B * c[2]
    private fun max3(c: FloatArray) = max(c[0], max(c[1], c[2]))
    private fun min3(c: FloatArray) = min(c[0], min(c[1], c[2]))
    private fun clamp01(c: FloatArray) { for (i in 0..2) c[i] = c[i].coerceIn(0f, 1f) }
    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 32-bit integer hash (lowbias32); identical to the GLSL `hashU`. */
    fun hashU(x0: Int): Int {
        var x = x0
        x = x xor (x ushr 16); x *= 0x7feb352d
        x = x xor (x ushr 15); x *= 0x846ca68b.toInt()
        x = x xor (x ushr 16)
        return x
    }

    /** Uniform `0..1` value for a lattice cell. */
    fun hash2(x: Int, y: Int): Float = (hashU(x xor hashU(y + 0x9e3779b9.toInt())) and 0xFFFF) / 65535f

    /** Smooth value noise in `0..1`. */
    fun valueNoise(x: Float, y: Float): Float {
        val ix = floor(x)
        val iy = floor(y)
        var fx = x - ix
        var fy = y - iy
        fx = fx * fx * (3f - 2f * fx)
        fy = fy * fy * (3f - 2f * fy)
        val xi = ix.toInt()
        val yi = iy.toInt()
        val a = hash2(xi, yi)
        val b = hash2(xi + 1, yi)
        val c = hash2(xi, yi + 1)
        val d = hash2(xi + 1, yi + 1)
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy
    }
}

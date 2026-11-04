package com.roylet.photos.editor.recipe

/**
 * Tuning constants of the colour pipeline. The GLSL source interpolates these values and the CPU
 * reference reads them, so the two renderers cannot drift apart.
 */
object PipelineConstants {
    const val SHARPEN_GAIN = 1.6f
    const val CLARITY_GAIN = 0.9f
    const val DEHAZE_GAIN = 0.55f
    const val DEHAZE_MIN_T = 0.2f
    const val HAZE_GAIN = 0.4f
    const val TEMP_GAIN = 0.25f
    const val TINT_GAIN = 0.2f
    const val BLACKS_GAIN = 0.15f
    const val WHITES_GAIN = 0.25f
    const val SHADOWS_UP = 2.0f
    const val SHADOWS_DOWN = 0.9f
    const val HIGHLIGHTS_UP = 0.9f
    const val HIGHLIGHTS_DOWN = 2.0f
    const val CONTRAST_GAIN = 0.6f
    const val TONE_EPSILON = 0.0001f
    const val HSL_HUE_DEGREES = 30f
    const val HSL_LUM_GAIN = 0.35f
    const val VIGNETTE_START = 0.15f
    const val VIGNETTE_DARKEN = 0.8f
    const val VIGNETTE_LIGHTEN = 0.7f
    const val GRAIN_GAIN = 0.18f
    /** Grain cells along the output's long edge (resolution independent). */
    const val GRAIN_CELLS = 1600f

    /** Rec. 709 luma weights. */
    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    /** Long edge of the blurred copy used by clarity/dehaze. */
    const val BLUR_LONG_EDGE = 320
    /** Box-blur radius as a fraction of [BLUR_LONG_EDGE]; three passes approximate a Gaussian. */
    const val BLUR_RADIUS_FRACTION = 0.015f
}

/**
 * A [Recipe]'s colour/effects values normalised for the renderers (sliders → `-1..1` or
 * `0..1`, exposure in EV, curves baked, LUT resolved).
 */
class RenderParams(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    /** 8 bands × (hue, saturation, luminance), each `-1..1`. */
    val hsl: FloatArray = FloatArray(24),
    val hslOn: Boolean = false,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val sharpen: Float = 0f,
    /** [CurveMath.compositeLut] output, or null for identity curves. */
    val curveLut: FloatArray? = null,
    val lut: Lut3D? = null,
    val lutAmount: Float = 0f,
) {
    companion object {
        val HSL_CENTRES: FloatArray = FloatArray(HslBand.entries.size) { HslBand.entries[it].hueDegrees }

        fun from(recipe: Recipe, lutResolver: LutResolver = LutResolver.Default): RenderParams {
            val r = recipe.normalized()
            val l = r.light
            val c = r.color
            val e = r.effects
            val hsl = FloatArray(24)
            c.hsl.forEachIndexed { i, b ->
                hsl[i * 3] = b.hue / 100f; hsl[i * 3 + 1] = b.saturation / 100f; hsl[i * 3 + 2] = b.luminance / 100f
            }
            val lut = r.filter?.takeIf { it.amount > 0f }?.let { lutResolver.resolve(it.lutId) }
            return RenderParams(
                exposure = l.exposure,
                contrast = l.contrast / 100f,
                highlights = l.highlights / 100f,
                shadows = l.shadows / 100f,
                whites = l.whites / 100f,
                blacks = l.blacks / 100f,
                temperature = c.temperature / 100f,
                tint = c.tint / 100f,
                vibrance = c.vibrance / 100f,
                saturation = c.saturation / 100f,
                hsl = hsl,
                hslOn = !c.isHslIdentity,
                clarity = e.clarity / 100f,
                dehaze = e.dehaze / 100f,
                vignette = e.vignette / 100f,
                grain = e.grain / 100f,
                sharpen = e.sharpen / 100f,
                curveLut = if (r.curves.isIdentity) null else CurveMath.compositeLut(r.curves),
                lut = lut,
                lutAmount = if (lut == null) 0f else (r.filter?.amount ?: 0f) / 100f,
            )
        }

        /** Grain lattice size for an output of the given aspect (width / height). */
        fun grainScale(aspect: Float): Pair<Float, Float> =
            if (aspect >= 1f) PipelineConstants.GRAIN_CELLS to PipelineConstants.GRAIN_CELLS / aspect
            else PipelineConstants.GRAIN_CELLS * aspect to PipelineConstants.GRAIN_CELLS
    }
}

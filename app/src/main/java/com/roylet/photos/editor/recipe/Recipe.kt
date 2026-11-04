package com.roylet.photos.editor.recipe

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A photo edit as pure data. Renderers (GL preview/export and the CPU reference) turn a source
 * image plus a [Recipe] into the edited image; nothing here touches pixels or Android APIs so the
 * type can move to a Kotlin Multiplatform module later.
 *
 * Units are the ones the UI shows:
 * - most sliders are `-100..100` (or `0..100` for one-sided effects), `0` = no change;
 * - [Light.exposure] is in EV stops (`-5..5`);
 * - [Geometry.straighten] is in degrees (`-45..45`);
 * - [Filter.amount] is `0..100`.
 *
 * [Ranges] lists every range and [Params] describes each slider generically.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Recipe(
    @EncodeDefault val version: Int = CURRENT_VERSION,
    val geometry: Geometry = Geometry(),
    val light: Light = Light(),
    val color: ColorAdjust = ColorAdjust(),
    val curves: Curves = Curves(),
    val effects: Effects = Effects(),
    val filter: Filter? = null,
) {
    /** True when rendering this recipe returns the source unchanged. */
    val isIdentity: Boolean
        get() = geometry.isIdentity && light.isIdentity && color.isIdentity &&
            curves.isIdentity && effects.isIdentity && (filter == null || filter.isIdentity)

    /** True when only the colour/effects are unchanged (geometry may differ). */
    val isColorIdentity: Boolean
        get() = light.isIdentity && color.isIdentity && curves.isIdentity &&
            effects.isIdentity && (filter == null || filter.isIdentity)

    /** Clamps every value into its documented range and repairs malformed lists. */
    fun normalized(): Recipe = copy(
        version = CURRENT_VERSION,
        geometry = geometry.normalized(),
        light = light.normalized(),
        color = color.normalized(),
        curves = curves.normalized(),
        effects = effects.normalized(),
        filter = filter?.normalized()?.takeUnless { it.lutId.isBlank() },
    )

    fun toJson(): String = RecipeJson.encodeToString(serializer(), this)

    companion object {
        const val CURRENT_VERSION = 1

        /** Decodes a recipe, ignoring unknown keys, and [normalized]s it. */
        fun fromJson(json: String): Recipe = RecipeJson.decodeFromString(serializer(), json).normalized()
    }
}

/** Json used for recipes: compact (defaults omitted except [Recipe.version]), forward compatible. */
val RecipeJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    coerceInputValues = true
}

/** Normalised crop rectangle inside the oriented (rotated/flipped) frame. `0..1` on both axes. */
@Serializable
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left == 0f && top == 0f && right == 1f && bottom == 1f

    fun normalized(): CropRect {
        val l = left.coerceIn(0f, 1f - MIN_SIZE)
        val t = top.coerceIn(0f, 1f - MIN_SIZE)
        val r = right.coerceIn(l + MIN_SIZE, 1f)
        val b = bottom.coerceIn(t + MIN_SIZE, 1f)
        return CropRect(l, t, r, b)
    }

    companion object {
        const val MIN_SIZE = 0.01f
        val FULL = CropRect()
    }
}

/**
 * Geometry, applied to the source in this order: [rotate90] quarter turns clockwise, then
 * [flipH]/[flipV], then [straighten] + [perspectiveVertical]/[perspectiveHorizontal] about the
 * centre (auto-scaled so no empty corners show), then [crop] in that frame.
 *
 * Use [rotatedCw], [flippedHorizontally] and [flippedVertically] from the UI: they transform the
 * other fields so the result is a visual rotation/mirror of what the user currently sees.
 */
@Serializable
data class Geometry(
    val crop: CropRect = CropRect(),
    val rotate90: Int = 0,
    val straighten: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val perspectiveVertical: Float = 0f,
    val perspectiveHorizontal: Float = 0f,
) {
    val isIdentity: Boolean
        get() = crop.isFull && rotate90.mod(4) == 0 && straighten == 0f && !flipH && !flipV &&
            perspectiveVertical == 0f && perspectiveHorizontal == 0f

    fun normalized() = copy(
        crop = crop.normalized(),
        rotate90 = rotate90.mod(4),
        straighten = straighten.coerceIn(Ranges.STRAIGHTEN),
        perspectiveVertical = perspectiveVertical.coerceIn(Ranges.BIPOLAR),
        perspectiveHorizontal = perspectiveHorizontal.coerceIn(Ranges.BIPOLAR),
    )

    /** The whole edited image rotated a quarter turn clockwise. */
    fun rotatedCw(): Geometry = copy(
        // Rotating after a single mirror equals rotating the other way before it.
        rotate90 = (rotate90 + if (flipH != flipV) 3 else 1).mod(4),
        crop = CropRect(1f - crop.bottom, crop.left, 1f - crop.top, crop.right),
        perspectiveHorizontal = -perspectiveVertical,
        perspectiveVertical = perspectiveHorizontal,
    )

    /** The whole edited image rotated a quarter turn counter-clockwise. */
    fun rotatedCcw(): Geometry = rotatedCw().rotatedCw().rotatedCw()

    /** The whole edited image mirrored left-right. */
    fun flippedHorizontally(): Geometry = copy(
        flipH = !flipH,
        straighten = -straighten,
        perspectiveHorizontal = -perspectiveHorizontal,
        crop = CropRect(1f - crop.right, crop.top, 1f - crop.left, crop.bottom),
    )

    /** The whole edited image mirrored top-bottom. */
    fun flippedVertically(): Geometry = copy(
        flipV = !flipV,
        straighten = -straighten,
        perspectiveVertical = -perspectiveVertical,
        crop = CropRect(crop.left, 1f - crop.bottom, crop.right, 1f - crop.top),
    )
}

@Serializable
data class Light(
    /** EV stops, applied in linear light. */
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
) {
    val isIdentity: Boolean
        get() = exposure == 0f && contrast == 0f && highlights == 0f && shadows == 0f &&
            whites == 0f && blacks == 0f

    fun normalized() = Light(
        exposure.coerceIn(Ranges.EXPOSURE),
        contrast.coerceIn(Ranges.BIPOLAR),
        highlights.coerceIn(Ranges.BIPOLAR),
        shadows.coerceIn(Ranges.BIPOLAR),
        whites.coerceIn(Ranges.BIPOLAR),
        blacks.coerceIn(Ranges.BIPOLAR),
    )
}

/** The eight HSL bands, with their centre hue in degrees. */
enum class HslBand(val hueDegrees: Float, val label: String) {
    RED(0f, "Red"),
    ORANGE(30f, "Orange"),
    YELLOW(60f, "Yellow"),
    GREEN(120f, "Green"),
    AQUA(180f, "Aqua"),
    BLUE(240f, "Blue"),
    PURPLE(270f, "Purple"),
    MAGENTA(300f, "Magenta"),
}

/** One HSL band: hue shift (±100 = ±30°), saturation and luminance, each `-100..100`. */
@Serializable
data class HslAdjust(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f) {
    val isIdentity: Boolean get() = hue == 0f && saturation == 0f && luminance == 0f
    fun normalized() = HslAdjust(
        hue.coerceIn(Ranges.BIPOLAR),
        saturation.coerceIn(Ranges.BIPOLAR),
        luminance.coerceIn(Ranges.BIPOLAR),
    )
}

@Serializable
data class ColorAdjust(
    /** Negative = cooler (blue), positive = warmer (yellow). */
    val temperature: Float = 0f,
    /** Negative = green, positive = magenta. */
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    /** Exactly 8 entries, indexed by [HslBand.ordinal]. */
    val hsl: List<HslAdjust> = DEFAULT_HSL,
) {
    val isIdentity: Boolean
        get() = temperature == 0f && tint == 0f && vibrance == 0f && saturation == 0f && isHslIdentity

    val isHslIdentity: Boolean get() = hsl.all { it.isIdentity }

    operator fun get(band: HslBand): HslAdjust = hsl.getOrElse(band.ordinal) { HslAdjust() }

    fun withHsl(band: HslBand, adjust: HslAdjust): ColorAdjust =
        copy(hsl = List(HslBand.entries.size) { if (it == band.ordinal) adjust else this[HslBand.entries[it]] })

    fun normalized() = ColorAdjust(
        temperature.coerceIn(Ranges.BIPOLAR),
        tint.coerceIn(Ranges.BIPOLAR),
        vibrance.coerceIn(Ranges.BIPOLAR),
        saturation.coerceIn(Ranges.BIPOLAR),
        List(HslBand.entries.size) { (hsl.getOrNull(it) ?: HslAdjust()).normalized() },
    )

    companion object {
        val DEFAULT_HSL: List<HslAdjust> = List(HslBand.entries.size) { HslAdjust() }
    }
}

/** A tone-curve control point; both coordinates `0..1`. */
@Serializable
data class CurvePoint(val x: Float, val y: Float)

/**
 * Tone curves as control points, interpolated with a monotone cubic ([CurveMath]). The [rgb]
 * curve is applied first, then each channel curve.
 */
@Serializable
data class Curves(
    val rgb: List<CurvePoint> = IDENTITY,
    val red: List<CurvePoint> = IDENTITY,
    val green: List<CurvePoint> = IDENTITY,
    val blue: List<CurvePoint> = IDENTITY,
) {
    val isIdentity: Boolean
        get() = CurveMath.isIdentity(rgb) && CurveMath.isIdentity(red) &&
            CurveMath.isIdentity(green) && CurveMath.isIdentity(blue)

    fun normalized() = Curves(
        CurveMath.normalize(rgb), CurveMath.normalize(red),
        CurveMath.normalize(green), CurveMath.normalize(blue),
    )

    companion object {
        val IDENTITY: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    }
}

@Serializable
data class Effects(
    /** Mid-tone local contrast, `-100..100`. */
    val clarity: Float = 0f,
    /** Removes (positive) or adds (negative) haze, `-100..100`. */
    val dehaze: Float = 0f,
    /** Negative darkens the corners, positive lightens them, `-100..100`. */
    val vignette: Float = 0f,
    /** Film grain, `0..100`. */
    val grain: Float = 0f,
    /** Unsharp-mask sharpening, `0..100`. */
    val sharpen: Float = 0f,
) {
    val isIdentity: Boolean
        get() = clarity == 0f && dehaze == 0f && vignette == 0f && grain == 0f && sharpen == 0f

    fun normalized() = Effects(
        clarity.coerceIn(Ranges.BIPOLAR),
        dehaze.coerceIn(Ranges.BIPOLAR),
        vignette.coerceIn(Ranges.BIPOLAR),
        grain.coerceIn(Ranges.UNIPOLAR),
        sharpen.coerceIn(Ranges.UNIPOLAR),
    )
}

/** A colour-lookup filter; [lutId] is a [Presets] id or an id registered with [LutRegistry]. */
@Serializable
data class Filter(val lutId: String, val amount: Float = 100f) {
    val isIdentity: Boolean get() = amount == 0f
    fun normalized() = copy(amount = amount.coerceIn(Ranges.UNIPOLAR))
}

/** Slider ranges. */
object Ranges {
    val BIPOLAR = -100f..100f
    val UNIPOLAR = 0f..100f
    val EXPOSURE = -5f..5f
    val STRAIGHTEN = -45f..45f
}

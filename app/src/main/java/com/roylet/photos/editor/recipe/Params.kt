package com.roylet.photos.editor.recipe

/** Tool groups, matching the editor's tool rail. */
enum class ParamGroup { GEOMETRY, LIGHT, COLOR, EFFECTS }

/**
 * A generic slider over one recipe value, so the UI can build its panels from [Params.all] and
 * reset a slider (double tap) with `param.set(recipe, param.default)`.
 */
class SliderParam(
    val id: String,
    val label: String,
    val group: ParamGroup,
    val range: ClosedFloatingPointRange<Float>,
    val default: Float = 0f,
    /** Shown next to the value, e.g. "EV" or "°". */
    val unit: String = "",
    val get: (Recipe) -> Float,
    val set: (Recipe, Float) -> Recipe,
) {
    fun isDefault(recipe: Recipe) = get(recipe) == default
    fun reset(recipe: Recipe) = set(recipe, default)
    override fun toString() = "SliderParam($id)"
}

object Params {
    private fun light(id: String, label: String, range: ClosedFloatingPointRange<Float> = Ranges.BIPOLAR, unit: String = "",
                      get: (Light) -> Float, set: (Light, Float) -> Light) =
        SliderParam(id, label, ParamGroup.LIGHT, range, 0f, unit, { get(it.light) }, { r, v -> r.copy(light = set(r.light, v)) })

    private fun color(id: String, label: String, get: (ColorAdjust) -> Float, set: (ColorAdjust, Float) -> ColorAdjust) =
        SliderParam(id, label, ParamGroup.COLOR, Ranges.BIPOLAR, 0f, "", { get(it.color) }, { r, v -> r.copy(color = set(r.color, v)) })

    private fun effect(id: String, label: String, range: ClosedFloatingPointRange<Float>, get: (Effects) -> Float, set: (Effects, Float) -> Effects) =
        SliderParam(id, label, ParamGroup.EFFECTS, range, 0f, "", { get(it.effects) }, { r, v -> r.copy(effects = set(r.effects, v)) })

    private fun geo(id: String, label: String, range: ClosedFloatingPointRange<Float>, unit: String, get: (Geometry) -> Float, set: (Geometry, Float) -> Geometry) =
        SliderParam(id, label, ParamGroup.GEOMETRY, range, 0f, unit, { get(it.geometry) }, { r, v -> r.copy(geometry = set(r.geometry, v)) })

    val exposure = light("exposure", "Exposure", Ranges.EXPOSURE, "EV", { it.exposure }) { l, v -> l.copy(exposure = v) }
    val contrast = light("contrast", "Contrast", get = { it.contrast }) { l, v -> l.copy(contrast = v) }
    val highlights = light("highlights", "Highlights", get = { it.highlights }) { l, v -> l.copy(highlights = v) }
    val shadows = light("shadows", "Shadows", get = { it.shadows }) { l, v -> l.copy(shadows = v) }
    val whites = light("whites", "Whites", get = { it.whites }) { l, v -> l.copy(whites = v) }
    val blacks = light("blacks", "Blacks", get = { it.blacks }) { l, v -> l.copy(blacks = v) }

    val temperature = color("temperature", "Temperature", { it.temperature }) { c, v -> c.copy(temperature = v) }
    val tint = color("tint", "Tint", { it.tint }) { c, v -> c.copy(tint = v) }
    val vibrance = color("vibrance", "Vibrance", { it.vibrance }) { c, v -> c.copy(vibrance = v) }
    val saturation = color("saturation", "Saturation", { it.saturation }) { c, v -> c.copy(saturation = v) }

    val clarity = effect("clarity", "Clarity", Ranges.BIPOLAR, { it.clarity }) { e, v -> e.copy(clarity = v) }
    val dehaze = effect("dehaze", "Dehaze", Ranges.BIPOLAR, { it.dehaze }) { e, v -> e.copy(dehaze = v) }
    val vignette = effect("vignette", "Vignette", Ranges.BIPOLAR, { it.vignette }) { e, v -> e.copy(vignette = v) }
    val grain = effect("grain", "Grain", Ranges.UNIPOLAR, { it.grain }) { e, v -> e.copy(grain = v) }
    val sharpen = effect("sharpen", "Sharpen", Ranges.UNIPOLAR, { it.sharpen }) { e, v -> e.copy(sharpen = v) }

    val straighten = geo("straighten", "Straighten", Ranges.STRAIGHTEN, "°", { it.straighten }) { g, v -> g.copy(straighten = v) }
    val perspectiveVertical = geo("perspectiveVertical", "Vertical", Ranges.BIPOLAR, "", { it.perspectiveVertical }) { g, v -> g.copy(perspectiveVertical = v) }
    val perspectiveHorizontal = geo("perspectiveHorizontal", "Horizontal", Ranges.BIPOLAR, "", { it.perspectiveHorizontal }) { g, v -> g.copy(perspectiveHorizontal = v) }

    val all: List<SliderParam> = listOf(
        exposure, contrast, highlights, shadows, whites, blacks,
        temperature, tint, vibrance, saturation,
        clarity, dehaze, vignette, grain, sharpen,
        straighten, perspectiveVertical, perspectiveHorizontal,
    )

    fun group(group: ParamGroup) = all.filter { it.group == group }
    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** The three sliders of one HSL band. */
    fun hsl(band: HslBand): List<SliderParam> = listOf(
        SliderParam("hsl.${band.name.lowercase()}.hue", "Hue", ParamGroup.COLOR, Ranges.BIPOLAR, 0f, "",
            { it.color[band].hue }) { r, v -> r.copy(color = r.color.withHsl(band, r.color[band].copy(hue = v))) },
        SliderParam("hsl.${band.name.lowercase()}.saturation", "Saturation", ParamGroup.COLOR, Ranges.BIPOLAR, 0f, "",
            { it.color[band].saturation }) { r, v -> r.copy(color = r.color.withHsl(band, r.color[band].copy(saturation = v))) },
        SliderParam("hsl.${band.name.lowercase()}.luminance", "Luminance", ParamGroup.COLOR, Ranges.BIPOLAR, 0f, "",
            { it.color[band].luminance }) { r, v -> r.copy(color = r.color.withHsl(band, r.color[band].copy(luminance = v))) },
    )

    /** Amount slider for the active filter (no-op when no filter is set). */
    val filterAmount = SliderParam("filter.amount", "Amount", ParamGroup.COLOR, Ranges.UNIPOLAR, 100f, "",
        { it.filter?.amount ?: 100f }) { r, v -> r.copy(filter = r.filter?.copy(amount = v)) }
}

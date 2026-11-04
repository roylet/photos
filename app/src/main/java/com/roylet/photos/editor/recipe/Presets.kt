package com.roylet.photos.editor.recipe

import kotlin.math.pow

/** Resolves a [Filter.lutId] to a LUT. */
fun interface LutResolver {
    fun resolve(id: String): Lut3D?

    companion object {
        /** Built-in [Presets] first, then anything added to [LutRegistry]. */
        val Default = LutResolver { id -> Presets.byId(id)?.lut ?: LutRegistry[id] }
    }
}

/** Extra LUTs (for example `.cube` files from assets) registered at runtime. */
object LutRegistry {
    @kotlin.concurrent.Volatile
    private var luts: Map<String, Lut3D> = emptyMap()

    operator fun get(id: String): Lut3D? = luts[id]
    fun register(id: String, lut: Lut3D) { luts = luts + (id to lut) }
    fun unregister(id: String) { luts = luts - id }
    val ids: Set<String> get() = luts.keys
}

/** A built-in filter: a named 3D LUT generated from a small colour function. */
class FilterPreset internal constructor(
    val id: String,
    val name: String,
    private val fn: (r: Float, g: Float, b: Float, out: FloatArray) -> Unit,
) {
    /** Built on first use (33³ lattice, ~0.5 MB of floats). */
    val lut: Lut3D by lazy { Lut3D.fromFunction(LUT_SIZE, name, fn) }

    /** The recipe filter that applies this preset at [amount] (`0..100`). */
    fun filter(amount: Float = 100f) = Filter(id, amount)

    companion object {
        const val LUT_SIZE = 33
    }
}

/** Built-in filters. */
object Presets {
    val Vivid = FilterPreset("vivid", "Vivid") { r, g, b, o ->
        set(o, r, g, b); saturate(o, 1.35f); contrast(o, 1.18f)
    }
    val Warm = FilterPreset("warm", "Warm") { r, g, b, o ->
        set(o, r * 1.05f + 0.025f, g * 1.01f + 0.01f, b * 0.88f); saturate(o, 1.06f)
    }
    val Cool = FilterPreset("cool", "Cool") { r, g, b, o ->
        set(o, r * 0.9f, g * 0.99f + 0.005f, b * 1.04f + 0.035f); saturate(o, 0.97f)
    }
    val Mono = FilterPreset("mono", "Mono") { r, g, b, o ->
        val y = luma(r, g, b); set(o, y, y, y); contrast(o, 1.1f)
    }
    val Fade = FilterPreset("fade", "Fade") { r, g, b, o ->
        set(o, r, g, b); saturate(o, 0.72f); levels(o, 0.13f, 0.94f)
    }
    val Film = FilterPreset("film", "Film") { r, g, b, o ->
        set(o, r, g, b); contrast(o, 1.12f); saturate(o, 0.9f)
        splitTone(o, shadow = floatArrayOf(-0.02f, 0.01f, 0.05f), highlight = floatArrayOf(0.05f, 0.02f, -0.04f))
        levels(o, 0.05f, 0.97f)
    }
    val Matte = FilterPreset("matte", "Matte") { r, g, b, o ->
        set(o, r, g, b); contrast(o, 0.88f); saturate(o, 0.88f); levels(o, 0.1f, 0.96f)
        splitTone(o, shadow = floatArrayOf(0f, 0.02f, 0.02f), highlight = floatArrayOf(0.02f, 0.01f, 0f))
    }
    val Noir = FilterPreset("noir", "Noir") { r, g, b, o ->
        // Red-weighted mono with deep blacks.
        val y = (0.45f * r + 0.45f * g + 0.1f * b).coerceIn(0f, 1f)
        set(o, y, y, y); contrast(o, 1.45f); levels(o, -0.04f, 1.02f)
    }

    val all: List<FilterPreset> = listOf(Vivid, Warm, Cool, Mono, Fade, Film, Matte, Noir)

    fun byId(id: String): FilterPreset? = all.firstOrNull { it.id == id }

    // --- colour helpers (display-referred rgb in 0..1) ---

    private fun set(o: FloatArray, r: Float, g: Float, b: Float) {
        o[0] = r.coerceIn(0f, 1f); o[1] = g.coerceIn(0f, 1f); o[2] = b.coerceIn(0f, 1f)
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun saturate(o: FloatArray, k: Float) {
        val y = luma(o[0], o[1], o[2])
        set(o, y + (o[0] - y) * k, y + (o[1] - y) * k, y + (o[2] - y) * k)
    }

    /** Pivoted power S-curve around 0.5 (same shape as the contrast slider). */
    private fun contrast(o: FloatArray, k: Float) {
        for (c in 0..2) {
            val x = o[c]
            o[c] = if (x < 0.5f) 0.5f * (2f * x).pow(k) else 1f - 0.5f * (2f - 2f * x).pow(k)
        }
    }

    private fun levels(o: FloatArray, black: Float, white: Float) {
        set(o, black + o[0] * (white - black), black + o[1] * (white - black), black + o[2] * (white - black))
    }

    private fun splitTone(o: FloatArray, shadow: FloatArray, highlight: FloatArray) {
        val y = luma(o[0], o[1], o[2])
        val ws = (1f - y) * (1f - y)
        val wh = y * y
        set(o, o[0] + shadow[0] * ws + highlight[0] * wh, o[1] + shadow[1] * ws + highlight[1] * wh,
            o[2] + shadow[2] * ws + highlight[2] * wh)
    }
}

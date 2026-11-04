package com.roylet.photos.editor.recipe

/**
 * A 3D colour lookup table over display-referred (sRGB-encoded) RGB in `0..1`.
 * [data] holds `size³` RGB triples with red varying fastest, then green, then blue — the `.cube`
 * order and the layout `glTexImage3D(width = r, height = g, depth = b)` expects.
 */
class Lut3D(val size: Int, val data: FloatArray, val title: String = "") {
    init {
        require(size >= 2) { "LUT size must be >= 2" }
        require(data.size == size * size * size * 3) { "LUT data must hold size^3 RGB triples" }
    }

    /** Trilinear sample, equivalent to a GL_LINEAR 3D texture lookup at texel centres. */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray = FloatArray(3)): FloatArray {
        val n = size - 1
        val fr = r.coerceIn(0f, 1f) * n
        val fg = g.coerceIn(0f, 1f) * n
        val fb = b.coerceIn(0f, 1f) * n
        val r0 = fr.toInt().coerceAtMost(n - 1)
        val g0 = fg.toInt().coerceAtMost(n - 1)
        val b0 = fb.toInt().coerceAtMost(n - 1)
        val tr = fr - r0
        val tg = fg - g0
        val tb = fb - b0
        for (c in 0..2) {
            fun v(ri: Int, gi: Int, bi: Int) = data[((bi * size + gi) * size + ri) * 3 + c]
            val c00 = v(r0, g0, b0) + (v(r0 + 1, g0, b0) - v(r0, g0, b0)) * tr
            val c10 = v(r0, g0 + 1, b0) + (v(r0 + 1, g0 + 1, b0) - v(r0, g0 + 1, b0)) * tr
            val c01 = v(r0, g0, b0 + 1) + (v(r0 + 1, g0, b0 + 1) - v(r0, g0, b0 + 1)) * tr
            val c11 = v(r0, g0 + 1, b0 + 1) + (v(r0 + 1, g0 + 1, b0 + 1) - v(r0, g0 + 1, b0 + 1)) * tr
            val c0 = c00 + (c10 - c00) * tg
            val c1 = c01 + (c11 - c01) * tg
            out[c] = c0 + (c1 - c0) * tb
        }
        return out
    }

    companion object {
        fun identity(size: Int = 17): Lut3D = fromFunction(size) { r, g, b, out -> out[0] = r; out[1] = g; out[2] = b }

        /** Builds a LUT by evaluating [fn] (input rgb → `out`) at every lattice point. */
        fun fromFunction(
            size: Int,
            title: String = "",
            fn: (r: Float, g: Float, b: Float, out: FloatArray) -> Unit,
        ): Lut3D {
            val data = FloatArray(size * size * size * 3)
            val tmp = FloatArray(3)
            val n = (size - 1).toFloat()
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                fn(r / n, g / n, b / n, tmp)
                data[i++] = tmp[0].coerceIn(0f, 1f)
                data[i++] = tmp[1].coerceIn(0f, 1f)
                data[i++] = tmp[2].coerceIn(0f, 1f)
            }
            return Lut3D(size, data, title)
        }

        /** Parses an Adobe/Resolve `.cube` 3D LUT (DOMAIN_MIN/MAX are honoured). */
        fun parseCube(text: String): Lut3D {
            var size = 0
            var title = ""
            val min = floatArrayOf(0f, 0f, 0f)
            val max = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> title = line.substringAfter(' ').trim().trim('"')
                    "LUT_3D_SIZE" -> size = parts[1].toInt()
                    "LUT_1D_SIZE" -> throw IllegalArgumentException("1D .cube LUTs are not supported")
                    "DOMAIN_MIN" -> for (c in 0..2) min[c] = parts[c + 1].toFloat()
                    "DOMAIN_MAX" -> for (c in 0..2) max[c] = parts[c + 1].toFloat()
                    "LUT_3D_INPUT_RANGE" -> { min.fill(parts[1].toFloat()); max.fill(parts[2].toFloat()) }
                    else -> if (parts.size >= 3 && parts[0].first().let { it.isDigit() || it == '-' || it == '.' }) {
                        for (c in 0..2) values += parts[c].toFloat()
                    }
                }
            }
            require(size >= 2) { "Missing LUT_3D_SIZE" }
            require(values.size == size * size * size * 3) {
                "Expected ${size * size * size} entries, found ${values.size / 3}"
            }
            val data = FloatArray(values.size) { values[it] }
            // The output range follows the domain; normalise into 0..1.
            for (i in data.indices) {
                val c = i % 3
                data[i] = ((data[i] - min[c]) / (max[c] - min[c])).coerceIn(0f, 1f)
            }
            return Lut3D(size, data, title)
        }
    }
}

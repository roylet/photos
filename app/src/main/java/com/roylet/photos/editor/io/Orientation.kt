package com.roylet.photos.editor.io

import kotlin.math.max
import kotlin.math.min

/**
 * EXIF orientation maths (values 1–8 as in `ExifInterface.ORIENTATION_*`). "Raw" is the pixel
 * grid as stored in the file, "oriented" is how it should be displayed.
 */
object Orientation {
    /** True for 5–8, where the oriented image swaps width and height. */
    fun swapsAxes(orientation: Int) = orientation in 5..8

    fun orientedSize(orientation: Int, rawW: Int, rawH: Int): Pair<Int, Int> =
        if (swapsAxes(orientation)) rawH to rawW else rawW to rawH

    /** Maps a point of the oriented image (continuous pixel coords) to the raw image. */
    fun orientedToRaw(orientation: Int, ox: Double, oy: Double, rawW: Int, rawH: Int): Pair<Double, Double> {
        val w = rawW.toDouble()
        val h = rawH.toDouble()
        return when (orientation) {
            2 -> (w - ox) to oy
            3 -> (w - ox) to (h - oy)
            4 -> ox to (h - oy)
            5 -> oy to ox
            6 -> oy to (h - ox)
            7 -> (w - oy) to (h - ox)
            8 -> (w - oy) to ox
            else -> ox to oy
        }
    }

    /** The raw pixel rect `[l, t, r, b)` holding the oriented rect `[l, t, r, b)`. */
    fun orientedRectToRaw(orientation: Int, l: Int, t: Int, r: Int, b: Int, rawW: Int, rawH: Int): IntArray {
        val a = orientedToRaw(orientation, l.toDouble(), t.toDouble(), rawW, rawH)
        val c = orientedToRaw(orientation, r.toDouble(), b.toDouble(), rawW, rawH)
        return intArrayOf(
            min(a.first, c.first).toInt(), min(a.second, c.second).toInt(),
            max(a.first, c.first).toInt(), max(a.second, c.second).toInt(),
        )
    }
}

package com.roylet.photos.domain.timeline

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One laid-out row: assets [start] until [start] + [widths].size, all [height] tall. Units are whatever the caller used (px). */
class JustifiedRow(val start: Int, val height: Float, val widths: FloatArray) {
    val count: Int get() = widths.size
    val end: Int get() = start + widths.size
    /** x of each cell's left edge, given the [spacing] used for the layout. */
    fun lefts(spacing: Float): FloatArray {
        val out = FloatArray(widths.size)
        var x = 0f
        for (i in widths.indices) { out[i] = x; x += widths[i] + spacing }
        return out
    }
}

/**
 * Google-Photos-style justified rows: each row keeps every photo's aspect ratio and is scaled so it
 * exactly fills [width]. Rows are built greedily, closing a row once it would be at least as wide as
 * the container at [targetHeight]. The last row stays at the target height (left-aligned) rather
 * than being blown up to full width.
 *
 * Pure function of its inputs, so the grid can lay out one month at a time, on demand.
 */
object JustifiedLayout {
    /** Extremely wide or tall images are clamped so one panorama can't make a row a sliver. */
    const val MIN_RATIO = 0.5f
    const val MAX_RATIO = 3f

    fun layout(
        ratios: FloatArray,
        width: Float,
        targetHeight: Float,
        spacing: Float = 0f,
        /** A row with too few images (e.g. one portrait) is capped at this height. */
        maxHeight: Float = targetHeight * 1.6f,
    ): List<JustifiedRow> {
        require(width > 0 && targetHeight > 0) { "width and targetHeight must be positive" }
        val rows = ArrayList<JustifiedRow>(ratios.size / 3 + 1)
        var start = 0
        while (start < ratios.size) {
            var sum = 0f
            var end = start
            while (end < ratios.size) {
                sum += clamp(ratios[end])
                end++
                val n = end - start
                if (sum * targetHeight + spacing * (n - 1) >= width) break
            }
            val n = end - start
            val avail = width - spacing * (n - 1)
            val full = sum * targetHeight + spacing * (n - 1) >= width
            val h = if (full) min(avail / sum, maxHeight) else min(targetHeight, avail / sum)
            val widths = FloatArray(n) { clamp(ratios[start + it]) * h }
            if (full && h < maxHeight) {
                // Absorb float error so the row ends exactly at the container edge.
                val drift = avail - widths.sum()
                widths[n - 1] += drift
            }
            rows += JustifiedRow(start, h, widths)
            start = end
        }
        return rows
    }

    /** Uniform square cells, [columns] per row (the compact month view). */
    fun squares(count: Int, width: Float, columns: Int, spacing: Float = 0f): List<JustifiedRow> {
        val side = (width - spacing * (columns - 1)) / columns
        return (0 until count step columns).map { s -> JustifiedRow(s, side, FloatArray(min(columns, count - s)) { side }) }
    }

    /** A cheap height estimate for a month that hasn't been fetched yet. */
    fun estimateHeight(count: Int, width: Float, targetHeight: Float, spacing: Float, averageRatio: Float = 1.25f): Float {
        if (count <= 0) return 0f
        val perRow = max(1f, (width + spacing) / (averageRatio * targetHeight + spacing))
        val rows = ceil(count / perRow)
        val h = (width - spacing * (perRow - 1)) / (perRow * averageRatio)
        return rows * h + (rows - 1) * spacing
    }

    fun clamp(ratio: Float): Float = if (ratio.isNaN() || ratio <= 0f) 1f else ratio.coerceIn(MIN_RATIO, MAX_RATIO)
}

package com.roylet.photos.timeline

import com.roylet.photos.domain.timeline.JustifiedLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JustifiedLayoutTest {
    private val eps = 0.01f

    @Test fun fullRowsFillTheWidthExactly() {
        val ratios = FloatArray(40) { listOf(1.5f, 0.75f, 1.33f, 1f, 2f)[it % 5] }
        val rows = JustifiedLayout.layout(ratios, width = 1000f, targetHeight = 200f, spacing = 6f)
        assertEquals(40, rows.sumOf { it.count })
        // Contiguous and in order.
        var next = 0
        rows.forEach { assertEquals(next, it.start); next = it.end }
        rows.dropLast(1).forEach { row ->
            val used = row.widths.sum() + 6f * (row.count - 1)
            assertEquals(1000f, used, eps)
            // Every cell keeps its aspect ratio.
            row.widths.forEachIndexed { i, w -> assertEquals(JustifiedLayout.clamp(ratios[row.start + i]) * row.height, w, 0.5f) }
            // Rows close once they reach the width at the target height, so they can only shrink below it.
            assertTrue("height ${row.height}", row.height <= 200f + eps && row.height > 100f)
        }
    }

    @Test fun lastRowKeepsTargetHeightAndIsLeftAligned() {
        val rows = JustifiedLayout.layout(floatArrayOf(1f, 1f, 1f, 1f, 1f), width = 1000f, targetHeight = 300f)
        // 300 * 4 >= 1000 -> first row has 4; last row has the single leftover at target height.
        assertEquals(listOf(4, 1), rows.map { it.count })
        assertEquals(250f, rows[0].height, eps)
        assertEquals(300f, rows[1].height, eps)
        assertEquals(300f, rows[1].widths[0], eps)
    }

    @Test fun extremeRatiosAreClampedAndSingleWideImagesFit() {
        val rows = JustifiedLayout.layout(floatArrayOf(10f, 0.05f), width = 500f, targetHeight = 200f)
        // A 10:1 panorama is treated as 3:1: wider than the container at 200 tall, so it gets a row of its own.
        assertEquals(1, rows[0].count)
        assertEquals(500f / 3f, rows[0].height, eps)
        assertEquals(500f, rows[0].widths[0], eps)
        assertEquals(JustifiedLayout.MIN_RATIO * rows[1].height, rows[1].widths[0], eps)
    }

    @Test fun lonePortraitIsCappedAtMaxHeight() {
        val rows = JustifiedLayout.layout(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f), width = 300f, targetHeight = 100f, maxHeight = 160f)
        rows.forEach { assertTrue(it.height <= 160f + eps) }
    }

    @Test fun densityChangesRowCount() {
        val ratios = FloatArray(60) { 4f / 3f }
        val big = JustifiedLayout.layout(ratios, 1080f, 1080f / 3 * 0.8f)
        val small = JustifiedLayout.layout(ratios, 1080f, 1080f / 7 * 0.8f)
        assertTrue(big.size > small.size)
        assertEquals(3, big[0].count)
        assertTrue(small[0].count in 6..7)
    }

    @Test fun squaresAndEstimates() {
        val sq = JustifiedLayout.squares(23, width = 1000f, columns = 10, spacing = 0f)
        assertEquals(listOf(10, 10, 3), sq.map { it.count })
        assertEquals(100f, sq[0].height, eps)
        val est = JustifiedLayout.estimateHeight(30, 1000f, 200f, 0f, averageRatio = 1f)
        val real = JustifiedLayout.layout(FloatArray(30) { 1f }, 1000f, 200f).sumOf { it.height.toDouble() }.toFloat()
        assertEquals(real, est, real * 0.25f)
        assertEquals(0f, JustifiedLayout.estimateHeight(0, 1000f, 200f, 0f), eps)
    }

    @Test fun lefts() {
        val row = JustifiedLayout.layout(floatArrayOf(1f, 1f), 210f, 100f, spacing = 10f)[0]
        assertEquals(listOf(0f, 110f), row.lefts(10f).toList())
    }
}

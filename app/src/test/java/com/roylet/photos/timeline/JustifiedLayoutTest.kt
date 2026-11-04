package com.roylet.photos.timeline

import com.roylet.photos.domain.timeline.JustifiedLayout
import com.roylet.photos.ui.gallery.GridDensity
import com.roylet.photos.ui.gallery.GridLayoutCache
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

    /* ---- Close-up levels (two-up and the one-up feed) ---- */

    private val phoneW = 1080f
    private val phoneVh = 1900f

    private fun level(ratios: FloatArray, perRow: Int, cap: Float, width: Float = phoneW, vh: Float = phoneVh, spacing: Float = 8f) =
        JustifiedLayout.layout(ratios, width, JustifiedLayout.targetHeight(width, perRow, spacing), spacing, maxHeight = vh * cap, maxPerRow = perRow)

    @Test fun targetHeightFitsNTypicalPhotosPerRow() {
        fun counts(ratio: Float, n: Int) =
            JustifiedLayout.layout(FloatArray(42) { ratio }, phoneW, JustifiedLayout.targetHeight(phoneW, n, 8f), 8f).dropLast(1).map { it.count }.toSet()
        // 3:2 landscapes: exactly N at the close levels; the densest level is allowed one fewer.
        for (n in listOf(1, 2, 3, 5)) assertEquals("3:2, $n per row", setOf(n), counts(1.5f, n))
        assertTrue(counts(1.5f, 7).all { it in 6..7 })
        // 4:3 (most phone cameras): exactly N everywhere.
        for (n in listOf(1, 2, 3, 5, 7)) assertEquals("4:3, $n per row", setOf(n), counts(4f / 3f, n))
    }

    @Test fun twoUpHoldsAtMostTwoAndFillsTheWidth() {
        val mixed = FloatArray(40) { listOf(1.5f, 0.667f, 1.33f, 0.75f, 1f, 0.5625f, 2.4f)[it % 7] }
        val rows = level(mixed, perRow = 2, cap = 0.5f)
        assertEquals(40, rows.sumOf { it.count })
        rows.forEach { assertTrue("count ${it.count}", it.count in 1..2) }
        rows.dropLast(1).forEach { row ->
            assertTrue(row.height <= phoneVh * 0.5f + eps)
            if (row.offset == 0f) assertEquals(phoneW, row.widths.sum() + 8f * (row.count - 1), eps)
        }
        // Landscapes pair up exactly as at the other levels.
        val land = level(FloatArray(10) { 1.5f }, perRow = 2, cap = 0.5f)
        assertEquals(List(5) { 2 }, land.map { it.count })
        assertEquals((phoneW - 8f) / 3f, land[0].height, eps)
    }

    @Test fun twoUpPortraitPairsStayTwoAndAreCapped() {
        // Without maxPerRow, four 2:3 portraits would share a row at the two-up target height.
        val rows = level(FloatArray(8) { 2f / 3f }, perRow = 2, cap = 0.5f)
        assertEquals(List(4) { 2 }, rows.map { it.count })
        rows.forEach { assertTrue(it.height <= phoneVh * 0.5f + eps) }
        // Very tall (clamped 1:2) pairs hit the cap and are centred.
        val tall = level(FloatArray(2) { 0.4f }, perRow = 2, cap = 0.5f, vh = 1600f)
        assertEquals(800f, tall[0].height, eps)
        val used = tall[0].widths.sum() + 8f
        assertEquals((phoneW - used) / 2f, tall[0].offset, eps)
    }

    @Test fun oneUpFeedIsFullWidthWithPortraitsCappedAndCentred() {
        val ratios = floatArrayOf(1.5f, 2f / 3f, 9f / 16f, 1f, 2.4f, 0.75f)
        val rows = level(ratios, perRow = 1, cap = 0.7f)
        assertEquals(List(ratios.size) { 1 }, rows.map { it.count })
        rows.forEachIndexed { i, row ->
            val r = JustifiedLayout.clamp(ratios[i])
            assertTrue("row $i ${row.height}", row.height <= phoneVh * 0.7f + eps)
            assertEquals(r * row.height, row.widths[0], 0.5f)
            if (phoneW / r <= phoneVh * 0.7f) {
                // Fits: full width, no margin.
                assertEquals(phoneW / r, row.height, eps)
                assertEquals(0f, row.offset, eps)
            } else {
                // Too tall: capped at 70% of the viewport, centred.
                assertEquals(phoneVh * 0.7f, row.height, eps)
                assertEquals((phoneW - row.widths[0]) / 2f, row.offset, eps)
                assertEquals(row.offset, row.lefts(8f)[0], eps)
            }
        }
    }

    @Test fun tabletWidthsScaleThePerRowCount() {
        // A 900dp tablet doubles the phone counts (see GridLayoutCache.columnsFor); the feed then shows two.
        assertEquals(1, GridLayoutCache.columnsFor(GridDensity.Feed, 1f))
        assertEquals(2, GridLayoutCache.columnsFor(GridDensity.Feed, 2.14f))
        assertEquals(4, GridLayoutCache.columnsFor(GridDensity.Duo, 2.14f))
        val w = 2400f
        val rows = level(FloatArray(12) { 1.5f }, perRow = 2, cap = 0.7f, width = w, vh = 1400f)
        rows.forEach { assertEquals(2, it.count); assertEquals((w - 8f) / 3f, it.height, eps) }
        val four = level(FloatArray(12) { 2f / 3f }, perRow = 4, cap = 0.5f, width = w, vh = 1400f)
        four.forEach { assertTrue(it.count <= 4); assertTrue(it.height <= 700f + eps) }
    }
}

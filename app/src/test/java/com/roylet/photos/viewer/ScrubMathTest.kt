package com.roylet.photos.viewer

import com.roylet.photos.ui.viewer.bubbleLeft
import com.roylet.photos.ui.viewer.extractionOrder
import com.roylet.photos.ui.viewer.nearestLoaded
import com.roylet.photos.ui.viewer.scrubLabel
import com.roylet.photos.ui.viewer.scrubPositionMs
import com.roylet.photos.ui.viewer.stripFrameCount
import com.roylet.photos.ui.viewer.stripFrameIndex
import com.roylet.photos.ui.viewer.stripFrameTimesMs
import com.roylet.photos.ui.viewer.tickIndex
import com.roylet.photos.ui.viewer.tickIntervalMs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrubMathTest {
    @Test fun labels() {
        assertEquals("1:23 / 4:05", scrubLabel(83_400, 245_000))
        assertEquals("0:00 / 0:07", scrubLabel(0, 7_900))
        assertEquals("1:02:03 / 2:00:00", scrubLabel(3_723_000, 7_200_000))
        // Clamped to the video.
        assertEquals("0:10 / 0:10", scrubLabel(12_000, 10_000))
        assertEquals("0:00 / 0:10", scrubLabel(-5, 10_000))
    }

    @Test fun positions() {
        assertEquals(0L, scrubPositionMs(-0.2f, 10_000))
        assertEquals(5_000L, scrubPositionMs(0.5f, 10_000))
        assertEquals(10_000L, scrubPositionMs(1.4f, 10_000))
        assertEquals(0L, scrubPositionMs(0.5f, 0))
    }

    @Test fun bubbleStaysOnScreen() {
        // Centred on the anchor when there is room.
        assertEquals(150f, bubbleLeft(anchorX = 200f, width = 100f, containerWidth = 400f, margin = 8f), 0f)
        // Clamped at both edges, respecting the margin.
        assertEquals(8f, bubbleLeft(10f, 100f, 400f, 8f), 0f)
        assertEquals(292f, bubbleLeft(395f, 100f, 400f, 8f), 0f)
        assertEquals(0f, bubbleLeft(-50f, 100f, 400f), 0f)
        // Wider than the container: centred.
        assertEquals(-20f, bubbleLeft(10f, 140f, 100f, 8f), 0f)
    }

    @Test fun frameTimesSampleSliceCentres() {
        assertEquals(10, stripFrameCount(245_000))
        assertEquals(6, stripFrameCount(6_500))
        assertEquals(4, stripFrameCount(1_200))
        assertEquals(0, stripFrameCount(0))
        assertArrayEquals(longArrayOf(500, 1500, 2500, 3500), stripFrameTimesMs(4_000, 4))
        val t = stripFrameTimesMs(100_000)
        assertEquals(10, t.size)
        assertEquals(5_000L, t.first())
        assertEquals(95_000L, t.last())
        assertEquals(0, stripFrameTimesMs(0).size)
    }

    @Test fun frameIndexForPosition() {
        assertEquals(0, stripFrameIndex(0, 100_000, 10))
        assertEquals(0, stripFrameIndex(9_999, 100_000, 10))
        assertEquals(1, stripFrameIndex(10_000, 100_000, 10))
        assertEquals(9, stripFrameIndex(100_000, 100_000, 10))
        assertEquals(9, stripFrameIndex(150_000, 100_000, 10))
        assertEquals(-1, stripFrameIndex(5, 0, 10))
    }

    @Test fun nearestLoadedFrame() {
        val loaded = setOf(0, 4, 8)
        assertEquals(4, nearestLoaded(4, 10) { it in loaded })
        assertEquals(4, nearestLoaded(5, 10) { it in loaded })
        assertEquals(4, nearestLoaded(6, 10) { it in loaded }) // tie between 4 and 8: earlier wins
        assertEquals(8, nearestLoaded(9, 10) { it in loaded })
        assertEquals(-1, nearestLoaded(3, 10) { false })
    }

    @Test fun extractionIsCoarseToFine() {
        assertEquals(listOf(0, 8, 4, 2, 6, 1, 3, 5, 7, 9), extractionOrder(10))
        assertEquals(listOf(0, 2, 1, 3), extractionOrder(4))
        assertEquals(listOf(0), extractionOrder(1))
        assertEquals((0 until 12).toSet(), extractionOrder(12).toSet())
    }

    @Test fun ticksEveryTenPercentOrChapter() {
        assertEquals(24_500L, tickIntervalMs(245_000))
        assertEquals(0, tickIndex(24_499, 245_000))
        assertEquals(1, tickIndex(24_500, 245_000))
        assertEquals(9, tickIndex(240_000, 245_000))
        // 45 minutes: 5-minute chapters (9 ticks), not 4.5-minute tenths.
        assertEquals(300_000L, tickIntervalMs(45 * 60_000L))
        assertEquals(3, tickIndex(16 * 60_000L, 45 * 60_000L))
        // 2 hours: 10-minute chapters (12 ticks).
        assertEquals(600_000L, tickIntervalMs(120 * 60_000L))
        assertEquals(900_000L, tickIntervalMs(150 * 60_000L))
        assertEquals(0L, tickIntervalMs(0))
    }
}

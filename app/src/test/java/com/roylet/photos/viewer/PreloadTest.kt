package com.roylet.photos.viewer

import androidx.compose.ui.unit.IntSize
import com.roylet.photos.ui.viewer.NeighbourPrefetcher
import com.roylet.photos.ui.viewer.PrefetchHandle
import com.roylet.photos.ui.viewer.PrefetchTarget
import com.roylet.photos.ui.viewer.fitPx
import com.roylet.photos.ui.viewer.prefetchWindow
import com.roylet.photos.ui.viewer.retryDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreloadTest {
    @Test fun windowIsNearestFirstAndClipped() {
        assertEquals(listOf(11, 9, 12, 8), prefetchWindow(10, 100, 2))
        assertEquals(listOf(1, 2), prefetchWindow(0, 100, 2))
        assertEquals(listOf(98, 97), prefetchWindow(99, 100, 2))
        assertEquals(emptyList<Int>(), prefetchWindow(0, 1, 2))
        assertEquals(emptyList<Int>(), prefetchWindow(5, 0, 2))
    }

    @Test fun retryBacksOffThenStops() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L), (0..3).map { retryDelayMs(it) })
        assertNull(retryDelayMs(4))
    }

    @Test fun fitMatchesPage() {
        assertEquals(IntSize(800, 600), fitPx(4f / 3f, 800, 1760))
        assertEquals(IntSize(352, 1760), fitPx(0.2f, 800, 1760))
        assertEquals(IntSize(800, 800), fitPx(Float.NaN, 800, 1760))
    }

    private class Fake(val known: Set<Int>) : PrefetchTarget {
        val buckets = mutableListOf<Int>()
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        override fun ensureLoadedAt(index: Int) { buckets += index }
        override fun enqueue(index: Int): PrefetchHandle? {
            if (index !in known) return null
            started += index
            return PrefetchHandle { cancelled += index }
        }
    }

    @Test fun loadsBucketsAheadOfTheWindow() {
        val t = Fake((0..100).toSet())
        NeighbourPrefetcher(t, radius = 2).moveTo(10, 100)
        assertEquals(listOf(10, 11, 9, 12, 8, 13, 7), t.buckets)
        assertEquals(emptyList<Int>(), t.started) // nothing until the current page has loaded
    }

    @Test fun prefetchesNeighboursOnceAndCancelsStaleOnMove() {
        val t = Fake((0..100).toSet())
        val p = NeighbourPrefetcher(t, radius = 2)
        p.moveTo(10, 100)
        p.prefetch(10, 100)
        p.prefetch(10, 100) // idempotent
        assertEquals(listOf(11, 9, 12, 8), t.started)

        p.moveTo(11, 100)
        assertEquals(listOf(8), t.cancelled) // 11 is now current and keeps going; 9, 12 still in range
        p.prefetch(11, 100)
        assertEquals(listOf(11, 9, 12, 8, 10, 13), t.started)

        p.moveTo(30, 100) // a jump: everything goes
        assertEquals(setOf(8, 9, 10, 11, 12, 13), t.cancelled.toSet())
        assertEquals(emptySet<Int>(), p.active)
    }

    @Test fun picksUpNeighboursWhoseBucketArrivesLater() {
        val known = mutableSetOf(10, 11)
        val t = Fake(known)
        val p = NeighbourPrefetcher(t, radius = 2)
        p.moveTo(10, 100)
        p.prefetch(10, 100)
        assertEquals(listOf(11), t.started)
        known += setOf(8, 9, 12)
        p.prefetch(10, 100)
        assertEquals(listOf(11, 9, 12, 8), t.started)
    }

    @Test fun clearCancelsEverything() {
        val t = Fake((0..100).toSet())
        val p = NeighbourPrefetcher(t)
        p.moveTo(50, 100); p.prefetch(50, 100); p.clear()
        assertEquals(setOf(48, 49, 51, 52), t.cancelled.toSet())
    }
}

package com.roylet.photos.timeline

import com.roylet.photos.domain.timeline.TimeBucket
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineSnapshot
import com.roylet.photos.ui.gallery.GridDensity
import com.roylet.photos.ui.gallery.GridItem
import com.roylet.photos.ui.gallery.GridLayoutCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid's rows are reused across snapshots: a month loading (or changing) elsewhere must hand
 * the LazyColumn the very same row objects for untouched months, so their GridRow skips recomposing.
 */
class GridLayoutCacheTest {
    private fun assets(prefix: String, n: Int) = List(n) { TimelineAsset("$prefix$it", if (it % 3 == 0) 0.75f else 1.5f, isImage = true, isFavorite = false) }
    private val buckets = listOf(TimeBucket("2026-11-01", 20), TimeBucket("2026-10-01", 30))
    private val nov = assets("n", 20)
    private val oct = assets("o", 30)

    private fun rows(items: List<GridItem>, bucket: Int) = items.filterIsInstance<GridItem.Row>().filter { it.bucket == bucket }

    @Test fun unchangedMonthsKeepTheirRowInstances() {
        val cache = GridLayoutCache()
        val first = cache.build(TimelineSnapshot(buckets, mapOf("2026-11-01" to nov)), 1080f, GridDensity.Medium, 4f)
        val second = cache.build(TimelineSnapshot(buckets, mapOf("2026-11-01" to nov, "2026-10-01" to oct)), 1080f, GridDensity.Medium, 4f)
        val before = rows(first.items, 0)
        val after = rows(second.items, 0)
        assertTrue(before.isNotEmpty())
        before.zip(after).forEach { (a, b) -> assertSame(a, b) }
        assertTrue(rows(second.items, 1).isNotEmpty())
    }

    @Test fun aChangedMonthGetsNewRows() {
        val cache = GridLayoutCache()
        val first = cache.build(TimelineSnapshot(buckets, mapOf("2026-11-01" to nov)), 1080f, GridDensity.Medium, 4f)
        val faved = nov.mapIndexed { i, a -> if (i == 0) a.copy(isFavorite = true) else a }
        val second = cache.build(TimelineSnapshot(buckets, mapOf("2026-11-01" to faved)), 1080f, GridDensity.Medium, 4f)
        assertNotSame(rows(first.items, 0).first(), rows(second.items, 0).first())
    }

    @Test fun aMonthThatMovedIndexIsRebuilt() {
        val cache = GridLayoutCache()
        val first = cache.build(TimelineSnapshot(buckets, mapOf("2026-11-01" to nov)), 1080f, GridDensity.Medium, 4f)
        val newer = listOf(TimeBucket("2026-12-01", 1)) + buckets
        val second = cache.build(TimelineSnapshot(newer, mapOf("2026-11-01" to nov)), 1080f, GridDensity.Medium, 4f)
        val moved = rows(second.items, 1)
        assertTrue(moved.isNotEmpty())
        assertNotSame(rows(first.items, 0).first(), moved.first())
    }

    /**
     * A month that hasn't loaded yet is one placeholder at its estimated height. In the one-up feed
     * 400 photos estimate to ~350k px, past what Compose can measure (262,142 px): the grid threw
     * "Can't represent a width of 0 and height of 322994 in Constraints" while scrolling down.
     */
    @Test fun bigUnloadedMonthsAreSplitBelowComposeLimits() {
        val big = listOf(TimeBucket("2026-11-01", 400), TimeBucket("2026-10-01", 20_000), TimeBucket("2026-09-01", 3))
        val snapshot = TimelineSnapshot(big, emptyMap())
        for (density in GridDensity.entries) {
            val model = GridLayoutCache().build(snapshot, 1080f, density, 8f, viewportHeightPx = 2200f)
            val pending = model.items.filterIsInstance<GridItem.Pending>()
            assertTrue(pending.all { it.heightPx <= GridLayoutCache.MAX_PENDING_PX })
            assertEquals(model.items.size, model.items.map { it.key }.toSet().size)
            // The parts add up to the month's estimate, and the scrubber/anchor math finds the right one.
            val feb = pending.filter { it.bucket == 1 }
            assertEquals(feb.first().monthHeightPx, feb.sumOf { it.heightPx.toDouble() }.toFloat(), 1f)
            val last = model.itemIndexOfAsset(snapshot.offsets[1] + 19_999)
            assertSame(feb.last(), model.items[last])
            assertSame(feb.first(), model.items[model.itemIndexOfAsset(snapshot.offsets[1])])
        }
        // The one-up feed: 400 photos would be one ~350k px item.
        val feed = GridLayoutCache().build(snapshot, 1080f, GridDensity.Feed, 8f, viewportHeightPx = 2200f)
        assertTrue(feed.items.filterIsInstance<GridItem.Pending>().count { it.bucket == 0 } > 1)
    }
}

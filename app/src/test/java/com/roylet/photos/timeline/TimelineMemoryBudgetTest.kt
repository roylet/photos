package com.roylet.photos.timeline

import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.domain.timeline.TimeBucket
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.domain.timeline.TimelineSource
import com.roylet.photos.ui.gallery.GridDensity
import com.roylet.photos.ui.gallery.GridLayoutCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * How much a fully scrolled timeline keeps on the Java heap: every month loaded (the repository
 * keeps them for its lifetime) and laid out, for 60k merged items with realistic fields. Measured
 * at ~490 bytes/item (~28 MB): a real cost, but not what runs a 256-512 MB heap out, so months
 * aren't evicted. The budget (64 MB) catches a regression such as per-item bitmaps or copies.
 */
class TimelineMemoryBudgetTest {
    private val months = 120
    private val perMonth = 500

    private val source = object : TimelineSource {
        val buckets = List(months) { i -> TimeBucket("%04d-%02d-01T00:00:00.000Z".format(2026 - i / 12, 12 - i % 12), perMonth) }
        override suspend fun buckets(filter: TimelineFilter) = buckets
        override suspend fun bucket(filter: TimelineFilter, key: String) = List(perMonth) { i ->
            val remote = UUID.randomUUID().toString()
            val local = 1_000_000L + key.hashCode().toLong() * 1000 + i
            TimelineAsset(
                id = AssetRef.local(local).id, ratio = 1.5f, isImage = true, isFavorite = false,
                thumbhash = "YJqGPQw7sFlslqhFafSE+Q6oJ1h2iHB2Rw==".substring(0, 20 + i % 10) + i,
                fileCreatedAt = 1_700_000_000_000L + i, localOffsetHours = 10.0,
                city = "Melbourne$i".substring(0, 9), country = "Australia",
                localRef = AssetRef.local(local), remoteRef = AssetRef.immich(remote),
            )
        }
    }

    private fun usedHeap(): Long {
        repeat(3) { System.gc(); Thread.sleep(50) }
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test fun aFullyLoadedTimelineStaysWithinBudget() {
        val before = usedHeap()
        val repo = TimelineRepository(source, TimelineFilter.Timeline, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        repo.start()
        repo.snapshot.value.buckets.indices.forEach(repo::ensureLoaded)
        val cache = GridLayoutCache()
        val model = cache.build(repo.snapshot.value, 1080f, GridDensity.Medium, 8f)
        val used = usedHeap() - before
        val items = repo.snapshot.value.total
        println("Timeline memory: ${used / 1024 / 1024} MB for $items items (${used / items} bytes/item), ${model.items.size} grid items")
        assertTrue(items == months * perMonth)
        assertTrue("used ${used / 1024 / 1024} MB", used < 64L * 1024 * 1024)
    }
}

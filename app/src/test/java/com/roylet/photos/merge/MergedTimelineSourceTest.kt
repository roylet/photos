package com.roylet.photos.merge

import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.domain.source.SourceBadge
import com.roylet.photos.domain.timeline.StaticTimeline
import com.roylet.photos.domain.timeline.TimeBucket
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineSource
import com.roylet.photos.domain.timeline.TimelineSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class MergedTimelineSourceTest {
    private fun t(month: Int, day: Int, h: Int = 12): Long = LocalDateTime.of(2026, month, day, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun phone(id: Long, at: Long, fav: Boolean = false, trashed: Boolean = false): TimelineAsset =
        TimelineAsset(AssetRef.local(id).id, 1.5f, true, fav, isTrashed = trashed, fileCreatedAt = at)
    private fun immich(id: String, at: Long, fav: Boolean = false, trashed: Boolean = false): TimelineAsset =
        TimelineAsset(id, 1.5f, true, fav, isTrashed = trashed, fileCreatedAt = at, thumbhash = "th-$id", city = "Melbourne")

    /** A source whose assets are filtered like the real ones (favourites, trash). */
    private class ListSource(val all: MutableList<TimelineAsset>) : TimelineSource {
        var failing = false
        val bucketCalls = mutableListOf<String>()
        private fun of(f: TimelineFilter) = all.filter { a ->
            (f.isTrashed == true) == a.isTrashed && (f.isFavorite != true || a.isFavorite)
        }
        override suspend fun buckets(filter: TimelineFilter): List<TimeBucket> { if (failing) throw java.io.IOException("down"); return StaticTimeline.buckets(of(filter)) }
        override suspend fun bucket(filter: TimelineFilter, key: String): List<TimelineAsset> {
            if (failing) throw java.io.IOException("down")
            bucketCalls += key
            return StaticTimeline.bucket(of(filter), key)
        }
    }

    private class FakeMatcher(val pairs: Map<Long, String>, val tentative: Set<Long> = emptySet()) : MonthMatcher {
        val asked = mutableListOf<String>()
        override suspend fun matchMonth(bucketKey: String, localIds: List<Long>, remoteIds: Set<String>): Map<Long, Match> {
            asked += bucketKey
            return localIds.mapNotNull { l -> pairs[l]?.takeIf { it in remoteIds }?.let { l to Match(it, l in tentative) } }.toMap()
        }
        override suspend fun cachedRemote(localIds: List<Long>): Map<Long, Match> = localIds.mapNotNull { l -> pairs[l]?.let { l to Match(it, false) } }.toMap()
        override suspend fun cachedLocal(remoteIds: List<String>): Map<String, Long> = pairs.entries.filter { it.value in remoteIds }.associate { it.value to it.key }
        override suspend fun pairsPerMonth(): Map<String, Int> = mapOf("2026-10-01T00:00:00.000Z" to 2)
    }

    private val oct = "2026-10-01T00:00:00.000Z"
    private val sep = "2026-09-01T00:00:00.000Z"
    private val aug = "2026-08-01T00:00:00.000Z"

    // October: two backed-up photos, one phone-only, one Immich-only. September: phone only. August: Immich only.
    private val local = ListSource(mutableListOf(
        phone(1, t(10, 5)), phone(2, t(10, 4), fav = true), phone(3, t(10, 3)),
        phone(4, t(9, 20)),
        phone(9, t(10, 1), trashed = true),
    ))
    private val remote = ListSource(mutableListOf(
        immich("a", t(10, 5)), immich("b", t(10, 4)), immich("c", t(10, 2), fav = true),
        immich("d", t(8, 1)),
        immich("z", t(10, 1), trashed = true),
    ))
    private val matcher = FakeMatcher(mapOf(1L to "a", 2L to "b"), tentative = setOf(2L))
    private val merged = MergedTimelineSource(local, remote) { matcher }

    @Test fun `matched pairs collapse into one asset with both refs, opened from the phone`() {
        val list = runBlocking { merged.bucket(TimelineFilter.Timeline, oct) }
        assertEquals(listOf("local:1", "local:2", "local:3", "c"), list.map { it.id })
        val one = list[0]
        assertEquals(AssetRef.local(1), one.localRef)
        assertEquals(AssetRef.immich("a"), one.remoteRef)
        assertEquals(SourceBadge.Both, one.sourceBadge)
        assertEquals("th-a", one.thumbhash) // Immich's extras carry over
        assertEquals("Melbourne", one.city)
        assertEquals(SourceBadge.LocalOnly, list[2].sourceBadge)
        assertEquals(SourceBadge.RemoteOnly, list[3].sourceBadge)
        assertTrue(list[1].isFavorite) // favourite on the phone only: favourite
    }

    @Test fun `months on one side only are just that side, and every month is listed`() {
        val buckets = runBlocking { merged.buckets(TimelineFilter.Timeline) }
        assertEquals(listOf(oct, sep, aug), buckets.map { it.key })
        assertEquals(listOf("local:4"), runBlocking { merged.bucket(TimelineFilter.Timeline, sep) }.map { it.id })
        assertEquals(listOf("d"), runBlocking { merged.bucket(TimelineFilter.Timeline, aug) }.map { it.id })
        // Nothing to match in a one-sided month.
        assertFalse(sep in matcher.asked || aug in matcher.asked)
    }

    @Test fun `counts are estimated from known pairs, then agree with the merged month`() {
        val before = runBlocking { merged.buckets(TimelineFilter.Timeline) }.associate { it.key to it.count }
        assertEquals(3 + 3 - 2, before[oct])
        assertEquals(1, before[sep])
        val loaded = runBlocking { merged.bucket(TimelineFilter.Timeline, oct) }
        val after = runBlocking { merged.buckets(TimelineFilter.Timeline) }
        assertEquals(loaded.size, after.first { it.key == oct }.count)
        val snap = TimelineSnapshot(after, mapOf(oct to loaded))
        assertEquals(4 + 1 + 1, snap.total)
    }

    @Test fun `favourites are the union of both sides`() {
        val favs = runBlocking { merged.bucket(TimelineFilter.Favorites, oct) }
        assertEquals(listOf("local:2", "c"), favs.map { it.id })
        // Item 2 is a favourite on the phone only, but its Immich copy is known.
        assertEquals(AssetRef.immich("b"), favs[0].remoteRef)
    }

    @Test fun `trash shows both trashes, each item in its own source`() {
        val trash = runBlocking { merged.bucket(TimelineFilter.Trash, oct) }
        assertEquals(setOf("local:9", "z"), trash.map { it.id }.toSet())
        assertEquals(setOf(SourceBadge.LocalOnly, SourceBadge.RemoteOnly), trash.map { it.sourceBadge }.toSet())
    }

    @Test fun `albums and archive go to Immich, phone folders to the phone`() {
        runBlocking { merged.bucket(TimelineFilter.Archive, oct) }
        runBlocking { merged.bucket(TimelineFilter.album("x"), oct) }
        assertTrue(local.bucketCalls.isEmpty())
        runBlocking { merged.bucket(TimelineFilter.album("folder:1"), oct) }
        assertEquals(1, local.bucketCalls.size)
    }

    @Test fun `with Immich unreachable the phone's side still shows`() {
        remote.failing = true
        assertEquals(listOf(oct, sep), runBlocking { merged.buckets(TimelineFilter.Timeline) }.map { it.key })
        assertEquals(listOf("local:1", "local:2", "local:3"), runBlocking { merged.bucket(TimelineFilter.Timeline, oct) }.map { it.id })
    }

    @Test fun `oldest first keeps the merged order`() {
        val f = TimelineFilter(newestFirst = false)
        assertEquals(listOf(aug, sep, oct), runBlocking { merged.buckets(f) }.map { it.key })
        assertEquals(listOf("c", "local:3", "local:2", "local:1"), runBlocking { merged.bucket(f, oct) }.map { it.id })
    }

    @Test fun `a match whose Immich asset isn't in the month isn't merged`() {
        val out = MergedTimelineSource.pair(listOf(phone(1, 5)), listOf(immich("b", 4)), mapOf(1L to Match("a", false)), true)
        assertEquals(listOf("local:1", "b"), out.map { it.id })
        assertTrue(out.none { it.sourceBadge == SourceBadge.Both })
    }
}

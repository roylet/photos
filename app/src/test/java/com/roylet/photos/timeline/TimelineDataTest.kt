package com.roylet.photos.timeline

import com.roylet.photos.domain.timeline.TimeBucket
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.domain.timeline.TimelineSnapshot
import com.roylet.photos.domain.timeline.TimelineSource
import com.roylet.photos.domain.timeline.Visibility
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichTimelineSource
import com.roylet.photos.immich.TimeBucketAssetsDto
import com.roylet.photos.immich.setFavorite
import com.roylet.photos.immich.timeBucket
import com.roylet.photos.immich.timeBuckets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimelineDataTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ImmichApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = ImmichApi(server.url("/").toString(), "secret-key")
    }

    @After fun tearDown() = server.shutdown()

    private val bucketJson = """
        {
          "id": ["a1", "a2", "a3"],
          "ratio": [1.5, 0.75, null],
          "isImage": [true, false, true],
          "isFavorite": [false, true, false],
          "isTrashed": [false, false, false],
          "thumbhash": ["1QcSHQRnh493V4dIh4eXh1h4kJUI", null, "abc"],
          "duration": [null, 65000, "0:00:03.500000"],
          "fileCreatedAt": ["2025-03-14T09:30:00.000Z", "2025-03-02T18:00:00+00:00", "bad"],
          "localOffsetHours": [11, -9.5, 0],
          "livePhotoVideoId": [null, null, "v3"],
          "city": ["Melbourne", null, null],
          "country": ["Australia", null, null],
          "stack": [null, ["s1", "4"], null],
          "visibility": ["timeline", "archive", "timeline"],
          "ownerId": ["u", "u", "u"],
          "projectionType": [null, null, null],
          "createdAt": ["x", "y", "z"],
          "somethingNew": [1, 2, 3]
        }
    """.trimIndent()

    @Test fun columnarBucketMapsToAssets() = runTest {
        server.enqueue(MockResponse().setBody(bucketJson))
        val assets = api.timeBucket(TimelineFilter.Timeline, "2025-03-01T00:00:00.000Z").toAssets()
        val req = server.takeRequest()
        assertEquals("/api/timeline/bucket", req.requestUrl!!.encodedPath)
        assertEquals("2025-03-01T00:00:00.000Z", req.requestUrl!!.queryParameter("timeBucket"))
        assertEquals("timeline", req.requestUrl!!.queryParameter("visibility"))
        assertEquals("true", req.requestUrl!!.queryParameter("withStacked"))
        assertEquals("desc", req.requestUrl!!.queryParameter("order"))
        assertEquals("secret-key", req.getHeader("x-api-key"))

        assertEquals(listOf("a1", "a2", "a3"), assets.map { it.id })
        val (a1, a2, a3) = assets
        assertEquals(1.5f, a1.ratio)
        assertEquals(1f, a3.ratio) // null ratio falls back to square
        assertTrue(a1.isImage); assertTrue(a2.isVideo)
        assertTrue(a2.isFavorite)
        assertEquals("1QcSHQRnh493V4dIh4eXh1h4kJUI", a1.thumbhash); assertNull(a2.thumbhash)
        assertNull(a1.durationMs); assertEquals(65_000L, a2.durationMs); assertEquals(3_500L, a3.durationMs)
        assertEquals(1741944600000L, a1.fileCreatedAt)
        assertEquals(1740938400000L, a2.fileCreatedAt)
        assertEquals(0L, a3.fileCreatedAt)
        assertEquals(1741944600000L + 11 * 3_600_000L, a1.localTakenAt)
        assertEquals(-9.5, a2.localOffsetHours, 0.0)
        assertEquals("v3", a3.livePhotoVideoId); assertTrue(a3.isLivePhoto)
        assertEquals("Melbourne", a1.city); assertEquals("Australia", a1.country)
        assertEquals(4, a2.stackCount); assertEquals(0, a1.stackCount)
        assertEquals(Visibility.ARCHIVE, a2.visibility)
    }

    @Test fun durationParsing() {
        assertNull(TimeBucketAssetsDto.parseDurationMs(null))
        assertNull(TimeBucketAssetsDto.parseDurationMs(JsonPrimitive("0:00:00.000000")))
        assertEquals(3_723_250L, TimeBucketAssetsDto.parseDurationMs(JsonPrimitive("1:02:03.250")))
        assertEquals(1500L, TimeBucketAssetsDto.parseDurationMs(JsonPrimitive(1500)))
    }

    @Test fun bucketsRequestCarriesTheFilter() = runTest {
        server.enqueue(MockResponse().setBody("""[{"timeBucket":"2025-03-01","count":3},{"timeBucket":"2025-01-01","count":120}]"""))
        val buckets = api.timeBuckets(TimelineFilter.album("alb-1"))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/timeline/buckets", url.encodedPath)
        assertEquals("alb-1", url.queryParameter("albumId"))
        assertNull(url.queryParameter("visibility"))
        assertEquals(listOf(3, 120), buckets.map { it.count })

        server.enqueue(MockResponse().setBody("[]"))
        api.timeBuckets(TimelineFilter.Trash)
        val trash = server.takeRequest().requestUrl!!
        assertEquals("true", trash.queryParameter("isTrashed"))
    }

    @Test fun favouriteIsABulkPut() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        api.setFavorite(listOf("a", "b"), true)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/assets", req.path)
        assertEquals("""{"ids":["a","b"],"isFavorite":true}""", req.body.readUtf8())
    }

    @Test fun sourceMapsBuckets() = runTest {
        server.enqueue(MockResponse().setBody("""[{"timeBucket":"2024-12-01","count":7}]"""))
        val source = ImmichTimelineSource { api }
        val b = source.buckets(TimelineFilter.Timeline).single()
        assertEquals(2024, b.year); assertEquals(12, b.month); assertEquals(7, b.count)
    }

    @Test fun filterEncodesForNavigation() {
        listOf(
            TimelineFilter.Timeline, TimelineFilter.Favorites, TimelineFilter.Archive, TimelineFilter.Trash,
            TimelineFilter.album("1f0e-ab", newestFirst = false), TimelineFilter.person("p-9"),
        ).forEach { assertEquals(it, TimelineFilter.decode(it.encode())) }
        assertEquals(TimelineFilter.Timeline, TimelineFilter.decode(""))
    }

    @Test fun snapshotIndexesAcrossBuckets() {
        val buckets = listOf(TimeBucket("2025-03-01", 3), TimeBucket("2025-02-01", 2), TimeBucket("2025-01-01", 4))
        val s = TimelineSnapshot(buckets, mapOf("2025-02-01" to listOf(asset("x"), asset("y"), asset("z"))))
        // The loaded bucket's real size (3) wins over its summary count (2).
        assertEquals(listOf(0, 3, 6, 10), s.offsets.toList())
        assertEquals(10, s.total)
        assertEquals(0, s.bucketIndexOf(2)); assertEquals(1, s.bucketIndexOf(3)); assertEquals(2, s.bucketIndexOf(9))
        assertNull(s.assetAt(0))
        assertEquals("y", s.assetAt(4)?.id)
        assertEquals(5, s.indexOf("z"))
        assertEquals(-1, s.indexOf("nope"))
    }

    @Test fun repositoryLoadsLazilyAndPrefetchesNeighbours() = runTest {
        val source = FakeSource(List(6) { TimeBucket("2025-0${6 - it}-01", 2) })
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val repo = TimelineRepository(source, TimelineFilter.Timeline, scope, prefetch = 1)
        repo.start()
        scope.advanceUntilIdle()
        assertEquals(TimelineRepository.Status.Ready, repo.status.value)
        // First month and its neighbour are warmed.
        assertEquals(setOf("2025-06-01", "2025-05-01"), repo.snapshot.value.loaded.keys)

        repo.ensureLoaded(4)
        scope.advanceUntilIdle()
        assertEquals(setOf("2025-06-01", "2025-05-01", "2025-03-01", "2025-02-01", "2025-01-01"), repo.snapshot.value.loaded.keys)
        // Each month is fetched once, however often it's asked for.
        repo.ensureLoaded(4); repo.ensureLoaded(5)
        scope.advanceUntilIdle()
        assertEquals(1, source.fetches.count { it == "2025-02-01" })

        val id = repo.snapshot.value.assetAt(0)!!.id
        repo.setFavorite(listOf(id), true)
        assertTrue(repo.snapshot.value.assetAt(0)!!.isFavorite)
        repo.remove(listOf(id))
        assertEquals(11, repo.snapshot.value.total)
        assertFalse(repo.snapshot.value.indexOf(id) >= 0)
    }

    @Test fun awaitBucketWaitsForTheFetch() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource(listOf(TimeBucket("2025-01-01", 1)), gate)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val repo = TimelineRepository(source, TimelineFilter.Timeline, scope)
        repo.start(); scope.advanceUntilIdle()
        assertTrue(repo.snapshot.value.loaded.isEmpty())
        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertEquals(1, repo.snapshot.value.loaded.size)
    }

    @Test fun failedListIsReported() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val repo = TimelineRepository(object : TimelineSource {
            override suspend fun buckets(filter: TimelineFilter): List<TimeBucket> = throw java.io.IOException("offline")
            override suspend fun bucket(filter: TimelineFilter, key: String): List<TimelineAsset> = emptyList()
        }, TimelineFilter.Timeline, scope)
        repo.start(); scope.advanceUntilIdle()
        assertEquals(TimelineRepository.Status.Failed("offline"), repo.status.value)
    }

    private fun asset(id: String) = TimelineAsset(id, 1f, isImage = true, isFavorite = false)

    private class FakeSource(val list: List<TimeBucket>, val gate: CompletableDeferred<Unit>? = null) : TimelineSource {
        val fetches = mutableListOf<String>()
        override suspend fun buckets(filter: TimelineFilter) = list
        override suspend fun bucket(filter: TimelineFilter, key: String): List<TimelineAsset> {
            gate?.await()
            fetches += key
            val n = list.first { it.key == key }.count
            return List(n) { TimelineAsset("$key-$it", 1f, isImage = true, isFavorite = false) }
        }
    }
}

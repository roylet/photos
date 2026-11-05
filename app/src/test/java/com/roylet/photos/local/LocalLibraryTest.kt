package com.roylet.photos.local

import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.FakeLibrary
import com.roylet.photos.domain.library.ConsentDeniedException
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.local.FakeMediaStore.Companion.photo
import com.roylet.photos.local.FakeMediaStore.Companion.utc
import com.roylet.photos.local.FakeMediaStore.Companion.video
import com.roylet.photos.ui.gallery.SourceMediaUrls
import com.roylet.photos.ui.permissions.MediaAccess
import com.roylet.photos.domain.library.AssetDetails
import com.roylet.photos.domain.source.MediaSourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

/** The phone's timeline and library over the index: filters, folders, search, MediaStore requests, Android 10's own trash, URLs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalLibraryTest {
    private val db = MediaDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val appDb = PhotosDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val appDao = appDb.app()
    private val dao = db.media()
    private val store = FakeMediaStore()
    private var access = MediaAccess.Full
    private val zone = ZoneId.of("Australia/Melbourne")
    private val indexer = MediaIndexer(dao, store, { access }, CoroutineScope(Dispatchers.Unconfined), { zone })
    private val now = utc(2026, 11, 5)

    /** Records requests; answers [allow]. */
    private val requests = mutableListOf<MediaRequest>()
    private var allow = true
    private val runner = MediaRequestRunner { requests += it; allow }

    private val io = object : LocalIo {
        override suspend fun details(item: MediaEntity) = AssetDetails("local:${item.id}", MediaSourceKind.Local, fileName = item.displayName)
        override suspend fun copy(item: MediaEntity, dest: File, onProgress: (Long, Long) -> Unit) { dest.writeText("x") }
    }

    private fun library(mediaStoreWrites: Boolean = true, legacy: LegacyFiles? = null) =
        MediaStoreLibrary(dao, appDao, indexer, mediaStoreWrites, runner, legacy, io, now = { now })

    private fun source(mediaStoreTrash: Boolean = true) = MediaStoreTimelineSource(dao, appDao, indexer, { access }, mediaStoreTrash)

    @Before fun fill() {
        store.put(photo(1, taken = utc(2026, 10, 3), bucket = 10, bucketName = "Camera").copy(isFavorite = true))
        store.put(video(2, taken = utc(2026, 10, 2)).copy(bucketId = 10, bucketName = "Camera"))
        store.put(photo(3, taken = utc(2026, 9, 9), bucket = 20, bucketName = "Screenshots", name = "Screenshot_beach.png"))
        store.put(photo(4, taken = utc(2025, 3, 1), bucket = 30, bucketName = "WhatsApp Images").copy(isTrashed = true, dateExpires = (now + 12 * 86_400_000L) / 1000))
        runBlocking { indexer.sync() }
    }

    @After fun close() = db.close()

    private fun keys(filter: TimelineFilter) = runBlocking { source().buckets(filter).map { it.key to it.count } }
    private fun ids(filter: TimelineFilter, key: String) = runBlocking { source().bucket(filter, key).map { it.id } }

    /* ---------------- Timeline ---------------- */

    @Test fun `timeline buckets by month newest first and leaves out the trash`() {
        assertEquals(listOf("2026-10-01T00:00:00.000Z" to 2, "2026-09-01T00:00:00.000Z" to 1), keys(TimelineFilter.Timeline))
        assertEquals(listOf("local:1", "local:2"), ids(TimelineFilter.Timeline, "2026-10-01T00:00:00.000Z"))
        val v = runBlocking { source().bucket(TimelineFilter.Timeline, "2026-10-01T00:00:00.000Z") }[1]
        assertFalse(v.isImage)
        assertEquals(12_000L, v.durationMs)
    }

    @Test fun `favourites, trash and folders filter the timeline`() {
        assertEquals(listOf("2026-10-01T00:00:00.000Z" to 1), keys(TimelineFilter.Favorites))
        assertEquals(listOf("2025-03-01T00:00:00.000Z" to 1), keys(TimelineFilter.Trash))
        val trashed = runBlocking { source().bucket(TimelineFilter.Trash, "2025-03-01T00:00:00.000Z") }.single()
        assertEquals(12, trashed.daysLeft(now))
        assertEquals(listOf("2026-10-01T00:00:00.000Z" to 2), keys(TimelineFilter.album("folder:10")))
        assertEquals(listOf("2026-09-01T00:00:00.000Z" to 1), keys(TimelineFilter.album("folder:20")))
        // Oldest first works too, and server-only filters have nothing on the phone.
        assertEquals(listOf("local:2", "local:1"), ids(TimelineFilter.album("folder:10", newestFirst = false), "2026-10-01T00:00:00.000Z"))
        assertEquals(emptyList<Any>(), keys(TimelineFilter.album("some-immich-album")))
        assertEquals(emptyList<Any>(), keys(TimelineFilter.person("p")))
        // Pinned to the phone (Library → On this device) is the plain timeline.
        assertEquals(keys(TimelineFilter.Timeline), keys(TimelineFilter.Device))
        assertEquals(keys(TimelineFilter.Trash), keys(TimelineFilter.DeviceTrash))
    }

    @Test fun `no access, no photos`() {
        access = MediaAccess.Denied
        assertEquals(emptyList<Any>(), keys(TimelineFilter.Timeline))
        assertEquals(emptyList<Any>(), ids(TimelineFilter.Timeline, "2026-10-01T00:00:00.000Z"))
    }

    /* ---------------- Folders and search ---------------- */

    @Test fun `folders are read-only albums, most recent first, with a cover and count`() {
        val folders = runBlocking { library().albums() }
        assertEquals(listOf("folder:10", "folder:20"), folders.map { it.id })
        assertEquals("Camera", folders[0].name)
        assertEquals(2, folders[0].assetCount)
        assertEquals("local:1", folders[0].coverAssetId)
        assertEquals("DCIM/Camera", folders[0].description)
        assertEquals("Screenshots", runBlocking { library().album("folder:20") }.name)
    }

    @Test fun `basic search by file name, folder and date`() {
        val lib = library()
        assertEquals(listOf("local:3"), runBlocking { lib.basicSearch("beach") }.map { it.id })
        assertEquals(listOf("local:1", "local:2"), runBlocking { lib.basicSearch("camera") }.map { it.id })
        assertEquals(listOf("local:1", "local:2"), runBlocking { lib.basicSearch("October 2026") }.map { it.id })
        assertEquals(listOf("local:1", "local:2", "local:3"), runBlocking { lib.basicSearch("2026") }.map { it.id })
        assertEquals(emptyList<String>(), runBlocking { lib.basicSearch("whatsapp") }.map { it.id }) // trashed
        assertEquals(emptyList<String>(), runBlocking { lib.basicSearch("  ") })
    }

    /* ---------------- MediaStore requests (Android 11+) ---------------- */

    @Test fun `favourite, trash, restore and delete build MediaStore requests with the right collections`() {
        val lib = library()
        runBlocking { lib.setFavorite(listOf("local:2", "local:3"), true) }
        assertEquals(
            MediaRequest.Favorite(listOf("content://media/external/video/media/2", "content://media/external/images/media/3"), true),
            requests.last(),
        )
        assertTrue(runBlocking { dao.byId(3) }!!.favorite)

        runBlocking { lib.trash(listOf("local:3")) }
        assertEquals(MediaRequest.Trash(listOf("content://media/external/images/media/3"), true), requests.last())
        assertTrue(runBlocking { dao.byId(3) }!!.trashed)
        assertEquals(30, runBlocking { source().bucket(TimelineFilter.Trash, "2026-09-01T00:00:00.000Z") }.single().daysLeft(now))

        runBlocking { lib.restore(listOf("local:3")) }
        assertEquals(MediaRequest.Trash(listOf("content://media/external/images/media/3"), false), requests.last())
        assertFalse(runBlocking { dao.byId(3) }!!.trashed)

        runBlocking { lib.deleteForever(listOf("local:4")) }
        assertEquals(MediaRequest.Delete(listOf("content://media/external/images/media/4")), requests.last())
        assertNull(runBlocking { dao.byId(4) })
    }

    @Test fun `big selections are split into requests of at most 500`() {
        val items = (1..1203L).map { photo(it).toEntity(zone) }
        val reqs = MediaRequest.trash(items, true)
        assertEquals(listOf(500, 500, 203), reqs.map { it.uris.size })
    }

    @Test fun `a declined dialog changes nothing and says so`() {
        allow = false
        try {
            runBlocking { library().trash(listOf("local:1")) }
            fail("expected ConsentDeniedException")
        } catch (_: ConsentDeniedException) {}
        assertFalse(runBlocking { dao.byId(1) }!!.trashed)
    }

    @Test fun `restore all and empty trash cover everything trashed`() {
        val lib = library()
        assertEquals(1, runBlocking { lib.restoreAll() })
        assertEquals(MediaRequest.Trash(listOf("content://media/external/images/media/4"), false), requests.last())
        runBlocking { lib.trash(listOf("local:1", "local:2")) }
        assertEquals(2, runBlocking { lib.emptyTrash() })
        assertEquals(2, runBlocking { dao.count() })
    }

    @Test fun `phone items share their content uri, nothing to download`() {
        val shared = runBlocking { library().shareUri("local:2") }!!
        assertEquals("content://media/external/video/media/2", shared.uri)
        assertEquals("video/mp4", shared.mimeType)
        assertNull(runBlocking { library().shareUri("some-immich-uuid") })
    }

    /* ---------------- Android 10 ---------------- */

    private class FakeLegacy(var allowDelete: Boolean = true) : LegacyFiles {
        val copies = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val restored = mutableListOf<Long>()
        var nextId = 100L
        override suspend fun copyToTrash(item: MediaEntity) = "/trash/${item.id}.jpg".also { copies += it }
        override suspend fun deleteOriginal(item: MediaEntity) = allowDelete
        override suspend fun restore(row: LegacyTrashEntity): Long { restored += row.originalId; return nextId++ }
        override fun deleteFile(path: String) { deleted += path }
    }

    @Test fun `Android 10 trash moves the original into the app's folder, and restore puts it back`() {
        val legacy = FakeLegacy()
        val lib = library(mediaStoreWrites = false, legacy = legacy)
        runBlocking { lib.trash(listOf("local:3")) }
        assertTrue(requests.isEmpty())
        assertEquals(listOf("/trash/3.jpg"), legacy.copies)
        assertNull(runBlocking { dao.byId(3) })
        val src = source(mediaStoreTrash = false)
        val trashed = runBlocking { src.bucket(TimelineFilter.Trash, "2026-09-01T00:00:00.000Z") }.single()
        assertTrue(trashed.id.startsWith("local:-"))
        assertEquals(30, trashed.daysLeft(now))
        // Its pixels come from the private copy.
        assertEquals("file:///trash/3.jpg", LocalMediaUrls.thumbnail(trashed.id.removePrefix("local:")))

        runBlocking { lib.restore(listOf(trashed.id)) }
        assertEquals(listOf(3L), legacy.restored)
        assertEquals(listOf("/trash/3.jpg"), legacy.deleted)
        assertEquals(emptyList<Any>(), runBlocking { src.buckets(TimelineFilter.Trash) })
    }

    @Test fun `Android 10 trash declined keeps the original and drops the copy`() {
        val legacy = FakeLegacy(allowDelete = false)
        try {
            runBlocking { library(mediaStoreWrites = false, legacy = legacy).trash(listOf("local:3")) }
            fail("expected ConsentDeniedException")
        } catch (_: ConsentDeniedException) {}
        assertEquals(legacy.copies, legacy.deleted)
        assertTrue(runBlocking { dao.byId(3) } != null)
        assertTrue(runBlocking { appDao.legacyTrash() }.isEmpty())
    }

    @Test fun `Android 10 favourites live in the app and survive a re-sync`() {
        val old = FakeMediaStore(hasGenerationsFlag = false)
        old.put(photo(7))
        val idx = MediaIndexer(dao, old, { access }, CoroutineScope(Dispatchers.Unconfined), { zone }, legacyFavorites = { appDao.legacyFavorites() })
        runBlocking { idx.sync() }
        val lib = MediaStoreLibrary(dao, appDao, idx, false, runner, FakeLegacy(), io)
        runBlocking { lib.setFavorite(listOf("local:7"), true) }
        assertTrue(requests.isEmpty())
        old.update(7) { it.copy(displayName = "edited.jpg") }
        runBlocking { idx.sync() }
        assertTrue(runBlocking { dao.byId(7) }!!.favorite)
        runBlocking { lib.setFavorite(listOf("local:7"), false) }
        runBlocking { idx.sync(force = true) }
        assertFalse(runBlocking { dao.byId(7) }!!.favorite)
    }

    /* ---------------- URLs ---------------- */

    @Test fun `media urls route local ids to the phone with the generation in the key`() {
        runBlocking { source().bucket(TimelineFilter.Timeline, "2026-10-01T00:00:00.000Z") } // remembers kinds/generations
        val urls = SourceMediaUrls(immich = null, local = LocalMediaUrls)
        val gen1 = runBlocking { dao.byId(1) }!!.generation
        assertEquals("localmedia://thumb/1?v=0&g=$gen1", urls.thumbnail("local:1"))
        assertEquals("localmedia://full/1?v=0&g=$gen1", urls.preview("local:1"))
        assertTrue(urls.preview("local:2").startsWith("localmedia://thumb/2?v=1"))
        assertEquals("content://media/external/images/media/1", urls.fullsize("local:1"))
        assertEquals("content://media/external/video/media/2", urls.video("local:2"))
        assertEquals("fake://thumbnail/abc-uuid", urls.thumbnail("abc-uuid"))
        assertEquals(Triple("thumb", 2L, true), LocalMediaUrls.parse(urls.thumbnail("local:2")))
        assertNull(LocalMediaUrls.parse("https://x/y"))
        // An edit bumps the generation, and with it the cache key.
        store.update(1) { it.copy(sizeBytes = 1) }
        runBlocking { indexer.sync() }
        runBlocking { source().bucket(TimelineFilter.Timeline, "2026-10-01T00:00:00.000Z") }
        assertTrue(urls.thumbnail("local:1") != "localmedia://thumb/1?v=0&g=$gen1")
    }

    @Test fun `with a server, calls about phone assets go to the phone`() {
        val immich = FakeLibrary()
        val routed = SourceRoutingLibrary(immich, library())
        runBlocking { routed.setFavorite(listOf("local:3", "abc"), true) }
        assertEquals(listOf("setFavorite abc true"), immich.calls)
        assertTrue(runBlocking { dao.byId(3) }!!.favorite)
        assertEquals("Camera", runBlocking { routed.album("folder:10") }.name)
        assertEquals(listOf("local:2"), runBlocking { routed.assets(listOf("local:2")) }.map { it.id })
    }
}

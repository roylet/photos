package com.roylet.photos.local

import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.domain.library.DeviceFolder
import com.roylet.photos.domain.library.DeviceFolderStore
import com.roylet.photos.domain.library.DeviceFolders
import com.roylet.photos.domain.library.FolderKind
import com.roylet.photos.domain.library.FolderPrefs
import com.roylet.photos.domain.library.PhotosFolderMode
import com.roylet.photos.domain.timeline.TimeBucket
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineSource
import com.roylet.photos.domain.source.SourceBadge
import com.roylet.photos.local.FakeMediaStore.Companion.photo
import com.roylet.photos.local.FakeMediaStore.Companion.utc
import com.roylet.photos.local.FakeMediaStore.Companion.video
import com.roylet.photos.merge.Match
import com.roylet.photos.merge.MergedTimelineSource
import com.roylet.photos.merge.MonthMatcher
import com.roylet.photos.ui.permissions.MediaAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Library → On this device: friendly names, Google Photos' order, path hints, hidden / Show in
 * Photos, the grouped folder query, the Photos timeline leaving folders out, and backup badges in
 * a folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceFoldersTest {
    private val db = MediaDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val appDb = PhotosDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.media()
    private val store = FakeMediaStore()
    private val zone = ZoneId.of("Australia/Melbourne")
    private val indexer = MediaIndexer(dao, store, { MediaAccess.Full }, CoroutineScope(Dispatchers.Unconfined), { zone })

    @After fun close() { db.close(); appDb.close() }

    private fun folder(id: Long, name: String, latest: Long, kind: FolderKind = FolderKind.Other, path: String = "Pictures/$name") =
        DeviceFolder(id, name, path, count = 1, latest = latest, coverId = "local:$id", kind = kind)

    /* ---------------- Names ---------------- */

    @Test fun `well-known folders get friendly names`() {
        fun name(bucket: String?, path: String?) = DeviceFolders.displayName(bucket, path)
        fun kind(bucket: String?, path: String?) = DeviceFolders.kindOf(bucket, path)
        assertEquals(FolderKind.Camera, kind("Camera", "DCIM/Camera/"))
        assertEquals("Camera", name("Camera", "DCIM/Camera/"))
        assertEquals(FolderKind.Screenshots, kind("Screenshots", "Pictures/Screenshots/"))
        assertEquals(FolderKind.Screenshots, kind("Screenshots", "DCIM/Screenshots/"))
        assertEquals("Screen recordings", name("ScreenRecords", "DCIM/ScreenRecords/"))
        assertEquals("Screen recordings", name("Screen recordings", "Movies/Screen recordings/"))
        assertEquals("Downloads", name("Download", "Download/"))
        assertEquals("WhatsApp Images", name("WhatsApp Images", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertEquals(FolderKind.WhatsAppVideo, kind("WhatsApp Video", "WhatsApp/Media/WhatsApp Video/"))
        assertEquals("Telegram", name("Telegram Images", "Pictures/Telegram/"))
        assertEquals("Telegram Video", name("Telegram Video", "Movies/Telegram/"))
        assertEquals(FolderKind.Instagram, kind("Instagram", "Pictures/Instagram/"))
        assertEquals(FolderKind.Snapchat, kind("Snapchat", "Pictures/Snapchat/"))
        assertEquals("Pictures", name("Pictures", "Pictures/"))
        assertEquals("Movies", name("Movies", "Movies/"))
        assertEquals("Edited", name("Photos Edited", "Pictures/Photos Edited/"))
        assertEquals(FolderKind.Edited, kind("Photos Edited", "Movies/Photos Edited/"))
        // Anything else keeps its own name; a "Camera" outside DCIM isn't the camera.
        assertEquals("Holiday 2025", name("Holiday 2025", "Pictures/Holiday 2025/"))
        assertEquals(FolderKind.Other, kind("Camera", "Pictures/Camera/"))
        assertEquals("Camera", name("Camera", "Pictures/Camera/"))
        assertEquals("Trips", name(null, "Pictures/Trips/"))
        assertEquals("Folder", name(null, null))
    }

    /* ---------------- Order ---------------- */

    @Test fun `camera first, then screenshots, then the rest by newest item`() {
        val list = listOf(
            folder(1, "Downloads", latest = 50, kind = FolderKind.Downloads),
            folder(2, "Screenshots", latest = 10, kind = FolderKind.Screenshots),
            folder(3, "WhatsApp Images", latest = 90, kind = FolderKind.WhatsAppImages),
            folder(4, "Camera", latest = 5, kind = FolderKind.Camera, path = "DCIM/Camera"),
            folder(5, "Holiday", latest = 70),
        )
        assertEquals(listOf(4L, 2L, 3L, 5L, 1L), DeviceFolders.order(list).map { it.bucketId })
    }

    @Test fun `folders with the same name stay apart, with a path hint`() {
        val internal = DeviceFolders.of(1, "Camera", "DCIM/Camera/", "external_primary", 3, 10, "local:1")
        val sd = DeviceFolders.of(2, "Camera", "DCIM/Camera/", "1a2b-3c4d", 2, 20, "local:2")
        val other = DeviceFolders.of(3, "Camera", "Pictures/Camera/", "external_primary", 1, 30, "local:3")
        val shots = DeviceFolders.of(4, "Screenshots", "Pictures/Screenshots/", "external_primary", 1, 30, "local:4")
        val hints = DeviceFolders.pathHints(listOf(internal, sd, other, shots))
        assertEquals("DCIM/Camera", hints[1])
        assertEquals("SD card · DCIM/Camera", hints[2])
        assertEquals("Pictures/Camera", hints[3])
        assertEquals("…/Media/WhatsApp Images", DeviceFolders.pathHint(DeviceFolders.of(5, "WhatsApp Images", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", null, 1, 1, null)))
        assertNull(hints[4])
        assertTrue(sd.removable)
        assertFalse(internal.removable)
        assertEquals(3, DeviceFolders.order(listOf(other, sd, internal)).map { it.id }.distinct().size)
    }

    /* ---------------- Hidden and Show in Photos ---------------- */

    @Test fun `hidden folders and Show in Photos`() {
        val camera = folder(1, "Camera", 1, FolderKind.Camera)
        val shots = folder(2, "Screenshots", 1, FolderKind.Screenshots)
        val memes = folder(3, "Memes", 1)
        val all = listOf(camera, shots, memes)

        val p = FolderPrefs()
        assertTrue(all.all(p::showsInPhotos))
        assertEquals(emptySet<Long>(), p.excludedFromPhotos(all))

        val off = p.withInPhotos(3, false).withHidden(2, true)
        assertEquals(setOf(3L), off.excludedFromPhotos(all))
        assertTrue(off.isHidden(shots))
        // Hidden from Library is not hidden from Photos.
        assertTrue(off.showsInPhotos(shots))
        assertFalse(off.withHidden(2, false).isHidden(shots))

        val selected = FolderPrefs(mode = PhotosFolderMode.CameraAndSelected)
        assertEquals(setOf(2L, 3L), selected.excludedFromPhotos(all))
        assertEquals(setOf(3L), selected.withInPhotos(2, true).excludedFromPhotos(all))
        assertEquals(setOf(1L, 2L, 3L), selected.withInPhotos(1, false).excludedFromPhotos(all))
    }

    @Test fun `the store orders, persists choices and tells the timeline`() = runBlocking {
        var saved: FolderPrefs? = null
        var photosChanged = 0
        val folders = DeviceFolderStore(
            { listOf(folder(3, "Memes", 9), folder(1, "Camera", 1, FolderKind.Camera)) },
            save = { saved = it }, scope = CoroutineScope(Dispatchers.Unconfined),
        ).apply { onPhotosChanged = { photosChanged++ } }
        folders.start()
        assertEquals(listOf(1L, 3L), folders.folders.value?.map { it.bucketId })

        folders.setHidden(3, true)
        assertEquals(setOf(3L), saved?.hidden)
        assertEquals(0, photosChanged)
        assertEquals(emptySet<Long>(), folders.excludedFromPhotos())

        folders.setShowInPhotos(3, false)
        assertEquals(1, photosChanged)
        assertEquals(setOf(3L), folders.excludedFromPhotos())
        folders.setShowInPhotos(3, false)
        assertEquals(1, photosChanged)

        folders.setShowInPhotos(3, true)
        folders.setMode(PhotosFolderMode.CameraAndSelected)
        assertEquals(3, photosChanged)
        assertEquals(emptySet<Long>(), folders.excludedFromPhotos())
        assertEquals(PhotosFolderMode.CameraAndSelected, saved?.mode)
    }

    /* ---------------- The grouped query ---------------- */

    private fun fill() {
        store.put(photo(1, taken = utc(2026, 10, 3), bucket = 10, bucketName = "Camera"))
        store.put(video(2, taken = utc(2026, 10, 5)).copy(bucketId = 10, bucketName = "Camera", relativePath = "DCIM/Camera/"))
        store.put(photo(3, taken = utc(2026, 9, 9), bucket = 20, bucketName = "Screenshots").copy(relativePath = "Pictures/Screenshots/"))
        store.put(photo(4, taken = utc(2026, 11, 1), bucket = 20, bucketName = "Screenshots").copy(relativePath = "Pictures/Screenshots/", isTrashed = true))
        store.put(photo(5, taken = utc(2026, 8, 1), bucket = 30, bucketName = "Memes").copy(relativePath = "Pictures/Memes/"))
        store.put(photo(6, taken = utc(2026, 10, 4), bucket = 30, bucketName = "Memes").copy(relativePath = "Pictures/Memes/"))
        runBlocking { indexer.sync() }
    }

    @Test fun `one grouped query gives counts, newest cover and its kind`() {
        fill()
        val rows = runBlocking { dao.folders() }.associateBy { it.bucketId }
        assertEquals(setOf(10L, 20L, 30L), rows.keys)
        assertEquals(2, rows.getValue(10).count)
        // The newest item is the cover, with its kind and generation for the thumbnail URL.
        assertEquals(2L, rows.getValue(10).coverId)
        assertTrue(rows.getValue(10).coverIsVideo)
        assertEquals(runBlocking { dao.byId(2) }!!.generation, rows.getValue(10).coverGeneration)
        assertEquals(utc(2026, 10, 5), rows.getValue(10).latest)
        // Trashed items count for nothing, not even the cover.
        assertEquals(1, rows.getValue(20).count)
        assertEquals(3L, rows.getValue(20).coverId)
        assertEquals(6L, rows.getValue(30).coverId)
        assertEquals("external_primary", rows.getValue(30).volume)

        val lib = MediaStoreLibrary(dao, appDb.app(), indexer, true, MediaRequestRunner { true }, null, NoIo)
        val folders = DeviceFolders.order(runBlocking { lib.deviceFolders() })
        assertEquals(listOf("Camera", "Screenshots", "Memes"), folders.map { it.name })
        assertEquals("local:2", folders[0].coverId)
        assertEquals("Pictures/Screenshots", folders[1].path)
        // The cover's URL is ready without another lookup.
        assertTrue(LocalMediaUrls.thumbnail("2").contains("v=1"))
    }

    @Test fun `the Photos timeline leaves out folders turned off, folders and the phone timeline don't`() {
        fill()
        var excluded = setOf(30L)
        val source = MediaStoreTimelineSource(dao, appDb.app(), indexer, { MediaAccess.Full }, true, excludedFromPhotos = { excluded })
        fun ids(filter: TimelineFilter) = runBlocking { source.buckets(filter).flatMap { b -> source.bucket(filter, b.key).map { it.id } } }
        assertEquals(listOf("local:2", "local:1", "local:3"), ids(TimelineFilter.Timeline))
        assertEquals(listOf(2, 1), runBlocking { source.buckets(TimelineFilter.Timeline) }.map { it.count })
        assertEquals(listOf("local:6", "local:5"), ids(TimelineFilter.album("folder:30")))
        assertEquals(5, ids(TimelineFilter.Device).size)
        excluded = emptySet()
        assertEquals(5, ids(TimelineFilter.Timeline).size)
    }

    /* ---------------- Backup badges in a folder ---------------- */

    @Test fun `a folder with a server marks the items Immich has`() {
        val phone = object : TimelineSource {
            override suspend fun buckets(filter: TimelineFilter) = listOf(TimeBucket("2026-10-01T00:00:00.000Z", 2))
            override suspend fun bucket(filter: TimelineFilter, key: String) = listOf(
                TimelineAsset("local:1", 1f, true, false), TimelineAsset("local:2", 1f, true, false),
            )
        }
        val remote = object : TimelineSource {
            override suspend fun buckets(filter: TimelineFilter) = error("a folder never asks Immich")
            override suspend fun bucket(filter: TimelineFilter, key: String) = error("a folder never asks Immich")
        }
        val matcher = object : MonthMatcher {
            override suspend fun matchMonth(bucketKey: String, localIds: List<Long>, remoteIds: Set<String>) = emptyMap<Long, Match>()
            override suspend fun cachedRemote(localIds: List<Long>) = mapOf(1L to Match("uuid-1", tentative = false))
            override suspend fun cachedLocal(remoteIds: List<String>) = emptyMap<String, Long>()
            override suspend fun pairsPerMonth() = emptyMap<String, Int>()
        }
        val merged = MergedTimelineSource(phone, remote) { matcher }
        val items = runBlocking { merged.bucket(TimelineFilter.album("folder:10"), "2026-10-01T00:00:00.000Z") }
        assertEquals(listOf("local:1", "local:2"), items.map { it.id })
        assertEquals(SourceBadge.Both, items[0].sourceBadge)
        assertEquals(SourceBadge.LocalOnly, items[1].sourceBadge)
    }

    private object NoIo : LocalIo {
        override suspend fun details(item: MediaEntity) = throw UnsupportedOperationException()
        override suspend fun copy(item: MediaEntity, dest: java.io.File, onProgress: (Long, Long) -> Unit) = Unit
    }
}

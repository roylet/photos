package com.roylet.photos.backup

import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.local.FakeMediaStore
import com.roylet.photos.local.FakeMediaStore.Companion.photo
import com.roylet.photos.local.FakeMediaStore.Companion.utc
import com.roylet.photos.local.FakeMediaStore.Companion.video
import com.roylet.photos.local.MatchEntity
import com.roylet.photos.local.MediaDatabase
import com.roylet.photos.local.MediaEntity
import com.roylet.photos.local.MediaIndexer
import com.roylet.photos.local.PhotosDatabase
import com.roylet.photos.local.Sha1Hasher
import com.roylet.photos.ui.permissions.MediaAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import java.util.Collections

/**
 * The backup loop against real Room databases (index + app database) and a MockWebServer Immich:
 * selection, skip-if-present, the upload request, the error paths, resume and the match cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupEngineTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val index = MediaDatabase.inMemory(context)
    private val appDb = PhotosDatabase.inMemory(context)
    private val store = FakeMediaStore()
    private val indexer = MediaIndexer(index.media(), store, { MediaAccess.Full }, CoroutineScope(Dispatchers.Unconfined), { ZoneId.of("UTC") })
    private val server = MockWebServer()
    private val api by lazy { ImmichApi(server.url("/").toString(), "key-1") }

    /** What the fake Immich holds: SHA-1 hex → (asset id, trashed). */
    private val immichHas = Collections.synchronizedMap(HashMap<String, Pair<String, Boolean>>())
    /** Status codes to answer the next uploads with (then 201). */
    private val uploadErrors = Collections.synchronizedList(ArrayList<Int>())
    private var nextUploadStatus = "created"
    private val uploads = Collections.synchronizedList(ArrayList<RecordedRequest>())
    private val unreadable = HashSet<Long>()
    private val trashedNow = HashSet<Long>()
    private val recorded = ArrayList<Long>()

    private fun bytes(id: Long) = "file bytes of $id".toByteArray()
    private fun sha(id: Long) = Sha1Hasher.sha1Hex(ByteArrayInputStream(bytes(id)))

    private fun open(e: MediaEntity): InputStream? {
        if (e.id in unreadable) throw IOException("gone")
        return ByteArrayInputStream(bytes(e.id))
    }

    private fun engine(batch: Int = 50) = BackupEngine(
        media = index.media(), queue = index.backup(), matches = index.matches(), store = appDb.backup(),
        remote = { ImmichBackupRemote(api) }, open = ::open,
        fileInfo = { e -> LocalFile(bytes(e.id).size.toLong(), modifiedAt = 1_700_000_000_000L + e.id, trashed = e.id in trashedNow) },
        now = { 42L }, batchSize = batch, onRecorded = { recorded += it },
    )

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/assets/bulk-upload-check" -> {
                    val body = request.body.readUtf8()
                    val ids = Regex("\"id\":\"([0-9a-f]{40})\"").findAll(body).map { it.groupValues[1] }.toList()
                    val results = ids.joinToString(",") { h ->
                        val has = immichHas[h]
                        if (has == null) """{"id":"$h","action":"accept"}"""
                        else """{"id":"$h","action":"reject","reason":"duplicate","assetId":"${has.first}","isTrashed":${has.second}}"""
                    }
                    MockResponse().setBody("""{"results":[$results]}""")
                }
                request.path == "/api/assets" && request.method == "POST" -> {
                    uploads += request
                    val err = synchronized(uploadErrors) { if (uploadErrors.isEmpty()) null else uploadErrors.removeAt(0) }
                    if (err != null) MockResponse().setResponseCode(err).setBody("""{"message":"error $err"}""")
                    else {
                        val sum = request.getHeader("x-immich-checksum")!!
                        val id = "asset-" + sum.take(8)
                        immichHas[sum] = id to false
                        MockResponse().setResponseCode(if (nextUploadStatus == "created") 201 else 200).setBody("""{"id":"$id","status":"$nextUploadStatus"}""")
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
        index.close()
        appDb.close()
    }

    private fun sync() = runBlocking { indexer.sync() }
    private fun run(sel: BackupSelection = BackupSelection(listOf(1L), includeVideos = true), stop: () -> Boolean = { false }) =
        runBlocking { engine().run(sel, stop) }
    private fun uploadedNames() = uploads.map { Regex("filename=\"([^\"]+)\"").find(it.body.clone().readUtf8())?.groupValues?.get(1) }
    private fun match(id: Long) = runBlocking { index.matches().byLocalIds(listOf(id)) }.firstOrNull()

    @Test fun `only selected folders, the chosen kinds and the chosen dates are backed up`() {
        store.put(photo(1, taken = utc(2026, 10, 1)))
        store.put(photo(2, taken = utc(2026, 10, 2), bucket = 2, bucketName = "Screenshots"))
        store.put(video(3, taken = utc(2026, 10, 3)))
        store.put(photo(4, taken = utc(2020, 1, 1)))
        sync()
        val r = run(BackupSelection(listOf(1L), includeVideos = false, since = utc(2026, 1, 1)))
        assertEquals(BackupRun.Done(uploaded = 1, alreadyThere = 0, failed = 0, excluded = 0), r)
        assertEquals(listOf("IMG_1.jpg"), uploadedNames())
        // Videos on, everything: the video and the old photo follow; Screenshots never.
        val r2 = run(BackupSelection(listOf(1L), includeVideos = true))
        assertEquals(2, (r2 as BackupRun.Done).uploaded)
        assertEquals(setOf("IMG_1.jpg", "VID_3.mp4", "IMG_4.jpg"), uploadedNames().toSet())
        assertNull(match(2))
    }

    @Test fun `the upload carries Immich's fields, the checksum header and the original bytes`() {
        store.put(photo(7, taken = utc(2026, 10, 5, 9, 30)).copy(isFavorite = true))
        sync()
        run()
        val req = uploads.single()
        assertEquals(sha(7), req.getHeader("x-immich-checksum"))
        assertEquals("key-1", req.getHeader("x-api-key"))
        assertNotNull(req.getHeader("Content-Length"))
        val body = req.body.readUtf8()
        fun field(name: String) = Regex("name=\"$name\"\\r\\n(?:[^\\r\\n]+\\r\\n)*\\r\\n([^\\r\\n]*)").find(body)?.groupValues?.get(1)
        assertEquals("2026-10-05T09:30:00Z", field("fileCreatedAt"))
        assertEquals("2023-11-14T22:13:20.007Z", field("fileModifiedAt"))
        assertEquals("IMG_7.jpg", field("filename"))
        assertEquals("true", field("isFavorite"))
        // Not in the current AssetMediaCreateDto: left out.
        assertFalse(body.contains("name=\"deviceAssetId\""))
        assertFalse(body.contains("name=\"deviceId\""))
        assertTrue(body.contains("filename=\"IMG_7.jpg\""))
        assertTrue(body.contains(String(bytes(7))))
    }

    @Test fun `the hash is computed when missing and kept in the index`() {
        store.put(photo(1)); sync()
        assertNull(runBlocking { index.media().byId(1) }!!.sha1)
        run()
        assertEquals(sha(1), runBlocking { index.media().byId(1) }!!.sha1)
    }

    @Test fun `a confirmed match in the cache is skipped without asking Immich`() {
        store.put(photo(1)); store.put(photo(2)); sync()
        val gen = runBlocking { index.media().byId(1) }!!.generation
        runBlocking { index.matches().upsert(listOf(MatchEntity(1, gen, "already", tentative = false, checkedAt = 1))) }
        run()
        assertEquals(listOf("IMG_2.jpg"), uploadedNames())
        assertEquals(1, server.requestCount - uploads.size) // one bulk check, for item 2 only
    }

    @Test fun `bulk-upload-check finds what Immich already has, and the match is recorded`() {
        store.put(photo(1)); store.put(photo(2)); sync()
        immichHas[sha(1)] = "remote-1" to false
        val r = run() as BackupRun.Done
        assertEquals(1, r.alreadyThere)
        assertEquals(1, r.uploaded)
        assertEquals(listOf("IMG_2.jpg"), uploadedNames())
        assertEquals("remote-1", match(1)!!.remoteId)
        assertFalse(match(1)!!.tentative)
        // The upload's result is in the cache too, so the merged timeline shows it on both sides.
        assertEquals("asset-" + sha(2).take(8), match(2)!!.remoteId)
        assertEquals(runBlocking { index.media().byId(2) }!!.generation, match(2)!!.generation)
        assertEquals(setOf(1L, 2L), recorded.toSet())
        // Nothing left: a second run makes no requests but the queue read.
        val before = server.requestCount
        run()
        assertEquals(before, server.requestCount)
    }

    @Test fun `a duplicate answer counts as backed up`() {
        nextUploadStatus = "duplicate"
        store.put(photo(1)); sync()
        val r = run() as BackupRun.Done
        assertEquals(1, r.alreadyThere)
        assertEquals(0, r.uploaded)
        assertNotNull(match(1)!!.remoteId)
    }

    @Test fun `a copy in Immich's Trash is never re-uploaded`() {
        store.put(photo(1)); sync()
        immichHas[sha(1)] = "trashed-1" to true
        val r = run() as BackupRun.Done
        assertEquals(1, r.excluded)
        assertTrue(uploads.isEmpty())
        val ex = runBlocking { appDb.backup().exclusions() }.single()
        assertEquals(BackupReasons.IN_IMMICH_TRASH, ex.reason)
        assertEquals(sha(1), ex.sha1)
    }

    @Test fun `transient errors end the run for a retry and keep nothing as failed`() {
        store.put(photo(1)); store.put(photo(2)); sync()
        uploadErrors += 503
        assertTrue(run() is BackupRun.Retry)
        assertTrue(runBlocking { appDb.backup().failures() }.isEmpty())
        uploadErrors += 429
        assertTrue(run() is BackupRun.Retry)
        // The retry uploads both.
        val r = run() as BackupRun.Done
        assertEquals(2, r.uploaded)
    }

    @Test fun `a network failure is transient too`() {
        store.put(photo(1)); sync()
        server.shutdown()
        assertTrue(run() is BackupRun.Retry)
        assertTrue(runBlocking { appDb.backup().failures() }.isEmpty())
    }

    @Test fun `permanent errors are recorded with a reason and skipped`() {
        store.put(photo(1, taken = utc(2026, 10, 2))); store.put(photo(2, taken = utc(2026, 10, 1))); store.put(photo(3, taken = utc(2026, 9, 1))); sync()
        uploadErrors += 415
        unreadable += 3
        val r = run() as BackupRun.Done
        assertEquals(1, r.uploaded)
        assertEquals(2, r.failed)
        val failures = runBlocking { appDb.backup().failures() }.associateBy { it.localId }
        assertEquals(415, failures[1]!!.status)
        assertEquals("error 415", failures[1]!!.reason)
        assertEquals("Couldn't read this file", failures[3]!!.reason)
        // Next run: they're skipped (same version), nothing uploaded.
        val before = uploads.size
        run()
        assertEquals(before, uploads.size)
        // An edit (new generation) tries again.
        store.update(1) { it.copy(sizeBytes = 3) }; sync()
        run()
        assertEquals(before + 1, uploads.size)
        assertNull(runBlocking { appDb.backup().failures() }.firstOrNull { it.localId == 1L })
    }

    @Test fun `auth errors stop the run`() {
        store.put(photo(1)); store.put(photo(2)); sync()
        uploadErrors += 401
        assertTrue(run() is BackupRun.AuthFailed)
        assertEquals(1, uploads.size)
        assertTrue(runBlocking { appDb.backup().failures() }.isEmpty())
        uploadErrors += 403
        assertTrue(run() is BackupRun.AuthFailed)
    }

    @Test fun `a stopped run resumes with the next item`() {
        for (i in 1L..4L) store.put(photo(i, taken = utc(2026, 10, i.toInt())))
        sync()
        var n = 0
        // Stop as soon as two uploads went (WorkManager stopping the job, or process death).
        val r = run(stop = { uploads.size >= 2 && n++ >= 0 })
        assertEquals(BackupRun.Stopped, r)
        assertEquals(listOf("IMG_4.jpg", "IMG_3.jpg"), uploadedNames())
        // A new engine (a new process) carries on from the index and the match cache.
        val r2 = run() as BackupRun.Done
        assertEquals(2, r2.uploaded)
        assertEquals(listOf("IMG_4.jpg", "IMG_3.jpg", "IMG_2.jpg", "IMG_1.jpg"), uploadedNames())
    }

    @Test fun `trashed items are never uploaded`() {
        store.put(photo(1)); store.put(photo(2).copy(isTrashed = true)); store.put(photo(3)); sync()
        // Item 3 went to the phone's Trash after the last sync: MediaStore says so at upload time.
        trashedNow += 3
        run()
        assertEquals(listOf("IMG_1.jpg"), uploadedNames())
    }

    @Test fun `excluded items are never uploaded, by id or by hash`() {
        store.put(photo(1)); store.put(photo(2)); store.put(photo(3)); sync()
        runBlocking {
            appDb.backup().putExclusions(listOf(
                BackupExclusionEntity(1, null, "r1", "IMG_1.jpg", 1, BackupReasons.DELETED_FROM_IMMICH, 1),
                // The same file, back under another id: matched by its SHA-1.
                BackupExclusionEntity(99, sha(2), "r2", "IMG_2.jpg", 1, BackupReasons.DELETED_FROM_IMMICH, 1),
            ))
        }
        run()
        assertEquals(listOf("IMG_3.jpg"), uploadedNames())
    }

    @Test fun `constraints follow the settings`() {
        val d = BackupOptions().constraints()
        assertEquals(NetworkType.UNMETERED, d.requiredNetworkType)
        assertFalse(d.requiresCharging())
        assertTrue(d.requiresBatteryNotLow())
        val any = BackupOptions(wifiOnly = false, whileChargingOnly = true).constraints()
        assertEquals(NetworkType.CONNECTED, any.requiredNetworkType)
        assertTrue(any.requiresCharging())
        assertTrue(any.requiresBatteryNotLow())
    }

    @Test fun `errors are classified for the retry rules`() {
        assertEquals(FailureKind.Auth, BackupEngine.classify(ImmichApi.httpError(401, "")))
        assertEquals(FailureKind.Auth, BackupEngine.classify(ImmichApi.httpError(403, "")))
        assertEquals(FailureKind.Transient, BackupEngine.classify(ImmichApi.httpError(408, "")))
        assertEquals(FailureKind.Transient, BackupEngine.classify(ImmichApi.httpError(500, "")))
        assertEquals(FailureKind.Transient, BackupEngine.classify(IOException("reset")))
        assertEquals(FailureKind.Permanent, BackupEngine.classify(ImmichApi.httpError(415, "")))
        assertEquals(FailureKind.Permanent, BackupEngine.classify(ImmichApi.httpError(400, "")))
        assertEquals(FailureKind.Permanent, BackupEngine.classify(LocalReadException("corrupt")))
    }
}

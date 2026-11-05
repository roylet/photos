package com.roylet.photos.local

import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.save.LocalSaveException
import com.roylet.photos.editor.save.RecipeMetadata
import com.roylet.photos.editor.save.SaveDestination
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.save.SaveRequest
import com.roylet.photos.editor.save.SaveStarter
import com.roylet.photos.editor.save.SinkInput
import com.roylet.photos.editor.save.LocalEditSource
import com.roylet.photos.local.FakeMediaStore.Companion.photo
import com.roylet.photos.local.FakeMediaStore.Companion.utc
import com.roylet.photos.ui.permissions.MediaAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

/** A MediaStore that records what's asked of it, in order. */
class FakeWriter : LocalMediaWriter {
    val log = mutableListOf<String>()
    val inserted = mutableListOf<NewMediaItem>()
    val pending = HashMap<Long, Boolean>()
    val bytes = HashMap<Long, ByteArray>()
    var nextId = 100L
    var failWrite = false
    var refusePaths = setOf<String>()

    override fun insertPending(item: NewMediaItem): Long {
        if (item.relativePath in refusePaths) { log += "refuse ${item.relativePath}"; throw IllegalArgumentException("not allowed") }
        val id = nextId++
        inserted += item
        pending[id] = true
        log += "insert $id pending"
        return id
    }
    override fun write(id: Long, isVideo: Boolean, from: File) {
        if (failWrite) throw java.io.IOException("disk full")
        bytes[id] = from.readBytes(); log += "write $id"
    }
    override fun publish(id: Long, isVideo: Boolean) { pending[id] = false; log += "publish $id" }
    override fun delete(id: Long, isVideo: Boolean) { pending.remove(id); log += "delete $id" }
    /** Makes the written file read back short (a truncated write). */
    var truncate = false
    override fun verify(id: Long, isVideo: Boolean, expectedBytes: Long): Boolean {
        val b = bytes[id] ?: return false
        val size = if (truncate) b.size / 2 else b.size
        return pending[id] == false && size > 0 && size.toLong() == expectedBytes
    }
}

class FakeTrasher(private val log: MutableList<String>, var allow: Boolean = true) : LocalTrasher {
    override suspend fun trash(id: Long, isVideo: Boolean): Boolean { log += "trash $id"; return allow }
    override suspend fun restore(id: Long, isVideo: Boolean): Boolean { log += "restore $id"; return allow }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalSaveSinkTest {
    @get:Rule val tmp = TemporaryFolder()
    private val db = MediaDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val appDb = PhotosDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.media()
    private val store = FakeMediaStore()
    private val indexer = MediaIndexer(dao, store, { MediaAccess.Full }, CoroutineScope(Dispatchers.Unconfined), { ZoneId.of("UTC") })
    private val writer = FakeWriter()
    private val trasher = FakeTrasher(writer.log)
    private val taken = utc(2026, 10, 3, 9)
    private lateinit var rendered: File
    private var checkpoint = LocalSaveCheckpoint()

    @Before fun setUp() {
        store.put(photo(7, taken = taken, name = "IMG_7.HEIC").copy(isFavorite = true))
        runBlocking { indexer.sync() }
        rendered = tmp.newFile("IMG_7-edited.jpg").apply { writeText("jpeg bytes") }
    }

    @After fun close() { db.close(); appDb.close() }

    private fun sink(cp: LocalSaveCheckpoint = checkpoint) =
        LocalSaveSink(dao, appDb.app(), writer, trasher, cp, onCheckpoint = { checkpoint = it }, now = { 42 })

    private fun input(mode: SaveMode, target: String = "local:7") = SinkInput(
        target, target, rendered, "IMG_7-edited.jpg", "image/jpeg", isVideo = false,
        RecipeMetadata.KEY, RecipeMetadata.encode(target, Recipe()), mode, group = false,
    )

    @Test fun `a copy is inserted pending next to the original with its date and favourite, then published`() {
        val r = runBlocking { sink().save(input(SaveMode.COPY)) }
        val item = writer.inserted.single()
        assertEquals("IMG_7-edited.jpg", item.displayName)
        assertEquals("image/jpeg", item.mimeType)
        assertEquals("DCIM/Camera/", item.relativePath)
        assertEquals(taken, item.dateTakenMs)
        assertTrue(item.favorite)
        assertEquals(listOf("insert 100 pending", "write 100", "publish 100"), writer.log)
        assertEquals(false, writer.pending[100])
        assertEquals("jpeg bytes", String(writer.bytes[100]!!))
        assertEquals("local:100", r.newAssetId)
        assertEquals("local:7", r.targetId)
        assertEquals(SaveMode.COPY, r.mode)
        assertTrue(r.local)
        assertFalse(r.originalTrashed)
    }

    @Test fun `replace trashes the original only after the new file is published`() {
        val r = runBlocking { sink().save(input(SaveMode.REPLACE)) }
        assertEquals(listOf("insert 100 pending", "write 100", "publish 100", "trash 7"), writer.log)
        assertTrue(r.originalTrashed)
        assertEquals(SaveMode.REPLACE, r.mode)
    }

    @Test fun `a refused trash leaves a copy with a warning`() {
        trasher.allow = false
        val r = runBlocking { sink().save(input(SaveMode.REPLACE)) }
        assertFalse(r.originalTrashed)
        assertEquals(SaveMode.COPY, r.mode)
        assertTrue(r.warning!!.contains("copy"))
    }

    @Test fun `a failed write deletes the pending item and trashes nothing`() {
        writer.failWrite = true
        val e = runCatching { runBlocking { sink().save(input(SaveMode.REPLACE)) } }.exceptionOrNull()
        assertTrue(e is LocalSaveException)
        assertEquals(listOf("insert 100 pending", "delete 100"), writer.log)
        assertNull(checkpoint.newId)
    }

    @Test fun `replace never trashes the original when the new file doesn't read back whole`() {
        writer.truncate = true
        val e = runCatching { runBlocking { sink().save(input(SaveMode.REPLACE)) } }.exceptionOrNull()
        assertTrue(e is LocalSaveException)
        assertEquals(listOf("insert 100 pending", "write 100", "publish 100", "delete 100"), writer.log)
        assertNull(checkpoint.trashed)
    }

    @Test fun `replace never trashes the original for an empty render`() {
        rendered.writeText("")
        val e = runCatching { runBlocking { sink().save(input(SaveMode.REPLACE)) } }.exceptionOrNull()
        assertTrue(e is LocalSaveException)
        assertFalse(writer.log.any { it.startsWith("trash") })
    }

    @Test fun `a folder Android won't take falls back to Pictures, Photos Edited`() {
        writer.refusePaths = setOf("DCIM/Camera/")
        runBlocking { sink().save(input(SaveMode.COPY)) }
        assertEquals(LocalSaveSink.PHOTOS_FOLDER, writer.inserted.single().relativePath)
    }

    @Test fun `a retry resumes from the checkpoint without inserting twice`() {
        runBlocking { sink().save(input(SaveMode.REPLACE)) }
        writer.log.clear()
        val again = runBlocking { sink(checkpoint).save(input(SaveMode.REPLACE)) }
        assertTrue(writer.log.isEmpty())
        assertEquals("local:100", again.newAssetId)
        assertTrue(again.originalTrashed)
    }

    @Test fun `the recipe is stored under the new id and reopens through the phone's edit source`() {
        runBlocking { sink().save(input(SaveMode.COPY)) }
        val row = runBlocking { appDb.app().edit(100) }!!
        assertEquals("local:7", row.sourceId)
        assertEquals(LocalEditEntity.PHOTO, row.kind)
        val source = LocalEditSource(dao, appDb.app()) { null }
        val meta = runBlocking { source.metadata("local:100", RecipeMetadata.KEY) }!!
        assertEquals("local:7", RecipeMetadata.decode(meta)!!.first)
        assertNull(runBlocking { source.metadata("local:100", "photos.videoEdit") })
    }

    @Test fun `undo restores the original and trashes the edit`() {
        val r = runBlocking { sink().save(input(SaveMode.REPLACE)) }
        writer.log.clear()
        runBlocking { sink().undoReplace(r) }
        assertEquals(listOf("restore 7", "trash 100"), writer.log)
    }

    @Test fun `the original must still be on the phone`() {
        val e = runCatching { runBlocking { sink().save(input(SaveMode.COPY, target = "local:999")) } }.exceptionOrNull()
        assertTrue(e is LocalSaveException)
        assertTrue(writer.log.isEmpty())
    }

    @Test fun `the phone edit source describes the item and copies its bytes`() {
        val source = LocalEditSource(dao, appDb.app()) { "original".byteInputStream() }
        val info = runBlocking { source.info("local:7") }
        assertEquals("IMG_7.HEIC", info.originalFileName)
        assertEquals("IMAGE", info.type)
        val dest = File(tmp.root, "copy/IMG_7.HEIC")
        runBlocking { source.obtain(info, dest) }
        assertEquals("original", dest.readText())
    }

    /* ---------------- Consent before enqueue ---------------- */

    @Test fun `a phone replace asks for consent before anything is queued`() {
        val log = mutableListOf<String>()
        val starter = SaveStarter { id -> log += "consent $id"; true }
        val job = runBlocking { starter.start(SaveRequest("local:7", SaveMode.REPLACE, false, SaveDestination.DEVICE)) { log += "enqueue"; "job-1" } }
        assertEquals("job-1", job)
        assertEquals(listOf("consent local:7", "enqueue"), log)
    }

    @Test fun `a declined consent queues nothing`() {
        var enqueued = false
        val starter = SaveStarter { false }
        val job = runBlocking { starter.start(SaveRequest("local:7", SaveMode.REPLACE, false, SaveDestination.BOTH, "abc")) { enqueued = true; "x" } }
        assertNull(job)
        assertFalse(enqueued)
    }

    @Test fun `copies and Immich-only saves need no consent`() {
        var asked = 0
        val starter = SaveStarter { asked++; false }
        runBlocking {
            starter.start(SaveRequest("local:7", SaveMode.COPY, false, SaveDestination.DEVICE)) { "a" }
            starter.start(SaveRequest("local:7", SaveMode.REPLACE, false, SaveDestination.IMMICH, "abc")) { "b" }
            starter.start(SaveRequest("abc", SaveMode.REPLACE, false, SaveDestination.IMMICH)) { "c" }
        }
        assertEquals(0, asked)
    }
}

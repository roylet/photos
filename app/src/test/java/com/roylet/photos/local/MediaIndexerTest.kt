package com.roylet.photos.local

import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.local.FakeMediaStore.Companion.photo
import com.roylet.photos.local.FakeMediaStore.Companion.utc
import com.roylet.photos.local.FakeMediaStore.Companion.video
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaIndexerTest {
    private val db = MediaDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.media()
    private val store = FakeMediaStore()
    private var access = MediaAccess.Full
    private var zone: ZoneId = ZoneId.of("Australia/Melbourne")
    private val changes = mutableListOf<SyncResult>()
    private val indexer = MediaIndexer(dao, store, { access }, CoroutineScope(Dispatchers.Unconfined), { zone }, onChanged = { changes += it })

    @After fun close() = db.close()

    private fun sync(force: Boolean = false) = runBlocking { indexer.sync(force) }
    private fun row(id: Long) = runBlocking { dao.byId(id) }

    @Test fun `the first sync is full and indexes everything`() {
        store.put(photo(1)); store.put(video(2)); store.put(photo(3))
        val r = sync()
        assertTrue(r.full)
        assertEquals(3, r.written)
        assertEquals(listOf<Long?>(null), store.queries)
        assertEquals(3, runBlocking { dao.count() })
        assertTrue(row(2)!!.isVideo)
        assertEquals(12_000L, row(2)!!.durationMs)
        assertTrue(indexer.ready.value)
        assertEquals(1, changes.size)
    }

    @Test fun `later syncs only read rows above the last generation`() {
        store.put(photo(1)); store.put(photo(2))
        sync()
        val seen = store.generation
        store.put(photo(3))
        store.update(1) { it.copy(displayName = "renamed.jpg") }
        val r = sync()
        assertFalse(r.full)
        assertEquals(seen, store.queries.last())
        assertEquals(2, r.written)
        assertEquals("renamed.jpg", row(1)!!.displayName)
        assertEquals(3, runBlocking { dao.count() })
    }

    @Test fun `nothing changed means no query and no refresh`() {
        store.put(photo(1))
        sync()
        changes.clear()
        val queries = store.queries.size
        val r = sync()
        assertFalse(r.changed)
        assertEquals(queries, store.queries.size)
        assertTrue(changes.isEmpty())
    }

    @Test fun `deletions are found by diffing ids`() {
        store.put(photo(1)); store.put(photo(2)); store.put(photo(3))
        sync()
        store.remove(2)
        val r = sync()
        assertEquals(1, r.deleted)
        assertNull(row(2))
        assertEquals(2, runBlocking { dao.count() })
    }

    @Test fun `a new MediaStore version forces a full sync`() {
        store.put(photo(1))
        sync()
        store.version = "v2"
        assertTrue(sync().full)
        assertEquals(null, store.queries.last())
    }

    @Test fun `a changed access level forces a full sync, and no access wipes the index without querying`() {
        store.put(photo(1)); store.put(photo(2))
        sync()
        access = MediaAccess.Partial
        assertTrue(sync().full)
        access = MediaAccess.Denied
        val queries = store.queries.size
        val ids = store.idQueries
        sync()
        assertEquals(queries, store.queries.size)
        assertEquals(ids, store.idQueries)
        assertEquals(0, runBlocking { dao.count() })
        // Granted again: built from scratch.
        access = MediaAccess.Full
        assertTrue(sync().full)
        assertEquals(2, runBlocking { dao.count() })
    }

    @Test fun `Android 10 has no generations, so every sync is full but unchanged rows aren't rewritten`() {
        val old = FakeMediaStore(hasGenerationsFlag = false)
        val idx = MediaIndexer(dao, old, { access }, CoroutineScope(Dispatchers.Unconfined), { zone })
        old.put(photo(1)); old.put(photo(2))
        runBlocking { idx.sync() }
        val r = runBlocking { idx.sync() }
        assertTrue(r.full)
        assertEquals(0, r.written)
    }

    @Test fun `a still-valid sha1 survives a sync, a stale one is dropped`() {
        store.put(photo(1)); store.put(photo(2))
        sync()
        runBlocking {
            assertEquals(1, dao.setSha1(1, "aaa", row(1)!!.generation))
            assertEquals(1, dao.setSha1(2, "bbb", row(2)!!.generation))
            // A hash computed for an older generation isn't stored.
            assertEquals(0, dao.setSha1(1, "zzz", row(1)!!.generation - 1))
        }
        store.version = "v2" // full sync rewrites everything it reads
        store.update(2) { it.copy(sizeBytes = 99) }
        sync()
        assertEquals("aaa", row(1)!!.sha1)
        assertNull(row(2)!!.sha1)
        assertEquals(listOf(2L), runBlocking { dao.needingSha1(10).map { it.id } })
    }

    @Test fun `moving time zones re-buckets the months`() {
        // 31 Jan 23:30 UTC is already February in Melbourne.
        store.put(photo(1, taken = utc(2026, 1, 31, 23, 30)))
        sync()
        assertEquals(202602, row(1)!!.month)
        zone = ZoneId.of("America/Los_Angeles")
        sync()
        assertEquals(202601, row(1)!!.month)
        assertEquals(-8 * 60, row(1)!!.offsetMinutes)
    }

    @Test fun `trashed and favourite flags and the expiry are mirrored`() {
        store.put(photo(1).copy(isFavorite = true))
        store.put(photo(2).copy(isTrashed = true, dateExpires = 1_800_000_000))
        sync()
        assertTrue(row(1)!!.favorite)
        assertTrue(row(2)!!.trashed)
        assertEquals(1_800_000_000_000L, row(2)!!.expiresAt)
    }

    @Test fun `50k rows sync in batches`() {
        val start = utc(2020, 1, 1)
        for (i in 1..50_000L) store.put(photo(i, taken = start + i * 3_600_000L, bucket = i % 7))
        val t0 = System.nanoTime()
        val r = sync()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(50_000, r.written)
        assertTrue(store.batches.all { it <= SQL_VARS })
        assertEquals(50_000, runBlocking { dao.count() })
        // Months page the timeline: ~5.7 years of hourly photos is 69 months.
        val months = runBlocking { dao.months(trashed = false, favorite = false, bucketId = null) }
        assertEquals(50_000, months.sumOf { it.count })
        assertTrue("sync took $ms ms", ms < 60_000)
        // A second sync with nothing new is cheap: no rows read.
        assertEquals(0, sync().written)
    }

    @Test fun `awaitReady returns once the first sync is done`() {
        store.put(photo(1))
        runBlocking { indexer.awaitReady() }
        assertTrue(indexer.ready.value)
        assertEquals(1, runBlocking { dao.count() })
    }
}

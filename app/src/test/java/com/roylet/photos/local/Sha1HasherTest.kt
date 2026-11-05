package com.roylet.photos.local

import androidx.test.core.app.ApplicationProvider
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Sha1HasherTest {
    private val db = MediaDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.media()
    private val store = FakeMediaStore()
    private val indexer = MediaIndexer(dao, store, { MediaAccess.Full }, CoroutineScope(Dispatchers.Unconfined), { ZoneId.of("UTC") })
    private val opened = mutableListOf<Long>()
    private val unreadable = HashSet<Long>()
    private val batches = mutableListOf<List<Long>>()

    private fun bytes(id: Long) = "photo $id".toByteArray()
    private fun open(e: MediaEntity): InputStream? {
        opened += e.id
        if (e.id in unreadable) throw IOException("gone")
        return ByteArrayInputStream(bytes(e.id))
    }

    private fun hasher(batch: Int = 2) = Sha1Hasher(dao, ::open, batchSize = batch, onBatch = { batches += it })

    @Before fun fill() {
        for (i in 1L..5L) store.put(photo(i, taken = utc(2026, 10, i.toInt())))
        runBlocking { indexer.sync() }
    }

    @After fun close() = db.close()

    @Test fun `hashes everything in batches, newest first, and stores Immich-compatible SHA-1`() {
        val run = runBlocking { hasher().run() }
        assertEquals(5, run.hashed)
        assertFalse(run.stopped)
        assertEquals(listOf(listOf(5L, 4L), listOf(3L, 2L), listOf(1L)), batches)
        val expected = Sha1Hasher.sha1Hex(ByteArrayInputStream(bytes(3)))
        assertEquals(expected, runBlocking { dao.byId(3) }!!.sha1)
        assertEquals(40, expected.length)
        assertEquals(Sha1Counts(5, 5), runBlocking { dao.sha1Counts() })
        // SHA-1("abc"), as Immich stores it (base64 of these bytes).
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Sha1Hasher.sha1Hex(ByteArrayInputStream("abc".toByteArray())))
    }

    @Test fun `a stopped run resumes where it left off`() {
        var calls = 0
        val first = runBlocking { hasher().run(shouldStop = { calls++ >= 3 }) }
        assertTrue(first.stopped)
        val doneFirst = runBlocking { dao.sha1Counts() }.done
        assertTrue(doneFirst in 1..4)
        opened.clear()
        val second = runBlocking { hasher().run() }
        assertEquals(5 - doneFirst, second.hashed)
        assertEquals(5 - doneFirst, opened.size) // nothing hashed twice
        assertEquals(Sha1Counts(5, 5), runBlocking { dao.sha1Counts() })
    }

    @Test fun `an item that changed while hashing isn't stored and comes round at its new generation`() {
        val h = Sha1Hasher(dao, { e ->
            if (e.id == 4L && opened.none { it == 4L }) {
                opened += 4L
                // The photo is edited between reading the queue and storing the hash.
                store.update(4) { it.copy(sizeBytes = 3_000_000) }
                runBlocking { indexer.sync() }
            }
            ByteArrayInputStream(bytes(e.id))
        }, batchSize = 2)
        val run = runBlocking { h.run() }
        assertEquals(1, run.stale)
        assertEquals(5, run.hashed)
        val row = runBlocking { dao.byId(4) }!!
        assertEquals(row.generation, row.sha1Generation)
    }

    @Test fun `unreadable items are skipped for the run without looping`() {
        unreadable += 2L
        val run = runBlocking { hasher().run() }
        assertEquals(4, run.hashed)
        assertEquals(1, run.failed)
        assertNull(runBlocking { dao.byId(2) }!!.sha1)
        assertEquals(1, opened.count { it == 2L })
        assertEquals(Sha1Counts(4, 5), runBlocking { dao.sha1Counts() })
    }

    @Test fun `an edit after hashing drops the hash from the counts`() {
        runBlocking { hasher().run() }
        store.update(1) { it.copy(sizeBytes = 1) }
        runBlocking { indexer.sync() }
        assertEquals(Sha1Counts(4, 5), runBlocking { dao.sha1Counts() })
        assertEquals(listOf(1L), runBlocking { dao.needingSha1(10) }.map { it.id })
    }
}

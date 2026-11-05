package com.roylet.photos.merge

import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.local.FakeMediaStore
import com.roylet.photos.local.FakeMediaStore.Companion.photo
import com.roylet.photos.local.FakeMediaStore.Companion.utc
import com.roylet.photos.local.MediaDatabase
import com.roylet.photos.local.MediaIndexer
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
import java.time.ZoneId

/** A scripted Immich: assets per month, and which hashes bulk-upload-check knows. */
class FakeRemote : RemoteLookup {
    val months = HashMap<String, MutableList<RemoteCandidate>>()
    val known = HashMap<String, String>()
    var monthCalls = 0
    val bulkCalls = mutableListOf<List<String>>()
    var offline = false

    override suspend fun month(bucketKey: String): List<RemoteCandidate> {
        monthCalls++
        if (offline) throw java.io.IOException("offline")
        return months[bucketKey].orEmpty().toList()
    }

    override suspend fun bySha1(hexes: List<String>): Map<String, String> {
        bulkCalls += hexes
        return hexes.mapNotNull { h -> known[h]?.let { h to it } }.toMap()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImmichMatcherTest {
    private val db = MediaDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.media()
    private val store = FakeMediaStore()
    private val indexer = MediaIndexer(dao, store, { MediaAccess.Full }, CoroutineScope(Dispatchers.Unconfined), { ZoneId.of("UTC") })
    private val remote = FakeRemote()
    private var now = 1_000_000L
    private val matcher = ImmichMatcher(db.matches(), dao, remote, now = { now }, negativeTtlMs = 10_000)

    private val month = "2026-10-01T00:00:00.000Z"
    private val t1 = utc(2026, 10, 3, 10)
    private val t2 = utc(2026, 10, 4, 10)
    private fun sha(n: Int) = "%040x".format(n)

    @Before fun fill() {
        store.put(photo(1, taken = t1))
        store.put(photo(2, taken = t2))
        runBlocking { indexer.sync() }
        remote.months[month] = mutableListOf(
            RemoteCandidate("r1", "IMG_1.jpg", t1 + 1_000, 2_000_000, sha(1)),
            RemoteCandidate("r2", "img_2.JPG", t2, null, sha(2)),
            RemoteCandidate("r3", "other.jpg", t2, null, sha(3)),
        )
    }

    @After fun close() = db.close()

    private fun match(remoteIds: Set<String> = setOf("r1", "r2", "r3")) = runBlocking { matcher.matchMonth(month, listOf(1, 2), remoteIds) }

    @Test fun `the heuristic pairs name, time within 2 s and size, marked tentative`() {
        val m = match()
        assertEquals(Match("r1", tentative = true), m[1])
        assertEquals(Match("r2", tentative = true), m[2])
    }

    @Test fun `the heuristic needs the same size and a close time`() {
        val l = LocalCandidate(1, 1, "a.jpg", 10_000, 100, null)
        assertTrue(Matching.heuristic(l, RemoteCandidate("r", "A.JPG", 12_000, 100, null)))
        assertFalse(Matching.heuristic(l, RemoteCandidate("r", "a.jpg", 12_001, 100, null)))
        assertFalse(Matching.heuristic(l, RemoteCandidate("r", "a.jpg", 10_000, 101, null)))
        assertFalse(Matching.heuristic(l, RemoteCandidate("r", "b.jpg", 10_000, 100, null)))
        assertTrue(Matching.heuristic(l, RemoteCandidate("r", "a.jpg", 10_000, null, null)))
    }

    @Test fun `a hash confirms a tentative match, or refutes it`() {
        match()
        // Item 1 hashes to r1's checksum; item 2 to something Immich doesn't have.
        runBlocking { dao.setSha1(1, sha(1), dao.byId(1)!!.generation); dao.setSha1(2, sha(9), dao.byId(2)!!.generation) }
        val m = match()
        assertEquals(Match("r1", tentative = false), m[1])
        assertNull(m[2])
    }

    @Test fun `a hash wins over a look-alike`() {
        // r3 has item 2's bytes although r2 has its name and time.
        runBlocking { dao.setSha1(2, sha(3), dao.byId(2)!!.generation) }
        assertEquals(Match("r3", tentative = false), match()[2])
    }

    @Test fun `answers are cached, and the month isn't fetched again`() {
        match()
        assertEquals(1, remote.monthCalls)
        val again = ImmichMatcher(db.matches(), dao, remote, now = { now })
        val m = runBlocking { again.matchMonth(month, listOf(1, 2), setOf("r1", "r2", "r3")) }
        assertEquals("r1", m[1]?.remoteId)
        assertEquals(1, remote.monthCalls) // answered from Room
    }

    @Test fun `an edit on the phone invalidates its answer`() {
        runBlocking { dao.setSha1(1, sha(1), dao.byId(1)!!.generation) }
        match()
        store.update(1) { it.copy(width = 10) } // edited: new generation, hash dropped
        runBlocking { indexer.sync() }
        val m = runBlocking { ImmichMatcher(db.matches(), dao, remote, now = { now }).matchMonth(month, listOf(1, 2), setOf("r1", "r2", "r3")) }
        assertEquals(Match("r1", tentative = true), m[1]) // re-evaluated: no hash yet, so a guess again
        assertEquals(2, remote.monthCalls)
    }

    @Test fun `an Immich asset that went away is re-checked`() {
        runBlocking { dao.setSha1(1, sha(1), dao.byId(1)!!.generation) }
        match()
        remote.months[month]!!.removeAt(0)
        matcher.refresh()
        val m = match(remoteIds = setOf("r2", "r3"))
        assertNull(m[1])
        runBlocking { matcher.forgetRemote(listOf("r2")) }
        assertNull(runBlocking { matcher.localOf("r2") })
    }

    @Test fun `no-match answers are re-asked after their time to live`() {
        runBlocking { dao.setSha1(1, sha(7), dao.byId(1)!!.generation) }
        assertNull(match()[1])
        remote.months[month]!!.add(RemoteCandidate("r7", "IMG_1.jpg", t1, null, sha(7)))
        matcher.refresh()
        assertNull(match(setOf("r1", "r2", "r3", "r7"))[1]) // still within the TTL
        now += 20_000
        assertEquals(Match("r7", false), match(setOf("r1", "r2", "r3", "r7"))[1])
    }

    @Test fun `hashed items are confirmed through bulk-upload-check`() {
        runBlocking { dao.setSha1(1, sha(1), dao.byId(1)!!.generation); dao.setSha1(2, sha(5), dao.byId(2)!!.generation) }
        remote.known[sha(1)] = "r1"
        runBlocking { matcher.onHashed(listOf(1, 2)) }
        assertEquals(listOf(listOf(sha(1), sha(5))), remote.bulkCalls)
        assertEquals(Match("r1", false), runBlocking { matcher.remoteOf(1) })
        assertNull(runBlocking { matcher.remoteOf(2) })
        assertEquals(1L, runBlocking { matcher.localOf("r1") })
        // The month needn't be fetched now: both answers are confirmed.
        match()
        assertEquals(0, remote.monthCalls)
    }

    @Test fun `cached phone copies say whether they're a guess, and drop answers for an edited item`() {
        match()
        assertEquals(mapOf("r1" to LocalMatch(1, tentative = true), "r2" to LocalMatch(2, tentative = true)), runBlocking { matcher.cachedLocalMatches(listOf("r1", "r2")) })
        runBlocking { dao.setSha1(1, sha(1), dao.byId(1)!!.generation) }
        remote.known[sha(1)] = "r1"
        runBlocking { matcher.onHashed(listOf(1)) }
        assertEquals(LocalMatch(1, tentative = false), runBlocking { matcher.cachedLocalMatches(listOf("r1")) }["r1"])
        // The phone item changed (new generation): its old answer no longer names it.
        store.put(photo(1, taken = t1)) // bumps its generation
        runBlocking { indexer.sync() }
        assertTrue(runBlocking { matcher.cachedLocalMatches(listOf("r1")) }.isEmpty())
        assertTrue(runBlocking { matcher.cachedLocal(listOf("r1")) }.isEmpty())
    }

    @Test fun `offline gives no answers and caches nothing`() {
        remote.offline = true
        assertTrue(match().isEmpty())
        remote.offline = false
        assertEquals(2, match().size)
    }

    @Test fun `checksums convert from Immich's base64`() {
        // SHA-1("abc") = a9993e36…, base64 qZk+NkcGgWq6PiVxeFDCbJzQ2J0=
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Checksums.base64ToHex("qZk+NkcGgWq6PiVxeFDCbJzQ2J0="))
        assertNull(Checksums.base64ToHex("abc"))
    }
}

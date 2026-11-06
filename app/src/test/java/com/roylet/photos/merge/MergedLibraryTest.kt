package com.roylet.photos.merge

import com.roylet.photos.FakeLibrary
import com.roylet.photos.domain.library.Album
import com.roylet.photos.domain.library.AssetDetails
import com.roylet.photos.domain.library.AssetLocation
import com.roylet.photos.domain.library.CameraInfo
import com.roylet.photos.domain.library.ConsentDeniedException
import com.roylet.photos.domain.library.DeleteTarget
import com.roylet.photos.domain.library.EmptyLocalLibrary
import com.roylet.photos.domain.library.TrashReceipt
import com.roylet.photos.domain.source.Capability
import com.roylet.photos.domain.source.MediaSourceKind
import com.roylet.photos.domain.timeline.TimelineAsset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergedLibraryTest {
    /** The phone side, recording calls. */
    private class Phone : EmptyLocalLibrary() {
        val calls = mutableListOf<String>()
        var denyFavorite = false
        override suspend fun setFavorite(ids: List<String>, favorite: Boolean) { if (denyFavorite) throw ConsentDeniedException(); calls += "fav ${ids.joinToString(",")} $favorite" }
        override suspend fun trash(ids: List<String>) { calls += "trash ${ids.joinToString(",")}" }
        override suspend fun restore(ids: List<String>) { calls += "restore ${ids.joinToString(",")}" }
        override suspend fun albums(): List<Album> = listOf(Album("folder:1", "Camera", assetCount = 3))
        override suspend fun basicSearch(query: String): List<TimelineAsset> =
            listOf(TimelineAsset("local:1", 1f, true, false), TimelineAsset("local:5", 1f, true, false))
        override suspend fun details(id: String) = AssetDetails(id, MediaSourceKind.Local, fileName = "IMG_1.jpg",
            camera = CameraInfo(make = "Google"), location = AssetLocation(latitude = 1.0, longitude = 2.0))
    }

    /** local:1 ↔ a, local:2 ↔ b. */
    private val pairs = object : MonthMatcher {
        val map = mapOf(1L to "a", 2L to "b")
        val forgotten = mutableListOf<String>()
        override suspend fun matchMonth(bucketKey: String, localIds: List<Long>, remoteIds: Set<String>) = emptyMap<Long, Match>()
        override suspend fun cachedRemote(localIds: List<Long>): Map<Long, Match> = localIds.mapNotNull { l -> map[l]?.let { l to Match(it, false) } }.toMap()
        override suspend fun cachedLocal(remoteIds: List<String>): Map<String, Long> = map.entries.filter { it.value in remoteIds }.associate { it.value to it.key }
        override suspend fun cachedLocalMatches(remoteIds: List<String>) = cachedLocal(remoteIds).mapValues { LocalMatch(it.value, tentative = false) }
        override suspend fun pairsPerMonth(): Map<String, Int> = emptyMap()
        override suspend fun forgetRemote(remoteIds: Collection<String>) { forgotten += remoteIds }
    }

    /** local:1 ↔ a guessed (file name + time), local:2 ↔ b confirmed. */
    private val guesses = object : MonthMatcher {
        override suspend fun matchMonth(bucketKey: String, localIds: List<Long>, remoteIds: Set<String>) = emptyMap<Long, Match>()
        override suspend fun cachedRemote(localIds: List<Long>): Map<Long, Match> =
            mapOf(1L to Match("a", tentative = true), 2L to Match("b", tentative = false)).filterKeys { it in localIds }
        override suspend fun cachedLocal(remoteIds: List<String>): Map<String, Long> = mapOf("a" to 1L, "b" to 2L).filterKeys { it in remoteIds }
        override suspend fun cachedLocalMatches(remoteIds: List<String>) =
            mapOf("a" to LocalMatch(1L, tentative = true), "b" to LocalMatch(2L, tentative = false)).filterKeys { it in remoteIds }
        override suspend fun pairsPerMonth(): Map<String, Int> = emptyMap()
    }

    @Test fun `a guessed pair never deletes the other copy`() {
        val l = MergedLibrary(immich, phone) { guesses }
        // Only the confirmed pair is offered the choice.
        assertEquals(setOf("local:2"), runBlocking { l.pairedIds(listOf("local:1", "local:2")) })
        // Both: the guessed item loses only its own (phone) copy; the confirmed pair loses both.
        val both = runBlocking { l.trash(listOf("local:1", "local:2"), DeleteTarget.Both) }
        assertEquals(listOf("local:1", "local:2"), both.local)
        assertEquals(listOf("b"), both.remote)
        assertTrue(both.partial)
        assertEquals(listOf("trash b"), immich.calls.filter { it.startsWith("trash") })
    }

    @Test fun `a guessed pair under the other side's choice isn't touched`() {
        val l = MergedLibrary(immich, phone) { guesses }
        // "Immich" for a phone item whose Immich copy is only a guess: the phone file stays, and so does "a".
        val r = runBlocking { l.trash(listOf("local:1"), DeleteTarget.Immich) }
        assertTrue(r.local.isEmpty() && r.remote.isEmpty())
        assertTrue(phone.calls.isEmpty())
        assertTrue(immich.calls.none { it.startsWith("trash") })
        // "This device" for the Immich asset of a guessed pair (favourites view): its phone guess isn't deleted.
        val d = runBlocking { l.trash(listOf("a"), DeleteTarget.Device) }
        assertTrue(d.local.isEmpty() && d.remote.isEmpty())
        assertTrue(phone.calls.isEmpty())
    }

    private val phone = Phone()
    private val immich = FakeLibrary()
    private val lib = MergedLibrary(immich, phone) { pairs }

    @Test fun `capabilities are both sources together`() {
        assertTrue(Capability.FOLDERS in lib.capabilities)
        assertTrue(Capability.SMART_SEARCH in lib.capabilities)
    }

    @Test fun `favourite applies to both copies`() {
        runBlocking { lib.setFavorite(listOf("local:1", "b", "local:3"), true) }
        assertEquals(listOf("fav local:1,local:3,local:2 true"), phone.calls)
        assertEquals(listOf("setFavorite b,a true"), immich.calls)
    }

    @Test fun `a declined phone favourite leaves Immich alone`() {
        phone.denyFavorite = true
        assertTrue(runCatching { runBlocking { lib.setFavorite(listOf("local:1"), true) } }.isFailure)
        assertTrue(immich.calls.isEmpty())
    }

    @Test fun `delete routes paired items by the choice and one-sided items to their side`() {
        val l = listOf("local:1", "local:3")
        val r = listOf("b", "z")
        val ofL = mapOf("local:1" to "a")
        val ofR = mapOf("b" to "local:2")
        assertEquals(TrashReceipt(listOf("local:1", "local:3", "local:2"), listOf("z"), partial = true), MergedLibrary.plan(l, r, ofL, ofR, DeleteTarget.Device))
        assertEquals(TrashReceipt(listOf("local:3"), listOf("a", "b", "z"), partial = true), MergedLibrary.plan(l, r, ofL, ofR, DeleteTarget.Immich))
        assertEquals(TrashReceipt(listOf("local:1", "local:3", "local:2"), listOf("a", "b", "z")), MergedLibrary.plan(l, r, ofL, ofR, DeleteTarget.Both))
        // Nothing paired: no choice made a difference.
        assertFalse(MergedLibrary.plan(listOf("local:3"), listOf("z"), emptyMap(), emptyMap(), DeleteTarget.Device).partial)
    }

    @Test fun `delete from both trashes each side, and undo restores both`() {
        val receipt = runBlocking { lib.trash(listOf("local:1", "z"), DeleteTarget.Both) }
        assertEquals(listOf("trash local:1"), phone.calls)
        assertEquals(listOf("trash a,z"), immich.calls)
        assertEquals(listOf("a", "z"), pairs.forgotten)
        runBlocking { lib.restore(receipt) }
        assertEquals("restore local:1", phone.calls.last())
        assertEquals("restore a,z", immich.calls.last())
    }

    @Test fun `if Immich's half fails the phone half is put back`() {
        immich.fail = java.io.IOException("offline")
        assertTrue(runCatching { runBlocking { lib.trash(listOf("local:1"), DeleteTarget.Both) } }.isFailure)
        assertEquals(listOf("trash local:1", "restore local:1"), phone.calls)
    }

    @Test fun `pairedIds finds items on both sides`() {
        assertEquals(setOf("local:1", "b"), runBlocking { lib.pairedIds(listOf("local:1", "local:3", "b", "z")) })
    }

    @Test fun `albums are Immich's then the phone's folders`() {
        val albums = runBlocking { lib.albums() }
        assertEquals("folder:1", albums.last().id)
        assertEquals(immich.albums.size + 1, albums.size)
    }

    @Test fun `server features use the Immich copy and skip phone-only items`() {
        runBlocking { lib.addToAlbum("al-1", listOf("local:1", "local:3", "z")) }
        assertTrue(immich.calls.any { it.contains("z") && it.contains("a") && !it.contains("local") })
    }

    @Test fun `search adds phone file names that aren't already in the results`() {
        val res = runBlocking { lib.smartSearch("beach") }
        assertTrue(res.any { it.id == "local:5" })
        assertTrue(res.any { it.id == "local:1" }) // its copy "a" isn't among the fake results
    }

    /** Immich side for search: answers [server] (or throws [serverFails]). */
    private fun searchLib(server: List<TimelineAsset>, serverFails: Throwable? = null, phoneSide: EmptyLocalLibrary = phone) =
        MergedLibrary(
            object : com.roylet.photos.domain.library.LibrarySource by immich {
                override suspend fun smartSearch(query: String): List<TimelineAsset> = serverFails?.let { throw it } ?: server
                override suspend fun basicSearch(query: String): List<TimelineAsset> = serverFails?.let { throw it } ?: server
            },
            phoneSide,
        ) { pairs }

    private fun asset(id: String) = TimelineAsset(id, 1f, true, false)

    @Test fun `merged search shows a pair once, as the Immich result with both copies`() {
        // "a" is local:1's Immich copy; the phone also matched local:1 and local:5. Immich repeats "z".
        val res = runBlocking { searchLib(listOf(asset("a"), asset("z"), asset("z"))).smartSearch("beach") }
        assertEquals(listOf("a", "z", "local:5"), res.map { it.id })
        assertEquals(com.roylet.photos.domain.source.SourceBadge.Both, res[0].sourceBadge)
        assertEquals("local:1", res[0].localRef?.id)
        assertEquals(res.size, res.map { it.id }.toSet().size)
    }

    @Test fun `merged search keeps the phone's results when Immich fails`() {
        val res = runBlocking { searchLib(emptyList(), serverFails = java.io.IOException("offline")).smartSearch("beach") }
        assertEquals(listOf("local:1", "local:5"), res.map { it.id })
        val basic = runBlocking { searchLib(emptyList(), serverFails = java.io.IOException("offline")).basicSearch("beach") }
        assertEquals(listOf("local:1", "local:5"), basic.map { it.id })
    }

    @Test fun `merged search keeps Immich's results when the phone fails`() {
        val broken = object : EmptyLocalLibrary() {
            override suspend fun basicSearch(query: String): List<TimelineAsset> = throw IllegalStateException("db closed")
        }
        assertEquals(listOf("a"), runBlocking { searchLib(listOf(asset("a")), phoneSide = broken).smartSearch("x") }.map { it.id })
    }

    @Test fun `merged search fails only when there's nothing to show`() {
        val e = runCatching {
            runBlocking { searchLib(emptyList(), serverFails = java.io.IOException("offline"), phoneSide = object : EmptyLocalLibrary() {}).smartSearch("x") }
        }.exceptionOrNull()
        assertEquals("offline", e?.message)
    }

    @Test fun `details prefer the phone's EXIF and add Immich's people and place`() {
        val server = AssetDetails("a", MediaSourceKind.Immich, camera = CameraInfo(make = "Sony"),
            location = AssetLocation(city = "Hobart", country = "Australia", latitude = 9.0, longitude = 9.0), people = listOf("Sam"), description = "Dinner")
        val phoneD = AssetDetails("local:1", MediaSourceKind.Local, camera = CameraInfo(make = "Google"), location = AssetLocation(latitude = 1.0, longitude = 2.0))
        val d = MergedLibrary.combineDetails(phoneD, server)!!
        assertEquals("Google", d.camera?.make)
        assertEquals(1.0, d.location?.latitude)
        assertEquals("Hobart", d.location?.city)
        assertEquals(listOf("Sam"), d.people)
        assertEquals("Dinner", d.description)
        assertEquals(MediaSourceKind.Local, d.source)
    }

    @Test fun `sharing uses the phone file when there is one`() {
        val phoneShare = object : EmptyLocalLibrary() {
            override suspend fun shareUri(id: String) = com.roylet.photos.domain.library.SharedUri("content://x/$id", "image/jpeg")
        }
        val l = MergedLibrary(immich, phoneShare) { pairs }
        assertEquals("content://x/local:1", runBlocking { l.shareUri("a") }?.uri)
        assertEquals(null, runBlocking { l.shareUri("z") })
    }
}

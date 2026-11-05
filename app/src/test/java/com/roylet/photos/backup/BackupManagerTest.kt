package com.roylet.photos.backup

import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.FakeLibrary
import com.roylet.photos.domain.library.DeleteTarget
import com.roylet.photos.domain.library.EmptyLocalLibrary
import com.roylet.photos.immich.UploadResult
import com.roylet.photos.immich.UploadSpec
import com.roylet.photos.local.FakeMediaStore
import com.roylet.photos.local.FakeMediaStore.Companion.photo
import com.roylet.photos.local.FakeMediaStore.Companion.utc
import com.roylet.photos.local.FolderRow
import com.roylet.photos.local.MatchEntity
import com.roylet.photos.local.MediaDatabase
import com.roylet.photos.local.MediaEntity
import com.roylet.photos.local.MediaIndexer
import com.roylet.photos.local.PhotosDatabase
import com.roylet.photos.local.Sha1Hasher
import com.roylet.photos.merge.LocalMatch
import com.roylet.photos.merge.Match
import com.roylet.photos.merge.MergedLibrary
import com.roylet.photos.merge.MonthMatcher
import com.roylet.photos.ui.permissions.MediaAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.time.ZoneId

/** The public API (folders, options, state, scheduling) and how backup respects deletions made in the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupManagerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val index = MediaDatabase.inMemory(context)
    private val appDb = PhotosDatabase.inMemory(context)
    private val store = FakeMediaStore()
    private val indexer = MediaIndexer(index.media(), store, { MediaAccess.Full }, CoroutineScope(Dispatchers.Unconfined), { ZoneId.of("UTC") })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class FakeScheduler : BackupScheduler {
        val calls = mutableListOf<String>()
        var last: BackupOptions? = null
        override fun schedule(options: BackupOptions) { calls += "schedule"; last = options }
        override fun runNow(options: BackupOptions) { calls += "runNow"; last = options }
        override fun stopRunning(options: BackupOptions) { calls += "stop" }
        override fun cancelAll() { calls += "cancelAll" }
    }

    private val scheduler = FakeScheduler()
    private val unmetered = MutableStateFlow(true)
    private val charging = MutableStateFlow(false)
    private val server = MutableStateFlow(true)
    private val prefs = BackupPrefs(null)
    private val manager = BackupManager(
        appDb.backup(), index.backup(), index.media(), prefs, scheduler,
        object : BackupConditions { override val unmetered = this@BackupManagerTest.unmetered; override val charging = this@BackupManagerTest.charging },
        server, scope, now = { 5_000L },
    )

    /** A fake Immich: SHA-1 → asset id. */
    private val immichHas = HashMap<String, String>()
    private val uploaded = mutableListOf<String>()
    private val remote = object : BackupRemote {
        override suspend fun check(hexes: List<String>) = hexes.mapNotNull { h -> immichHas[h]?.let { h to RemoteCopy(it, false) } }.toMap()
        override suspend fun upload(spec: UploadSpec, onProgress: (Long) -> Unit): UploadResult {
            spec.open().use { it.readBytes() }
            uploaded += spec.filename
            immichHas[spec.checksum] = "new-" + spec.filename
            return UploadResult("new-" + spec.filename, "created")
        }
    }

    private fun bytes(id: Long) = "bytes $id".toByteArray()
    private fun sha(id: Long) = Sha1Hasher.sha1Hex(ByteArrayInputStream(bytes(id)))
    private val engine = BackupEngine(
        index.media(), index.backup(), index.matches(), appDb.backup(), { remote },
        open = { e: MediaEntity -> ByteArrayInputStream(bytes(e.id)) },
        fileInfo = { e -> LocalFile(bytes(e.id).size.toLong(), 1L) },
    )

    @Before fun fill() {
        store.put(photo(1, taken = utc(2026, 10, 3)))
        store.put(photo(2, taken = utc(2026, 10, 2)))
        store.put(photo(3, taken = utc(2026, 10, 1)))
        store.put(photo(10, bucket = 2, bucketName = "Screenshots").copy(relativePath = "Pictures/Screenshots/"))
        runBlocking { indexer.sync() }
    }

    @After fun close() {
        scope.cancel()
        index.close()
        appDb.close()
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }
    private fun runBackup() = runBlocking { engine.run(manager.selection()) }

    @Test fun `defaults are off, Wi-Fi only, videos on, everything, and Camera suggested`() {
        val o = manager.options.value
        assertFalse(o.enabled)
        assertTrue(o.wifiOnly)
        assertFalse(o.whileChargingOnly)
        assertTrue(o.includeVideos)
        assertEquals(null, o.newerThan)
        assertEquals(listOf(1L), runBlocking { manager.selectedBuckets() })
        assertTrue(await { manager.isFolderEnabled(1).first() })
        assertFalse(await { manager.isFolderEnabled(2).first() })
        assertTrue(BackupManager.isCamera(FolderRow(1, "Camera", "DCIM/Camera/", 1, 1, 1)))
        assertFalse(BackupManager.isCamera(FolderRow(1, "Camera", "Pictures/Camera/", 1, 1, 1)))
    }

    @Test fun `folder toggles persist and starting one kicks a run`() {
        manager.setEnabled(true)
        scheduler.calls.clear()
        manager.setFolderEnabled(2, true)
        assertTrue(await { manager.isFolderEnabled(2).first { it } })
        manager.setFolderEnabled(1, false)
        assertTrue(await { !manager.isFolderEnabled(1).first { !it } })
        assertEquals(listOf(2L), runBlocking { manager.selectedBuckets() })
        assertTrue("runNow" in scheduler.calls)
    }

    @Test fun `folder status counts backed-up, pending and failed items`() {
        val gen = runBlocking { index.media().byId(1) }!!.generation
        runBlocking {
            index.matches().upsert(listOf(MatchEntity(1, gen, "r1", tentative = false, checkedAt = 1)))
            appDb.backup().putFailure(BackupFailureEntity(3, 0, "IMG_3.jpg", 1, "error 415", 415, 1))
        }
        val s = await { manager.folderStatus(1).first { it.eligible == 3 && it.failed == 1 } }
        assertEquals(FolderBackupStatus(enabled = true, eligible = 3, backedUp = 1, pending = 1, failed = 1, excluded = 0), s)
        assertEquals(1, await { manager.folders.first { it != null } }!!.first { it.bucketId == 2L }.count)
    }

    @Test fun `the switches drive the jobs`() {
        manager.setEnabled(true)
        assertEquals(listOf("schedule", "runNow"), scheduler.calls)
        manager.setWhileChargingOnly(true)
        assertTrue(scheduler.last!!.whileChargingOnly)
        assertTrue(scheduler.last!!.constraints().requiresCharging())
        scheduler.calls.clear()
        manager.pause()
        assertEquals(listOf("cancelAll"), scheduler.calls)
        assertTrue(manager.options.value.paused)
        assertFalse(manager.shouldRun())
        scheduler.calls.clear()
        manager.resume()
        assertEquals(listOf("schedule", "runNow"), scheduler.calls)
        // Signed out: nothing runs.
        server.value = false
        scheduler.calls.clear()
        manager.onServerChanged(false)
        assertEquals(listOf("cancelAll"), scheduler.calls)
        assertFalse(manager.shouldRun())
        // "From now on" stores the moment it was chosen.
        manager.setFromNowOn(true)
        assertEquals(5_000L, manager.options.value.newerThan)
    }

    @Test fun `an auth failure pauses backup until the next sign-in`() {
        manager.setEnabled(true)
        manager.reportFinished(BackupRun.AuthFailed("Immich rejected the API key"))
        assertFalse(manager.shouldRun())
        assertEquals(BackupState.Error("Immich rejected the API key", signIn = true), await { manager.state.first { it is BackupState.Error } })
        manager.onServerChanged(true)
        assertTrue(manager.shouldRun())
    }

    @Test fun `state shows what backup is doing or waiting for`() {
        val on = BackupOptions(enabled = true)
        fun st(o: BackupOptions = on, server: Boolean = true, auth: String? = null, run: RunState = RunState.NotRunning, pending: Int = 4, wifi: Boolean = true, power: Boolean = false) =
            BackupManager.stateOf(o, server, auth, run, pending, wifi, power)
        assertEquals(BackupState.Off, st(o = BackupOptions()))
        assertEquals(BackupState.NoServer, st(server = false))
        assertEquals(BackupState.Paused, st(o = on.copy(paused = true)))
        assertEquals(BackupState.WaitingForWifi(4), st(wifi = false))
        assertEquals(BackupState.Idle(0), st(wifi = false, pending = 0))
        assertEquals(BackupState.WaitingForCharging(4), st(o = on.copy(whileChargingOnly = true)))
        assertEquals(BackupState.Idle(4), st(o = on.copy(wifiOnly = false), wifi = false))
        assertEquals(BackupState.Scanning, st(run = RunState.Running(null)))
        assertEquals(BackupState.Uploading(3, 120, "IMG_3.jpg", 0.5f, 3), st(run = RunState.Running(BackupProgress.Uploading(3, 120, 3, "IMG_3.jpg", 0.5f))))
        assertEquals(BackupState.Error("Server error HTTP 503"), st(run = RunState.Failed("Server error HTTP 503")))
        assertEquals(BackupState.Error("bad key", signIn = true), st(auth = "bad key"))
        // Live: Wi-Fi drops while items are pending.
        manager.setEnabled(true)
        unmetered.value = false
        assertEquals(BackupState.WaitingForWifi(3), await { manager.state.first { it is BackupState.WaitingForWifi } })
    }

    /* ---------------- Deletions made in the app (the delete sheet's "Immich" / "Both") ---------------- */

    /** local:1 ↔ a, local:2 ↔ b, confirmed. */
    private val pairs = object : MonthMatcher {
        val map = mapOf(1L to "a", 2L to "b")
        override suspend fun matchMonth(bucketKey: String, localIds: List<Long>, remoteIds: Set<String>) = emptyMap<Long, Match>()
        override suspend fun cachedRemote(localIds: List<Long>): Map<Long, Match> = localIds.mapNotNull { l -> map[l]?.let { l to Match(it, false) } }.toMap()
        override suspend fun cachedLocal(remoteIds: List<String>): Map<String, Long> = map.entries.filter { it.value in remoteIds }.associate { it.value to it.key }
        override suspend fun cachedLocalMatches(remoteIds: List<String>) = cachedLocal(remoteIds).mapValues { LocalMatch(it.value, tentative = false) }
        override suspend fun pairsPerMonth(): Map<String, Int> = emptyMap()
    }

    private val immichLib = FakeLibrary()
    private val phoneLib = object : EmptyLocalLibrary() {
        override suspend fun trash(ids: List<String>) {
            // What MediaStore and the index do: the item goes to the phone's Trash.
            ids.forEach { id -> store.update(id.removePrefix("local:").toLong()) { it.copy(isTrashed = true) } }
            indexer.sync()
        }
        override suspend fun restore(ids: List<String>) {}
    }
    private val merged = MergedLibrary(immichLib, phoneLib, remoteDeletions = manager) { pairs }

    @Test fun `deleting only the Immich copy means the phone copy is never uploaded again`() {
        val receipt = runBlocking { merged.trash(listOf("local:1"), DeleteTarget.Immich) }
        assertEquals(listOf("a"), receipt.remote)
        val ex = runBlocking { appDb.backup().exclusions() }.single()
        assertEquals(1L, ex.localId)
        assertEquals("a", ex.remoteId)
        assertEquals(BackupReasons.DELETED_FROM_IMMICH, ex.reason)
        // Even though Immich (after emptying its Trash) would accept the file again.
        runBackup()
        assertEquals(listOf("IMG_2.jpg", "IMG_3.jpg"), uploaded)
        assertEquals(1, await { manager.exclusions.first { it.isNotEmpty() } }.size)
        assertEquals(1, await { manager.folderStatus(1).first { it.excluded == 1 } }.excluded)
    }

    @Test fun `undoing the delete allows backup again`() {
        val receipt = runBlocking { merged.trash(listOf("local:1"), DeleteTarget.Immich) }
        runBlocking { merged.restore(receipt) }
        assertTrue(runBlocking { appDb.backup().exclusions() }.isEmpty())
        runBackup()
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg", "IMG_3.jpg"), uploaded)
    }

    @Test fun `restoring from Immich's Trash page also allows backup again`() {
        runBlocking { merged.trash(listOf("local:1"), DeleteTarget.Immich) }
        runBlocking { merged.restore(listOf("a")) }
        assertTrue(runBlocking { appDb.backup().exclusions() }.isEmpty())
    }

    @Test fun `allow backup again from the Backup screen`() {
        runBlocking { merged.trash(listOf("local:1"), DeleteTarget.Immich) }
        manager.allowBackupAgain(1)
        await { manager.exclusions.first { it.isEmpty() } }
        runBackup()
        assertTrue("IMG_1.jpg" in uploaded)
    }

    @Test fun `deleting both copies leaves nothing to upload`() {
        runBlocking { merged.trash(listOf("local:1"), DeleteTarget.Both) }
        assertTrue(runBlocking { appDb.backup().exclusions() }.isEmpty())
        runBackup()
        assertFalse("IMG_1.jpg" in uploaded)
        // And a phone-only delete leaves Immich's copy, with nothing to exclude.
        runBlocking { merged.trash(listOf("local:2"), DeleteTarget.Device) }
        assertTrue(runBlocking { appDb.backup().exclusions() }.isEmpty())
    }

    @Test fun `the delete sheet's warning knows which items are in backed-up folders`() {
        assertFalse(runBlocking { manager.inBackedUpFolder(listOf(1L)) }) // backup is off
        manager.setEnabled(true)
        assertTrue(runBlocking { manager.inBackedUpFolder(listOf(1L)) })
        assertFalse(runBlocking { manager.inBackedUpFolder(listOf(10L)) })
        assertNotNull(prefs.installId)
    }
}

package com.roylet.photos.backup

import com.roylet.photos.local.FolderRow
import com.roylet.photos.local.MediaDao
import com.roylet.photos.local.SQL_VARS
import com.roylet.photos.domain.library.RemoteDeletionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** When backup may run; the WorkManager side ([WorkBackupScheduler]) or a fake in tests. */
interface BackupScheduler {
    /** Keeps the periodic job (about hourly) and the new-media trigger in step with [options]. */
    fun schedule(options: BackupOptions)
    /** A one-off run as soon as the constraints allow ("Back up now", new photos in a selected folder). */
    fun runNow(options: BackupOptions)
    /** Stops the run in progress (its progress is kept); the periodic job stays. */
    fun stopRunning(options: BackupOptions)
    /** Off, paused or signed out: nothing runs. */
    fun cancelAll()
}

/** Whether the phone is on an unmetered network and charging, for the "waiting for" states. */
interface BackupConditions {
    val unmetered: Flow<Boolean>
    val charging: Flow<Boolean>
}

/** The live part of a run, reported by the worker. */
internal sealed interface RunState {
    data object NotRunning : RunState
    data class Running(val progress: BackupProgress?) : RunState
    data class Failed(val message: String) : RunState
}

/**
 * Backup's public API (`container.backup`, docs/SOURCES.md §10): the switches, per-folder choices
 * and counts, the state, and the runs. Folder cards and screens use [setFolderEnabled],
 * [isFolderEnabled] and [folderStatus]; the Backup screen uses the rest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupManager(
    private val store: BackupDao,
    private val queue: BackupMediaDao,
    private val media: MediaDao,
    private val prefs: BackupPrefs,
    private val scheduler: BackupScheduler,
    conditions: BackupConditions,
    /** True while an Immich server is configured. */
    private val server: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : RemoteDeletionListener {
    val options: StateFlow<BackupOptions> = prefs.options

    /** Held by the worker for a whole run: two runs never upload side by side. */
    val runLock = kotlinx.coroutines.sync.Mutex()

    private val run = MutableStateFlow<RunState>(RunState.NotRunning)
    private val lastRunAt = MutableStateFlow<Long?>(null)

    private val _uploadingId = MutableStateFlow<Long?>(null)
    /** MediaStore id of the item uploading right now (the grid's "uploading" mark), or null. */
    val uploadingId: StateFlow<Long?> = _uploadingId.asStateFlow()

    /* ---------------- Folders ---------------- */

    private val choices: Flow<Map<Long, Boolean>> = store.foldersFlow().map { rows -> rows.associate { it.bucketId to it.enabled } }

    /** Every phone folder with its cover, counts and toggle, newest first. */
    val folders: StateFlow<List<BackupFolder>?> = combine(
        queue.foldersFlow(),
        choices,
        options.map { it.includeVideos to (it.newerThan ?: 0L) }.distinctUntilChanged().flatMapLatest { (v, s) -> queue.countsFlow(v, s) },
        store.failuresFlow(),
        store.exclusionsFlow(),
    ) { folders, chosen, counts, failures, exclusions ->
        val byBucket = counts.associateBy { it.bucketId }
        val failedIn = failures.groupingBy { it.bucketId }.eachCount()
        val excludedIn = exclusions.groupingBy { it.bucketId }.eachCount()
        folders.map { f ->
            val c = byBucket[f.bucketId]
            val total = c?.total ?: 0
            val done = c?.backedUp ?: 0
            val failed = failedIn[f.bucketId] ?: 0
            val excluded = excludedIn[f.bucketId] ?: 0
            BackupFolder(
                bucketId = f.bucketId,
                name = f.bucketName ?: f.relativePath?.trimEnd('/')?.substringAfterLast('/') ?: "Folder",
                relativePath = f.relativePath,
                count = f.count,
                coverId = f.coverId,
                status = FolderBackupStatus(
                    enabled = chosen[f.bucketId] ?: isCamera(f),
                    eligible = total, backedUp = done, failed = failed, excluded = excluded,
                    pending = (total - done - failed - excluded).coerceAtLeast(0),
                ),
            )
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Turns backup on or off for one folder (the folder cards' toggle). Turning one on starts a run. */
    fun setFolderEnabled(bucketId: Long, enabled: Boolean) {
        scope.launch {
            store.putFolder(BackupFolderEntity(bucketId, enabled, now()))
            if (enabled) kick()
        }
    }

    /** Whether [bucketId] is backed up (a stored choice, else the default: Camera on). */
    fun isFolderEnabled(bucketId: Long): Flow<Boolean> = folders.filterNotNull().map { list ->
        list.firstOrNull { it.bucketId == bucketId }?.status?.enabled ?: false
    }.distinctUntilChanged()

    /** One folder's counts and toggle. */
    fun folderStatus(bucketId: Long): Flow<FolderBackupStatus> = folders.filterNotNull().map { list ->
        list.firstOrNull { it.bucketId == bucketId }?.status ?: FolderBackupStatus(enabled = false)
    }.distinctUntilChanged()

    /** The folders backup covers right now: switched on, plus Camera folders the user never switched. */
    suspend fun selectedBuckets(): List<Long> {
        val chosen = store.folders().associate { it.bucketId to it.enabled }
        val all = media.folders()
        return all.filter { chosen[it.bucketId] ?: isCamera(it) }.map { it.bucketId }
    }

    /** Whether any of [mediaStoreIds] is in a folder that's backed up while backup is on (the delete sheet's warning). */
    suspend fun inBackedUpFolder(mediaStoreIds: List<Long>): Boolean {
        if (!options.value.enabled || mediaStoreIds.isEmpty()) return false
        val selected = selectedBuckets().toHashSet()
        return mediaStoreIds.chunked(SQL_VARS).any { part -> queue.bucketsOf(part).any { it.bucketId in selected } }
    }

    /* ---------------- Options ---------------- */

    /** The master switch. Turning it on suggests Camera (the default) and starts a run. */
    fun setEnabled(enabled: Boolean) = change { it.copy(enabled = enabled, paused = if (enabled) false else it.paused) }
    fun setWifiOnly(v: Boolean) = change { it.copy(wifiOnly = v) }
    fun setWhileChargingOnly(v: Boolean) = change { it.copy(whileChargingOnly = v) }
    fun setIncludeVideos(v: Boolean) = change { it.copy(includeVideos = v) }
    /** Everything (false), or only what's taken from now on (true). */
    fun setFromNowOn(fromNow: Boolean) = change { it.copy(newerThan = if (fromNow) now() else null) }

    private fun change(edit: (BackupOptions) -> BackupOptions) {
        val before = options.value
        prefs.update(edit)
        val after = options.value
        if (before == after) return
        reschedule()
        if (after.enabled && !after.paused && (!before.enabled || before.paused)) kick()
    }

    /* ---------------- Runs ---------------- */

    /** Back up now: a run as soon as the constraints allow (Wi-Fi, charging when asked). */
    fun backUpNow() {
        if (!options.value.enabled) return
        if (options.value.paused) prefs.update { it.copy(paused = false) }
        reschedule()
        kick()
    }

    /** Stops uploading until [resume]; what's done is kept. */
    fun pause() {
        prefs.update { it.copy(paused = true) }
        scheduler.cancelAll()
        run.value = RunState.NotRunning
        _uploadingId.value = null
    }

    fun resume() {
        prefs.update { it.copy(paused = false) }
        reschedule()
        kick()
    }

    /** Stops the run in progress (the notification's Cancel); the hourly job carries on later. */
    fun cancelRun() {
        scheduler.stopRunning(options.value)
        run.value = RunState.NotRunning
        _uploadingId.value = null
    }

    /** At app start: keeps the jobs in step with the switches. */
    fun start() = reschedule()

    /** The index changed (new photos, edits): back up what's new in the selected folders. */
    fun onIndexChanged() {
        if (canRun()) scheduler.runNow(options.value)
    }

    /** Signed in or out (or into another account). A new sign-in clears the "sign in again" pause. */
    fun onServerChanged(connected: Boolean) {
        if (connected) prefs.setAuthError(null)
        reschedule()
        if (connected) kick()
    }

    private fun canRun(): Boolean {
        val o = options.value
        return o.enabled && !o.paused && server.value && prefs.authError.value == null
    }

    private fun reschedule() {
        runCatching { if (canRun()) scheduler.schedule(options.value) else scheduler.cancelAll() }
    }

    private fun kick() { runCatching { if (canRun()) scheduler.runNow(options.value) } }

    /** Whether the worker should run at all. */
    fun shouldRun(): Boolean = canRun()

    /** The selection for a run now. */
    suspend fun selection(): BackupSelection = BackupSelection.of(selectedBuckets(), options.value)

    internal fun reportStarted() { run.value = RunState.Running(null) }

    internal fun report(progress: BackupProgress) {
        run.value = RunState.Running(progress)
        _uploadingId.value = (progress as? BackupProgress.Uploading)?.localId
    }

    /** A run ended; [BackupRun.AuthFailed] pauses backup until the next sign-in. */
    internal fun reportFinished(result: BackupRun) {
        _uploadingId.value = null
        lastRunAt.value = now()
        run.value = when (result) {
            is BackupRun.Retry -> RunState.Failed(result.message)
            is BackupRun.AuthFailed -> { prefs.setAuthError(result.message); scheduler.cancelAll(); RunState.NotRunning }
            else -> RunState.NotRunning
        }
    }

    /* ---------------- State ---------------- */

    private val pending: Flow<Int> = folders.map { list -> list?.filter { it.status.enabled }?.sumOf { it.status.pending } ?: 0 }.distinctUntilChanged()

    private val waits: Flow<Pair<Boolean, Boolean>> = combine(conditions.unmetered, conditions.charging) { u, c -> u to c }

    private val inputs = combine(options, server, prefs.authError, run, lastRunAt) { o, s, auth, r, last -> Inputs(o, s, auth, r, last) }

    /** Off / no server / idle / scanning / uploading n of m / waiting for Wi-Fi or charging / paused / error. */
    val state: StateFlow<BackupState> = combine(inputs, pending, waits) { i, p, (unmetered, charging) ->
        stateOf(i.options, i.server, i.auth, i.run, p, unmetered, charging, i.last)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), BackupState.Off)

    private data class Inputs(val options: BackupOptions, val server: Boolean, val auth: String?, val run: RunState, val last: Long?)

    /* ---------------- Failures and exclusions ---------------- */

    /** "Couldn't back up (n)", newest first. */
    val failures: Flow<List<BackupFailure>> = store.failuresFlow().map { rows -> rows.map { BackupFailure(it.localId, it.displayName, it.reason, it.failedAt) } }

    /** "Not backing up (n)": deleted from Immich by the user; never re-uploaded until allowed again. */
    val exclusions: Flow<List<BackupExclusion>> = store.exclusionsFlow().map { rows -> rows.map { BackupExclusion(it.localId, it.displayName, it.reason, it.excludedAt) } }

    /** "Allow backup again" for one excluded item. */
    fun allowBackupAgain(localId: Long) {
        scope.launch { store.removeExclusions(listOf(localId)); kick() }
    }

    /** Tries every failed item again on the next run. */
    fun retryFailed() {
        scope.launch { store.clearAllFailures(); kick() }
    }

    /** The user deleted these phone items' Immich copies and kept the phone files: never back them up again. */
    override suspend fun remoteCopiesDeleted(kept: Map<Long, String>) {
        if (kept.isEmpty()) return
        val rows = kept.keys.toList().chunked(SQL_VARS).flatMap { media.byIds(it) }.associateBy { it.id }
        val t = now()
        store.putExclusions(kept.map { (localId, remoteId) ->
            val e = rows[localId]
            BackupExclusionEntity(
                localId, e?.sha1?.takeIf { e.sha1Generation == e.generation }, remoteId, e?.displayName, e?.bucketId,
                BackupReasons.DELETED_FROM_IMMICH, t,
            )
        })
    }

    /** The Immich copies came back (undo, or restored from Immich's Trash): backup may cover them again. */
    override suspend fun remoteCopiesRestored(remoteIds: List<String>) {
        if (remoteIds.isEmpty()) return
        val removed = remoteIds.chunked(SQL_VARS).sumOf { store.removeExclusionsByRemote(it) }
        if (removed > 0) kick()
    }

    companion object {
        /** The default suggestion: the camera's own folder (DCIM/Camera). */
        fun isCamera(f: FolderRow): Boolean {
            val path = f.relativePath?.trimEnd('/')
            return if (path != null) path.equals("DCIM/Camera", ignoreCase = true) else f.bucketName.equals("Camera", ignoreCase = true)
        }

        internal fun stateOf(
            o: BackupOptions,
            server: Boolean,
            authError: String?,
            run: RunState,
            pending: Int,
            unmetered: Boolean,
            charging: Boolean,
            lastRunAt: Long? = null,
        ): BackupState = when {
            !o.enabled -> BackupState.Off
            !server -> BackupState.NoServer
            authError != null -> BackupState.Error(authError, signIn = true)
            o.paused -> BackupState.Paused
            run is RunState.Running -> when (val p = run.progress) {
                is BackupProgress.Uploading -> BackupState.Uploading(p.current, p.total, p.name, p.fraction, p.localId)
                else -> BackupState.Scanning
            }
            pending > 0 && o.wifiOnly && !unmetered -> BackupState.WaitingForWifi(pending)
            pending > 0 && o.whileChargingOnly && !charging -> BackupState.WaitingForCharging(pending)
            run is RunState.Failed && pending > 0 -> BackupState.Error(run.message)
            else -> BackupState.Idle(pending, lastRunAt)
        }
    }
}

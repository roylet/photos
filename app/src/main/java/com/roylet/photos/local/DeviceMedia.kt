package com.roylet.photos.local

import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.roylet.photos.ui.components.SnackbarController
import com.roylet.photos.ui.permissions.ConsentLauncher
import com.roylet.photos.ui.permissions.MediaAccess
import com.roylet.photos.ui.permissions.MediaPermissions
import kotlinx.coroutines.CoroutineScope
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The phone's photos, put together: the Room index, its sync (ContentObserver while the app is in
 * the foreground, and on every return to it), the timeline source and the library. Built once by
 * AppContainer; [start] hooks up the process lifecycle.
 */
class DeviceMedia(
    context: Context,
    private val scope: CoroutineScope,
    consent: ConsentLauncher,
    snackbar: SnackbarController,
    offeredManageMedia: () -> Boolean,
    markOfferedManageMedia: () -> Unit,
    /** After a sync changed the index: refresh the phone timelines on screen. */
    onChanged: () -> Unit,
    /** Whether phone items should be hashed (only worth it while an Immich server is connected, for matching). */
    private val wantHashes: () -> Boolean = { false },
) {
    private val app = context.applicationContext
    private val access: () -> MediaAccess = { MediaPermissions.access(app) }
    private val mediaStoreWrites = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** The app's own data (Android 10 trash, local edit recipes): strict migrations, never rebuilt. */
    val appDb: PhotosDatabase by lazy { PhotosDatabase.open(app) }
    val appDao: AppDao get() = appDb.app()

    /** The MediaStore index (a cache); a v1 index hands its Android 10 rows to [appDb] while migrating. */
    val db: MediaDatabase by lazy { MediaDatabase.open(app) { rows -> appDb.importLegacy(rows) } }
    val dao: MediaDao get() = db.media()

    val indexer: MediaIndexer by lazy {
        MediaIndexer(dao, AndroidMediaStoreReader(app), access, scope, onChanged = { onChanged(); scheduleHashing() }, legacyFavorites = { appDao.legacyFavorites() })
    }

    val timeline: MediaStoreTimelineSource by lazy { MediaStoreTimelineSource(dao, appDao, indexer, access, mediaStoreTrash = mediaStoreWrites) }

    private val offer = ManageMediaOffer(app, snackbar, scope, offeredManageMedia, markOfferedManageMedia)

    val library: MediaStoreLibrary by lazy {
        MediaStoreLibrary(
            dao = dao,
            appDao = appDao,
            indexer = indexer,
            mediaStoreWrites = mediaStoreWrites,
            runner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ConsentRequestRunner(app, consent, offer::maybeOffer) else MediaRequestRunner { false },
            legacy = if (mediaStoreWrites) null else AndroidLegacyFiles(app, consent),
            io = AndroidLocalIo(app),
        )
    }

    /** Called with the ids of each hashed batch (the Immich matcher picks them up). */
    var onHashed: suspend (List<Long>) -> Unit = {}

    /** Hashes phone items for matching; run by [Sha1Worker]. */
    val hasher: Sha1Hasher by lazy { Sha1Hasher(dao, { Sha1Worker.openOriginal(app, it) }, onBatch = { onHashed(it) }, busy = { com.roylet.photos.util.UserActivity.busy() }) }

    private val _matching = MutableStateFlow<Sha1Progress?>(null)
    /** Hashing progress for Settings ("Matching with Immich: 1,234 / 5,000"); null until first read. */
    val matching: StateFlow<Sha1Progress?> = _matching.asStateFlow()

    internal fun publishMatching(p: Sha1Progress) { _matching.value = p }

    /** Re-reads [matching] from the index. */
    fun refreshMatching() {
        scopeLaunch { val c = dao.sha1Counts(); _matching.value = Sha1Progress(c.done, c.total) }
    }

    /** Starts (or keeps) the hashing job when matching is wanted; cancels it otherwise. */
    fun scheduleHashing() {
        runCatching { if (wantHashes() && access() != MediaAccess.Denied) Sha1Worker.schedule(app) else Sha1Worker.cancel(app) }
            .onFailure { Log.w("DeviceMedia", "Couldn't schedule hashing", it) }
    }

    /** Moves phone items to Trash and back for editor saves (Replace) and their undo. */
    val trasher: LocalTrasher by lazy { AndroidLocalTrasher(app, dao) { library } }

    /** The phone's save sink for one editor save job, resuming from [checkpoint]. */
    fun saveSink(checkpoint: LocalSaveCheckpoint, onCheckpoint: (LocalSaveCheckpoint) -> Unit): LocalSaveSink =
        LocalSaveSink(dao, appDao, AndroidMediaWriter(app), trasher, checkpoint, onCheckpoint, onWritten = { indexer.requestSync(0) })

    private val observer = MediaObserver(app) { indexer.requestSync() }

    /** Starts watching: a sync whenever the app comes to the foreground, the observer while it's there. */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (access() != MediaAccess.Denied) observer.register()
                indexer.requestSync(0)
                scheduleHashing()
                scopeLaunch { library.purgeExpiredLegacyTrash() }
            }
            override fun onStop(owner: LifecycleOwner) = observer.unregister()
        })
    }

    private fun scopeLaunch(block: suspend () -> Unit) { scope.launch { runCatching { block() } } }

    /** Access changed (granted, narrowed, revoked): re-read everything. */
    fun onAccessChanged() {
        if (access() != MediaAccess.Denied) observer.register() else observer.unregister()
        scopeLaunch { indexer.sync(force = true) }
    }
}

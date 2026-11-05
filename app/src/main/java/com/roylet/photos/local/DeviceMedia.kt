package com.roylet.photos.local

import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.roylet.photos.domain.library.Album
import com.roylet.photos.domain.library.AssetDetails
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.LocalLibrary
import com.roylet.photos.domain.library.SharedUri
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.ui.components.SnackbarController
import com.roylet.photos.ui.permissions.ConsentLauncher
import com.roylet.photos.ui.permissions.MediaAccess
import com.roylet.photos.ui.permissions.MediaPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

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
) {
    private val app = context.applicationContext
    private val access: () -> MediaAccess = { MediaPermissions.access(app) }
    private val mediaStoreWrites = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val db: MediaDatabase by lazy { MediaDatabase.open(app) }
    val dao: MediaDao get() = db.media()

    val indexer: MediaIndexer by lazy {
        MediaIndexer(dao, AndroidMediaStoreReader(app), access, scope, onChanged = { onChanged() })
    }

    val timeline: MediaStoreTimelineSource by lazy { MediaStoreTimelineSource(dao, indexer, access, mediaStoreTrash = mediaStoreWrites) }

    private val offer = ManageMediaOffer(app, snackbar, scope, offeredManageMedia, markOfferedManageMedia)

    val library: MediaStoreLibrary by lazy {
        MediaStoreLibrary(
            dao = dao,
            indexer = indexer,
            mediaStoreWrites = mediaStoreWrites,
            runner = if (mediaStoreWrites) ConsentRequestRunner(app, consent, offer::maybeOffer) else MediaRequestRunner { false },
            legacy = if (mediaStoreWrites) null else AndroidLegacyFiles(app, consent),
            io = AndroidLocalIo(app),
        )
    }

    private val observer = MediaObserver(app) { indexer.requestSync() }

    /** Starts watching: a sync whenever the app comes to the foreground, the observer while it's there. */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (access() != MediaAccess.Denied) observer.register()
                indexer.requestSync(0)
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

/**
 * The library while an Immich server is connected and the phone's photos are browsed alongside
 * (Library → On this device): calls about specific assets go to the source in their id
 * (`local:` → the phone), folders (`folder:`) to the phone, everything else to Immich. The
 * merged library (agent C) replaces this.
 */
class SourceRoutingLibrary(private val immich: LibrarySource, private val local: LocalLibrary) : LibrarySource by immich {
    private inline fun <T> split(ids: List<String>, block: (LibrarySource, List<String>) -> T): List<T> {
        val (phone, server) = ids.partition(AssetRef::isLocal)
        return buildList {
            if (phone.isNotEmpty()) add(block(local, phone))
            if (server.isNotEmpty()) add(block(immich, server))
        }
    }

    private fun of(id: String): LibrarySource = if (AssetRef.isLocal(id)) local else immich

    override suspend fun setFavorite(ids: List<String>, favorite: Boolean) { split(ids) { s, part -> s.setFavorite(part, favorite) } }
    override suspend fun trash(ids: List<String>) { split(ids) { s, part -> s.trash(part) } }
    override suspend fun restore(ids: List<String>) { split(ids) { s, part -> s.restore(part) } }
    override suspend fun deleteForever(ids: List<String>) { split(ids) { s, part -> s.deleteForever(part) } }
    override suspend fun details(id: String): AssetDetails? = of(id).details(id)
    override suspend fun shareUri(id: String): SharedUri? = of(id).shareUri(id)
    override suspend fun download(id: String, dir: File, onProgress: (Long, Long) -> Unit): File = of(id).download(id, dir, onProgress)
    override suspend fun album(id: String): Album = if (id.startsWith(TimelineFilter.FOLDER_PREFIX)) local.album(id) else immich.album(id)

    override suspend fun assets(ids: List<String>): List<TimelineAsset> {
        val found = split(ids) { s, part -> s.assets(part) }.flatten().associateBy { it.id }
        return ids.mapNotNull { found[it] }
    }
}

package com.roylet.photos.ui.actions

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.Album
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.domain.library.DeleteTarget
import com.roylet.photos.domain.library.TargetedTrash
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.ShareLinkOptions
import com.roylet.photos.domain.library.SharedUri
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRegistry
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.domain.timeline.Visibility
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.ui.components.SnackTone
import com.roylet.photos.ui.components.SnackbarController
import com.roylet.photos.ui.gallery.AssetActions
import com.roylet.photos.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** The sheets the actions can open; drawn by [ActionSheets]. */
sealed interface ActionSheet {
    data class AddToAlbum(val ids: List<String>, val timeline: TimelineRepository?) : ActionSheet
    /** [albumId] set: the sheet offers an album link (send-file is disabled). */
    data class Share(val ids: List<String>, val albumId: String? = null) : ActionSheet
    /** "Delete from: this device / Immich / both", for a selection with [paired] items on both. */
    data class DeleteChoice(
        val ids: List<String>,
        val timeline: TimelineRepository?,
        val paired: Int,
        /** Some are in a folder that's backed up: choosing "Immich" warns that they won't be backed up again. */
        val backedUp: Boolean = false,
    ) : ActionSheet
}

/** Hands files or text to other apps; Android's share sheet and clipboard in the app. */
interface ShareLauncher {
    fun sendFiles(files: List<File>)
    /** Sends downloaded [files] and phone items by their own [uris] in one share. */
    fun send(files: List<File>, uris: List<SharedUri>) = sendFiles(files)
    fun sendText(text: String)
    fun copy(text: String)
}

/** Progress of a "send file" download: [done] of [count] files, bytes of the current one. */
data class Transfer(val done: Int, val count: Int, val bytes: Long = 0, val totalBytes: Long = -1) {
    val fraction: Float get() {
        val current = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
        return ((done + current) / count.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

sealed interface LinkState {
    data object Idle : LinkState
    data object Creating : LinkState
    data class Ready(val url: String) : LinkState
}

/**
 * The app's [AssetActions]: optimistic updates on the timeline the user is looking at (and the
 * other cached timelines), the network call, then a snackbar, with undo or a rollback on error.
 * Also owns the add-to-album and share sheets' state.
 */
@Stable
class LibraryActions(
    private val library: () -> LibrarySource,
    val albums: AlbumStore,
    val snackbar: SnackbarController,
    private val scope: CoroutineScope,
    private val launcher: ShareLauncher,
    val prefs: UiPrefs,
    private val shareDir: File,
    private val timelines: TimelineRegistry? = null,
    /** Who's signed in: the remembered delete choice lasts for one session. */
    private val session: () -> Any? = { null },
    /** Whether any of these ids is in a folder backup covers (docs/SOURCES.md §10). */
    private val inBackedUpFolder: suspend (List<String>) -> Boolean = { false },
) : AssetActions {

    /** The answer to "Delete from:" for this session (asked once, then used for every delete). */
    private var rememberedDelete: Pair<Any?, DeleteTarget>? = null

    /** The delete choice remembered for this session, if any. */
    val deleteTarget: DeleteTarget? get() = rememberedDelete?.takeIf { it.first == session() }?.second

    fun forgetDeleteChoice() { rememberedDelete = null }

    var sheet by mutableStateOf<ActionSheet?>(null)
        private set
    var transfer by mutableStateOf<Transfer?>(null)
        private set
    var link by mutableStateOf<LinkState>(LinkState.Idle)
        private set
    private var transferJob: Job? = null

    fun closeSheet() {
        cancelTransfer()
        sheet = null
        link = LinkState.Idle
    }

    private fun describe(e: Throwable) = ImmichApi.describe(e)

    private fun items(n: Int) = Format.plural(n, "item")

    /** Applies [change] to every cached timeline (besides [except]). */
    private fun others(except: TimelineRepository?, change: (TimelineRepository) -> Unit) {
        timelines?.forEachRepo { if (it !== except) change(it) }
    }

    /* ---------------- Favourite ---------------- */

    override suspend fun setFavorite(ids: List<String>, favorite: Boolean) = library().setFavorite(ids, favorite)

    override fun favorite(ids: List<String>, favorite: Boolean, timeline: TimelineRepository?) {
        if (ids.isEmpty()) return
        val before = timeline?.let { previousFavorites(it, ids) }.orEmpty()
        timeline?.setFavorite(ids, favorite)
        others(timeline) { r -> if (r.filter.isFavorite == true) r.markStale() else r.setFavorite(ids, favorite) }
        scope.launch {
            runCatching { library().setFavorite(ids, favorite) }.onFailure { e ->
                if (e is CancellationException) throw e
                // Roll back each asset to what it was (a multi-select may have mixed states).
                timeline?.updateAssets(ids) { a -> a.copy(isFavorite = before[a.id] ?: !favorite) }
                others(timeline) { r -> r.setFavorite(ids, !favorite) }
                snackbar.show("Couldn't update favourite: ${describe(e)}", tone = SnackTone.Error)
            }
        }
    }

    private fun previousFavorites(timeline: TimelineRepository, ids: List<String>): Map<String, Boolean> {
        val want = ids.toHashSet()
        val out = HashMap<String, Boolean>()
        timeline.snapshot.value.loaded.values.forEach { list -> list.forEach { if (it.id in want) out[it.id] = it.isFavorite } }
        return out
    }

    /* ---------------- Archive / trash ---------------- */

    override fun archive(ids: List<String>, timeline: TimelineRepository?) {
        if (ids.isEmpty()) return
        val unarchive = timeline?.filter?.visibility == Visibility.ARCHIVE
        val target = if (unarchive) Visibility.TIMELINE else Visibility.ARCHIVE
        val previous = if (unarchive) Visibility.ARCHIVE else Visibility.TIMELINE
        // Timelines filtered by visibility drop the assets; others (albums) just relabel them.
        val removal = if (timeline != null && timeline.filter.visibility != null) timeline.remove(ids) else {
            timeline?.updateAssets(ids) { it.copy(visibility = target) }; null
        }
        scope.launch {
            runCatching { library().setVisibility(ids, target) }
                .onSuccess {
                    others(timeline) { r ->
                        if (r.filter.visibility == previous) r.remove(ids)
                        else if (r.filter.visibility == target) r.markStale()
                    }
                    snackbar.show(
                        if (unarchive) "${items(ids.size)} back in Photos" else "Archived ${items(ids.size)}",
                        actionLabel = "Undo",
                        onAction = {
                            scope.launch {
                                runCatching { library().setVisibility(ids, previous) }
                                    .onSuccess {
                                        removal?.let { timeline?.reinsert(it) } ?: timeline?.updateAssets(ids) { it.copy(visibility = previous) }
                                        others(timeline) { r -> if (r.filter.visibility != null) r.markStale() }
                                    }
                                    .onFailure { e -> snackbar.error("Couldn't undo: ${describe(e)}") }
                            }
                        },
                    )
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    removal?.let { timeline?.reinsert(it) } ?: timeline?.updateAssets(ids) { it.copy(visibility = previous) }
                    snackbar.error("Couldn't ${if (unarchive) "unarchive" else "archive"}: ${describe(e)}")
                }
        }
    }

    override fun delete(ids: List<String>, timeline: TimelineRepository?) {
        if (ids.isEmpty()) return
        val lib = library()
        if (lib is TargetedTrash) {
            // Items on both the phone and Immich: ask where to delete them from (once a session).
            val remembered = deleteTarget
            if (remembered != null && remembered != DeleteTarget.Immich) return deleteFrom(lib, ids, remembered, timeline)
            scope.launch {
                val paired = runCatching { lib.pairedIds(ids) }.getOrDefault(emptySet())
                if (paired.isEmpty()) return@launch deleteFrom(lib, ids, DeleteTarget.Both, timeline)
                // A remembered "Immich" still asks when backup covers the photos: they won't be backed up again.
                val backedUp = runCatching { inBackedUpFolder(paired.toList()) }.getOrDefault(false)
                if (remembered != null && !backedUp) deleteFrom(lib, ids, remembered, timeline)
                else sheet = ActionSheet.DeleteChoice(ids, timeline, paired.size, backedUp)
            }
            return
        }
        val removal = timeline?.remove(ids)
        val elsewhere = HashMap<TimelineRepository, TimelineRepository.Removal>()
        others(timeline) { r -> if (r.filter.isTrashed == true) r.markStale() else elsewhere[r] = r.remove(ids) }
        scope.launch {
            runCatching { library().trash(ids) }
                .onSuccess {
                    snackbar.show("Moved ${items(ids.size)} to Trash", actionLabel = "Undo", onAction = {
                        scope.launch {
                            runCatching { library().restore(ids) }
                                .onSuccess {
                                    removal?.let { timeline?.reinsert(it) }
                                    elsewhere.forEach { (r, rem) -> r.reinsert(rem) }
                                    others(timeline) { r -> if (r.filter.isTrashed == true) r.markStale() }
                                    snackbar.show("Restored ${items(ids.size)}", tone = SnackTone.Success)
                                }
                                .onFailure { e -> snackbar.error("Couldn't restore: ${describe(e)}") }
                        }
                    })
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    removal?.let { timeline?.reinsert(it) }
                    elsewhere.forEach { (r, rem) -> r.reinsert(rem) }
                    snackbar.error("Couldn't delete: ${describe(e)}")
                }
        }
    }

    /** The answer from the delete-choice sheet: remembered for the session, then applied. */
    fun chooseDelete(target: DeleteTarget) {
        val choice = sheet as? ActionSheet.DeleteChoice ?: return
        sheet = null
        rememberedDelete = session() to target
        val lib = library() as? TargetedTrash ?: return
        deleteFrom(lib, choice.ids, target, choice.timeline)
    }

    /**
     * Trashes [ids] where [target] says (one-sided items from their own side), with UNDO. When
     * only one copy of a pair went, the timelines reload so the other copy shows again on its own.
     */
    private fun deleteFrom(lib: TargetedTrash, ids: List<String>, target: DeleteTarget, timeline: TimelineRepository?) {
        val removal = timeline?.remove(ids)
        val elsewhere = HashMap<TimelineRepository, TimelineRepository.Removal>()
        others(timeline) { r -> if (r.filter.isTrashed == true) r.markStale() else elsewhere[r] = r.remove(ids) }
        fun rollBack() {
            removal?.let { timeline?.reinsert(it) }
            elsewhere.forEach { (r, rem) -> r.reinsert(rem) }
        }
        scope.launch {
            runCatching { lib.trash(ids, target) }
                .onSuccess { receipt ->
                    if (receipt.local.isEmpty() && receipt.remote.isEmpty()) {
                        // Only guessed pairs, and the choice was the side that's only a guess: nothing went.
                        rollBack()
                        snackbar.show("Nothing deleted: not confirmed as the same photo in ${target.label} yet")
                        return@onSuccess
                    }
                    if (receipt.partial) timelines?.forEachRepo { r -> if (r.filter.isTrashed != true && r.filter.listId == null) r.refresh(keepLoaded = true) }
                    val where = when {
                        receipt.local.isNotEmpty() && receipt.remote.isEmpty() -> " on this device"
                        receipt.remote.isNotEmpty() && receipt.local.isEmpty() -> " in Immich"
                        else -> ""
                    }
                    snackbar.show("Moved ${items(ids.size)} to Trash$where", actionLabel = "Undo", onAction = {
                        scope.launch {
                            runCatching { lib.restore(receipt) }
                                .onSuccess {
                                    if (receipt.partial) timelines?.forEachRepo { r -> if (r.filter.listId == null) r.refresh(keepLoaded = true) }
                                    else rollBack()
                                    others(timeline) { r -> if (r.filter.isTrashed == true) r.markStale() }
                                    snackbar.show("Restored ${items(ids.size)}", tone = SnackTone.Success)
                                }
                                .onFailure { e -> snackbar.error("Couldn't restore: ${describe(e)}") }
                        }
                    })
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    rollBack()
                    snackbar.error("Couldn't delete: ${describe(e)}")
                }
        }
    }

    override fun restore(ids: List<String>, timeline: TimelineRepository?) {
        if (ids.isEmpty()) return
        val removal = timeline?.remove(ids)
        scope.launch {
            runCatching { library().restore(ids) }
                .onSuccess {
                    others(timeline) { it.markStale() }
                    snackbar.show("Restored ${items(ids.size)}", tone = SnackTone.Success)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    removal?.let { timeline?.reinsert(it) }
                    snackbar.error("Couldn't restore: ${describe(e)}")
                }
        }
    }

    override fun deleteForever(ids: List<String>, timeline: TimelineRepository?) {
        if (ids.isEmpty()) return
        val removal = timeline?.remove(ids)
        scope.launch {
            runCatching { library().deleteForever(ids) }
                .onSuccess { snackbar.show("Deleted ${items(ids.size)} for good", tone = SnackTone.Success) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    removal?.let { timeline?.reinsert(it) }
                    snackbar.error("Couldn't delete: ${describe(e)}")
                }
        }
    }

    /** Trash page: restore everything. */
    fun restoreAll(timeline: TimelineRepository?) = scope.launch {
        runCatching { library().restoreAll() }
            .onSuccess { n ->
                timeline?.refresh()
                others(timeline) { it.markStale() }
                snackbar.show(if (n > 0) "Restored ${items(n)}" else "Restoring everything in Trash", tone = SnackTone.Success)
            }
            .onFailure { e -> snackbar.error("Couldn't restore: ${describe(e)}") }
    }

    /** Trash page: delete everything for good. */
    fun emptyTrash(timeline: TimelineRepository?) = scope.launch {
        runCatching { library().emptyTrash() }
            .onSuccess { n ->
                timeline?.refresh()
                snackbar.show(if (n > 0) "Deleted ${items(n)} for good" else "Trash emptied", tone = SnackTone.Success)
            }
            .onFailure { e -> snackbar.error("Couldn't empty Trash: ${describe(e)}") }
    }

    /* ---------------- Albums ---------------- */

    override fun addToAlbum(ids: List<String>, timeline: TimelineRepository?) {
        if (ids.isEmpty()) return
        albums.refresh()
        sheet = ActionSheet.AddToAlbum(ids, timeline)
    }

    /** Which albums already hold every asset in [ids] (for the sheet's check marks). */
    suspend fun membership(ids: List<String>): Set<String> = runCatching { library().albumsContaining(ids) }.getOrDefault(emptySet())

    /**
     * Adds [ids] to [album] (or removes them when [add] is false). [onResult] gets the final
     * membership: the new state, or the old one after a failure.
     */
    fun toggleAlbum(album: Album, ids: List<String>, add: Boolean, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            runCatching { if (add) library().addToAlbum(album.id, ids) else library().removeFromAlbum(album.id, ids) }
                .onSuccess { n ->
                    albums.adjustCount(album.id, if (add) n else -n, cover = ids.firstOrNull())
                    timelines?.forEachRepo { if (it.filter.albumId == album.id) it.markStale() }
                    onResult(add)
                    snackbar.show(
                        if (add) (if (n == 0) "Already in ${album.name}" else "Added ${items(n)} to ${album.name}") else "Removed from ${album.name}",
                        tone = SnackTone.Success,
                    )
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    onResult(!add)
                    snackbar.error("Couldn't update ${album.name}: ${describe(e)}")
                }
        }
    }

    /** Creates an album already holding [ids] (one request). */
    fun createAlbum(name: String, ids: List<String>, onFailed: () -> Unit = {}, onCreated: (Album) -> Unit = {}) {
        val clean = name.trim().ifEmpty { "Untitled" }
        scope.launch {
            runCatching { library().createAlbum(clean, ids) }
                .onSuccess { album ->
                    albums.upsert(album.copy(updatedAt = maxOf(album.updatedAt, System.currentTimeMillis())))
                    onCreated(album)
                    snackbar.show(if (ids.isEmpty()) "Created ${album.name}" else "Added ${items(ids.size)} to new album ${album.name}", tone = SnackTone.Success)
                }
                .onFailure { e -> if (e is CancellationException) throw e; onFailed(); snackbar.error("Couldn't create album: ${describe(e)}") }
        }
    }

    fun renameAlbum(album: Album, name: String, description: String) {
        val updated = album.copy(name = name.trim().ifEmpty { album.name }, description = description.trim())
        albums.upsert(updated)
        scope.launch {
            runCatching { library().updateAlbum(album.id, name = updated.name, description = updated.description) }
                .onSuccess { albums.upsert(it); snackbar.show("Album updated", tone = SnackTone.Success) }
                .onFailure { e -> albums.upsert(album); snackbar.error("Couldn't rename: ${describe(e)}") }
        }
    }

    override fun setAlbumCover(albumId: String, assetId: String) {
        scope.launch {
            runCatching { library().updateAlbum(albumId, coverAssetId = assetId) }
                .onSuccess { albums.upsert(it); snackbar.show("Album cover updated", tone = SnackTone.Success) }
                .onFailure { e -> snackbar.error("Couldn't change the cover: ${describe(e)}") }
        }
    }

    override fun removeFromAlbum(albumId: String, ids: List<String>, timeline: TimelineRepository?) {
        if (ids.isEmpty()) return
        val removal = timeline?.remove(ids)
        val name = albums.find(albumId)?.name ?: "album"
        scope.launch {
            runCatching { library().removeFromAlbum(albumId, ids) }
                .onSuccess { n ->
                    albums.adjustCount(albumId, -n)
                    snackbar.show("Removed ${items(ids.size)} from $name", actionLabel = "Undo", onAction = {
                        scope.launch {
                            runCatching { library().addToAlbum(albumId, ids) }
                                .onSuccess { m -> albums.adjustCount(albumId, m); removal?.let { timeline?.reinsert(it) } }
                                .onFailure { e -> snackbar.error("Couldn't undo: ${describe(e)}") }
                        }
                    })
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    removal?.let { timeline?.reinsert(it) }
                    snackbar.error("Couldn't remove: ${describe(e)}")
                }
        }
    }

    fun deleteAlbum(album: Album, onDeleted: () -> Unit = {}) {
        scope.launch {
            runCatching { library().deleteAlbum(album.id) }
                .onSuccess { albums.removeLocal(album.id); onDeleted(); snackbar.show("Deleted ${album.name}", tone = SnackTone.Success) }
                .onFailure { e -> snackbar.error("Couldn't delete ${album.name}: ${describe(e)}") }
        }
    }

    /* ---------------- Share ---------------- */

    override fun share(ids: List<String>, timeline: TimelineRepository?) {
        if (ids.isEmpty()) return
        link = LinkState.Idle
        sheet = ActionSheet.Share(ids)
    }

    override fun shareAlbum(albumId: String) {
        link = LinkState.Idle
        sheet = ActionSheet.Share(emptyList(), albumId)
    }

    /**
     * Opens Android's share sheet with the originals. Phone items go by their own content URI (no
     * copy); the rest are downloaded first, with progress.
     */
    fun sendFiles(ids: List<String>) {
        if (ids.isEmpty() || transferJob?.isActive == true) return
        prefs.shareMode = "file"
        transfer = Transfer(0, ids.size)
        transferJob = scope.launch {
            try {
                val uris = ArrayList<SharedUri>()
                val download = ArrayList<String>()
                ids.forEach { id -> library().shareUri(id)?.let(uris::add) ?: download.add(id) }
                // Old shares are no longer needed once the user starts a new one.
                if (download.isNotEmpty()) shareDir.listFiles()?.forEach { it.deleteRecursively() }
                val base = uris.size
                transfer = Transfer(base, ids.size)
                val files = download.mapIndexed { i, id ->
                    library().download(id, shareDir) { bytes, total -> transfer = Transfer(base + i, ids.size, bytes, total) }
                        .also { transfer = Transfer(base + i + 1, ids.size) }
                }
                if (uris.isEmpty()) launcher.sendFiles(files) else launcher.send(files, uris)
                sheet = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                snackbar.error("Couldn't download: ${describe(e)}")
            } finally {
                transfer = null
            }
        }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null
        transfer = null
    }

    fun createLink(ids: List<String>, albumId: String?, options: ShareLinkOptions) {
        prefs.shareMode = "link"
        link = LinkState.Creating
        scope.launch {
            runCatching { library().createSharedLink(ids.takeIf { albumId == null }, albumId, options) }
                .onSuccess { url ->
                    link = LinkState.Ready(url)
                    if (albumId != null) albums.find(albumId)?.let { albums.upsert(it.copy(hasSharedLink = true)) }
                }
                .onFailure { e -> link = LinkState.Idle; snackbar.error("Couldn't create the link: ${describe(e)}") }
        }
    }

    fun sendLink(url: String) { launcher.sendText(url); closeSheet() }

    fun copyLink(url: String) {
        launcher.copy(url)
        closeSheet()
        snackbar.show("Link copied", tone = SnackTone.Success)
    }

    /** Registers [assets] as a browsable list timeline (search results, map clusters). */
    fun listTimeline(id: String, assets: List<TimelineAsset>): TimelineFilter? = timelines?.putList(id, assets)
}

/** The app's [LibraryActions] for album/trash/share specific UI; null in previews. */
val LocalLibraryActions = staticCompositionLocalOf<LibraryActions?> { null }

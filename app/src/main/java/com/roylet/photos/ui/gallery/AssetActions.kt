package com.roylet.photos.ui.gallery

import androidx.compose.runtime.staticCompositionLocalOf
import com.roylet.photos.domain.timeline.TimelineRepository

/**
 * What the viewer's bottom bar and the grid's selection bar can do to assets. The grid and viewer
 * only call these; the implementation (sheets, snackbars, API calls) is provided through
 * [LocalAssetActions], so the actions can be built and swapped independently of the screens.
 * The app's implementation is `ui.actions.LibraryActions`.
 *
 * [timeline] is the timeline the assets were picked from, for optimistic updates
 * (e.g. `timeline.remove(ids)` after a delete).
 */
interface AssetActions {
    /** Network part of a favourite toggle; callers update the UI first and roll back if this throws. */
    suspend fun setFavorite(ids: List<String>, favorite: Boolean)

    /** Optimistic favourite toggle: the grid/viewer update now; the app's implementation rolls back with a snackbar on failure. */
    fun favorite(ids: List<String>, favorite: Boolean, timeline: TimelineRepository?) { timeline?.setFavorite(ids, favorite) }

    fun share(ids: List<String>, timeline: TimelineRepository?) {}
    fun addToAlbum(ids: List<String>, timeline: TimelineRepository?) {}
    /** Archives, or unarchives when [timeline] is the Archive. */
    fun archive(ids: List<String>, timeline: TimelineRepository?) {}
    /** Moves to Immich trash (with undo). */
    fun delete(ids: List<String>, timeline: TimelineRepository?) {}

    /* Album pages. */
    fun shareAlbum(albumId: String) {}
    fun removeFromAlbum(albumId: String, ids: List<String>, timeline: TimelineRepository?) {}
    fun setAlbumCover(albumId: String, assetId: String) {}

    /* Trash. */
    fun restore(ids: List<String>, timeline: TimelineRepository?) {}
    fun deleteForever(ids: List<String>, timeline: TimelineRepository?) {}
}

/** Does nothing beyond local favourite toggles; used in previews and screenshots. */
object NoopAssetActions : AssetActions {
    override suspend fun setFavorite(ids: List<String>, favorite: Boolean) {}
}

val LocalAssetActions = staticCompositionLocalOf<AssetActions> { NoopAssetActions }

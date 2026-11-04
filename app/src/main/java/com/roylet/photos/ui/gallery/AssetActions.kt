package com.roylet.photos.ui.gallery

import androidx.compose.runtime.staticCompositionLocalOf
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.setFavorite

/**
 * What the viewer's bottom bar and the grid's selection bar can do to assets. The grid and viewer
 * only call these; the implementation (sheets, snackbars, API calls) is provided through
 * [LocalAssetActions], so the actions can be built and swapped independently of the screens.
 *
 * [timeline] is the timeline the assets were picked from, for optimistic updates
 * (e.g. `timeline.remove(ids)` after a delete).
 */
interface AssetActions {
    /** Network part of a favourite toggle; callers update the UI first and roll back if this throws. */
    suspend fun setFavorite(ids: List<String>, favorite: Boolean)

    fun share(ids: List<String>, timeline: TimelineRepository?) {}
    fun addToAlbum(ids: List<String>, timeline: TimelineRepository?) {}
    fun archive(ids: List<String>, timeline: TimelineRepository?) {}
    /** Moves to Immich trash (with undo). */
    fun delete(ids: List<String>, timeline: TimelineRepository?) {}
}

/** Favourite works against the API; the rest are stubs until the actions sheets land. */
class ImmichAssetActions(private val api: () -> ImmichApi?) : AssetActions {
    override suspend fun setFavorite(ids: List<String>, favorite: Boolean) {
        (api() ?: throw ImmichException("Not signed in")).setFavorite(ids, favorite)
    }
    // TODO(actions): share sheet (send file / share link), add-to-album sheet, archive, delete → trash with undo.
}

/** Does nothing; used in previews and screenshots. */
object NoopAssetActions : AssetActions {
    override suspend fun setFavorite(ids: List<String>, favorite: Boolean) {}
}

val LocalAssetActions = staticCompositionLocalOf<AssetActions> { NoopAssetActions }

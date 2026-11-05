package com.roylet.photos.domain.library

import com.roylet.photos.domain.source.Capabilities
import com.roylet.photos.domain.timeline.TimeBucket
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineSource
import com.roylet.photos.domain.timeline.Visibility
import java.io.File
import java.time.LocalDate

/*
 * The phone's own library (MediaStore), as seen by the rest of the app. Agent B implements these
 * two interfaces over MediaStore; until then the app plugs in the empty ones below, so it runs
 * with an empty "On this device" timeline. See docs/SOURCES.md.
 */

/**
 * The phone's timeline. Ids are tagged `local:<MediaStore _ID>` (see AssetRef). Filters map as:
 * - [TimelineFilter.Timeline]: everything not trashed; `visibility` is ignored (no archive);
 * - `isFavorite = true`: MediaStore IS_FAVORITE (Android 11+);
 * - `isTrashed = true`: QUERY_ARG_MATCH_TRASHED (Android 11+) or the app's own trash on 10;
 * - `albumId`: a folder (BUCKET_ID), tagged `folder:<bucketId>`;
 * - `listId`: never reaches the source (the registry serves lists itself).
 * Bucket keys use Immich's format (`yyyy-MM-01T00:00:00.000Z`) so the grid's month headers and
 * the merged timeline line up.
 */
interface LocalTimelineSource : TimelineSource

/** A [LocalTimelineSource] with no photos: the placeholder until the MediaStore source lands. */
object EmptyLocalTimelineSource : LocalTimelineSource {
    override suspend fun buckets(filter: TimelineFilter): List<TimeBucket> = emptyList()
    override suspend fun bucket(filter: TimelineFilter, key: String): List<TimelineAsset> = emptyList()
}

/**
 * The phone's library: folders as read-only albums, favourite and trash through MediaStore
 * requests (with consent via `ConsentLauncher`). Everything Immich-only has a default here that
 * returns nothing or throws [UnsupportedCapabilityException], so an implementation only writes
 * what the phone can do. [capabilities] says which of those the UI may offer.
 */
interface LocalLibrary : LibrarySource {
    override val capabilities: Capabilities get() = Capabilities.Local

    /* Folders are read-only. */
    override suspend fun createAlbum(name: String, assetIds: List<String>): Album = throw UnsupportedCapabilityException("Creating albums")
    override suspend fun updateAlbum(id: String, name: String?, description: String?, coverAssetId: String?): Album = throw UnsupportedCapabilityException("Editing folders")
    override suspend fun deleteAlbum(id: String): Unit = throw UnsupportedCapabilityException("Deleting folders")
    override suspend fun addToAlbum(albumId: String, assetIds: List<String>): Int = throw UnsupportedCapabilityException("Adding to a folder")
    override suspend fun removeFromAlbum(albumId: String, assetIds: List<String>): Int = throw UnsupportedCapabilityException("Removing from a folder")
    override suspend fun albumsContaining(assetIds: List<String>): Set<String> = emptySet()

    /* Server features. */
    override suspend fun createSharedLink(assetIds: List<String>?, albumId: String?, options: ShareLinkOptions): String = throw UnsupportedCapabilityException("Share links")
    override suspend fun setVisibility(ids: List<String>, visibility: Visibility): Unit = throw UnsupportedCapabilityException("Archive")
    override suspend fun smartSearch(query: String): List<TimelineAsset> = throw UnsupportedCapabilityException("Smart search")
    override suspend fun placeSearch(city: String?, country: String?): List<TimelineAsset> = emptyList()
    override suspend fun explore(): List<ExploreSection> = emptyList()
    override suspend fun suggestions(type: String): List<String> = emptyList()
    override suspend fun people(): List<Person> = emptyList()
    override suspend fun mapMarkers(): List<MapMarker> = emptyList()
    override suspend fun memories(day: LocalDate): List<Memory> = emptyList()
    /** Android doesn't promise a period for every version; the Trash note is hidden. */
    override suspend fun trashDays(): Int? = null
}

/** A [LocalLibrary] with no photos and no folders: the placeholder until the MediaStore library lands. */
open class EmptyLocalLibrary : LocalLibrary {
    override suspend fun setFavorite(ids: List<String>, favorite: Boolean): Unit = throw UnsupportedCapabilityException("Favourites on this device")
    override suspend fun details(id: String): AssetDetails? = null
    override suspend fun albums(): List<Album> = emptyList()
    override suspend fun album(id: String): Album = throw NoSuchElementException("No folder $id")
    override suspend fun trash(ids: List<String>): Unit = throw UnsupportedCapabilityException("Trash on this device")
    override suspend fun restore(ids: List<String>): Unit = throw UnsupportedCapabilityException("Trash on this device")
    override suspend fun deleteForever(ids: List<String>): Unit = throw UnsupportedCapabilityException("Trash on this device")
    override suspend fun restoreAll(): Int = 0
    override suspend fun emptyTrash(): Int = 0
    override suspend fun assets(ids: List<String>): List<TimelineAsset> = emptyList()
    override suspend fun download(id: String, dir: File, onProgress: (Long, Long) -> Unit): File = throw UnsupportedCapabilityException("Sharing files from this device")
}

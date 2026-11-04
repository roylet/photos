package com.roylet.photos.domain.library

import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.Visibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/*
 * Pure-Kotlin library model (albums, people, places, memories, sharing) and the source the
 * screens talk to. Immich implements it in the app; tests and screenshots swap in a fake.
 */

data class Album(
    val id: String,
    val name: String,
    val description: String = "",
    val assetCount: Int = 0,
    val coverAssetId: String? = null,
    /** Shared with other users. */
    val shared: Boolean = false,
    val hasSharedLink: Boolean = false,
    val ownerName: String? = null,
    val ownedByMe: Boolean = true,
    /** Epoch millis: last change to the album (or its newest asset), for "recent first". */
    val updatedAt: Long = 0,
    val startDate: Long? = null,
    val endDate: Long? = null,
)

data class Person(val id: String, val name: String, val isFavorite: Boolean = false)

data class MapMarker(val id: String, val lat: Double, val lon: Double, val city: String? = null, val country: String? = null)

/** An "on this day" memory: photos from [year]. */
data class Memory(val id: String, val year: Int, val assets: List<TimelineAsset>) {
    fun title(today: LocalDate = LocalDate.now()): String {
        val n = today.year - year
        return if (n <= 1) "1 year ago" else "$n years ago"
    }
}

data class ExploreItem(val value: String, val asset: TimelineAsset)
data class ExploreSection(val fieldName: String, val items: List<ExploreItem>) {
    val isPlaces: Boolean get() = fieldName.contains("city", ignoreCase = true)
}

/** Shared-link options from the share sheet. */
data class ShareLinkOptions(
    /** Null = never expires. */
    val expiresInDays: Int? = null,
    val allowDownload: Boolean = true,
    val showMetadata: Boolean = true,
    val password: String? = null,
)

/** Album sort orders on the Albums tab. */
enum class AlbumSort(val label: String) {
    Recent("Recent"), Name("Name"), Count("Most photos");

    fun apply(list: List<Album>): List<Album> = when (this) {
        Recent -> list.sortedByDescending { it.updatedAt }
        Name -> list.sortedBy { it.name.lowercase() }
        Count -> list.sortedByDescending { it.assetCount }
    }
}

interface LibrarySource {
    suspend fun albums(): List<Album>
    /** Ids of the albums that contain every one of [assetIds]. */
    suspend fun albumsContaining(assetIds: List<String>): Set<String>
    suspend fun album(id: String): Album
    suspend fun createAlbum(name: String, assetIds: List<String>): Album
    suspend fun updateAlbum(id: String, name: String? = null, description: String? = null, coverAssetId: String? = null): Album
    suspend fun deleteAlbum(id: String)
    /** Returns how many were actually added (duplicates are skipped by the server). */
    suspend fun addToAlbum(albumId: String, assetIds: List<String>): Int
    suspend fun removeFromAlbum(albumId: String, assetIds: List<String>): Int

    /** Creates a link and returns its public URL. Exactly one of [assetIds] / [albumId]. */
    suspend fun createSharedLink(assetIds: List<String>?, albumId: String?, options: ShareLinkOptions): String

    suspend fun setVisibility(ids: List<String>, visibility: Visibility)
    suspend fun trash(ids: List<String>)
    suspend fun restore(ids: List<String>)
    suspend fun deleteForever(ids: List<String>)
    suspend fun restoreAll(): Int
    suspend fun emptyTrash(): Int
    /** How long the server keeps trashed items. */
    suspend fun trashDays(): Int

    suspend fun smartSearch(query: String): List<TimelineAsset>
    suspend fun placeSearch(city: String?, country: String?): List<TimelineAsset>
    suspend fun explore(): List<ExploreSection>
    suspend fun suggestions(type: String): List<String>
    suspend fun people(): List<Person>
    suspend fun mapMarkers(): List<MapMarker>
    /** Full details for [ids] (map clusters), in the given order; missing ones are skipped. */
    suspend fun assets(ids: List<String>): List<TimelineAsset>
    suspend fun memories(day: LocalDate): List<Memory>

    /** Downloads asset [id]'s original into [dir]; returns the file (named like the original). */
    suspend fun download(id: String, dir: File, onProgress: (Long, Long) -> Unit): File
}

/**
 * The album list, cached for the session and shared by the Albums tab, album pages and the
 * add-to-album sheet. Mutations update the cache immediately so every screen stays in step.
 */
class AlbumStore(
    private val source: () -> LibrarySource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _albums = MutableStateFlow<List<Album>?>(null)
    /** Null until the first load finishes. */
    val albums: StateFlow<List<Album>?> = _albums.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun start() { if (_albums.value == null && !_loading.value) refresh() }

    fun refresh() {
        _loading.value = true
        scope.launch {
            runCatching { source().albums() }
                .onSuccess { _albums.value = it; _error.value = null }
                .onFailure { _error.value = it.message ?: it.javaClass.simpleName }
            _loading.value = false
        }
    }

    fun find(id: String): Album? = _albums.value?.firstOrNull { it.id == id }

    fun upsert(album: Album) = _albums.update { list ->
        val l = list.orEmpty()
        if (l.any { it.id == album.id }) l.map { if (it.id == album.id) album else it } else listOf(album) + l
    }

    fun removeLocal(id: String) = _albums.update { it?.filterNot { a -> a.id == id } }

    fun adjustCount(id: String, delta: Int, cover: String? = null) = _albums.update { list ->
        list?.map { if (it.id == id) it.copy(assetCount = (it.assetCount + delta).coerceAtLeast(0), coverAssetId = it.coverAssetId ?: cover, updatedAt = System.currentTimeMillis()) else it }
    }

    fun clear() { _albums.value = null }
}

/** Today's memories, fetched once per day. */
class MemoryStore(
    private val source: () -> LibrarySource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()
    private var loadedFor: LocalDate? = null

    fun start(today: LocalDate = LocalDate.now()) {
        if (loadedFor == today) return
        loadedFor = today
        scope.launch {
            runCatching { source().memories(today) }
                .onSuccess { list -> _memories.value = list.filter { it.assets.isNotEmpty() }.sortedByDescending { it.year } }
                .onFailure { loadedFor = null }
        }
    }

    fun clear() { loadedFor = null; _memories.value = emptyList() }
}

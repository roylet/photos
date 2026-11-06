package com.roylet.photos.immich

import com.roylet.photos.domain.library.Album
import com.roylet.photos.domain.library.AssetDetails
import com.roylet.photos.domain.source.Capabilities
import com.roylet.photos.domain.library.ExploreItem
import com.roylet.photos.domain.library.ExploreSection
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.MapMarker
import com.roylet.photos.domain.library.Memory
import com.roylet.photos.domain.library.Person
import com.roylet.photos.domain.library.ShareLinkOptions
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.Visibility
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** The production [LibrarySource]. [api] is resolved per call so a sign-in or URL switch applies immediately. */
class ImmichLibrary(
    private val api: () -> ImmichApi?,
    /** The signed-in user's id, to tell owned albums from shared ones. */
    private val userId: () -> String? = { null },
    private val now: () -> Instant = Instant::now,
) : LibrarySource {
    private fun requireApi() = api() ?: throw ImmichException("Not signed in")

    private var config: ServerConfigDto? = null
    private suspend fun config(): ServerConfigDto = config ?: requireApi().serverConfig().also { config = it }

    override val capabilities: Capabilities get() = Capabilities.Immich

    override suspend fun setFavorite(ids: List<String>, favorite: Boolean) = requireApi().setFavorite(ids, favorite)

    override suspend fun details(id: String): AssetDetails = requireApi().asset(id).toDetails()

    override suspend fun albums(): List<Album> = requireApi().albums().map { it.toAlbum() }

    override suspend fun albumsContaining(assetIds: List<String>): Set<String> = coroutineScope {
        if (assetIds.isEmpty()) return@coroutineScope emptySet()
        val api = requireApi()
        // One call per asset; big selections only need a sample to be a useful hint.
        val sets = assetIds.take(MAX_MEMBERSHIP_CHECKS).map { id -> async { api.albums(assetId = id).map { it.id }.toSet() } }.awaitAll()
        if (assetIds.size > MAX_MEMBERSHIP_CHECKS) emptySet() else sets.reduce { a, b -> a intersect b }
    }

    override suspend fun album(id: String): Album = requireApi().album(id).toAlbum()

    override suspend fun createAlbum(name: String, assetIds: List<String>): Album = requireApi().createAlbum(name, assetIds).toAlbum()

    override suspend fun updateAlbum(id: String, name: String?, description: String?, coverAssetId: String?): Album =
        requireApi().updateAlbum(id, UpdateAlbumBody(name, description, coverAssetId)).toAlbum()

    override suspend fun deleteAlbum(id: String) = requireApi().deleteAlbum(id)

    override suspend fun addToAlbum(albumId: String, assetIds: List<String>): Int =
        requireApi().addAssetsToAlbum(albumId, assetIds).count { it.success }

    override suspend fun removeFromAlbum(albumId: String, assetIds: List<String>): Int =
        requireApi().removeAssetsFromAlbum(albumId, assetIds).count { it.success }

    override suspend fun createSharedLink(assetIds: List<String>?, albumId: String?, options: ShareLinkOptions): String {
        val api = requireApi()
        val link = api.createSharedLink(
            SharedLinkCreateBody(
                type = if (albumId != null) "ALBUM" else "INDIVIDUAL",
                assetIds = if (albumId == null) assetIds else null,
                albumId = albumId,
                expiresAt = options.expiresInDays?.let { now().plusSeconds(it * 86_400L).toString() },
                allowDownload = options.allowDownload,
                showMetadata = options.showMetadata,
                password = options.password?.takeIf { it.isNotBlank() },
            ),
        )
        val base = runCatching { config().externalDomain }.getOrNull()?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: api.origin
        return sharedLinkUrl(base, link)
    }

    override suspend fun setVisibility(ids: List<String>, visibility: Visibility) = requireApi().setVisibility(ids, visibility)
    override suspend fun trash(ids: List<String>) = requireApi().deleteAssets(ids, force = false)
    override suspend fun restore(ids: List<String>) { requireApi().restoreAssets(ids) }
    override suspend fun deleteForever(ids: List<String>) = requireApi().deleteAssets(ids, force = true)
    override suspend fun restoreAll(): Int = requireApi().restoreTrash().count
    override suspend fun emptyTrash(): Int = requireApi().emptyTrash().count
    override suspend fun trashDays(): Int = config().trashDays

    /** Immich's metadata search by original file name. */
    override suspend fun basicSearch(query: String): List<TimelineAsset> =
        requireApi().metadataSearch(MetadataSearchBody(originalFileName = query)).assets.items.toSearchResults()

    /**
     * Immich's smart (CLIP) search. A server with smart search turned off (machine learning
     * disabled) answers 400 "Smart search is not enabled", and older or proxied ones 404 / 501:
     * then this falls back to the file-name search rather than failing.
     */
    override suspend fun smartSearch(query: String): List<TimelineAsset> {
        val api = requireApi()
        return try {
            api.smartSearch(query).assets.items.toSearchResults()
        } catch (e: ImmichException) {
            if (e.status !in SMART_SEARCH_UNAVAILABLE) throw e
            api.metadataSearch(MetadataSearchBody(originalFileName = query)).assets.items.toSearchResults()
        }
    }

    override suspend fun placeSearch(city: String?, country: String?): List<TimelineAsset> =
        requireApi().metadataSearch(MetadataSearchBody(city = city, country = country)).assets.items.toSearchResults()

    override suspend fun explore(): List<ExploreSection> = requireApi().explore().map { s ->
        val items = s.items.mapNotNull { item ->
            val value = item.value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val asset = item.data ?: return@mapNotNull null
            ExploreItem(value, asset.toTimelineAsset())
        }.distinctBy { it.value }
        ExploreSection(s.fieldName, items)
    }.filter { it.items.isNotEmpty() }

    override suspend fun suggestions(type: String): List<String> = requireApi().searchSuggestions(type).filter { it.isNotBlank() }

    override suspend fun people(): List<Person> = requireApi().people().people
        .filter { !it.isHidden }
        .map { Person(it.id, it.name, it.isFavorite) }
        // Named people first (favourites on top), then the unnamed faces in server order.
        .sortedWith(compareBy<Person>({ it.name.isBlank() }, { !it.isFavorite }))

    override suspend fun mapMarkers(): List<MapMarker> = requireApi().mapMarkers().map { MapMarker(it.id, it.lat, it.lon, it.city, it.country) }

    override suspend fun assets(ids: List<String>): List<TimelineAsset> = coroutineScope {
        val api = requireApi()
        val gate = Semaphore(8)
        ids.map { id -> async { gate.withPermit { runCatching { api.asset(id).toTimelineAsset() }.getOrNull() } } }.awaitAll().filterNotNull()
    }

    override suspend fun memories(day: LocalDate): List<Memory> = requireApi().memories(day).map { m ->
        Memory(m.id, m.data.year, m.assets.map { it.toTimelineAsset() })
    }

    override suspend fun download(id: String, dir: File, onProgress: (Long, Long) -> Unit): File {
        val api = requireApi()
        val name = runCatching { api.asset(id).originalFileName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "$id.jpg"
        // One folder per asset keeps the original file name without clashes.
        return api.downloadOriginal(id, File(File(dir, id), safeName(name)), edited = true, onProgress = onProgress)
    }

    private fun AlbumDto.toAlbum(): Album {
        val me = userId()
        val ownerId = ownerId ?: owner?.id ?: albumUsers.firstOrNull { it.role == "owner" }?.user?.id
        val ownerName = owner?.name ?: albumUsers.firstOrNull { it.role == "owner" }?.user?.name
        return Album(
            id = id,
            name = albumName.ifBlank { "Untitled" },
            description = description,
            assetCount = assetCount,
            coverAssetId = albumThumbnailAssetId,
            shared = shared || albumUsers.size > 1,
            hasSharedLink = hasSharedLink,
            ownerName = ownerName,
            ownedByMe = me == null || ownerId == null || ownerId == me,
            updatedAt = listOfNotNull(lastModifiedAssetTimestamp, updatedAt).mapNotNull(TimeBucketAssetsDto::parseInstantMillis).maxOrNull() ?: 0L,
            startDate = startDate?.let(TimeBucketAssetsDto::parseInstantMillis),
            endDate = endDate?.let(TimeBucketAssetsDto::parseInstantMillis),
        )
    }

    companion object {
        /** Statuses meaning "this server has no smart search" (not a failure of the query itself). */
        val SMART_SEARCH_UNAVAILABLE = setOf(400, 404, 501)

        /** One grid cell per asset, in the server's (relevance) order. */
        internal fun List<AssetDto>.toSearchResults(): List<TimelineAsset> = distinctBy { it.id }.map { it.toTimelineAsset() }

        const val MAX_MEMBERSHIP_CHECKS = 20

        /** Immich serves shared links at `/share/{key}` (or `/s/{slug}` for custom slugs). */
        fun sharedLinkUrl(base: String, link: SharedLinkDto): String =
            if (!link.slug.isNullOrBlank()) "$base/s/${link.slug}" else "$base/share/${link.key}"

        fun safeName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120)
    }
}

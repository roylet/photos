package com.roylet.photos.immich

import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.Visibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.coroutines.coroutineContext

/*
 * Library endpoints (Immich v3.4): shared links, visibility, trash, search, people, map,
 * memories and original downloads, as extensions on [ImmichApi].
 */

/* ---- Shared links ---- */

@Serializable
data class SharedLinkCreateBody(
    val type: String,
    val assetIds: List<String>? = null,
    val albumId: String? = null,
    val expiresAt: String? = null,
    val allowDownload: Boolean = true,
    val showMetadata: Boolean = true,
    val password: String? = null,
    val description: String? = null,
)

@Serializable data class SharedLinkDto(val id: String, val key: String, val type: String = "INDIVIDUAL", val slug: String? = null, val expiresAt: String? = null)

suspend fun ImmichApi.createSharedLink(body: SharedLinkCreateBody): SharedLinkDto = call(post("/shared-links", body))

/* ---- Bulk asset changes ---- */

@Serializable private data class VisibilityBody(val ids: List<String>, val visibility: String)
@Serializable private data class DeleteBody(val ids: List<String>, val force: Boolean)
@Serializable data class TrashCountDto(val count: Int = 0)

/** Archive (`archive`) or unarchive (`timeline`) via `PUT /assets`. */
suspend fun ImmichApi.setVisibility(ids: List<String>, visibility: Visibility) = send(put("/assets", VisibilityBody(ids, visibility.wire)))

/** `DELETE /assets`: to the trash, or gone for good with [force]. */
suspend fun ImmichApi.deleteAssets(ids: List<String>, force: Boolean = false) = send(delete("/assets", DeleteBody(ids, force)))

suspend fun ImmichApi.restoreAssets(ids: List<String>): TrashCountDto = call(post("/trash/restore/assets", BulkIdsBody(ids)))
suspend fun ImmichApi.restoreTrash(): TrashCountDto = call(post("/trash/restore", emptyMap<String, String>()))
suspend fun ImmichApi.emptyTrash(): TrashCountDto = call(post("/trash/empty", emptyMap<String, String>()))

/* ---- Server ---- */

@Serializable data class ServerConfigDto(val externalDomain: String = "", val trashDays: Int = 30)

suspend fun ImmichApi.serverConfig(): ServerConfigDto = call(get("/server/config"))

/* ---- Search ---- */

@Serializable data class SmartSearchBody(val query: String, val size: Int = 200, val page: Int? = null, val withExif: Boolean = true)
@Serializable data class MetadataSearchBody(val city: String? = null, val country: String? = null, val size: Int = 200, val withExif: Boolean = true)
@Serializable data class SearchAssetsDto(val items: List<AssetDto> = emptyList(), val total: Int = 0, val nextPage: String? = null)
@Serializable data class SearchResponseDto(val assets: SearchAssetsDto = SearchAssetsDto())
@Serializable data class ExploreItemDto(val value: String, val data: AssetDto)
@Serializable data class ExploreDto(val fieldName: String, val items: List<ExploreItemDto> = emptyList())

suspend fun ImmichApi.smartSearch(query: String, size: Int = 200): SearchResponseDto = call(post("/search/smart", SmartSearchBody(query, size)))
suspend fun ImmichApi.metadataSearch(body: MetadataSearchBody): SearchResponseDto = call(post("/search/metadata", body))
suspend fun ImmichApi.explore(): List<ExploreDto> = call(get("/search/explore"))

/** `type` is one of country, state, city, camera-make, camera-model, camera-lens-model. */
suspend fun ImmichApi.searchSuggestions(type: String): List<String> =
    call(authed(Request.Builder().url(url("/search/suggestions").toHttpUrl().newBuilder().addQueryParameter("type", type).build()).get()))

/* ---- People ---- */

@Serializable data class PersonDto(val id: String, val name: String = "", val isHidden: Boolean = false, val isFavorite: Boolean = false, val thumbnailPath: String = "")
@Serializable data class PeopleDto(val people: List<PersonDto> = emptyList(), val total: Int = 0, val hidden: Int = 0, val hasNextPage: Boolean = false)

suspend fun ImmichApi.people(size: Int = 500): PeopleDto =
    call(authed(Request.Builder().url(url("/people").toHttpUrl().newBuilder().addQueryParameter("size", size.toString()).build()).get()))

fun ImmichApi.personThumbnailUrl(id: String): String = url("/people/$id/thumbnail")

/* ---- Map ---- */

@Serializable data class MapMarkerDto(val id: String, val lat: Double, val lon: Double, val city: String? = null, val state: String? = null, val country: String? = null)

suspend fun ImmichApi.mapMarkers(): List<MapMarkerDto> = call(get("/map/markers"))

/* ---- Memories ---- */

@Serializable data class MemoryDataDto(val year: Int = 0)
@Serializable data class MemoryDto(val id: String, val type: String = "on_this_day", val data: MemoryDataDto = MemoryDataDto(), val assets: List<AssetDto> = emptyList(), val memoryAt: String? = null)

suspend fun ImmichApi.memories(forDay: LocalDate): List<MemoryDto> =
    call(authed(Request.Builder().url(url("/memories").toHttpUrl().newBuilder().addQueryParameter("for", forDay.toString()).build()).get()))

/* ---- Downloads ---- */

/**
 * Streams asset [id]'s original (Immich's edited rendition if there is one) into [dest] through
 * the shared client, reporting (bytes so far, total or -1). Cancellable between chunks; a
 * partial file is deleted on failure.
 */
suspend fun ImmichApi.downloadOriginal(id: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
    val res = client().newCall(authed(Request.Builder().url(originalUrl(id)).get())).await()
    try {
        res.use {
            if (!it.isSuccessful) throw ImmichApi.httpError(it.code, it.body?.string().orEmpty())
            val body = it.body ?: throw IOException("Empty download")
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
        dest
    } catch (e: Throwable) {
        dest.delete()
        throw e
    }
}

/* ---- Mapping ---- */

/** Turns a full asset (search, memories, map) into a grid cell. */
fun AssetDto.toTimelineAsset(): TimelineAsset {
    val exif = exifInfo
    val swap = exif?.orientation in setOf("5", "6", "7", "8")
    val w = (width ?: exif?.exifImageWidth?.let { if (swap) exif.exifImageHeight else it })?.toFloat()
    val h = (height ?: exif?.exifImageHeight?.let { if (swap) exif.exifImageWidth else it })?.toFloat()
    val created = fileCreatedAt?.let(TimeBucketAssetsDto::parseInstantMillis) ?: 0L
    val local = localDateTime?.let { s -> runCatching { LocalDateTime.parse(s.removeSuffix("Z").substringBefore('+')).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
    return TimelineAsset(
        id = id,
        ratio = if (w != null && h != null && w > 0 && h > 0) w / h else 1f,
        isImage = type != "VIDEO",
        isFavorite = isFavorite,
        isTrashed = isTrashed,
        thumbhash = thumbhash,
        durationMs = TimeBucketAssetsDto.parseDurationMs(duration),
        fileCreatedAt = created,
        localOffsetHours = if (local != null && created != 0L) Math.round((local - created) / 900_000.0) / 4.0 else 0.0,
        livePhotoVideoId = livePhotoVideoId,
        city = exif?.city,
        country = exif?.country,
        visibility = visibility?.let { v -> Visibility.entries.firstOrNull { it.wire == v } } ?: if (isArchived) Visibility.ARCHIVE else Visibility.TIMELINE,
    )
}

package com.roylet.photos.immich

import com.roylet.photos.domain.timeline.TimeBucket
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineSource
import com.roylet.photos.domain.timeline.Visibility
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.OffsetDateTime

/*
 * Timeline and asset-detail endpoints (Immich v3.4), as extensions on [ImmichApi].
 */

@Serializable data class TimeBucketDto(val timeBucket: String, val count: Int)

/** `GET /timeline/bucket`: one array per field, index-aligned. */
@Serializable
data class TimeBucketAssetsDto(
    val id: List<String> = emptyList(),
    val ratio: List<Double?> = emptyList(),
    val isImage: List<Boolean> = emptyList(),
    val isFavorite: List<Boolean> = emptyList(),
    val isTrashed: List<Boolean> = emptyList(),
    val thumbhash: List<String?> = emptyList(),
    /** Milliseconds on v3; older servers sent "H:MM:SS.ffffff" strings, so both are accepted. */
    val duration: List<JsonElement?> = emptyList(),
    val fileCreatedAt: List<String> = emptyList(),
    val localOffsetHours: List<Double?> = emptyList(),
    val livePhotoVideoId: List<String?> = emptyList(),
    val city: List<String?> = emptyList(),
    val country: List<String?> = emptyList(),
    val stack: List<List<String>?> = emptyList(),
    val visibility: List<String?> = emptyList(),
) {
    fun toAssets(): List<TimelineAsset> = id.indices.map { i ->
        TimelineAsset(
            id = id[i],
            ratio = ratio.getOrNull(i)?.toFloat()?.takeIf { it > 0f && it.isFinite() } ?: 1f,
            isImage = isImage.getOrNull(i) ?: true,
            isFavorite = isFavorite.getOrNull(i) ?: false,
            isTrashed = isTrashed.getOrNull(i) ?: false,
            thumbhash = thumbhash.getOrNull(i),
            durationMs = parseDurationMs(duration.getOrNull(i)),
            fileCreatedAt = fileCreatedAt.getOrNull(i)?.let(::parseInstantMillis) ?: 0L,
            localOffsetHours = localOffsetHours.getOrNull(i) ?: 0.0,
            livePhotoVideoId = livePhotoVideoId.getOrNull(i),
            city = city.getOrNull(i),
            country = country.getOrNull(i),
            stackCount = stack.getOrNull(i)?.getOrNull(1)?.toIntOrNull() ?: 0,
            visibility = visibility.getOrNull(i)?.let { v -> Visibility.entries.firstOrNull { it.wire == v } } ?: Visibility.TIMELINE,
        )
    }

    companion object {
        fun parseDurationMs(e: JsonElement?): Long? {
            if (e == null || e is JsonNull) return null
            val p = e as? JsonPrimitive ?: return null
            if (!p.isString) return p.longOrNull ?: p.doubleOrNull?.toLong()
            // "0:00:05.123456" (h:mm:ss.micro); a zero duration means a still image.
            val parts = p.content.split(":")
            if (parts.size != 3) return p.content.toLongOrNull()
            val h = parts[0].toLongOrNull() ?: return null
            val m = parts[1].toLongOrNull() ?: return null
            val s = parts[2].toDoubleOrNull() ?: return null
            return ((h * 3600 + m * 60) * 1000 + (s * 1000).toLong()).takeIf { it > 0 }
        }

        fun parseInstantMillis(s: String): Long? = runCatching { Instant.parse(s).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .getOrNull()
    }
}

@Serializable
data class ExifDto(
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val fNumber: Double? = null,
    val focalLength: Double? = null,
    val iso: Int? = null,
    val exposureTime: String? = null,
    val exifImageWidth: Int? = null,
    val exifImageHeight: Int? = null,
    val fileSizeInByte: Long? = null,
    val dateTimeOriginal: String? = null,
    val timeZone: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val description: String? = null,
    val orientation: String? = null,
)

/** `GET /assets/{id}` (the fields the viewer uses). */
@Serializable
data class AssetDto(
    val id: String,
    val type: String = "IMAGE",
    val originalFileName: String = "",
    val originalMimeType: String? = null,
    val fileCreatedAt: String? = null,
    val localDateTime: String? = null,
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val isEdited: Boolean = false,
    val visibility: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val duration: JsonElement? = null,
    val livePhotoVideoId: String? = null,
    val thumbhash: String? = null,
    val exifInfo: ExifDto? = null,
)

@Serializable private data class FavoriteBody(val ids: List<String>, val isFavorite: Boolean)

/** Immich's `AssetMediaSize` for `/assets/{id}/thumbnail`. */
enum class ThumbSize(val wire: String) { THUMBNAIL("thumbnail"), PREVIEW("preview"), FULLSIZE("fullsize") }

private fun ImmichApi.query(path: String, params: List<Pair<String, String>>): String =
    url(path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()

suspend fun ImmichApi.timeBuckets(filter: TimelineFilter): List<TimeBucketDto> =
    call(authed(okhttp3.Request.Builder().url(query("/timeline/buckets", filter.toQuery())).get()))

suspend fun ImmichApi.timeBucket(filter: TimelineFilter, timeBucket: String): TimeBucketAssetsDto =
    call(authed(okhttp3.Request.Builder().url(query("/timeline/bucket", filter.toQuery() + ("timeBucket" to timeBucket))).get()))

suspend fun ImmichApi.asset(id: String): AssetDto = call(get("/assets/$id"))

/** Bulk favourite toggle (`PUT /assets`). */
suspend fun ImmichApi.setFavorite(ids: List<String>, favorite: Boolean) = send(put("/assets", FavoriteBody(ids, favorite)))

/**
 * Image URLs. `edited=true` makes Immich return its own edited rendition when one exists and the
 * original otherwise, so it is always safe to pass.
 */
fun ImmichApi.thumbnailUrl(id: String, size: ThumbSize = ThumbSize.THUMBNAIL): String =
    "${url("/assets/$id/thumbnail")}?size=${size.wire}&edited=true"

fun ImmichApi.originalUrl(id: String): String = "${url("/assets/$id/original")}?edited=true"

fun ImmichApi.videoPlaybackUrl(id: String): String = url("/assets/$id/video/playback")

/** The production [TimelineSource]. [api] is resolved per call so a sign-in or URL switch applies immediately. */
class ImmichTimelineSource(private val api: () -> ImmichApi?) : TimelineSource {
    override suspend fun buckets(filter: TimelineFilter): List<TimeBucket> =
        requireApi().timeBuckets(filter).map { TimeBucket(it.timeBucket, it.count) }

    override suspend fun bucket(filter: TimelineFilter, key: String): List<TimelineAsset> =
        requireApi().timeBucket(filter, key).toAssets()

    private fun requireApi() = api() ?: throw ImmichException("Not signed in")
}

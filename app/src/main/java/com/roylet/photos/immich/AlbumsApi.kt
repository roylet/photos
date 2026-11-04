package com.roylet.photos.immich

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * Album endpoints (Immich v3.4), as extensions on [ImmichApi].
 */

@Serializable data class AlbumUserDto(val role: String = "editor", val user: UserMe)

@Serializable
data class AlbumDto(
    val id: String,
    val albumName: String = "",
    val description: String = "",
    val albumThumbnailAssetId: String? = null,
    val assetCount: Int = 0,
    val shared: Boolean = false,
    val hasSharedLink: Boolean = false,
    val albumUsers: List<AlbumUserDto> = emptyList(),
    val ownerId: String? = null,
    val owner: UserMe? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val lastModifiedAssetTimestamp: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val order: String? = null,
)

@Serializable data class CreateAlbumBody(val albumName: String, val assetIds: List<String>? = null, val description: String? = null)

@Serializable
data class UpdateAlbumBody(
    val albumName: String? = null,
    val description: String? = null,
    val albumThumbnailAssetId: String? = null,
)

@Serializable data class BulkIdsBody(val ids: List<String>)

@Serializable data class BulkIdResultDto(val id: String, val success: Boolean, val error: String? = null)

/** Every album the user owns or that is shared with them; with [assetId], only the ones containing that asset. */
suspend fun ImmichApi.albums(assetId: String? = null): List<AlbumDto> {
    val url = url("/albums").toHttpUrl().newBuilder().apply { if (assetId != null) addQueryParameter("assetId", assetId) }.build()
    return call(authed(Request.Builder().url(url).get()))
}

suspend fun ImmichApi.album(id: String): AlbumDto = call(get("/albums/$id"))

/** Creates an album, optionally already holding [assetIds] (one call, so "new album from selection" is atomic). */
suspend fun ImmichApi.createAlbum(name: String, assetIds: List<String> = emptyList(), description: String? = null): AlbumDto =
    call(post("/albums", CreateAlbumBody(name, assetIds.takeIf { it.isNotEmpty() }, description)))

suspend fun ImmichApi.updateAlbum(id: String, body: UpdateAlbumBody): AlbumDto =
    call(authed(Request.Builder().url(url("/albums/$id")).patch(ImmichApi.json.encodeToString(body).toRequestBodyJson())))

suspend fun ImmichApi.deleteAlbum(id: String) = send(authed(Request.Builder().url(url("/albums/$id")).delete()))

suspend fun ImmichApi.addAssetsToAlbum(id: String, assetIds: List<String>): List<BulkIdResultDto> =
    call(put("/albums/$id/assets", BulkIdsBody(assetIds)))

suspend fun ImmichApi.removeAssetsFromAlbum(id: String, assetIds: List<String>): List<BulkIdResultDto> =
    call(delete("/albums/$id/assets", BulkIdsBody(assetIds)))

internal fun String.toRequestBodyJson(): okhttp3.RequestBody = toRequestBody("application/json".toMediaType())

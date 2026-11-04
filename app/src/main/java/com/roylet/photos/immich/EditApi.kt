package com.roylet.photos.immich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/*
 * Endpoints the photo editor's open/save flow needs, as extensions on [ImmichApi] so the core
 * client stays small. Permissions: asset.read, asset.download, asset.upload, asset.copy,
 * asset.update (metadata), asset.delete (trash + restore) and stack.create — all already in
 * [ImmichApi.PERMISSIONS].
 */

/** The fields of `AssetResponseDto` the editor uses. */
@Serializable
data class EditAssetInfo(
    val id: String,
    val type: String = "IMAGE",
    val originalFileName: String = "photo.jpg",
    val originalMimeType: String? = null,
    val fileCreatedAt: String,
    val fileModifiedAt: String,
    val isEdited: Boolean = false,
    val isTrashed: Boolean = false,
    val checksum: String = "",
    val width: Int? = null,
    val height: Int? = null,
)

/** `PUT /assets/copy`. No defaults, so every flag is always sent explicitly. */
@Serializable
data class AssetCopyRequest(
    val sourceId: String,
    val targetId: String,
    val albums: Boolean,
    val favorite: Boolean,
    val sharedLinks: Boolean,
    val stack: Boolean,
    val sidecar: Boolean,
)

@Serializable data class StackCreateRequest(val assetIds: List<String>)
@Serializable data class StackCreated(val id: String, val primaryAssetId: String = "")
@Serializable data class AssetDeleteRequest(val ids: List<String>, val force: Boolean)
@Serializable data class BulkIds(val ids: List<String>)
@Serializable data class TrashCount(val count: Int = 0)
@Serializable data class MetadataItem(val key: String, val value: JsonObject)
@Serializable data class MetadataUpsert(val items: List<MetadataItem>)
@Serializable data class MetadataEntry(val key: String, val value: JsonObject, val updatedAt: String = "")

suspend fun ImmichApi.assetInfo(id: String): EditAssetInfo = call(get("/assets/${seg(id)}"))

/**
 * Streams `GET /assets/{id}/original` into [dest] (written to a temp file and renamed only once
 * complete). [edited] asks for Immich's own edited rendition (`asset.isEdited`).
 * [onProgress] gets (bytes so far, total or -1 when the server sends no length).
 */
suspend fun ImmichApi.downloadOriginal(
    id: String,
    dest: File,
    edited: Boolean = false,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
): File = withContext(Dispatchers.IO) {
    val path = "/assets/${seg(id)}/original" + if (edited) "?edited=true" else ""
    client().newCall(get(path)).await().use { res ->
        if (!res.isSuccessful) throw ImmichApi.httpError(res.code, res.body?.string().orEmpty())
        val body = res.body ?: throw IOException("Empty response")
        val total = body.contentLength()
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    onProgress(0, total)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 256 * 1024 || done == total) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                    if (total >= 0 && done != total) throw IOException("Download ended early ($done of $total bytes)")
                    onProgress(done, if (total >= 0) total else done)
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Couldn't save the download")
        } finally {
            tmp.delete()
        }
        dest
    }
}

suspend fun ImmichApi.copyAsset(request: AssetCopyRequest) = send(put("/assets/copy", request))

suspend fun ImmichApi.createStack(assetIds: List<String>): StackCreated =
    call(post("/stacks", StackCreateRequest(assetIds)))

// `DELETE /assets` (deleteAssets) lives in LibraryApi.kt; force=false moves assets to Trash.

suspend fun ImmichApi.restoreFromTrash(ids: List<String>): TrashCount =
    call(post("/trash/restore/assets", BulkIds(ids)))

/** `PUT /assets/{id}/metadata` with one key. Needs `asset.update`. */
suspend fun ImmichApi.putMetadata(id: String, key: String, value: JsonObject) {
    call<List<MetadataEntry>>(put("/assets/${seg(id)}/metadata", MetadataUpsert(listOf(MetadataItem(key, value)))))
}

/** `GET /assets/{id}/metadata/{key}`, or null when the asset has no such key. */
suspend fun ImmichApi.getMetadata(id: String, key: String): JsonObject? = try {
    call<MetadataEntry>(get("/assets/${seg(id)}/metadata/${seg(key)}")).value
} catch (e: ImmichException) {
    if (e.status == 404 || e.status == 400) null else throw e
}

private fun seg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

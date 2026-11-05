package com.roylet.photos.editor.save

import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.immich.EditAssetInfo
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.assetInfo
import com.roylet.photos.immich.downloadOriginal
import com.roylet.photos.immich.getMetadata
import com.roylet.photos.local.AppDao
import com.roylet.photos.local.LocalEditEntity
import com.roylet.photos.local.MediaDao
import com.roylet.photos.local.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.InputStream
import java.time.Instant
import kotlin.coroutines.coroutineContext

/*
 * Where the editors get the original from (docs/SOURCES.md §9): Immich (details, the saved-edit
 * metadata, a download) or this phone (MediaStore index, the app's edit table, a copy of the
 * content URI). Both editors and their save jobs use it.
 */
interface EditSource {
    /** The item's details for editing. */
    suspend fun info(id: String): EditAssetInfo

    /** The saved-edit value under [key] (RecipeMetadata.KEY or the video key) when this app made [id]. */
    suspend fun metadata(id: String, key: String): JsonObject?

    /** Puts [info]'s original at [dest] (via a `.part` file); [onProgress] gets (bytes, total or -1). */
    suspend fun obtain(info: EditAssetInfo, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): File
}

/** Immich: `GET /assets/{id}`, asset metadata and the original download (as before 3a). */
class ImmichEditSource(private val api: () -> ImmichApi?) : EditSource {
    private fun api() = api.invoke() ?: throw ImmichException("Sign in to Immich to edit this.")
    override suspend fun info(id: String): EditAssetInfo = api().assetInfo(id)
    override suspend fun metadata(id: String, key: String): JsonObject? = runCatching { api().getMetadata(id, key) }.getOrNull()
    override suspend fun obtain(info: EditAssetInfo, dest: File, onProgress: (Long, Long) -> Unit): File =
        api().downloadOriginal(info.id, dest, edited = info.isEdited, onProgress = onProgress)
}

/**
 * This phone: details from the MediaStore index, the saved edit from [AppDao] (`local_edit`),
 * and the original copied out of its content URI (the editors' decoders and exporters read files).
 */
class LocalEditSource(
    private val dao: MediaDao,
    private val appDao: AppDao,
    /** Opens an item's original bytes (with its location, when allowed). */
    private val open: (MediaEntity) -> InputStream?,
) : EditSource {
    private suspend fun entity(id: String): MediaEntity {
        val key = AssetRef.parse(id)?.mediaStoreId ?: throw ImmichException("This isn't a photo on this phone.")
        return dao.byId(key) ?: throw ImmichException("This item is no longer on this phone.")
    }

    override suspend fun info(id: String): EditAssetInfo = entity(id).toEditInfo()

    override suspend fun metadata(id: String, key: String): JsonObject? {
        val n = AssetRef.parse(id)?.mediaStoreId ?: return null
        val row = appDao.edit(n) ?: return null
        val wanted = if (key == RecipeMetadata.KEY) LocalEditEntity.PHOTO else LocalEditEntity.VIDEO
        if (row.kind != wanted) return null
        return runCatching { ImmichApi.json.parseToJsonElement(row.json).jsonObject }.getOrNull()
    }

    override suspend fun obtain(info: EditAssetInfo, dest: File, onProgress: (Long, Long) -> Unit): File {
        val e = entity(info.id)
        val total = e.sizeBytes ?: -1L
        val part = File(dest.parentFile, dest.name + ".part")
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val input = open(e) ?: throw ImmichException("This item can't be read.")
            input.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
        }
        return dest
    }

    companion object {
        /** The index row as the editors' [EditAssetInfo]; the generation stands in for the checksum (it changes with every edit). */
        fun MediaEntity.toEditInfo() = EditAssetInfo(
            id = AssetRef.local(id).id,
            type = if (isVideo) "VIDEO" else "IMAGE",
            originalFileName = displayName ?: if (isVideo) "video.mp4" else "photo.jpg",
            originalMimeType = mimeType,
            fileCreatedAt = Instant.ofEpochMilli(takenAt).toString(),
            fileModifiedAt = Instant.ofEpochMilli(takenAt).toString(),
            checksum = "g$generation",
            width = width,
            height = height,
        )
    }
}

/** Each id to its own source: `local:` ids to the phone, the rest to Immich. */
class RoutingEditSource(private val immich: EditSource, private val local: EditSource) : EditSource {
    private fun of(id: String) = if (AssetRef.isLocal(id)) local else immich
    override suspend fun info(id: String) = of(id).info(id)
    override suspend fun metadata(id: String, key: String) = of(id).metadata(id, key)
    override suspend fun obtain(info: EditAssetInfo, dest: File, onProgress: (Long, Long) -> Unit) = of(info.id).obtain(info, dest, onProgress)
}

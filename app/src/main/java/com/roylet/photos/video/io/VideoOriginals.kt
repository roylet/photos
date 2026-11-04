package com.roylet.photos.video.io

import android.os.StatFs
import com.roylet.photos.immich.EditAssetInfo
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.downloadOriginal
import com.roylet.photos.util.Format
import java.io.File

/**
 * Originals downloaded for the video editor, under [dir] (normally `cacheDir/video-edit`).
 * Videos can be gigabytes, so only the [keep] most recently used originals are kept, and a
 * download refuses to start without room for it plus [headroomBytes].
 *
 * Downloads go through [downloadOriginal] (the shared OkHttp client, so the API key and LAN/public
 * switching apply) into a `.part` file that is renamed only when complete.
 */
class VideoOriginals(
    private val dir: File,
    private val keep: Int = 2,
    private val headroomBytes: Long = 300L * 1024 * 1024,
    /** Free bytes at a path; overridable for tests. */
    private val freeBytes: (File) -> Long = { f -> runCatching { StatFs(f.path).availableBytes }.getOrDefault(Long.MAX_VALUE) },
) {
    /** Where [info]'s original lives once downloaded. The checksum in the name catches a replaced asset. */
    fun fileFor(info: EditAssetInfo): File =
        File(File(dir, "${info.id}-${Integer.toHexString(info.checksum.hashCode())}"), safeName(info.originalFileName))

    /** The cached original, or null when it isn't downloaded (completely) yet. */
    fun cached(info: EditAssetInfo): File? = fileFor(info).takeIf { it.isFile }

    /**
     * Returns the local original, downloading it first when needed. [onProgress] gets
     * (bytes, total or -1). Throws [ImmichException] for a full disk or a failed download.
     */
    suspend fun obtain(api: ImmichApi, info: EditAssetInfo, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val file = fileFor(info)
        val folder = file.parentFile!!
        if (file.isFile) {
            folder.setLastModified(System.currentTimeMillis())
            onProgress(file.length(), file.length())
            return file
        }
        prune(except = folder)
        folder.mkdirs()
        folder.setLastModified(System.currentTimeMillis())
        var checked = false
        return api.downloadOriginal(info.id, file, edited = info.isEdited) { done, total ->
            if (!checked && total > 0) {
                checked = true
                val free = freeBytes(folder)
                if (free < total + headroomBytes) {
                    throw ImmichException(
                        "Not enough free space to edit this video: it needs ${Format.bytes(total + headroomBytes)}, " +
                            "and ${Format.bytes(free)} is free.",
                    )
                }
            }
            onProgress(done, total)
        }
    }

    /** Deletes all but the [keep] most recently used originals (never [except]). */
    fun prune(except: File? = null) {
        val folders = dir.listFiles { f -> f.isDirectory && f != except }?.sortedByDescending { it.lastModified() } ?: return
        val budget = (keep - (if (except != null) 1 else 0)).coerceAtLeast(0)
        folders.drop(budget).forEach { it.deleteRecursively() }
    }

    /** Free bytes where outputs are written, for the pre-export space check. */
    fun freeSpace(): Long = freeBytes(dir.apply { mkdirs() })

    companion object {
        fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "video.mp4" }
    }
}

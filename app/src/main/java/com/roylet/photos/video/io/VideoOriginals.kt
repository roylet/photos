package com.roylet.photos.video.io

import com.roylet.photos.editor.save.EditSource
import com.roylet.photos.editor.save.ImmichEditSource

import android.os.StatFs
import com.roylet.photos.immich.EditAssetInfo
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Originals downloaded for the video editor, under [dir] (normally `cacheDir/video-edit`).
 * Videos can be gigabytes, so only the [keep] most recently used originals are kept, and a
 * download refuses to start without room for it plus [headroomBytes].
 *
 * Originals come from an [EditSource]: an Immich download (the shared OkHttp client, so the API
 * key and LAN/public switching apply) or a copy of a phone video, into a `.part` file that is
 * renamed only when complete. Two callers asking for
 * the same original (the editor and a queued save) share one download.
 *
 * Pruning never deletes an original that is still needed: one [hold]s (the open editor, a running
 * save, a download in progress) or one a queued save will read ([pendingSourceIds], asset ids).
 */
class VideoOriginals(
    private val dir: File,
    private val keep: Int = 2,
    private val headroomBytes: Long = 300L * 1024 * 1024,
    /** Free bytes at a path; overridable for tests. */
    private val freeBytes: (File) -> Long = { f -> runCatching { StatFs(f.path).availableBytes }.getOrDefault(Long.MAX_VALUE) },
    /** Source asset ids of saves that are queued or haven't finished exporting yet. */
    private val pendingSourceIds: () -> Set<String> = { emptySet() },
) {
    private val holds = HashMap<File, Int>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Keeps [file]'s original from being pruned until the returned handle is closed (ref-counted,
     * so the editor and a save can hold the same one). Closing twice is harmless.
     */
    fun hold(file: File): Closeable {
        val folder = file.absoluteFile.parentFile ?: return Closeable {}
        synchronized(holds) { holds[folder] = (holds[folder] ?: 0) + 1 }
        var open = true
        return Closeable {
            synchronized(holds) {
                if (!open) return@Closeable
                open = false
                val n = (holds[folder] ?: 1) - 1
                if (n <= 0) holds.remove(folder) else holds[folder] = n
            }
        }
    }

    private fun isHeld(folder: File): Boolean = synchronized(holds) { holds.containsKey(folder.absoluteFile) }

    /** Where [info]'s original lives once downloaded. The checksum in the name catches a replaced asset. */
    fun fileFor(info: EditAssetInfo): File =
        File(File(dir, "${folderKey(info.id)}-${Integer.toHexString(info.checksum.hashCode())}"), safeName(info.originalFileName))

    /** The cached original, or null when it isn't downloaded (completely) yet. */
    fun cached(info: EditAssetInfo): File? = fileFor(info).takeIf { it.isFile }

    /**
     * Returns the local original, downloading it first when needed. [onProgress] gets
     * (bytes, total or -1). Throws [ImmichException] for a full disk or a failed download.
     */
    suspend fun obtain(api: ImmichApi, info: EditAssetInfo, onProgress: (Long, Long) -> Unit = { _, _ -> }): File =
        obtain(ImmichEditSource { api }, info, onProgress)

    /** The same from any [EditSource]: an Immich download, or a copy of a phone video. */
    suspend fun obtain(source: EditSource, info: EditAssetInfo, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val file = fileFor(info)
        val folder = file.parentFile!!
        return hold(file).use { locks.getOrPut(file.path) { Mutex() }.withLock { download(source, info, file, folder, onProgress) } }
    }

    private suspend fun download(source: EditSource, info: EditAssetInfo, file: File, folder: File, onProgress: (Long, Long) -> Unit): File {
        if (file.isFile) {
            folder.setLastModified(System.currentTimeMillis())
            onProgress(file.length(), file.length())
            return file
        }
        withContext(Dispatchers.IO) {
            prune(except = folder)
            folder.mkdirs()
            folder.setLastModified(System.currentTimeMillis())
        }
        var checked = false
        return source.obtain(info, file) { done, total ->
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

    /** Deletes all but the [keep] most recently used originals (never [except], a held one or one a queued save needs). */
    fun prune(except: File? = null) {
        val pending = runCatching(pendingSourceIds).getOrDefault(emptySet())
        val folders = dir.listFiles { f ->
            f.isDirectory && f != except && !isHeld(f) && pending.none { id -> f.name.startsWith("${folderKey(id)}-") }
        }?.sortedByDescending { it.lastModified() } ?: return
        val budget = (keep - (if (except != null) 1 else 0)).coerceAtLeast(0)
        folders.drop(budget).forEach { it.deleteRecursively() }
    }

    /** Free bytes where outputs are written, for the pre-export space check. */
    fun freeSpace(): Long = freeBytes(dir.apply { mkdirs() })

    companion object {
        /** Folder-safe form of an asset id (`local:5` → `local_5`). */
        fun folderKey(id: String) = id.replace(':', '_')

        fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "video.mp4" }
    }
}

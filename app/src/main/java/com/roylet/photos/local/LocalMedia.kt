package com.roylet.photos.local

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ContentMetadata
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Dimension
import coil3.toUri
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.ui.gallery.MediaUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What [LocalMediaUrls] needs to know about a phone asset beyond its id: image or video (which
 * MediaStore collection), its generation (part of the cache key, so an edited photo isn't served
 * from cache) and, for Android 10's own trash, the private file. Filled whenever the index hands
 * assets to the UI (timeline months, folders, search), so the grid never waits on a lookup.
 */
object LocalAssetInfo {
    data class Info(val isVideo: Boolean, val generation: Long, val file: String? = null, val mimeType: String? = null)

    private val map = ConcurrentHashMap<Long, Info>()

    fun put(id: Long, info: Info) { map[id] = info }
    operator fun get(id: Long): Info? = map[id]
    fun clear() = map.clear()

    fun remember(e: MediaEntity) = put(e.id, Info(e.isVideo, e.generation, mimeType = e.mimeType))
}

/** A phone asset for the grid: `local:<id>`, ratio after orientation, favourite/trash flags, expiry. */
fun MediaEntity.toAsset(): TimelineAsset {
    LocalAssetInfo.remember(this)
    return TimelineAsset(
        id = AssetRef.local(id).id,
        ratio = ratio,
        isImage = !isVideo,
        isFavorite = favorite,
        isTrashed = trashed,
        durationMs = if (isVideo) (durationMs ?: 0) else null,
        fileCreatedAt = takenAt,
        localOffsetHours = offsetMinutes / 60.0,
        expiresAt = expiresAt,
    )
}

/** Android 10's trashed copies use negative ids (`local:-<rowId>`) so they can't collide with MediaStore's. */
fun legacyTrashId(rowId: Long): Long = -rowId

fun LegacyTrashEntity.toAsset(): TimelineAsset {
    val id = legacyTrashId(rowId)
    LocalAssetInfo.put(id, LocalAssetInfo.Info(isVideo, trashedAt, file = path, mimeType = mimeType))
    return TimelineAsset(
        id = AssetRef.local(id).id,
        ratio = displayRatio(width, height, orientation),
        isImage = !isVideo,
        isFavorite = favorite,
        isTrashed = true,
        durationMs = if (isVideo) (durationMs ?: 0) else null,
        fileCreatedAt = takenAt,
        localOffsetHours = offsetMinutes / 60.0,
        expiresAt = expiresAt,
    )
}

/**
 * URLs for phone assets. [SourceMediaUrls] hands these the bare MediaStore id.
 * - thumbnail: `localmedia://thumb/<id>?v=<0|1>&g=<generation>`, served by [LocalThumbnailFetcher]
 *   through `ContentResolver.loadThumbnail` (MediaStore keeps those cached on disk);
 * - preview: the same for videos; images decode the original, sampled to the requested size;
 * - fullsize / original / video: the plain `content://` URI (Coil, telephoto and ExoPlayer read it).
 * The generation in the URL is the memory-cache key, so an edit shows up at once.
 */
object LocalMediaUrls : MediaUrls {
    const val SCHEME = "localmedia"

    private fun info(key: String) = key.toLongOrNull()?.let { LocalAssetInfo[it] }

    private fun local(key: String, mode: String): String {
        val id = key.toLongOrNull() ?: return "$SCHEME://$mode/0"
        val i = LocalAssetInfo[id]
        i?.file?.let { return Uri.fromFile(File(it)).toString() }
        return "$SCHEME://$mode/$id?v=${if (i?.isVideo == true) 1 else 0}&g=${i?.generation ?: 0}"
    }

    override fun thumbnail(id: String) = local(id, "thumb")
    override fun preview(id: String) = local(id, if (info(id)?.isVideo == true) "thumb" else "full")
    override fun fullsize(id: String) = original(id)
    override fun original(id: String): String {
        val n = id.toLongOrNull() ?: return ""
        val i = LocalAssetInfo[n]
        i?.file?.let { return Uri.fromFile(File(it)).toString() }
        return mediaUriString(n, i?.isVideo == true)
    }
    override fun video(id: String) = original(id)

    /** Parses one of our URLs: (mode, id, isVideo), or null. */
    fun parse(url: String): Triple<String, Long, Boolean>? {
        if (!url.startsWith("$SCHEME://")) return null
        val rest = url.removePrefix("$SCHEME://")
        val mode = rest.substringBefore('/')
        val id = rest.substringAfter('/').substringBefore('?').toLongOrNull() ?: return null
        val video = rest.substringAfter('?', "").split('&').any { it == "v=1" }
        return Triple(mode, id, video)
    }
}

/**
 * Coil fetcher for [LocalMediaUrls]' `localmedia://` URLs. Thumbnails come from
 * `ContentResolver.loadThumbnail` (fast: MediaStore caches them), cancelled through a
 * CancellationSignal when the cell scrolls away; `full` hands the content URI to Coil's own
 * content fetcher so the original is decoded with sampling and EXIF rotation.
 */
class LocalThumbnailFetcher(
    private val resolver: ContentResolver,
    private val mode: String,
    private val id: Long,
    private val isVideo: Boolean,
    private val options: Options,
) : Fetcher {
    // The descriptor is closed with the ImageSource once Coil has decoded it.
    @SuppressLint("Recycle")
    override suspend fun fetch(): FetchResult? {
        val uri = mediaUri(id, isVideo)
        if (mode == "full") {
            // What Coil's own content fetcher does: the decoder samples to the requested size and applies EXIF rotation.
            val afd = withContext(Dispatchers.IO) { resolver.openAssetFileDescriptor(uri, "r") } ?: return null
            return SourceFetchResult(
                ImageSource(afd.createInputStream().source().buffer(), options.fileSystem, ContentMetadata(uri.toString().toUri(), afd)),
                resolver.getType(uri),
                DataSource.DISK,
            )
        }
        val side = thumbnailSide(options.size.width, options.size.height)
        val bitmap = suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            Dispatchers.IO.asExecutor().execute {
                try {
                    cont.resume(resolver.loadThumbnail(uri, Size(side, side), signal))
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
        return ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val resolver: ContentResolver) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != LocalMediaUrls.SCHEME) return null
            val (mode, id, video) = LocalMediaUrls.parse(data.toString()) ?: return null
            return LocalThumbnailFetcher(resolver, mode, id, video, options)
        }
    }

    companion object {
        /** loadThumbnail's square bound: the larger requested side, 512 when the size is open. */
        fun thumbnailSide(width: Dimension, height: Dimension): Int {
            val w = (width as? Dimension.Pixels)?.px ?: 0
            val h = (height as? Dimension.Pixels)?.px ?: 0
            return maxOf(w, h).takeIf { it > 0 }?.coerceAtMost(2048) ?: 512
        }
    }
}

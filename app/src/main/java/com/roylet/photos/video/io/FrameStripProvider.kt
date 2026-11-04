package com.roylet.photos.video.io

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.asImageBitmap
import com.roylet.photos.ui.viewer.VideoFrameStrip
import com.roylet.photos.ui.viewer.extractFrames
import com.roylet.photos.ui.viewer.extractionOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File
import kotlin.coroutines.coroutineContext

/** Decodes single frames from one video. Not thread-safe; one per extraction. */
interface FrameSource : Closeable {
    /** The frame at [timeMs], scaled to fit [maxPx]; [exact] decodes the exact frame, else the nearest keyframe (fast). */
    fun frameAt(timeMs: Long, maxPx: Int, exact: Boolean): Bitmap?
}

/** [FrameSource] over a local file with [MediaMetadataRetriever] (hardware decoders). */
class RetrieverFrameSource(file: File) : FrameSource {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(file.path) }

    override fun frameAt(timeMs: Long, maxPx: Int, exact: Boolean): Bitmap? = retriever.getScaledFrameAtTime(
        timeMs * 1000,
        if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        maxPx, maxPx,
    )

    override fun close() { runCatching { retriever.release() } }
}

/**
 * Thumbnails for the trim strip, from the LOCAL original (fast, exact, no network). Frames are
 * kept in a byte-bounded LRU keyed by file, time and size, so scrolling/zooming the strip or
 * reopening the editor doesn't decode again. Extraction is cancellable between frames (one 4K
 * frame decode can't be interrupted, ~50–150 ms).
 *
 * Before the download finishes, [fillRemote] fills the same kind of strip over HTTP Range
 * requests with the viewer's extractor.
 */
class FrameStripProvider(
    private val open: (File) -> FrameSource = { RetrieverFrameSource(it) },
    cacheBytes: Int = 24 * 1024 * 1024,
) {
    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount.coerceAtLeast(1)
    }

    private fun key(file: File, timeMs: Long, maxPx: Int, exact: Boolean) =
        "${file.path}|${file.lastModified()}|$timeMs|$maxPx|${if (exact) "x" else "k"}"

    fun cached(file: File, timeMs: Long, maxPx: Int, exact: Boolean = false): Bitmap? = cache.get(key(file, timeMs, maxPx, exact))

    /**
     * Extracts frames at [timesMs] (coarse to fine, so a half-loaded strip is spread across the
     * clip) and hands each to [onFrame] on the main thread, cached ones first and immediately.
     * Failures leave gaps; cancellation stops after the current frame.
     */
    suspend fun load(
        file: File,
        timesMs: LongArray,
        maxPx: Int = DEFAULT_PX,
        exact: Boolean = false,
        onFrame: (index: Int, frame: Bitmap) -> Unit,
    ) {
        val missing = ArrayList<Int>()
        for (i in extractionOrder(timesMs.size)) {
            val hit = cached(file, timesMs[i], maxPx, exact)
            if (hit != null) withContext(Dispatchers.Main) { onFrame(i, hit) } else missing += i
        }
        if (missing.isEmpty()) return
        withContext(Dispatchers.IO) {
            val source = try {
                open(file)
            } catch (e: Exception) {
                return@withContext
            }
            try {
                for (i in missing) {
                    coroutineContext.ensureActive()
                    val bmp = runCatching { source.frameAt(timesMs[i], maxPx, exact) }.getOrNull() ?: continue
                    cache.put(key(file, timesMs[i], maxPx, exact), bmp)
                    coroutineContext.ensureActive()
                    withContext(Dispatchers.Main) { onFrame(i, bmp) }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                source.close()
            }
        }
    }

    /** One exact frame (e.g. under a trim handle), cached. */
    suspend fun frame(file: File, timeMs: Long, maxPx: Int = 480, exact: Boolean = true): Bitmap? {
        cached(file, timeMs, maxPx, exact)?.let { return it }
        return withContext(Dispatchers.IO) {
            val source = runCatching { open(file) }.getOrNull() ?: return@withContext null
            try {
                runCatching { source.frameAt(timeMs, maxPx, exact) }.getOrNull()?.also { cache.put(key(file, timeMs, maxPx, exact), it) }
            } finally {
                source.close()
            }
        }
    }

    /** Fills a Compose [VideoFrameStrip] (the viewer's strip type) from the local [file]. */
    suspend fun fill(strip: VideoFrameStrip, file: File, maxPx: Int = DEFAULT_PX) {
        load(file, strip.timesMs, maxPx) { i, bmp -> if (strip.frames[i] == null) strip.frames[i] = bmp.asImageBitmap() }
    }

    /** Before the original is downloaded: fills [strip] over HTTP Range requests (viewer's extractor). */
    suspend fun fillRemote(strip: VideoFrameStrip, client: OkHttpClient, url: String, maxPx: Int = DEFAULT_PX) =
        extractFrames(client, url, strip, maxPx)

    fun clear() = cache.evictAll()

    companion object {
        const val DEFAULT_PX = 160

        /** [count] sample times centred in equal slices of [durationMs]. */
        fun sampleTimes(durationMs: Long, count: Int): LongArray =
            if (durationMs <= 0 || count <= 0) LongArray(0) else LongArray(count) { i -> ((i + 0.5) * durationMs / count).toLong() }
    }
}

package com.roylet.photos.ui.viewer

import android.graphics.Bitmap
import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import com.roylet.photos.ui.gallery.LocalMediaHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * A [MediaDataSource] that reads [url] with HTTP Range requests through [client], so a
 * [MediaMetadataRetriever] can pull a handful of frames out of a remote video without
 * downloading it. Going through the app's shared client keeps the LAN/public rewrite, the
 * API-key interceptor and its guard in charge of authentication: nothing here adds a key.
 *
 * Reads are served from [chunkSize]-aligned blocks, the last [maxChunks] of which are kept
 * (the retriever makes many small reads around the same spots: the header, the index, a
 * keyframe). A read never crosses a block, so it may return fewer bytes than asked; that is
 * allowed by the [MediaDataSource] contract. Servers that ignore Range (200 instead of 206)
 * still work, by skipping to the block in the full body.
 */
class RangeMediaDataSource(
    private val client: OkHttpClient,
    private val url: String,
    private val chunkSize: Int = 256 * 1024,
    private val maxChunks: Int = 6,
) : MediaDataSource() {
    private val lock = Any()
    private val chunks = object : LinkedHashMap<Long, ByteArray>(maxChunks + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > maxChunks
    }
    @Volatile private var closed = false
    @Volatile private var call: Call? = null
    /** Total length once a response has told us; -1 until then (or if the server never says). */
    @Volatile var knownSize: Long = -1
        private set
    /** Number of HTTP requests made, for tests. */
    @Volatile var requests: Int = 0
        private set

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) throw IOException("closed")
        if (size <= 0) return 0
        if (position < 0) throw IOException("negative position $position")
        if (knownSize in 0..position) return -1
        val start = position / chunkSize * chunkSize
        val block = synchronized(lock) { chunks[start] ?: fetch(start).also { chunks[start] = it } }
        val at = (position - start).toInt()
        if (at >= block.size) return -1
        val n = minOf(size, block.size - at)
        System.arraycopy(block, at, buffer, offset, n)
        return n
    }

    override fun getSize(): Long {
        if (closed) throw IOException("closed")
        if (knownSize < 0) synchronized(lock) { if (knownSize < 0 && chunks[0L] == null) chunks[0L] = fetch(0L) }
        return knownSize
    }

    /** Stops the source: an in-flight request is cancelled and later reads fail, so a blocked retriever returns. */
    override fun close() {
        closed = true
        call?.cancel()
    }

    private fun fetch(start: Long): ByteArray {
        val end = start + chunkSize - 1
        val req = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
        val c = client.newCall(req)
        call = c
        requests++
        if (closed) c.cancel()
        c.execute().use { resp ->
            when (resp.code) {
                416 -> {
                    contentRangeTotal(resp.header("Content-Range"))?.let { knownSize = it }
                    return ByteArray(0)
                }
                206 -> {
                    contentRangeTotal(resp.header("Content-Range"))?.let { knownSize = it }
                    val body = resp.body ?: return ByteArray(0)
                    return readUpTo(body.byteStream(), chunkSize)
                }
                200 -> {
                    val body = resp.body ?: return ByteArray(0)
                    body.contentLength().takeIf { it >= 0 }?.let { knownSize = it }
                    val stream = body.byteStream()
                    var skipped = 0L
                    while (skipped < start) {
                        val s = stream.skip(start - skipped)
                        if (s <= 0) { if (stream.read() < 0) return ByteArray(0) else skipped++ } else skipped += s
                    }
                    return readUpTo(stream, chunkSize)
                }
                else -> throw IOException("HTTP ${resp.code} for range $start-$end")
            }
        }
    }

    private fun readUpTo(stream: java.io.InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val r = stream.read(buf, n, max - n)
            if (r < 0) break
            n += r
        }
        return if (n == max) buf else buf.copyOf(n)
    }

    companion object {
        /** The total length from a Content-Range like "bytes 0-1023/4096"; null when absent or unknown ("*"). */
        internal fun contentRangeTotal(header: String?): Long? =
            header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
    }
}

/**
 * The thumbnails along the scrub bar for one video: [timesMs] are the sample points, [frames]
 * fills in (on the main thread) as extraction proceeds.
 */
@Stable
class VideoFrameStrip(val timesMs: LongArray) {
    val frames = mutableStateListOf<ImageBitmap?>().apply { repeat(timesMs.size) { add(null) } }
    val count get() = timesMs.size
    val complete get() = frames.all { it != null }

    /** The loaded frame nearest to [positionMs], if any: what the scrub bubble shows. */
    fun nearest(positionMs: Long, durationMs: Long): ImageBitmap? {
        val i = nearestLoaded(stripFrameIndex(positionMs, durationMs, count), count) { frames[it] != null }
        return if (i >= 0) frames[i] else null
    }
}

/**
 * Frame strips by asset id. A strip is ~10 frames of ≤[FRAME_MAX_PX] px, a few hundred KB, so
 * the last 16 videos cost a few MB at most.
 */
object VideoFrameCache {
    const val FRAME_MAX_PX = 200
    private val cache = LruCache<String, VideoFrameStrip>(16)

    fun get(key: String): VideoFrameStrip? = cache.get(key)
    fun put(key: String, strip: VideoFrameStrip) { cache.put(key, strip) }

    /** The cached strip for [key], or a new empty one sampled across [durationMs]; null without a duration. */
    fun obtain(key: String, durationMs: Long): VideoFrameStrip? {
        cache.get(key)?.let { return it }
        if (durationMs <= 0) return null
        return VideoFrameStrip(stripFrameTimesMs(durationMs)).also { cache.put(key, it) }
    }

    fun clear() = cache.evictAll()
}

/**
 * Fills [strip]'s missing frames from [url], coarse to fine, off the main thread. Cancelling the
 * calling coroutine closes the data source, which unblocks the retriever; the retriever is always
 * released. Failures just leave frames empty: the live video frame is the real preview anyway.
 */
suspend fun extractFrames(client: OkHttpClient, url: String, strip: VideoFrameStrip, maxPx: Int = VideoFrameCache.FRAME_MAX_PX) =
    extractFrames(null, client, url, strip, maxPx)

/**
 * [extractFrames] for any URL: `content://` (a phone video) is read by the retriever straight from
 * the ContentResolver with [context]; http(s) goes through [client] with Range requests.
 */
suspend fun extractFrames(context: Context?, client: OkHttpClient?, url: String, strip: VideoFrameStrip, maxPx: Int = VideoFrameCache.FRAME_MAX_PX) {
    if (strip.complete) return
    val local = url.startsWith("content://") || url.startsWith("file://")
    if (local && context == null) return
    if (!local && client == null) return
    val missing = withContext(Dispatchers.Main) { extractionOrder(strip.count).filter { strip.frames[it] == null } }
    withContext(Dispatchers.IO) {
        val source = if (local) null else RangeMediaDataSource(client!!, url)
        val unhook = coroutineContext.job.invokeOnCompletion { source?.close() }
        val retriever = MediaMetadataRetriever()
        try {
            if (source != null) retriever.setDataSource(source) else retriever.setDataSource(context, Uri.parse(url))
            for (i in missing) {
                ensureActive()
                val bmp: Bitmap = retriever.getScaledFrameAtTime(
                    strip.timesMs[i] * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxPx, maxPx,
                ) ?: continue
                ensureActive()
                val img = bmp.asImageBitmap().also { it.prepareToDraw() }
                withContext(Dispatchers.Main) { strip.frames[i] = img }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Unsupported container, network gone, source closed: keep whatever was extracted.
        } finally {
            unhook.dispose()
            runCatching { retriever.release() }
            source?.close()
        }
    }
}

/**
 * The frame strip for [key], extracting it in the background once [active] (the video started
 * playing or the bar was touched). Extraction stops when [active] turns false or this leaves
 * composition (e.g. the page is swiped away). Null until the duration is known.
 */
@Composable
fun rememberVideoFrames(key: String, url: String?, durationMs: Long, active: Boolean): VideoFrameStrip? {
    val strip = remember(key, durationMs > 0) { VideoFrameCache.obtain(key, durationMs) }
    val client = LocalMediaHttpClient.current
    val context = LocalContext.current.applicationContext
    val inspection = LocalInspectionMode.current
    if (strip != null && url != null && !inspection) {
        LaunchedEffect(strip, url, active) { if (active) extractFrames(context, client, url, strip) }
    }
    return strip
}

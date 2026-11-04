package com.roylet.photos.ui.viewer

import android.content.Context
import androidx.compose.ui.unit.IntSize
import coil3.request.ImageRequest
import com.roylet.photos.ui.gallery.MediaUrls
import kotlin.math.roundToInt

/**
 * Pixel size an image of [ratio] is shown at when fitted inside a [maxW]×[maxH] page. The page
 * and the prefetcher both use this, so a prefetched bitmap has the same size and memory-cache key
 * as the one the page asks for, and the page gets a cache hit.
 */
internal fun fitPx(ratio: Float, maxW: Int, maxH: Int): IntSize {
    val r = if (ratio > 0f && ratio.isFinite()) ratio else 1f
    val w = maxW.coerceAtLeast(1)
    val h = maxH.coerceAtLeast(1)
    return if (w.toFloat() / h > r) IntSize((h * r).roundToInt().coerceAtLeast(1), h)
    else IntSize(w, (w / r).roundToInt().coerceAtLeast(1))
}

/**
 * The viewer's `preview` request for asset [id] at [size]. It is keyed on the plain URL, the way
 * the grid keys its thumbnails, so the page, the prefetcher and a video poster all share one
 * memory-cache entry.
 */
internal fun previewRequest(context: Context, urls: MediaUrls, id: String, size: IntSize): ImageRequest.Builder {
    val url = urls.preview(id)
    return ImageRequest.Builder(context).data(url).memoryCacheKey(url).size(size.width, size.height)
}

/**
 * Indices to prefetch around [current], nearest first, with forward before backward at each
 * distance: +1, -1, +2, -2, and so on. [current] itself is excluded.
 */
internal fun prefetchWindow(current: Int, total: Int, radius: Int): List<Int> {
    if (total <= 0 || current !in 0 until total) return emptyList()
    val out = ArrayList<Int>(radius * 2)
    for (d in 1..radius) {
        if (current + d < total) out += current + d
        if (current - d >= 0) out += current - d
    }
    return out
}

/**
 * Backoff before automatic retry number [attempt] (0-based) of a failed image load. Null means
 * stop retrying automatically and leave it to a tap. The delays are 1, 2, 4 and 8 seconds, enough
 * to ride out a LAN-to-public switch or a brief 5xx.
 */
internal fun retryDelayMs(attempt: Int): Long? = if (attempt in 0 until MAX_AUTO_RETRIES) 1000L shl attempt else null

internal const val MAX_AUTO_RETRIES = 4

/** What the prefetcher drives: the timeline (buckets) and the image loader (one request per index). */
internal interface PrefetchTarget {
    /** Makes sure the timeline bucket holding [index] is loaded or loading. */
    fun ensureLoadedAt(index: Int)

    /** Starts prefetching [index]'s preview. Returns null when its asset isn't loaded yet. */
    fun enqueue(index: Int): PrefetchHandle?
}

/** One prefetch in flight (or done). Cancelling a finished one is a no-op. */
internal fun interface PrefetchHandle { fun cancel() }

/**
 * Keeps the previews of the pages around the current one loading, so swiping shows them at once.
 *
 * - [moveTo] runs as soon as the pager settles. It loads the timeline buckets one page beyond the
 *   window and cancels prefetches that are now out of range.
 * - [prefetch] runs once the current page has its image, so neighbours never compete with it. It
 *   is idempotent, so it can be called again as buckets arrive.
 */
internal class NeighbourPrefetcher(private val target: PrefetchTarget, private val radius: Int = 2) {
    private val running = LinkedHashMap<Int, PrefetchHandle>()

    /** Indices with a prefetch started (and not cancelled). For tests. */
    val active: Set<Int> get() = running.keys

    fun moveTo(current: Int, total: Int) {
        if (current in 0 until total) target.ensureLoadedAt(current)
        prefetchWindow(current, total, radius + 1).forEach(target::ensureLoadedAt)
        // The page now current keeps its prefetch: it's the image wanted most.
        val keep = prefetchWindow(current, total, radius).toSet() + current
        val it = running.entries.iterator()
        while (it.hasNext()) {
            val (index, handle) = it.next()
            if (index !in keep) { handle.cancel(); it.remove() }
        }
    }

    fun prefetch(current: Int, total: Int) {
        for (i in prefetchWindow(current, total, radius)) {
            if (i in running) continue
            target.enqueue(i)?.let { running[i] = it }
        }
    }

    fun clear() {
        running.values.forEach { it.cancel() }
        running.clear()
    }
}

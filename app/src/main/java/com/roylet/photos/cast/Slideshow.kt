package com.roylet.photos.cast

import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** A running slideshow, for the UI. [index] is the timeline index on show. */
data class SlideshowState(val intervalMs: Long, val shuffle: Boolean, val index: Int, val shown: Int, val total: Int)

/** What a slideshow walks through: the viewer's timeline. */
interface SlideshowQueue {
    val total: Int
    /** The asset at [index], loading its month if needed; null if it can't be had. */
    suspend fun assetAt(index: Int): TimelineAsset?
}

/** A [TimelineRepository] as a queue: months load on demand as the show reaches them. */
class TimelineQueue(private val timeline: TimelineRepository, private val loadTimeoutMs: Long = 10_000) : SlideshowQueue {
    override val total: Int get() = timeline.snapshot.value.total

    override suspend fun assetAt(index: Int): TimelineAsset? {
        timeline.snapshot.value.assetAt(index)?.let { return it }
        timeline.ensureLoadedAt(index)
        return withTimeoutOrNull(loadTimeoutMs) { timeline.snapshot.first { it.assetAt(index) != null }.assetAt(index) }
    }
}

/** The order a slideshow visits a [total]-item queue from [start]: onwards and round, or a shuffle that starts at [start]. */
fun slideshowOrder(total: Int, start: Int, shuffle: Boolean, random: Random = Random): List<Int> {
    if (total <= 0) return emptyList()
    val from = start.coerceIn(0, total - 1)
    if (!shuffle) return List(total) { (from + it) % total }
    val rest = (0 until total).filter { it != from }.shuffled(random)
    return listOf(from) + rest
}

/**
 * Advances a queue every [SlideshowState.intervalMs]: photos stay up for the interval; a video
 * plays to its end ([playback] says ended) or its length plus a margin, whichever is first. It
 * loops. Used by the cast controller (each step goes to the TV) and by the viewer on its own
 * (each step turns the page).
 */
class SlideshowRunner(
    private val scope: CoroutineScope,
    private val show: (index: Int, asset: TimelineAsset) -> Unit,
    private val playback: () -> RemotePlayback,
    private val onState: (SlideshowState?) -> Unit,
    private val random: Random = Random,
) {
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun start(queue: SlideshowQueue, startIndex: Int, intervalMs: Long, shuffle: Boolean) {
        stop()
        val order = slideshowOrder(queue.total, startIndex, shuffle, random)
        if (order.isEmpty()) return
        job = scope.launch {
            var shown = 0
            var i = 0
            while (true) {
                val index = order[i % order.size]
                val asset = queue.assetAt(index)
                if (asset != null) {
                    shown++
                    onState(SlideshowState(intervalMs, shuffle, index, shown, order.size))
                    show(index, asset)
                    if (asset.isVideo) awaitVideo(asset, intervalMs) else delay(intervalMs)
                } else {
                    delay(POLL_MS)
                }
                i++
            }
        }
    }

    fun stop() {
        val j = job ?: return
        job = null
        j.cancel()
        onState(null)
    }

    private suspend fun awaitVideo(asset: TimelineAsset, intervalMs: Long) {
        val limit = ((asset.durationMs ?: 0) + VIDEO_MARGIN_MS).coerceAtLeast(intervalMs).coerceAtMost(MAX_VIDEO_MS)
        var waited = 0L
        // Give the receiver a moment to start before "ended" can mean anything.
        delay(POLL_MS * 4)
        waited += POLL_MS * 4
        while (waited < limit) {
            val p = playback()
            if (p.mediaId == asset.id && (p.ended || p.error != null)) break
            delay(POLL_MS)
            waited += POLL_MS
        }
    }

    companion object {
        val INTERVALS_S = listOf(3, 5, 10)
        private const val POLL_MS = 250L
        private const val VIDEO_MARGIN_MS = 3_000L
        private const val MAX_VIDEO_MS = 10 * 60_000L
    }
}

package com.roylet.photos.video.ui

import com.roylet.photos.video.model.VideoEdit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Maps source time to x on the trim strip and back, for a strip [widthPx] wide showing the whole
 * source at [zoom] 1 and a [zoom]× longer content scrolled by [scrollPx] otherwise. Pure, so the
 * gesture code stays small and the maths is unit tested.
 */
data class TimelineViewport(
    val durationMs: Long,
    val widthPx: Float,
    val zoom: Float = 1f,
    val scrollPx: Float = 0f,
) {
    val contentWidth: Float get() = widthPx * zoom
    private val dur: Long get() = durationMs.coerceAtLeast(1)

    /** Milliseconds per pixel at this zoom. */
    val msPerPx: Float get() = dur / contentWidth.coerceAtLeast(1f)

    /** x (in strip pixels, 0 = left edge of the visible strip) of source time [ms]. */
    fun xOf(ms: Long): Float = ms.toFloat() / dur * contentWidth - scrollPx

    /** Source time under strip x [x], clamped to the source. */
    fun msAt(x: Float): Long = ((x + scrollPx) / contentWidth.coerceAtLeast(1f) * dur).roundToLong().coerceIn(0, dur)

    /** First and last visible source time. */
    val visibleStartMs: Long get() = msAt(0f)
    val visibleEndMs: Long get() = msAt(widthPx)

    /** Zooms to [newZoom] (clamped to `1..maxZoom`) keeping the time under [focusX] where it is. */
    fun zoomedAround(newZoom: Float, focusX: Float): TimelineViewport {
        val z = newZoom.coerceIn(1f, maxZoom(durationMs, widthPx))
        val anchor = (focusX + scrollPx) / contentWidth.coerceAtLeast(1f)
        return copy(zoom = z, scrollPx = anchor * widthPx * z - focusX).clamped()
    }

    fun scrolledBy(dx: Float): TimelineViewport = copy(scrollPx = scrollPx + dx).clamped()

    /** Scrolls the least amount that brings [ms] at least [marginPx] inside the visible strip. */
    fun revealing(ms: Long, marginPx: Float): TimelineViewport {
        val x = xOf(ms)
        val m = min(marginPx, widthPx / 2f)
        return when {
            x < m -> scrolledBy(x - m)
            x > widthPx - m -> scrolledBy(x - (widthPx - m))
            else -> this
        }
    }

    fun clamped(): TimelineViewport {
        val z = zoom.coerceIn(1f, maxZoom(durationMs, widthPx))
        val maxScroll = max(0f, widthPx * z - widthPx)
        return copy(zoom = z, scrollPx = scrollPx.coerceIn(0f, maxScroll))
    }

    companion object {
        /** The finest zoom shows about this much time across the strip. */
        const val MIN_VISIBLE_MS = 1_500L
        const val MAX_ZOOM = 96f

        fun maxZoom(durationMs: Long, widthPx: Float): Float =
            (durationMs.toFloat() / MIN_VISIBLE_MS).coerceIn(1f, MAX_ZOOM).let { if (widthPx <= 0f) 1f else it }
    }
}

/** Which edge of a kept segment a trim handle moves. */
enum class TrimEdge { START, END }

data class TrimHandle(val segment: Int, val edge: TrimEdge)

/** What a touch on the strip lands on. */
sealed interface StripHit {
    data class Handle(val handle: TrimHandle) : StripHit
    data class Segment(val index: Int) : StripHit
    /** Cut-out time between (or outside) the kept segments. */
    data object Gap : StripHit
}

/** Result of a handle drag step: the new edit, where the edge ended up, and the target it snapped to (if any). */
data class TrimDrag(val edit: VideoEdit, val edgeMs: Long, val snappedTo: Long?)

/** The trim strip's interaction rules. */
object TrimMath {
    /** Shortest segment the handles leave (the model's own floor is [VideoEdit.MIN_SEGMENT_MS]). */
    const val MIN_TRIM_MS = 300L

    /** How close (in px) a handle must come to a target to snap to it. */
    const val SNAP_PX = 12f

    /** Where a dragged edge can snap: the source ends, the playhead and every other segment edge. */
    fun snapTargets(edit: VideoEdit, playheadSourceMs: Long?, dragging: TrimHandle): List<Long> {
        val out = ArrayList<Long>()
        out += 0L
        out += edit.sourceDurationMs
        if (playheadSourceMs != null) out += playheadSourceMs
        edit.segments.forEachIndexed { i, s ->
            if (!(i == dragging.segment && dragging.edge == TrimEdge.START)) out += s.startMs
            if (!(i == dragging.segment && dragging.edge == TrimEdge.END)) out += s.endMs
        }
        return out.distinct()
    }

    /** The target nearest to [ms] within [thresholdMs], or null. */
    fun snap(ms: Long, targets: List<Long>, thresholdMs: Long): Long? =
        targets.filter { abs(it - ms) <= thresholdMs }.minByOrNull { abs(it - ms) }

    /**
     * Moves [handle] of [start] to source time [requestedMs] (snapping to [targets] within
     * [thresholdMs]), clamped so the segment stays inside the source, doesn't overlap its
     * neighbours and keeps at least [minMs]. [snappedTo] is set only when the edge really sits on
     * the target after clamping.
     */
    fun dragHandle(
        start: VideoEdit,
        handle: TrimHandle,
        requestedMs: Long,
        targets: List<Long> = emptyList(),
        thresholdMs: Long = 0,
        minMs: Long = MIN_TRIM_MS,
    ): TrimDrag {
        val i = handle.segment
        val seg = start.segments.getOrNull(i) ?: return TrimDrag(start, requestedMs, null)
        val snapped = snap(requestedMs, targets, thresholdMs)
        var ms = snapped ?: requestedMs
        val min = max(minMs, VideoEdit.MIN_SEGMENT_MS)
        ms = when (handle.edge) {
            // A segment already shorter than [min] can't be shortened further, only lengthened.
            TrimEdge.START -> min(ms, max(seg.endMs - min, if (seg.durationMs < min) seg.startMs else Long.MIN_VALUE))
            TrimEdge.END -> max(ms, min(seg.startMs + min, if (seg.durationMs < min) seg.endMs else Long.MAX_VALUE))
        }
        val next = when (handle.edge) {
            TrimEdge.START -> start.trimSegment(i, ms, seg.endMs)
            TrimEdge.END -> start.trimSegment(i, seg.startMs, ms)
        }
        val s = next.segments[i]
        val edge = if (handle.edge == TrimEdge.START) s.startMs else s.endMs
        return TrimDrag(next, edge, snapped?.takeIf { it == edge })
    }

    /**
     * What strip x [x] hits: a handle within [handleSlopPx] (on a shared cut, the side the finger
     * is on decides), else the kept segment under it, else a gap.
     */
    fun hitTest(edit: VideoEdit, vp: TimelineViewport, x: Float, handleSlopPx: Float): StripHit {
        var best: TrimHandle? = null
        var bestD = Float.MAX_VALUE
        edit.segments.forEachIndexed { i, s ->
            val sx = vp.xOf(s.startMs)
            val ex = vp.xOf(s.endMs)
            // Handles sit just outside their edge: START to the left, END to the right.
            val ds = abs(x - sx)
            val de = abs(x - ex)
            if (ds <= handleSlopPx && (ds < bestD || (ds == bestD && x >= sx))) { best = TrimHandle(i, TrimEdge.START); bestD = ds }
            if (de <= handleSlopPx && (de < bestD || (de == bestD && x < ex))) { best = TrimHandle(i, TrimEdge.END); bestD = de }
        }
        // On a shared cut (end of i == start of i+1) the finger's side picks the handle.
        best?.let { h ->
            val s = edit.segments[h.segment]
            val cut = vp.xOf(if (h.edge == TrimEdge.START) s.startMs else s.endMs)
            if (h.edge == TrimEdge.START && x < cut) {
                val prev = edit.segments.getOrNull(h.segment - 1)
                if (prev != null && prev.endMs == s.startMs) return StripHit.Handle(TrimHandle(h.segment - 1, TrimEdge.END))
            }
            if (h.edge == TrimEdge.END && x >= cut) {
                val next = edit.segments.getOrNull(h.segment + 1)
                if (next != null && next.startMs == s.endMs) return StripHit.Handle(TrimHandle(h.segment + 1, TrimEdge.START))
            }
            return StripHit.Handle(h)
        }
        val ms = vp.msAt(x)
        val idx = edit.segments.indexOfFirst { ms >= it.startMs && ms < it.endMs || (ms == it.endMs && it == edit.segments.last()) }
        return if (idx >= 0) StripHit.Segment(idx) else StripHit.Gap
    }

    /** [sourceMs] moved into the nearest kept segment (the playhead can't sit in cut-out time). */
    fun nearestKept(edit: VideoEdit, sourceMs: Long): Long {
        var best = sourceMs
        var bestD = Long.MAX_VALUE
        for (s in edit.segments) {
            val c = sourceMs.coerceIn(s.startMs, s.endMs)
            val d = abs(c - sourceMs)
            if (d < bestD) { bestD = d; best = c }
        }
        return best
    }

    /** Output time for the playhead dragged to strip x [x] (cut-out time snaps to the nearest kept moment). */
    fun scrubToOutput(edit: VideoEdit, vp: TimelineViewport, x: Float): Long {
        val ms = nearestKept(edit, vp.msAt(x))
        return edit.sourceToOutput(ms) ?: 0L
    }

    /** Strip x of the playhead at output time [outputMs]. */
    fun playheadX(edit: VideoEdit, vp: TimelineViewport, outputMs: Long): Float = vp.xOf(playheadSourceMs(edit, outputMs))

    /** Source time shown at output time [outputMs]; the very end maps to the last kept moment. */
    fun playheadSourceMs(edit: VideoEdit, outputMs: Long): Long = edit.outputToSource(outputMs).sourceMs

    /** "0:07.4", "12:03.0", "1:02:03.5". */
    fun formatTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val tenths = (t / 100) % 10
        val s = t / 1000
        val h = s / 3600
        val m = (s / 60) % 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%d", h, m, sec, tenths)
        else String.format(Locale.US, "%d:%02d.%d", m, sec, tenths)
    }

    /** "12.4s", "1m 05s", "1h 02m". */
    fun formatDuration(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return when {
            t < 60_000 -> String.format(Locale.US, "%.1fs", t / 1000.0)
            t < 3_600_000 -> String.format(Locale.US, "%dm %02ds", t / 60_000, (t / 1000) % 60)
            else -> String.format(Locale.US, "%dh %02dm", t / 3_600_000, (t / 60_000) % 60)
        }
    }
}

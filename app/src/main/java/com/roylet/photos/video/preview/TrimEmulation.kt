package com.roylet.photos.video.preview

import com.roylet.photos.video.model.TimeRange
import com.roylet.photos.video.model.VideoEdit

/**
 * The simplified preview plays the whole, unclipped source and emulates the trims itself: it
 * starts at the first kept moment, jumps over cut-out ranges by seeking at their boundaries and
 * stops at the end of the last kept range. This is the decision, as pure logic.
 */
object TrimEmulation {
    sealed interface Step {
        /** Inside kept time: keep playing. */
        data object Continue : Step

        /** In (or about to enter) cut-out time: seek the source to [sourceMs]. */
        data class SeekTo(val sourceMs: Long) : Step

        /** Past the last kept moment: stop at [sourceMs], its end. */
        data class End(val sourceMs: Long) : Step
    }

    /** The kept ranges with touching ones merged (a split without a cut plays straight through). */
    fun runs(segments: List<TimeRange>): List<TimeRange> {
        val out = ArrayList<TimeRange>()
        for (s in segments.sortedBy { it.startMs }) {
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.endMs) out[out.lastIndex] = TimeRange(last.startMs, maxOf(last.endMs, s.endMs))
            else out += s
        }
        return out
    }

    /**
     * What to do with the source at [sourceMs]. [lookaheadMs] (about half the polling interval)
     * makes the jump a little early rather than showing a few frames of cut-out time.
     */
    fun step(edit: VideoEdit, sourceMs: Long, lookaheadMs: Long = 0): Step {
        val runs = runs(edit.segments)
        for (r in runs) {
            if (sourceMs < r.startMs) return Step.SeekTo(r.startMs)
            if (sourceMs + lookaheadMs < r.endMs) return Step.Continue
        }
        return Step.End(runs.lastOrNull()?.endMs ?: 0L)
    }
}

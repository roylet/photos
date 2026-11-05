package com.roylet.photos.video.preview

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.roylet.photos.video.model.TimeRange
import com.roylet.photos.video.model.VideoEdit

/**
 * Checks and shapes every time range before the preview hands it to Media3. The edit's source
 * length comes from the file's metadata (MediaMetadataRetriever / the server), which can be a few
 * ms longer than the stream the player actually decodes; the player's own duration wins.
 */
@OptIn(UnstableApi::class)
object PreviewRanges {
    /** A range Media3 can clip to: inside `0..sourceDurationMs` and not empty. */
    fun isValid(r: TimeRange, sourceDurationMs: Long): Boolean =
        r.startMs >= 0 && r.endMs > r.startMs && r.startMs < sourceDurationMs && r.endMs <= sourceDurationMs

    /** The valid ranges of [segments] (the others are dropped; [VideoEdit.normalized] edits have none). */
    fun validated(segments: List<TimeRange>, sourceDurationMs: Long): List<TimeRange> =
        segments.filter { isValid(it, sourceDurationMs) }

    /**
     * The clipping for kept range [r]. An end at the source end is left open
     * (`C.TIME_END_OF_SOURCE`), so the player plays to the real end of the stream instead of a
     * metadata duration that can lie past it.
     */
    fun clipping(r: TimeRange, sourceDurationMs: Long): MediaItem.ClippingConfiguration =
        MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(r.startMs.coerceAtLeast(0))
            .apply { if (r.endMs < sourceDurationMs) setEndPositionMs(r.endMs) }
            .build()

    /** [edit] with its source length cut to [realDurationMs] (the player's), when that is shorter. */
    fun clampToSource(edit: VideoEdit, realDurationMs: Long?): VideoEdit {
        if (realDurationMs == null || realDurationMs <= 0 || realDurationMs >= edit.sourceDurationMs) return edit
        return edit.copy(sourceDurationMs = realDurationMs.coerceAtLeast(VideoEdit.MIN_SEGMENT_MS)).normalized()
    }

    /**
     * The source's real length read from the player's [timeline] once it is known: from the
     * unclipped item when [unclipped], else from the last item when its end is open
     * ([clipping]): its window lasts from [lastStartMs] to the real end. Null while unknown.
     */
    fun sourceDurationMs(timeline: Timeline, unclipped: Boolean, lastEndOpen: Boolean, lastStartMs: Long): Long? {
        if (timeline.isEmpty) return null
        val w = Timeline.Window()
        timeline.getWindow(if (unclipped) 0 else timeline.windowCount - 1, w)
        if (w.isPlaceholder || w.durationUs == C.TIME_UNSET || w.durationUs <= 0) return null
        val ms = Util.usToMs(w.durationUs)
        return when {
            unclipped -> ms
            lastEndOpen -> lastStartMs + ms
            else -> null
        }
    }
}

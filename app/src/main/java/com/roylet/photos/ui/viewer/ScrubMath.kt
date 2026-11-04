package com.roylet.photos.ui.viewer

import com.roylet.photos.ui.gallery.clock

/*
 * Pure arithmetic behind video scrubbing: labels, bubble placement, which frames the strip
 * samples, which one the bubble shows, and where the haptic ticks fall. No Android or Compose.
 */

/** "1:23 / 4:05" for the scrub bubble. */
internal fun scrubLabel(positionMs: Long, durationMs: Long): String =
    "${clock(positionMs.coerceIn(0, durationMs.coerceAtLeast(0)))} / ${clock(durationMs.coerceAtLeast(0))}"

/** [fraction] of [durationMs], clamped to the video. */
internal fun scrubPositionMs(fraction: Float, durationMs: Long): Long =
    (fraction.coerceIn(0f, 1f).toDouble() * durationMs.coerceAtLeast(0)).toLong()

/**
 * Left edge of a bubble [width] wide centred on [anchorX], kept [margin] inside a container
 * [containerWidth] wide. A bubble wider than the room left is centred in the container.
 */
internal fun bubbleLeft(anchorX: Float, width: Float, containerWidth: Float, margin: Float = 0f): Float {
    val min = margin
    val max = containerWidth - margin - width
    if (max < min) return (containerWidth - width) / 2f
    return (anchorX - width / 2f).coerceIn(min, max)
}

/** How many frames the strip samples: 10 for anything 10 s or longer, fewer (min 4) for short clips. */
internal fun stripFrameCount(durationMs: Long): Int = if (durationMs <= 0) 0 else (durationMs / 1000).toInt().coerceIn(4, 10)

/** The strip's sample times: the centre of each of [count] equal slices of the video. */
internal fun stripFrameTimesMs(durationMs: Long, count: Int = stripFrameCount(durationMs)): LongArray {
    if (durationMs <= 0 || count <= 0) return LongArray(0)
    return LongArray(count) { i -> ((i + 0.5) * durationMs / count).toLong() }
}

/** The strip slice that [positionMs] falls in, i.e. the frame whose sample time is nearest. */
internal fun stripFrameIndex(positionMs: Long, durationMs: Long, count: Int): Int {
    if (count <= 0 || durationMs <= 0) return -1
    return ((positionMs.toDouble() / durationMs) * count).toInt().coerceIn(0, count - 1)
}

/**
 * The index of the loaded frame closest to [target] (ties go to the earlier one), or -1 when
 * nothing is loaded yet.
 */
internal fun nearestLoaded(target: Int, count: Int, loaded: (Int) -> Boolean): Int {
    if (target !in 0 until count) return -1
    for (d in 0 until count) {
        if (target - d >= 0 && loaded(target - d)) return target - d
        if (target + d < count && loaded(target + d)) return target + d
    }
    return -1
}

/**
 * The order to extract [count] frames in, coarse to fine (0, 8, 4, 2, 6, 1, 3 …), so a strip
 * that is only partly loaded is still spread across the whole video.
 */
internal fun extractionOrder(count: Int): List<Int> {
    if (count <= 0) return emptyList()
    var step = Integer.highestOneBit(count)
    if (step < count) step *= 2
    val out = LinkedHashSet<Int>()
    while (step >= 1) {
        var i = 0
        while (i < count) { out += i; i += step }
        step /= 2
    }
    return out.toList()
}

private val chapterSteps = longArrayOf(60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000)

/**
 * Spacing of the haptic ticks: every 10% of the video, or for videos over 10 minutes the
 * shortest round "chapter" step (1, 2, 5, 10, 15, 30, 60 min) that gives at most 12 ticks.
 */
internal fun tickIntervalMs(durationMs: Long): Long {
    if (durationMs <= 0) return 0
    if (durationMs <= 600_000) return (durationMs / 10).coerceAtLeast(1)
    return chapterSteps.firstOrNull { durationMs / it <= 12 } ?: chapterSteps.last()
}

/** Which tick segment [positionMs] is in; the scrubber ticks whenever this changes. */
internal fun tickIndex(positionMs: Long, durationMs: Long): Int {
    val step = tickIntervalMs(durationMs)
    if (step <= 0) return 0
    return (positionMs.coerceIn(0, durationMs) / step).toInt()
}

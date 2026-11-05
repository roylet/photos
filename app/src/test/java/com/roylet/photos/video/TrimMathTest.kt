package com.roylet.photos.video

import com.roylet.photos.video.export.EncoderCaps
import com.roylet.photos.video.export.SourceInfo
import com.roylet.photos.video.model.OutputQuality
import com.roylet.photos.video.model.OutputResolution
import com.roylet.photos.video.model.OutputSettings
import com.roylet.photos.video.model.TimeRange
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.ui.ExportEstimates
import com.roylet.photos.video.ui.SpeedScale
import com.roylet.photos.video.ui.StripHit
import com.roylet.photos.video.ui.TimelineViewport
import com.roylet.photos.video.ui.TrimEdge
import com.roylet.photos.video.ui.TrimHandle
import com.roylet.photos.video.ui.TrimMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimMathTest {
    private val ten = VideoEdit.initial(10_000)
    private val two = ten.copy(segments = listOf(TimeRange(0, 4_000), TimeRange(6_000, 10_000)))

    // ---- viewport ----

    @Test fun viewportMapsBothWays() {
        val vp = TimelineViewport(10_000, 1000f)
        assertEquals(500f, vp.xOf(5_000), 0.01f)
        assertEquals(2_500L, vp.msAt(250f))
        assertEquals(0L, vp.msAt(-40f))
        assertEquals(10_000L, vp.msAt(4000f))
    }

    @Test fun zoomKeepsTheFocusTimeUnderTheFinger() {
        val vp = TimelineViewport(10_000, 1000f).zoomedAround(4f, 250f)
        assertEquals(4f, vp.zoom, 0.001f)
        assertEquals(2_500L, vp.msAt(250f))
        assertEquals(250f, vp.xOf(2_500), 0.5f)
    }

    @Test fun zoomAndScrollAreClamped() {
        val vp = TimelineViewport(3_000, 1000f).zoomedAround(50f, 900f)
        assertEquals(2f, vp.zoom, 0.001f) // 3 s at 1.5 s per strip at most
        assertTrue(vp.scrollPx in 0f..1000f)
        assertEquals(0f, TimelineViewport(10_000, 1000f, 2f).scrolledBy(-500f).scrollPx)
        assertEquals(1000f, TimelineViewport(10_000, 1000f, 2f).scrolledBy(5000f).scrollPx)
        assertEquals(1f, TimelineViewport(10_000, 1000f, 3f).zoomedAround(0.2f, 0f).zoom)
    }

    @Test fun playheadMappingWithZoom() {
        val vp = TimelineViewport(10_000, 1000f, zoom = 2f, scrollPx = 500f) // shows 2.5 s .. 7.5 s
        // Output 5 s in the two-segment edit is source 7 s (after the 4..6 s gap).
        assertEquals(7_000L, TrimMath.playheadSourceMs(two, 5_000))
        assertEquals(900f, TrimMath.playheadX(two, vp, 5_000), 0.5f)
        // Scrubbing into the gap lands on the nearest kept moment.
        assertEquals(4_000L, TrimMath.scrubToOutput(two, vp, vp.xOf(4_600)))
        assertEquals(4_000L, TrimMath.scrubToOutput(two, vp, vp.xOf(5_400))) // 6 s is output 4 s too
        assertEquals(5_000L, TrimMath.scrubToOutput(two, vp, 900f))
    }

    @Test fun revealingScrollsTheLeastAmount() {
        val vp = TimelineViewport(10_000, 1000f, zoom = 4f, scrollPx = 0f)
        assertEquals(0f, vp.revealing(1_000, 50f).scrollPx)
        val moved = vp.revealing(5_000, 50f)
        assertEquals(950f, moved.xOf(5_000), 0.5f)
    }

    // ---- handles ----

    @Test fun startHandleMovesAndClampsToSourceAndMinimum() {
        val h = TrimHandle(0, TrimEdge.START)
        assertEquals(TimeRange(1_200, 10_000), TrimMath.dragHandle(ten, h, 1_200).edit.segments[0])
        assertEquals(0L, TrimMath.dragHandle(ten, h, -500).edgeMs)
        // Can't pass the end minus the minimum length.
        assertEquals(10_000 - TrimMath.MIN_TRIM_MS, TrimMath.dragHandle(ten, h, 12_000).edgeMs)
    }

    @Test fun endHandleStopsAtTheNextSegmentAndMinimum() {
        val end0 = TrimHandle(0, TrimEdge.END)
        assertEquals(6_000L, TrimMath.dragHandle(two, end0, 8_000).edgeMs) // can't overlap the next
        assertEquals(TrimMath.MIN_TRIM_MS, TrimMath.dragHandle(two, end0, 10).edgeMs)
        val start1 = TrimHandle(1, TrimEdge.START)
        assertEquals(4_000L, TrimMath.dragHandle(two, start1, 2_000).edgeMs)
    }

    @Test fun shortSegmentCanOnlyGrow() {
        val tiny = ten.copy(segments = listOf(TimeRange(1_000, 1_150)))
        val r = TrimMath.dragHandle(tiny, TrimHandle(0, TrimEdge.START), 1_100)
        assertEquals(1_000L, r.edgeMs)
        assertEquals(800L, TrimMath.dragHandle(tiny, TrimHandle(0, TrimEdge.START), 800).edgeMs)
    }

    @Test fun snapsToPlayheadAndOtherEdgesWithinThreshold() {
        val h = TrimHandle(0, TrimEdge.END)
        val targets = TrimMath.snapTargets(two, playheadSourceMs = 2_500, dragging = h)
        assertTrue(2_500L in targets && 6_000L in targets && 0L in targets)
        assertFalse(4_000L in targets) // its own edge isn't a target
        val snapped = TrimMath.dragHandle(two, h, 2_560, targets, thresholdMs = 100)
        assertEquals(2_500L, snapped.edgeMs)
        assertEquals(2_500L, snapped.snappedTo)
        val free = TrimMath.dragHandle(two, h, 2_700, targets, thresholdMs = 100)
        assertEquals(2_700L, free.edgeMs)
        assertNull(free.snappedTo)
    }

    @Test fun snapIsReportedOnlyWhenTheEdgeReallySitsThere() {
        // Snapping END of 0 to the start of segment 1 (6 s) is allowed and sits there.
        val h = TrimHandle(0, TrimEdge.END)
        val r = TrimMath.dragHandle(two, h, 5_950, TrimMath.snapTargets(two, null, h), 100)
        assertEquals(6_000L, r.snappedTo)
        // Target 0 for START of segment 1 is clamped away by segment 0: no snap reported.
        val s = TrimHandle(1, TrimEdge.START)
        val r2 = TrimMath.dragHandle(two, s, 50, listOf(0L), 100)
        assertEquals(4_000L, r2.edgeMs)
        assertNull(r2.snappedTo)
    }

    @Test fun nearestSnapTargetWins() {
        assertEquals(1_000L, TrimMath.snap(1_030, listOf(900, 1_000, 1_100), 80))
        assertNull(TrimMath.snap(1_500, listOf(900, 1_000), 80))
    }

    // ---- hit testing ----

    @Test fun hitTestFindsHandlesSegmentsAndGaps() {
        val vp = TimelineViewport(10_000, 1000f)
        assertEquals(StripHit.Handle(TrimHandle(0, TrimEdge.START)), TrimMath.hitTest(two, vp, 5f, 20f))
        assertEquals(StripHit.Handle(TrimHandle(0, TrimEdge.END)), TrimMath.hitTest(two, vp, 405f, 20f))
        assertEquals(StripHit.Handle(TrimHandle(1, TrimEdge.START)), TrimMath.hitTest(two, vp, 595f, 20f))
        assertEquals(StripHit.Segment(0), TrimMath.hitTest(two, vp, 200f, 20f))
        assertEquals(StripHit.Segment(1), TrimMath.hitTest(two, vp, 800f, 20f))
        assertEquals(StripHit.Gap, TrimMath.hitTest(two, vp, 500f, 20f))
    }

    @Test fun onASharedCutTheFingerSidePicksTheHandle() {
        val split = ten.splitAt(5_000)
        val vp = TimelineViewport(10_000, 1000f)
        assertEquals(StripHit.Handle(TrimHandle(0, TrimEdge.END)), TrimMath.hitTest(split, vp, 495f, 20f))
        assertEquals(StripHit.Handle(TrimHandle(1, TrimEdge.START)), TrimMath.hitTest(split, vp, 505f, 20f))
    }

    // ---- labels, speed, estimate ----

    @Test fun timeFormatting() {
        assertEquals("0:07.4", TrimMath.formatTime(7_480))
        assertEquals("12:03.0", TrimMath.formatTime(723_000))
        assertEquals("1:02:03.5", TrimMath.formatTime(3_723_500))
        assertEquals("12.6s", TrimMath.formatDuration(12_600))
        assertEquals("1m 05s", TrimMath.formatDuration(65_000))
    }

    @Test fun speedSliderIsLogarithmicAroundOne() {
        assertEquals(0f, SpeedScale.toSlider(1f), 1e-4f)
        assertEquals(-1f, SpeedScale.toSlider(0.25f), 1e-4f)
        assertEquals(1f, SpeedScale.toSlider(4f), 1e-4f)
        assertEquals(2f, SpeedScale.fromSlider(0.5f), 1e-4f)
        assertEquals("2×", SpeedScale.format(2f))
        assertEquals("1.5×", SpeedScale.format(1.5f))
        assertEquals("0.25×", SpeedScale.format(0.25f))
    }

    @Test fun estimateFollowsLengthResolutionAndQuality() {
        val src = SourceInfo(1920, 1080, 10_000, bitrate = 20_000_000, frameRate = 30f)
        val full = ExportEstimates.estimate(ten.copy(speed = 1.001f), src, EncoderCaps())
        val half = ExportEstimates.estimate(two.copy(speed = 1.001f), src, EncoderCaps())
        assertTrue(half.bytes < full.bytes * 0.85)
        val small = ExportEstimates.estimate(ten.copy(speed = 1.001f, output = OutputSettings(OutputResolution.P720, OutputQuality.STANDARD)), src, EncoderCaps())
        assertEquals(720, small.height)
        assertTrue(small.bytes < full.bytes)
        assertFalse(ExportEstimates.offered(OutputResolution.P1080, ten, src))
        assertTrue(ExportEstimates.offered(OutputResolution.P720, ten, src))
    }
}

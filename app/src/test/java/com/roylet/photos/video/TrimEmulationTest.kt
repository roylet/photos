package com.roylet.photos.video

import com.roylet.photos.video.model.TimeRange
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.preview.TrimEmulation
import com.roylet.photos.video.preview.TrimEmulation.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class TrimEmulationTest {
    // Kept: [1 s, 3 s) and [6 s, 9 s) of a 10 s source.
    private val edit = VideoEdit.initial(10_000).copy(segments = listOf(TimeRange(1_000, 3_000), TimeRange(6_000, 9_000)))

    @Test fun startsAtTheFirstKeptMoment() {
        assertEquals(Step.SeekTo(1_000), TrimEmulation.step(edit, 0))
        assertEquals(Step.Continue, TrimEmulation.step(edit, 1_000))
    }

    @Test fun jumpsOverACutAtItsBoundary() {
        assertEquals(Step.Continue, TrimEmulation.step(edit, 2_970, lookaheadMs = 20))
        assertEquals(Step.SeekTo(6_000), TrimEmulation.step(edit, 2_985, lookaheadMs = 20)) // just before the cut
        assertEquals(Step.SeekTo(6_000), TrimEmulation.step(edit, 4_000)) // inside it
        assertEquals(Step.Continue, TrimEmulation.step(edit, 6_000))
    }

    @Test fun stopsAtTheEndOfTheLastKeptRange() {
        assertEquals(Step.End(9_000), TrimEmulation.step(edit, 8_990, lookaheadMs = 20))
        assertEquals(Step.End(9_000), TrimEmulation.step(edit, 9_500))
    }

    @Test fun touchingSegmentsPlayStraightThrough() {
        val split = VideoEdit.initial(10_000).splitAt(4_000)
        assertEquals(listOf(TimeRange(0, 10_000)), TrimEmulation.runs(split.segments))
        assertEquals(Step.Continue, TrimEmulation.step(split, 3_990, lookaheadMs = 20))
    }
}

package com.roylet.photos.video

import androidx.media3.common.C
import com.roylet.photos.video.model.TimeRange
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.preview.PreviewRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreviewRangesTest {
    @Test fun validatorRejectsEmptyReversedNegativeAndPastTheEnd() {
        assertTrue(PreviewRanges.isValid(TimeRange(0, 24_860), 24_860))
        assertFalse(PreviewRanges.isValid(TimeRange(0, 24_900), 24_860)) // past the real end
        assertFalse(PreviewRanges.isValid(TimeRange(500, 500), 24_860))
        assertFalse(PreviewRanges.isValid(TimeRange(900, 500), 24_860))
        assertFalse(PreviewRanges.isValid(TimeRange(-1, 500), 24_860))
        assertFalse(PreviewRanges.isValid(TimeRange(24_860, 24_900), 24_860))
        assertEquals(listOf(TimeRange(0, 1_000)), PreviewRanges.validated(listOf(TimeRange(0, 1_000), TimeRange(2_000, 1_500)), 5_000))
    }

    @Test fun anEndAtTheSourceEndIsLeftOpen() {
        val open = PreviewRanges.clipping(TimeRange(1_000, 24_900), 24_900)
        assertEquals(1_000L, open.startPositionMs)
        assertEquals(C.TIME_END_OF_SOURCE, open.endPositionMs)
        assertEquals(8_000L, PreviewRanges.clipping(TimeRange(1_000, 8_000), 24_900).endPositionMs)
    }

    @Test fun segmentsAreClampedToThePlayersDuration() {
        val e = VideoEdit.initial(24_900).splitAt(10_000)
        assertSame(e, PreviewRanges.clampToSource(e, null))
        assertSame(e, PreviewRanges.clampToSource(e, 25_000)) // never lengthened
        val c = PreviewRanges.clampToSource(e, 24_840)
        assertEquals(24_840L, c.sourceDurationMs)
        assertEquals(listOf(TimeRange(0, 10_000), TimeRange(10_000, 24_840)), c.segments)
        assertTrue(c.segments.all { PreviewRanges.isValid(it, 24_840) })
        // A segment wholly past the real end goes away.
        val tail = VideoEdit.initial(24_900).copy(segments = listOf(TimeRange(0, 5_000), TimeRange(24_850, 24_900)))
        assertEquals(listOf(TimeRange(0, 5_000)), PreviewRanges.clampToSource(tail, 24_840).segments)
    }
}

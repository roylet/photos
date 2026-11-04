package com.roylet.photos.video

import com.roylet.photos.editor.recipe.CropRect
import com.roylet.photos.editor.recipe.Filter
import com.roylet.photos.editor.recipe.Geometry
import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.video.model.AudioSettings
import com.roylet.photos.video.model.OutputCodec
import com.roylet.photos.video.model.OutputResolution
import com.roylet.photos.video.model.OutputSettings
import com.roylet.photos.video.model.TimeRange
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.model.VideoGeometry
import com.roylet.photos.video.model.VideoHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEditTest {
    private val ten = VideoEdit.initial(10_000)

    @Test fun initialIsIdentity() {
        assertTrue(ten.isIdentity)
        assertEquals(listOf(TimeRange(0, 10_000)), ten.segments)
        assertEquals(10_000, ten.outputDurationMs())
    }

    @Test fun eachKindOfChangeBreaksIdentity() {
        assertFalse(ten.trimStart(500).isIdentity)
        assertFalse(ten.copy(geometry = VideoGeometry(rotate90 = 1)).isIdentity)
        assertFalse(ten.withSpeed(2f).isIdentity)
        assertFalse(ten.copy(audio = AudioSettings(mute = true)).isIdentity)
        assertFalse(ten.copy(audio = AudioSettings(volume = 150f)).isIdentity)
        assertFalse(ten.copy(color = Recipe(light = Light(exposure = 1f))).isIdentity)
        assertFalse(ten.copy(output = OutputSettings(resolution = OutputResolution.P720)).isIdentity)
        // Codec/quality alone isn't an edit.
        assertTrue(ten.copy(output = OutputSettings(codec = OutputCodec.H264)).isIdentity)
    }

    @Test fun splitMakesTwoSegmentsAndKeepsDuration() {
        val e = ten.splitAt(4_000)
        assertEquals(listOf(TimeRange(0, 4_000), TimeRange(4_000, 10_000)), e.segments)
        assertEquals(10_000, e.outputDurationMs())
        assertTrue(e.isIdentity.not())
        assertFalse(e.isFullLength)
    }

    @Test fun splitTooCloseToAnEdgeIsANoOp() {
        assertSame(ten, ten.splitAt(50))
        assertSame(ten, ten.splitAt(9_960))
        val e = ten.splitAt(4_000)
        assertSame(e, e.splitAt(4_000)) // exactly on the cut
    }

    @Test fun splitUsesOutputTimeWithSpeed() {
        val e = ten.withSpeed(2f).splitAt(2_000) // output 2s at 2x = source 4s
        assertEquals(4_000, e.segments[0].endMs)
    }

    @Test fun deleteSegment() {
        val e = ten.splitAt(3_000).splitAt(6_000).deleteSegment(1)
        assertEquals(listOf(TimeRange(0, 3_000), TimeRange(6_000, 10_000)), e.segments)
        assertEquals(7_000, e.outputDurationMs())
        val one = e.deleteSegment(0)
        assertEquals(listOf(TimeRange(6_000, 10_000)), one.segments)
        assertSame(one, one.deleteSegment(0)) // last one stays
        assertSame(one, one.deleteSegment(5))
    }

    @Test fun trimClampsToSourceAndNeighbours() {
        val e = ten.splitAt(5_000)
        // Can't extend the first segment over the second.
        assertEquals(TimeRange(0, 5_000), e.trimSegment(0, -100, 7_000).segments[0])
        // Can't go past the end.
        assertEquals(TimeRange(5_000, 10_000), e.trimSegment(1, 5_000, 12_000).segments[1])
        // Collapsing is stopped at the minimum length; the dragged edge gives way.
        val dragEnd = e.trimSegment(0, 0, 0).segments[0]
        assertEquals(TimeRange(0, VideoEdit.MIN_SEGMENT_MS), dragEnd)
        val dragStart = e.trimSegment(0, 5_000, 5_000).segments[0]
        assertEquals(TimeRange(5_000 - VideoEdit.MIN_SEGMENT_MS, 5_000), dragStart)
    }

    @Test fun trimStartAndEnd() {
        val e = ten.trimStart(1_000).trimEnd(8_000)
        assertEquals(listOf(TimeRange(1_000, 8_000)), e.segments)
        assertEquals(7_000, e.outputDurationMs())
        assertEquals(listOf(TimeRange(0, 10_000)), e.resetSegments().segments)
    }

    @Test fun outputToSourceAcrossCutsAndSpeed() {
        val e = ten.splitAt(3_000).splitAt(6_000).deleteSegment(1).withSpeed(2f)
        // Kept: [0,3000) + [6000,10000) = 7000 ms source, 3500 ms output.
        assertEquals(3_500, e.outputDurationMs())
        assertEquals(0L, e.outputToSource(0).sourceMs)
        assertEquals(2_000L, e.outputToSource(1_000).sourceMs)
        val atCut = e.outputToSource(1_500)
        assertEquals(1, atCut.segmentIndex)
        assertEquals(6_000L, atCut.sourceMs)
        assertEquals(7_000L, e.outputToSource(2_000).sourceMs)
        // Clamped at both ends.
        assertEquals(0L, e.outputToSource(-5).sourceMs)
        val end = e.outputToSource(99_999)
        assertEquals(1, end.segmentIndex)
        assertEquals(10_000L, end.sourceMs)
    }

    @Test fun sourceToOutputRoundTrips() {
        val e = ten.splitAt(3_000).splitAt(6_000).deleteSegment(1).withSpeed(0.5f)
        assertEquals(0L, e.sourceToOutput(0))
        assertEquals(4_000L, e.sourceToOutput(2_000))
        assertNull(e.sourceToOutput(4_500)) // cut out
        assertEquals(6_000L, e.sourceToOutput(6_000))
        for (out in listOf(0L, 1_000L, 5_000L, 9_000L, 13_000L)) {
            assertEquals(out, e.sourceToOutput(e.outputToSource(out).sourceMs))
        }
        assertEquals(6_000L, e.segmentOutputStartMs(1))
    }

    @Test fun normalizedRepairsSegments() {
        val messy = VideoEdit(
            sourceDurationMs = 10_000,
            segments = listOf(TimeRange(8_000, 12_000), TimeRange(5_000, 2_000), TimeRange(4_000, 4_050), TimeRange(3_000, 9_000)),
            speed = 10f,
            audio = AudioSettings(volume = 500f),
            color = Recipe(geometry = Geometry(rotate90 = 1)),
        ).normalized()
        assertEquals(listOf(TimeRange(2_000, 5_000), TimeRange(5_000, 9_000), TimeRange(9_000, 10_000)), messy.segments)
        assertEquals(4f, messy.speed)
        assertEquals(200f, messy.audio.volume)
        assertTrue(messy.color.geometry.isIdentity)
        val empty = VideoEdit(sourceDurationMs = 5_000, segments = emptyList()).normalized()
        assertEquals(listOf(TimeRange(0, 5_000)), empty.segments)
    }

    @Test fun jsonRoundTripAndForwardCompat() {
        val e = ten.splitAt(4_000).deleteSegment(0).withSpeed(1.5f).copy(
            geometry = VideoGeometry(crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f), rotate90 = 1, flipH = true),
            audio = AudioSettings(volume = 50f),
            color = Recipe(light = Light(exposure = 0.7f), filter = Filter("vivid", 60f)),
            output = OutputSettings(OutputResolution.P1080, codec = OutputCodec.HEVC),
        )
        val json = e.toJson()
        assertTrue(json.contains("\"version\":1"))
        assertEquals(e, VideoEdit.fromJson(json))
        val future = json.dropLast(1) + ",\"newThing\":{\"x\":1}}"
        assertEquals(e, VideoEdit.fromJson(future))
        assertEquals(4f, VideoEdit.fromJson("{\"sourceDurationMs\":1000,\"speed\":9}").speed)
        // Minimal documents decode with defaults.
        assertEquals(VideoEdit.initial(1_000), VideoEdit.fromJson("{\"sourceDurationMs\":1000}"))
    }

    @Test fun geometryHelpers() {
        val g = VideoGeometry().rotatedCw()
        assertEquals(1, g.rotate90)
        assertEquals(Pair(1080, 1920), g.outputSize(1920, 1080))
        val cropped = VideoGeometry(crop = CropRect(0f, 0f, 0.5f, 0.5f))
        assertEquals(Pair(960, 540), cropped.outputSize(1920, 1080))
        assertTrue(VideoGeometry().flippedHorizontally().flippedHorizontally().isIdentity)
        assertTrue(g.rotatedCcw().isIdentity)
    }

    @Test fun historyCoalescesGestures() {
        val h = VideoHistory(ten)
        h.update { it.splitAt(5_000) }
        assertTrue(h.canUndo.value)
        h.beginGesture()
        for (t in 100L..2_000L step 100) h.update { it.trimSegment(0, t, it.segments[0].endMs) }
        h.endGesture()
        assertEquals(2_000, h.edit.segments[0].startMs)
        assertTrue(h.undo())
        assertEquals(0, h.edit.segments[0].startMs)
        assertTrue(h.undo())
        assertEquals(1, h.edit.segments.size)
        assertFalse(h.undo())
        assertTrue(h.redo()); assertTrue(h.redo())
        assertEquals(2_000, h.edit.segments[0].startMs)
        h.beginGesture(); h.update { it.withSpeed(2f) }; h.cancelGesture()
        assertEquals(1f, h.edit.speed)
        assertFalse(h.canRedo.value)
    }
}

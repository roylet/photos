package com.roylet.photos.video

import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.video.model.AudioSettings
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.model.VideoGeometry
import com.roylet.photos.video.preview.VideoPreviewController
import org.junit.After
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoFrameProcessingException
import com.roylet.photos.video.model.TimeRange
import com.roylet.photos.video.preview.PreviewEffectPlan
import com.roylet.photos.video.preview.PreviewErrors
import com.roylet.photos.video.preview.PreviewNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The playlist/parameter wiring of the preview (frames themselves need a device). */
@RunWith(RobolectricTestRunner::class)
class VideoPreviewControllerTest {
    private lateinit var controller: VideoPreviewController
    private val edit = VideoEdit.initial(10_000).splitAt(3_000).splitAt(6_000).deleteSegment(1)

    @Before fun setUp() {
        controller = VideoPreviewController(ApplicationProvider.getApplicationContext())
        controller.setSource(File("/nonexistent/clip.mp4"))
    }

    @After fun tearDown() = controller.release()

    @Test fun segmentsBecomeAClippedPlaylist() {
        controller.setEdit(edit)
        val p = controller.player
        assertEquals(2, p.mediaItemCount)
        assertEquals(0L, p.getMediaItemAt(0).clippingConfiguration.startPositionMs)
        assertEquals(3_000L, p.getMediaItemAt(0).clippingConfiguration.endPositionMs)
        assertEquals(6_000L, p.getMediaItemAt(1).clippingConfiguration.startPositionMs)
        assertEquals(7_000L, controller.outputDuration.value)
    }

    @Test fun seekMapsOutputTimeAcrossCutsAndSpeed() {
        controller.setEdit(edit.withSpeed(2f))
        assertEquals(3_500L, controller.outputDuration.value)
        controller.seekToOutputTime(2_000) // 4 s of kept time = 1 s into the second segment
        assertEquals(1, controller.player.currentMediaItemIndex)
        assertEquals(1_000L, controller.player.currentPosition)
        assertEquals(2_000L, controller.outputPosition.value)
        assertEquals(2f, controller.player.playbackParameters.speed)
        assertEquals(1f, controller.player.playbackParameters.pitch)
    }

    @Test fun colourSpeedAndVolumeDontRebuildThePlaylist() {
        controller.setEdit(edit)
        controller.seekToOutputTime(4_000)
        val items = controller.player.currentTimeline
        controller.setEdit(edit.copy(color = Recipe(light = Light(exposure = 1f)), audio = AudioSettings(mute = true)))
        assertEquals(items, controller.player.currentTimeline)
        assertEquals(1, controller.player.currentMediaItemIndex)
        assertEquals(0f, controller.player.volume)
        controller.setEdit(edit.copy(geometry = VideoGeometry(rotate90 = 1)))
        assertEquals(2, controller.player.mediaItemCount)
    }

    @Test fun changingSegmentsKeepsTheSourceMoment() {
        controller.setEdit(edit)
        controller.seekToOutputTime(5_000) // source 8 s
        controller.setEdit(edit.deleteSegment(0)) // only [6 s, 10 s) left
        assertEquals(1, controller.player.mediaItemCount)
        assertEquals(2_000L, controller.player.currentPosition)
    }

    // ---- effects: only when they change something, and a fallback when they fail ----

    private val graded = edit.copy(color = Recipe(light = Light(exposure = 1f)))

    @Test fun identityLookRunsWithoutAnyEffects() {
        assertTrue(PreviewEffectPlan.of(edit).isEmpty)
        assertEquals(PreviewEffectPlan(emptyList(), true), PreviewEffectPlan.of(graded))
        assertFalse(PreviewEffectPlan.of(edit.copy(geometry = VideoGeometry(flipH = true))).isEmpty)
        assertTrue(PreviewEffectPlan.of(graded, disabled = true).isEmpty)

        controller.setEdit(edit)
        assertTrue(controller.effects.isEmpty)
        controller.setEdit(graded)
        assertTrue(controller.effects.color)
        controller.setEdit(edit)
        assertTrue(controller.effects.isEmpty)
    }

    @Test fun effectErrorsAreRecognised() {
        val gl = VideoFrameProcessingException("GL error 0x502 in colour pass")
        assertTrue(PreviewErrors.isEffectError(PlaybackException("x", gl, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED)))
        assertTrue(PreviewErrors.isEffectError(PlaybackException("x", RuntimeException(gl), PlaybackException.ERROR_CODE_UNSPECIFIED)))
        assertFalse(PreviewErrors.isEffectError(PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)))
    }

    @Test fun anEffectFailureFallsBackToNoEffectsAndKeepsPlaying() {
        controller.setEdit(graded)
        controller.seekToOutputTime(4_000)
        controller.play()
        controller.handleError(
            PlaybackException("boom", VideoFrameProcessingException("shader"), PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED),
        )
        assertTrue(controller.effectsDisabled)
        assertTrue(controller.effects.isEmpty)
        assertEquals(PreviewNotice.NoFilters, controller.notice.value)
        assertTrue(controller.player.playWhenReady)
        assertEquals(1, controller.player.currentMediaItemIndex)
        assertEquals(1_000L, controller.player.currentPosition)
        // Further looks stay effect-free; the notice stays while there is a look to miss.
        controller.setEdit(graded.copy(color = Recipe(light = Light(exposure = 0.5f))))
        assertTrue(controller.effects.isEmpty)
        assertEquals(PreviewNotice.NoFilters, controller.notice.value)
        controller.setEdit(edit)
        assertNull(controller.notice.value)
    }

    @Test fun otherErrorsBecomeANoticeWithRetry() {
        controller.setEdit(edit)
        controller.handleError(PlaybackException("gone", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertTrue(controller.notice.value is PreviewNotice.Failed)
        assertFalse(controller.effectsDisabled)
        controller.player.stop() // what an error leaves behind
        controller.retry()
        assertNull(controller.notice.value)
        assertNotEquals(Player.STATE_IDLE, controller.player.playbackState)
    }

    // ---- playback state across edits, background and scrubbing ----

    @Test fun trimmingKeepsThePositionAndPlayState() {
        controller.setEdit(edit)
        controller.seekToOutputTime(1_000)
        controller.play()
        controller.setEdit(edit.copy(segments = listOf(TimeRange(0, 3_000), TimeRange(6_000, 9_000))))
        assertEquals(1_000L, controller.outputPosition.value)
        assertEquals(0, controller.player.currentMediaItemIndex)
        assertEquals(1_000L, controller.player.currentPosition)
        assertTrue(controller.player.playWhenReady)
        assertEquals(9_000L, controller.player.getMediaItemAt(1).clippingConfiguration.endPositionMs)
    }

    @Test fun playAtTheEndStartsOver() {
        controller.setEdit(edit)
        controller.seekToOutputTime(7_000 - 40)
        controller.play()
        assertEquals(0L, controller.outputPosition.value)
        assertEquals(0, controller.player.currentMediaItemIndex)
        assertTrue(controller.player.playWhenReady)
        controller.togglePlay()
        assertFalse(controller.player.playWhenReady)
    }

    @Test fun backgroundStopsAndForegroundPreparesAgain() {
        controller.setEdit(edit)
        controller.seekToOutputTime(5_000)
        controller.onBackground()
        assertEquals(Player.STATE_IDLE, controller.player.playbackState)
        controller.onForeground()
        assertNotEquals(Player.STATE_IDLE, controller.player.playbackState)
        assertEquals(2, controller.player.mediaItemCount)
        // Play also recovers a stopped player.
        controller.onBackground()
        controller.play()
        assertNotEquals(Player.STATE_IDLE, controller.player.playbackState)
    }

    @Test fun handleScrubPreviewsSourceTimeThenRestsOnTheEdit() {
        controller.setEdit(edit)
        controller.play()
        controller.beginScrub(sourceTime = true)
        assertFalse(controller.player.playWhenReady)
        assertTrue(controller.player.isScrubbingModeEnabled)
        assertEquals(1, controller.player.mediaItemCount) // the whole, unclipped source
        controller.scrubToSource(4_500) // cut-out time today
        assertEquals(4_500L, controller.player.currentPosition)
        val trimmed = edit.copy(segments = listOf(TimeRange(0, 4_500), TimeRange(6_000, 10_000)))
        controller.endScrub(trimmed, restOutputMs = 4_460)
        assertFalse(controller.player.isScrubbingModeEnabled)
        assertEquals(2, controller.player.mediaItemCount)
        assertEquals(4_500L, controller.player.getMediaItemAt(0).clippingConfiguration.endPositionMs)
        assertEquals(4_460L, controller.outputPosition.value)
        assertEquals(0, controller.player.currentMediaItemIndex)
        assertEquals(4_460L, controller.player.currentPosition)
        assertTrue(controller.player.playWhenReady) // it was playing
    }

    @Test fun playheadScrubSeeksInOutputTime() {
        controller.setEdit(edit)
        controller.beginScrub(sourceTime = false)
        assertEquals(2, controller.player.mediaItemCount)
        controller.seekToOutputTime(5_000)
        controller.endScrub()
        assertEquals(5_000L, controller.outputPosition.value)
        assertEquals(1, controller.player.currentMediaItemIndex)
        assertFalse(controller.player.playWhenReady)
    }
}

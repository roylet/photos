package com.roylet.photos.video

import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.video.model.AudioSettings
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.model.VideoGeometry
import com.roylet.photos.video.preview.VideoPreviewController
import org.junit.After
import org.junit.Assert.assertEquals
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
}

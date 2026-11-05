package com.roylet.photos.ui.components

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeepScreenOnTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun refCountKeepsFlagUntilLastHolderReleases() {
        val view = View(rule.activity)
        ScreenOnRequests.acquire(view)
        ScreenOnRequests.acquire(view)
        assertTrue(view.keepScreenOn)
        ScreenOnRequests.release(view)
        assertTrue("one holder still wants it", view.keepScreenOn)
        ScreenOnRequests.release(view)
        assertFalse(view.keepScreenOn)
        ScreenOnRequests.release(view) // extra release is harmless
        assertFalse(view.keepScreenOn)
        assertEquals(0, ScreenOnRequests.count(view))
    }

    @Test fun twoComposedHoldersDoNotClearEachOther() {
        var first by mutableStateOf(true)
        var second by mutableStateOf(true)
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            KeepScreenOn(first)
            KeepScreenOn(second)
        }
        rule.waitForIdle()
        assertTrue(view.keepScreenOn)
        first = false
        rule.waitForIdle()
        assertTrue(view.keepScreenOn)
        second = false
        rule.waitForIdle()
        assertFalse(view.keepScreenOn)
    }

    @Test fun followsIsPlayingAndClearsOnDispose() {
        val player = FakePlayer()
        var shown by mutableStateOf(true)
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            if (shown) KeepScreenOnWhilePlaying(player)
        }
        rule.waitForIdle()
        assertFalse("not playing yet", view.keepScreenOn)

        rule.runOnIdle { player.play() }
        rule.waitForIdle()
        assertTrue("playing", view.keepScreenOn)

        rule.runOnIdle { player.pause() }
        rule.waitForIdle()
        assertFalse("paused", view.keepScreenOn)

        rule.runOnIdle { player.play() }
        rule.waitForIdle()
        assertTrue(view.keepScreenOn)

        shown = false
        rule.waitForIdle()
        assertFalse("left the screen", view.keepScreenOn)
    }

    @Test fun bufferingOrEndedIsNotPlaying() {
        val player = FakePlayer()
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            KeepScreenOnWhilePlaying(player)
        }
        rule.runOnIdle { player.play() }
        rule.waitForIdle()
        assertTrue(view.keepScreenOn)
        rule.runOnIdle { player.setPlaybackStateForTest(Player.STATE_BUFFERING) }
        rule.waitForIdle()
        assertFalse("buffering", view.keepScreenOn)
        rule.runOnIdle { player.setPlaybackStateForTest(Player.STATE_ENDED) }
        rule.waitForIdle()
        assertFalse("ended", view.keepScreenOn)
    }
}

/** A minimal player whose play/pause and playback state are set directly. */
@androidx.annotation.OptIn(UnstableApi::class)
private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var playWhenReady = false
    private var playbackState = Player.STATE_READY

    fun setPlaybackStateForTest(state: Int) {
        playbackState = state
        invalidateState()
    }

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).build())
        .setPlaylist(listOf(MediaItemData.Builder("item").setMediaItem(MediaItem.fromUri("file:///v.mp4")).build()))
        .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackState(playbackState)
        .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        return Futures.immediateVoidFuture()
    }
}

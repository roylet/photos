package com.roylet.photos.ui.components

import android.view.View
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.Player
import java.util.WeakHashMap

/**
 * Reference-counted `keepScreenOn` requests per view: the flag is on while at least one holder
 * asks for it, so two players on one screen (pager pages composed beyond the viewport, a live
 * photo's motion beside a video) never clear each other's request. Main thread only.
 */
internal object ScreenOnRequests {
    private val counts = WeakHashMap<View, Int>()

    @MainThread
    fun acquire(view: View) {
        val n = (counts[view] ?: 0) + 1
        counts[view] = n
        if (n == 1) view.keepScreenOn = true
    }

    @MainThread
    fun release(view: View) {
        val n = (counts[view] ?: return) - 1
        if (n <= 0) {
            counts.remove(view)
            view.keepScreenOn = false
        } else {
            counts[view] = n
        }
    }

    internal fun count(view: View): Int = counts[view] ?: 0
}

/** Keeps the screen awake while [active] and this is in composition; the request is dropped on dispose. */
@Composable
fun KeepScreenOn(active: Boolean) {
    if (!active) return
    val view = LocalView.current
    DisposableEffect(view) {
        ScreenOnRequests.acquire(view)
        onDispose { ScreenOnRequests.release(view) }
    }
}

/** [Player.isPlaying] as Compose state (false for a null player). */
@Composable
fun rememberIsPlaying(player: Player?): Boolean {
    if (player == null) return false
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(l)
        playing = player.isPlaying
        onDispose { player.removeListener(l) }
    }
    return playing
}

/** Keeps the screen awake only while [player] is actually playing (not paused, ended or stalled). */
@Composable
fun KeepScreenOnWhilePlaying(player: Player?) = KeepScreenOn(rememberIsPlaying(player))

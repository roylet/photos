package com.roylet.photos.cast

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The real [CastDevice]: Media3's [RemoteCastPlayer] (with [PhotoMediaItemConverter], so photos go
 * out as Cast photos) plus the Cast SDK's session and device-state listeners.
 *
 * [init] must run on the main thread (MainActivity does it); without Google Play services the
 * shared CastContext never arrives and [availability] stays [CastAvailability.Unsupported].
 * [player] is what [CastService]'s MediaSession wraps.
 */
@OptIn(UnstableApi::class)
class Media3CastDevice(context: Context) : CastDevice {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _availability = MutableStateFlow(CastAvailability.Unsupported)
    override val availability: StateFlow<CastAvailability> = _availability.asStateFlow()
    private val _target = MutableStateFlow<CastTarget?>(null)
    override val target: StateFlow<CastTarget?> = _target.asStateFlow()
    private val _playback = MutableStateFlow(RemotePlayback())
    override val playback: StateFlow<RemotePlayback> = _playback.asStateFlow()

    private var castContext: CastContext? = null

    /** The remote player, once the Cast SDK is up. */
    var player: RemoteCastPlayer? = null
        private set

    private var started = false

    fun init() {
        if (started) return
        started = true
        runCatching {
            CastContext.getSharedInstance(app, ContextCompat.getMainExecutor(app))
                .addOnSuccessListener { setUp(it) }
                .addOnFailureListener { Log.i(TAG, "Cast unavailable: ${it.message}") }
        }.onFailure { Log.i(TAG, "Cast unavailable: ${it.message}") }
    }

    private fun setUp(ctx: CastContext) {
        castContext = ctx
        val p = runCatching { RemoteCastPlayer.Builder(app).setMediaItemConverter(PhotoMediaItemConverter()).build() }
            .onFailure { Log.w(TAG, "No cast player", it) }.getOrNull() ?: return
        player = p
        p.addListener(playerListener)
        ctx.addCastStateListener(stateListener)
        stateListener.onCastStateChanged(ctx.castState)
        ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        ctx.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::connected)
    }

    private val stateListener = CastStateListener { state ->
        _availability.value = when (state) {
            CastState.NO_DEVICES_AVAILABLE -> CastAvailability.NoDevices
            CastState.CONNECTING -> CastAvailability.Connecting
            CastState.CONNECTED -> CastAvailability.Connected
            else -> CastAvailability.Available
        }
    }

    private fun session(): CastSession? = castContext?.sessionManager?.currentCastSession

    private fun connected(session: CastSession) {
        _target.value = CastTarget(session.castDevice?.friendlyName?.takeIf { it.isNotBlank() } ?: "TV")
        _playback.value = RemotePlayback(volume = runCatching { session.volume.toFloat() }.getOrDefault(1f))
        tick()
    }

    private fun disconnected() {
        _target.value = null
        _playback.value = RemotePlayback()
        main.removeCallbacks(ticker)
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = connected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected(session)
        override fun onSessionEnded(session: CastSession, error: Int) = disconnected()
        override fun onSessionStartFailed(session: CastSession, error: Int) = disconnected()
        override fun onSessionResumeFailed(session: CastSession, error: Int) = disconnected()
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
        override fun onPlayerError(error: PlaybackException) {
            _playback.value = _playback.value.copy(error = "${error.errorCodeName}: ${error.message.orEmpty()}", playing = false, buffering = false)
        }
    }

    /** Position and volume while connected; the Cast SDK only pushes state changes. */
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (_target.value != null) main.postDelayed(this, TICK_MS)
        }
    }

    private fun tick() {
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun refresh() {
        val p = player ?: return
        if (_target.value == null) return
        val state = p.playbackState
        val previous = _playback.value
        val id = p.currentMediaItem?.mediaId
        _playback.value = previous.copy(
            mediaId = id,
            playing = p.isPlaying,
            buffering = state == Player.STATE_BUFFERING,
            ended = state == Player.STATE_ENDED,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it > 0 } ?: 0,
            volume = runCatching { session()?.volume?.toFloat() }.getOrNull() ?: previous.volume,
            // A new item clears the last one's error.
            error = if (id != previous.mediaId) null else previous.error ?: p.playerError?.let { "${it.errorCodeName}: ${it.message.orEmpty()}" },
        )
    }

    override fun load(media: CastMedia) {
        val p = player ?: return
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setMediaType(if (media.isVideo) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MIXED)
            .build()
        val item = MediaItem.Builder()
            .setMediaId(media.id)
            .setUri(Uri.parse(media.url))
            .setMimeType(media.contentType)
            .setMediaMetadata(metadata)
            .build()
        _playback.value = _playback.value.copy(mediaId = media.id, error = null, ended = false, positionMs = 0, durationMs = media.durationMs ?: 0)
        p.setMediaItem(item)
        p.prepare()
        p.play()
    }

    override fun play() { player?.play() }
    override fun pause() { player?.pause() }
    override fun seekTo(positionMs: Long) { player?.seekTo(positionMs) }

    override fun setVolume(fraction: Float) {
        val v = fraction.coerceIn(0f, 1f)
        runCatching { session()?.setVolume(v.toDouble()) }
        _playback.value = _playback.value.copy(volume = v)
    }

    override fun disconnect() {
        runCatching { castContext?.sessionManager?.endCurrentSession(true) }
    }

    private companion object {
        const val TAG = "Cast"
        const val TICK_MS = 500L
    }
}

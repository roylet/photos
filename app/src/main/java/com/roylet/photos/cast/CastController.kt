package com.roylet.photos.cast

import com.roylet.photos.domain.timeline.TimelineAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What's on the TV. */
data class NowCasting(val asset: TimelineAsset, val media: CastMedia, val route: CastRoute, val hevc: Boolean = false) {
    val viaPhone: Boolean get() = route !is CastRoute.Direct
}

/** Everything the cast UI shows (banner, remote panel, mini controller, sheets). */
data class CastState(
    val availability: CastAvailability = CastAvailability.Unsupported,
    /** The connected TV; casting while non-null. */
    val target: CastTarget? = null,
    val current: NowCasting? = null,
    /** Resolving or sending an item. */
    val loading: Boolean = false,
    val playback: RemotePlayback = RemotePlayback(),
    /** A friendly sentence about what failed, for the current item. */
    val error: String? = null,
    /** Something worth knowing once (e.g. why this goes through the phone). */
    val notice: String? = null,
    /** An Immich item needs a login session: "Sign in once to cast". */
    val needsSignIn: Boolean = false,
    /** The slideshow on the TV, while one runs. */
    val slideshow: SlideshowState? = null,
) {
    val casting: Boolean get() = target != null
    /** Show the cast button at all (Play services present). */
    val supported: Boolean get() = availability != CastAvailability.Unsupported
    /** Devices around (or connected): the Photos header's cast icon. */
    val devicesAvailable: Boolean get() = availability == CastAvailability.Available || availability == CastAvailability.Connecting || availability == CastAvailability.Connected
}

/** Starts and stops [CastService] (the Media3 session that also keeps the relay alive). */
interface CastServiceLauncher {
    fun start()
    fun stop()
}

/** The relay's lifetime, as the controller drives it ([PhoneMediaProxy]). */
interface RelayLifecycle {
    fun start(): Boolean
    fun stop()
}

/**
 * The cast session's brain, process-wide (`container.cast`): the viewer tells it what's on screen
 * ([show]) and it puts that on the TV; it runs the slideshow, the transport controls and the
 * clean-up when the session ends (the Immich child session deleted, the relay stopped, the
 * service stopped).
 *
 * The phone's timeline is the queue: one item is loaded at a time, as Immich's apps do, so
 * swiping on the phone, the filmstrip and the slideshow all go through [show].
 * Main thread only.
 */
class CastController(
    private val device: CastDevice,
    private val resolver: CastResolver,
    private val keys: () -> CastSessionManager?,
    private val relay: RelayLifecycle,
    private val service: CastServiceLauncher,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    private var loadJob: Job? = null
    /** The last item asked for (re-sent after "Sign in once to cast"). */
    private var requested: TimelineAsset? = null
    private val shownNotes = HashSet<String>()
    private val slideshows = SlideshowRunner(scope, { _, asset -> show(asset) }, { _state.value.playback }, { s -> _state.update { it.copy(slideshow = s) } })

    init {
        scope.launch { device.availability.collect { a -> _state.update { it.copy(availability = a) } } }
        scope.launch {
            var was: CastTarget? = null
            device.target.collect { t ->
                _state.update { it.copy(target = t) }
                if (t != null && was == null) started()
                if (t == null && was != null) ended()
                was = t
            }
        }
        scope.launch {
            device.playback.collect { p ->
                _state.update { s ->
                    val failed = p.error != null && s.current != null && p.mediaId == s.current.media.id
                    s.copy(playback = p, error = if (failed) friendlyError(s.current!!) else s.error)
                }
            }
        }
    }

    val casting: Boolean get() = _state.value.casting

    /** Puts [asset] on the TV (the viewer calls this when a page settles while casting). */
    fun show(asset: TimelineAsset) {
        requested = asset
        if (!casting) return
        val s = _state.value
        if (s.current?.asset?.id == asset.id && s.error == null && !s.needsSignIn) return
        load(asset)
    }

    private fun load(asset: TimelineAsset) {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null, needsSignIn = false) }
        loadJob = scope.launch {
            when (val r = resolver.resolve(asset)) {
                is Resolution.Ready -> {
                    if (!casting) return@launch
                    device.load(r.media)
                    val note = r.note?.takeIf { shownNotes.add(it) }
                    _state.update { it.copy(current = NowCasting(asset, r.media, r.route, r.hevc), loading = false, notice = note ?: it.notice) }
                }
                Resolution.NeedsSignIn -> _state.update { it.copy(loading = false, needsSignIn = true) }
                is Resolution.Failed -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    /** "Slideshow on TV": walks [queue] from [startIndex], one item every [intervalMs] (videos play through). */
    fun startSlideshow(queue: SlideshowQueue, startIndex: Int, intervalMs: Long, shuffle: Boolean) {
        if (!casting) return
        slideshows.start(queue, startIndex, intervalMs, shuffle)
    }

    fun stopSlideshow() = slideshows.stop()

    fun togglePlay() {
        if (_state.value.playback.playing) device.pause() else device.play()
    }

    fun seekTo(positionMs: Long) = device.seekTo(positionMs.coerceAtLeast(0))

    fun setVolume(fraction: Float) = device.setVolume(fraction)

    /** STOP CASTING: ends the session (the clean-up runs when the device reports it ended). */
    fun stop() {
        slideshows.stop()
        device.disconnect()
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissSignIn() = _state.update { it.copy(needsSignIn = false) }

    /** "Sign in once to cast" stored a new login session: try the item again. */
    fun onSignedIn() {
        keys()?.onSignedIn()
        _state.update { it.copy(needsSignIn = false) }
        requested?.let { if (casting) load(it) }
    }

    /** [CastService] is up: bind the relay now, so the first phone item doesn't wait for it. */
    fun onServiceStarted() {
        if (casting) relay.start()
    }

    fun onServiceStopped() {}

    private fun started() {
        shownNotes.clear()
        service.start()
        // The viewer was open on something when the session connected: show it.
        requested?.let(::load)
    }

    private fun ended() {
        loadJob?.cancel()
        slideshows.stop()
        _state.update { it.copy(current = null, loading = false, error = null, notice = null, needsSignIn = false, playback = RemotePlayback()) }
        scope.launch { runCatching { keys()?.end() } }
        relay.stop()
        service.stop()
    }

    private fun friendlyError(now: NowCasting): String = when {
        now.media.isVideo && now.hevc -> "TV couldn't load this video (HEVC not supported)"
        now.media.isVideo -> "TV couldn't play this video. Older Chromecasts can't play HEVC (H.265) or 4K."
        now.viaPhone -> "TV couldn't load this photo from your phone. Phone and TV must be on the same Wi-Fi."
        else -> "TV couldn't load this photo"
    }
}

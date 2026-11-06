package com.roylet.photos.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/** A Chromecast that remembers what it was asked to do. */
class FakeCastDevice(availability: CastAvailability = CastAvailability.Available) : CastDevice {
    override val availability = MutableStateFlow(availability)
    override val target = MutableStateFlow<CastTarget?>(null)
    override val playback = MutableStateFlow(RemotePlayback())
    val loaded = mutableListOf<CastMedia>()
    val seeks = mutableListOf<Long>()
    var disconnects = 0

    fun connect(name: String = "Living Room TV") {
        target.value = CastTarget(name)
    }

    override fun load(media: CastMedia) {
        loaded += media
        playback.value = RemotePlayback(mediaId = media.id, playing = media.isVideo, durationMs = media.durationMs ?: 0, volume = playback.value.volume)
    }

    override fun play() { playback.value = playback.value.copy(playing = true) }
    override fun pause() { playback.value = playback.value.copy(playing = false) }
    override fun seekTo(positionMs: Long) { seeks += positionMs; playback.value = playback.value.copy(positionMs = positionMs) }
    override fun setVolume(fraction: Float) { playback.value = playback.value.copy(volume = fraction) }

    override fun disconnect() {
        disconnects++
        target.value = null
    }

    /** The receiver reports it couldn't load the current item. */
    fun fail(message: String = "ERROR_CODE_IO_UNSPECIFIED: load failed") {
        playback.value = playback.value.copy(error = message, playing = false)
    }
}

/** A relay that hands out fixed LAN URLs. */
class FakeProxy(var unavailable: String? = null, var hevc: Boolean = false) : MediaProxy {
    val prepared = mutableListOf<CastRoute>()
    override suspend fun prepare(route: CastRoute, isVideo: Boolean): ProxyItem {
        unavailable?.let { throw ProxyUnavailableException(it) }
        prepared += route
        val key = when (route) {
            is CastRoute.PhoneLocal -> "local-${route.mediaStoreId}"
            is CastRoute.PhoneImmich -> "immich-${route.immichId}"
            is CastRoute.Direct -> error("direct")
        }
        return ProxyItem("http://192.168.1.23:41234/tok/m/$key.${if (isVideo) "mp4" else "jpg"}", if (isVideo) "video/mp4" else "image/jpeg", hevc = hevc && isVideo)
    }
}

class CountingRelay : RelayLifecycle {
    var starts = 0
    var stops = 0
    override fun start(): Boolean { starts++; return true }
    override fun stop() { stops++ }
}

class CountingLauncher : CastServiceLauncher {
    var starts = 0
    var stops = 0
    override fun start() { starts++ }
    override fun stop() { stops++ }
}

/** A controller over fakes: no server, every item through [proxy]. */
fun fakeCastController(
    scope: CoroutineScope,
    device: FakeCastDevice = FakeCastDevice(),
    proxy: MediaProxy = FakeProxy(),
    resolver: CastResolver = CastResolver({ null }, { null }, { null }, proxy),
    keys: () -> CastSessionManager? = { null },
    relay: RelayLifecycle = CountingRelay(),
    launcher: CastServiceLauncher = CountingLauncher(),
) = CastController(device, resolver, keys, relay, launcher, scope)

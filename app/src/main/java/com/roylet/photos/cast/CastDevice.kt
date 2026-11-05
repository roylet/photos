package com.roylet.photos.cast

import kotlinx.coroutines.flow.StateFlow

/** Whether there's anything to cast to (drives the Photos header's cast icon). */
enum class CastAvailability {
    /** No Google Play services / Cast SDK on this phone: no cast UI at all. */
    Unsupported,
    NoDevices,
    Available,
    Connecting,
    Connected,
}

/** The TV (or speaker group) a cast session is connected to. */
data class CastTarget(val name: String)

/** One item for the TV: a URL it can fetch, and what that URL really returns. */
data class CastMedia(
    /** The app's asset id (`local:` or an Immich UUID). */
    val id: String,
    val url: String,
    /** The Content-Type the URL returns (never the original's mime when the URL serves a derivative). */
    val contentType: String,
    val isVideo: Boolean,
    val title: String? = null,
    val durationMs: Long? = null,
)

/** What the TV is doing, as last reported. */
data class RemotePlayback(
    val mediaId: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 0..1, the receiver's volume. */
    val volume: Float = 1f,
    /** The receiver couldn't load or play [mediaId]; a raw description (the controller words it for people). */
    val error: String? = null,
)

/**
 * The seam between [CastController] and the Cast SDK: one item at a time (the controller keeps
 * the "queue": the viewer's timeline), plus transport controls. [Media3CastDevice] is the real
 * one (Media3's RemoteCastPlayer); tests use a fake. Call from the main thread.
 */
interface CastDevice {
    val availability: StateFlow<CastAvailability>
    /** The connected TV, or null while not casting. */
    val target: StateFlow<CastTarget?>
    val playback: StateFlow<RemotePlayback>
    fun load(media: CastMedia)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(fraction: Float)
    /** Ends the cast session and closes the receiver on the TV. */
    fun disconnect()
}

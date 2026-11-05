package com.roylet.photos.cast

import com.roylet.photos.domain.timeline.TimelineAsset

/** One item [LocalMediaServer] is ready to serve: its URL and what that URL returns. */
data class ProxyItem(val url: String, val contentType: String, val hevc: Boolean = false)

/** Failures the phone relay can name, so the UI can say what to fix. */
class ProxyUnavailableException(message: String) : Exception(message)

/** The phone-side relay as the resolver sees it ([LocalMediaServer] behind it). */
interface MediaProxy {
    /** Readies [route] (a phone file, or an Immich asset to relay) and returns its URL; throws [ProxyUnavailableException]. */
    suspend fun prepare(route: CastRoute, isVideo: Boolean): ProxyItem
}

/** What the controller can send to the TV for one asset. */
sealed interface Resolution {
    /** [note] is shown once, e.g. why it goes through the phone. */
    data class Ready(val media: CastMedia, val route: CastRoute, val hevc: Boolean = false, val note: String? = null) : Resolution
    data object NeedsSignIn : Resolution
    data class Failed(val message: String) : Resolution
}

/**
 * Picks the route for an asset and builds what the TV is given:
 * - Immich (or both): the direct public-HTTPS URL with a child-session key, announcing the type a
 *   HEAD of it reports ([CastUrls.photoContentType] / [CastUrls.videoContentType]);
 * - phone-only: [LocalMediaServer];
 * - Immich without a usable public address, or with no session token while a phone copy exists:
 *   through the phone too, with a note.
 */
class CastResolver(
    /** [CastUrls.directBase] for the current server and setting; null when direct casting can't work. */
    private val directBase: () -> String?,
    private val keys: () -> CastSessionManager?,
    /** HEAD [url] through the shared client: its Content-Type, or null. */
    private val probe: suspend (url: String) -> String?,
    private val proxy: MediaProxy,
    /** Why Immich items go through the phone (no public HTTPS address, or the setting is off). */
    private val relayNote: () -> String = { NOTE_NO_PUBLIC },
) {
    suspend fun resolve(asset: TimelineAsset): Resolution {
        val base = directBase()
        val route = CastRouting.route(asset, direct = base != null) ?: return Resolution.Failed("Nothing to cast for this item")
        val title = CastUrls.title(asset)
        if (route is CastRoute.Direct && base != null) {
            val sessions = keys() ?: return viaPhone(asset, title, CastRoute.PhoneImmich(route.immichId), null)
            when (val k = sessions.key()) {
                is KeyResult.Ok -> return direct(asset, title, base, route.immichId, k.key.token)
                // A phone copy can still go without signing in.
                KeyResult.NeedsSignIn -> {
                    val local = CastRouting.mediaStoreId(asset) ?: return Resolution.NeedsSignIn
                    return viaPhone(asset, title, CastRoute.PhoneLocal(local), null)
                }
                is KeyResult.Failed -> {
                    val local = CastRouting.mediaStoreId(asset) ?: return Resolution.Failed(k.message)
                    return viaPhone(asset, title, CastRoute.PhoneLocal(local), null)
                }
            }
        }
        val note = if (route is CastRoute.PhoneImmich) relayNote() else null
        return viaPhone(asset, title, route, note)
    }

    private suspend fun direct(asset: TimelineAsset, title: String, base: String, id: String, key: String): Resolution {
        if (asset.isVideo) {
            val url = CastUrls.video(base, id, key)
            return Resolution.Ready(CastMedia(asset.id, url, CastUrls.videoContentType(probe(url)), isVideo = true, title = title, durationMs = asset.durationMs), CastRoute.Direct(id))
        }
        val full = CastUrls.photo(base, id, key)
        val probed = probe(full)
        // Fullsize generation off: the server redirects to the original, which may be HEIC.
        val url = if (CastUrls.photoNeedsPreview(probed)) CastUrls.photoPreview(base, id, key) else full
        val type = if (url == full) CastUrls.photoContentType(probed) else CastUrls.photoContentType(probe(url))
        return Resolution.Ready(CastMedia(asset.id, url, type, isVideo = false, title = title), CastRoute.Direct(id))
    }

    private suspend fun viaPhone(asset: TimelineAsset, title: String, route: CastRoute, note: String?): Resolution = try {
        val item = proxy.prepare(route, asset.isVideo)
        Resolution.Ready(
            CastMedia(asset.id, item.url, item.contentType, isVideo = asset.isVideo, title = title, durationMs = asset.durationMs),
            route, hevc = item.hevc, note = note,
        )
    } catch (e: ProxyUnavailableException) {
        Resolution.Failed(e.message ?: "Couldn't cast this item")
    }

    companion object {
        const val NOTE_NO_PUBLIC = "Casting through your phone: Immich has no public HTTPS address. Keep the phone on the same Wi-Fi."
        const val NOTE_PUBLIC_OFF = "Casting through your phone (\"Use public address for casting\" is off). Keep the phone on the same Wi-Fi."
    }
}

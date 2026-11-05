package com.roylet.photos.cast

import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.net.Subnet
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where the TV fetches an asset from. */
sealed interface CastRoute {
    /** Straight from the Immich server's public HTTPS address, with a `sessionKey`. */
    data class Direct(val immichId: String) : CastRoute
    /** A file on this phone, through [LocalMediaServer]. */
    data class PhoneLocal(val mediaStoreId: Long) : CastRoute
    /** An Immich asset relayed by the phone (no public HTTPS address to send the TV to). */
    data class PhoneImmich(val immichId: String) : CastRoute
}

object CastRouting {
    /** The Immich copy of [asset]: its remote ref when merged, else its id unless that's a phone id. */
    fun immichId(asset: TimelineAsset): String? = asset.remoteRef?.takeIf { it.isImmich }?.key ?: AssetRef.immichIdOrNull(asset.id)

    /** The phone copy of [asset]. */
    fun mediaStoreId(asset: TimelineAsset): Long? = asset.localRef?.mediaStoreId ?: AssetRef.parse(asset.id)?.mediaStoreId

    /**
     * Prefer the server (no phone in the middle, works with the phone asleep), then the phone's own
     * file, then the server relayed through the phone. [direct] is whether a direct URL can be
     * made at all (a public HTTPS address and the setting on).
     */
    fun route(asset: TimelineAsset, direct: Boolean): CastRoute? {
        val remote = immichId(asset)
        val local = mediaStoreId(asset)
        return when {
            direct && remote != null -> CastRoute.Direct(remote)
            local != null -> CastRoute.PhoneLocal(local)
            remote != null -> CastRoute.PhoneImmich(remote)
            else -> null
        }
    }
}

/**
 * The URLs a Chromecast is given for Immich assets, as Immich's own apps build them, and the
 * content type to announce for each.
 */
object CastUrls {
    /**
     * The base for direct URLs: the saved public URL, only if it's HTTPS on a public DNS name.
     * The Chromecast resolves names with public DNS (often 8.8.8.8, not the router) and insists
     * on a valid certificate, so a LAN address, an IP literal or `.local` won't do. Null (relay
     * through the phone) when the setting is off or the address doesn't qualify.
     */
    fun directBase(publicUrl: String?, usePublic: Boolean = true): String? {
        if (!usePublic || publicUrl == null) return null
        val url = publicUrl.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https") return null
        val host = url.host
        if (Subnet.parse(host) != null || host.contains(':') || Subnet.isPrivateHost(host) || !host.contains('.')) return null
        return publicUrl.trimEnd('/')
    }

    /** The fullsize JPEG (the server converts HEIC/RAW), with edits applied. */
    fun photo(base: String, id: String, sessionKey: String): String = build(base, "/api/assets/$id/thumbnail", sessionKey, "size" to "fullsize", "edited" to "true")

    /** The ~1440 px preview, when fullsize comes back as something a TV can't show. */
    fun photoPreview(base: String, id: String, sessionKey: String): String = build(base, "/api/assets/$id/thumbnail", sessionKey, "size" to "preview", "edited" to "true")

    /** The server's playback stream (the transcode when there is one, else the original). Range requests work. */
    fun video(base: String, id: String, sessionKey: String): String = build(base, "/api/assets/$id/video/playback", sessionKey)

    private fun build(base: String, path: String, sessionKey: String, vararg query: Pair<String, String>): String {
        val b = "$base$path".toHttpUrlOrNull()!!.newBuilder()
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        // Last, as Immich's apps do: `&sessionKey=<token>`.
        b.addQueryParameter("sessionKey", sessionKey)
        return b.build().toString()
    }

    /** Image types the Default Media Receiver (a Chrome renderer) shows. */
    private val CASTABLE_IMAGES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp")

    /**
     * What to announce for a photo URL: what a HEAD of it said, if the TV can show that; else
     * image/jpeg, which is what `size=fullsize` serves. Never the original's mime: announcing
     * `image/heic` for the converted JPEG is the bug in Immich's mobile cast.
     */
    fun photoContentType(probed: String?): String = baseType(probed)?.takeIf { it in CASTABLE_IMAGES } ?: "image/jpeg"

    /** A HEAD said the "fullsize" is an image the TV can't show (the server redirected to a HEIC original). */
    fun photoNeedsPreview(probed: String?): Boolean = baseType(probed)?.let { it.startsWith("image/") && it !in CASTABLE_IMAGES } == true

    /**
     * What to announce for a video URL: the HEAD's type if it's video, with QuickTime announced as
     * MP4 (same codecs; the receiver refuses `video/quicktime`), else video/mp4.
     */
    fun videoContentType(probed: String?): String {
        val t = baseType(probed)?.takeIf { it.startsWith("video/") } ?: return "video/mp4"
        return if (t == "video/quicktime") "video/mp4" else t
    }

    private fun baseType(contentType: String?): String? = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** The title the TV and the notification show: the date taken, and the place when known. */
    fun title(asset: TimelineAsset): String {
        val zone = ZoneOffset.ofTotalSeconds((asset.localOffsetHours * 3600).toInt().coerceIn(-18 * 3600, 18 * 3600))
        val date = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(asset.fileCreatedAt).atZone(zone))
        return listOfNotNull(date, asset.city ?: asset.country).joinToString(" · ")
    }
}

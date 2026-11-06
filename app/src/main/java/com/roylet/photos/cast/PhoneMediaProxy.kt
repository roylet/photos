package com.roylet.photos.cast

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections

/** A phone photo or video, as the relay needs to know it. */
data class PhoneFile(
    val id: Long,
    val isVideo: Boolean,
    val mimeType: String?,
    /** Stored size (before orientation). */
    val width: Int,
    val height: Int,
    /** Changes whenever the file does (cache key). */
    val generation: Long,
)

/** Reading phone files ([AndroidPhoneFiles] on a device; fakes in tests). */
interface PhoneFiles {
    suspend fun info(id: Long): PhoneFile?
    /** The whole original (photos only: they're transcoded in memory). */
    fun readAll(file: PhoneFile): ByteArray
    /** A seekable body for a video. */
    fun video(file: PhoneFile): ProxyContent
    /** Whether the video is HEVC (older Chromecasts can't play it). */
    fun isHevc(file: PhoneFile): Boolean = false
}

/** Fetching Immich assets for the relay, through the shared client (API key on the phone only). */
interface ImmichRelay {
    /** The ~1440 px preview and its content type. */
    suspend fun preview(id: String): Pair<ByteArray, String>
    /** The playback stream, ranged. */
    suspend fun video(id: String): ProxyContent
}

/**
 * The phone side of casting ([MediaProxy]): owns the [LocalMediaServer] for the cast session and
 * readies what it serves.
 * - Phone photos: JPEGs ≤ [PhotoPlan.MAX_EDGE] go as they are; everything else (HEIC, PNG, big
 *   JPEGs) is transcoded to a ≤ 1920 px JPEG once and kept for a few items.
 * - Phone videos: the original file, ranged (H.264 plays everywhere; HEVC is flagged for the
 *   error message on TVs that can't play it).
 * - Immich assets (no public HTTPS address): the preview JPEG, or the playback stream ranged.
 *
 * [start] binds to the Wi-Fi address (from [CastService]); [prepare] starts it if needed and
 * rebinds when the address changed; [onNetworkChanged] stops it as soon as the address goes.
 * [stop] at the end of the cast session.
 */
class PhoneMediaProxy(
    private val wifiAddress: () -> String?,
    private val phone: PhoneFiles,
    private val immich: () -> ImmichRelay?,
    private val transcoder: JpegTranscoder,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newServer: (host: String) -> LocalMediaServer = { LocalMediaServer(it) },
) : MediaProxy {
    @Volatile private var server: LocalMediaServer? = null
    private val jpegs: MutableMap<String, ByteArray> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > CACHED_JPEGS
        },
    )

    val running: Boolean get() = server?.isAlive == true

    /** Binds the server to the current Wi-Fi address; false when there's none. */
    @Synchronized
    fun start(): Boolean = ensureServer() != null

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        jpegs.clear()
    }

    /**
     * The network changed: if the Wi-Fi address the server is bound to is gone (Wi-Fi off, another
     * network, mobile data), stop it now rather than at the next item. [prepare] rebinds.
     */
    @Synchronized
    fun onNetworkChanged() {
        val s = server ?: return
        if (wifiAddress() == s.host) return
        s.stop()
        server = null
    }

    @Synchronized
    private fun ensureServer(): LocalMediaServer? {
        val host = wifiAddress() ?: return null
        val s = server
        if (s != null && s.isAlive && s.host == host) return s
        s?.stop()
        return try {
            newServer(host).also { it.startServing(); server = it }
        } catch (e: IOException) {
            server = null
            null
        }
    }

    override suspend fun prepare(route: CastRoute, isVideo: Boolean): ProxyItem = withContext(io) {
        val s = ensureServer() ?: throw ProxyUnavailableException(NO_WIFI)
        when (route) {
            is CastRoute.PhoneLocal -> preparePhone(s, route.mediaStoreId)
            is CastRoute.PhoneImmich -> prepareImmich(s, route.immichId, isVideo)
            is CastRoute.Direct -> throw IllegalArgumentException("direct routes don't use the relay")
        }
    }

    private suspend fun preparePhone(s: LocalMediaServer, id: Long): ProxyItem {
        val file = phone.info(id) ?: throw ProxyUnavailableException("This item is no longer on the phone")
        if (file.isVideo) {
            val type = CastUrls.videoContentType(file.mimeType)
            val url = s.register("local-$id.${extension(type)}") { phone.video(file) }
            return ProxyItem(url, type, hevc = runCatching { phone.isHevc(file) }.getOrDefault(false))
        }
        if (PhotoPlan.passThrough(file.mimeType, file.width, file.height)) {
            val url = s.register("local-$id.jpg") { BytesContent(phone.readAll(file), "image/jpeg") }
            return ProxyItem(url, "image/jpeg")
        }
        // Transcode now (not on the TV's request) so a failure is reported here, and once.
        val cacheKey = "$id-${file.generation}"
        val jpeg = jpegs[cacheKey] ?: try {
            transcoder.transcode(phone.readAll(file), PhotoPlan.MAX_EDGE).also { jpegs[cacheKey] = it }
        } catch (e: Exception) {
            throw ProxyUnavailableException("Couldn't convert this photo for the TV")
        }
        val url = s.register("local-$id.jpg") { BytesContent(jpeg, "image/jpeg") }
        return ProxyItem(url, "image/jpeg")
    }

    private suspend fun prepareImmich(s: LocalMediaServer, id: String, isVideo: Boolean): ProxyItem {
        val relay = immich() ?: throw ProxyUnavailableException("Not connected to Immich")
        val safe = id.filter { it.isLetterOrDigit() || it == '-' }
        return try {
            if (isVideo) {
                val content = relay.video(id)
                val type = CastUrls.videoContentType(content.contentType)
                ProxyItem(s.register("immich-$safe.${extension(type)}") { content }, type)
            } else {
                val (bytes, type) = relay.preview(id)
                val announced = CastUrls.photoContentType(type)
                ProxyItem(s.register("immich-$safe.jpg") { BytesContent(bytes, announced) }, announced)
            }
        } catch (e: IOException) {
            throw ProxyUnavailableException("Couldn't fetch this item from Immich")
        }
    }

    private fun extension(type: String) = when (type) {
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        else -> "mp4"
    }

    companion object {
        const val NO_WIFI = "Phone and TV must be on the same Wi-Fi to cast phone photos"
        private const val CACHED_JPEGS = 6
    }
}

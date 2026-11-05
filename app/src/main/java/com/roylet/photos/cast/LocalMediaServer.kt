package com.roylet.photos.cast

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.Collections

/** A body [LocalMediaServer] can serve from any offset (for Range requests). */
interface ProxyContent {
    val contentType: String
    val length: Long
    /** The body from [offset] on; closed by the server when the response is sent. */
    fun open(offset: Long): InputStream
}

class BytesContent(private val bytes: ByteArray, override val contentType: String) : ProxyContent {
    override val length: Long get() = bytes.size.toLong()
    override fun open(offset: Long): InputStream = ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
}

/**
 * The phone's media server for the Chromecast: phone-only photos and videos (and Immich assets
 * when there's no public HTTPS address) on the same Wi-Fi. NanoHTTPD (BSD-3, ~50 KB).
 *
 * - Bound to [host], the phone's Wi-Fi/ethernet IPv4 address, never all interfaces; a random port.
 * - Every URL starts with a per-session random token: `/<token>/m/<key>`. A wrong token is 403.
 * - It serves only what was [register]ed for this cast session (the items actually sent to the
 *   TV), never arbitrary MediaStore ids.
 * - GET and HEAD, `Range` (206 / 416, `Accept-Ranges: bytes`) so the TV can seek in videos.
 * - Runs only while casting ([CastService]); [stop] when the cast session ends.
 *
 * Android 17 (targetSdk 37) puts local-network access behind ACCESS_LOCAL_NETWORK. At targetSdk
 * 35 INTERNET is enough. TODO(targetSdk 37): request it before the first phone-photo cast.
 */
class LocalMediaServer(
    host: String,
    port: Int = 0,
    val token: String = newToken(),
) : NanoHTTPD(host, port) {
    private val items: MutableMap<String, () -> ProxyContent> = Collections.synchronizedMap(
        object : LinkedHashMap<String, () -> ProxyContent>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, () -> ProxyContent>?) = size > MAX_ITEMS
        },
    )

    val host: String get() = hostname

    fun startServing() = start(SOCKET_READ_TIMEOUT, true)

    /** Makes [key] servable; [content] runs per request (on the server's thread). Returns its URL. */
    fun register(key: String, content: () -> ProxyContent): String {
        require(key.matches(KEY)) { "bad key" }
        items[key] = content
        return urlFor(key)
    }

    fun urlFor(key: String): String = "http://$hostname:$listeningPort/$token/m/$key"

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        if (method != Method.GET && method != Method.HEAD) return plain(Response.Status.METHOD_NOT_ALLOWED, "GET or HEAD")
        val parts = session.uri.trimStart('/').split('/')
        if (parts.size != 3 || parts[1] != "m") return plain(Response.Status.NOT_FOUND, "Not found")
        if (!constantTimeEquals(parts[0], token)) return plain(Response.Status.FORBIDDEN, "Forbidden")
        val factory = items[parts[2]] ?: return plain(Response.Status.NOT_FOUND, "Not found")
        val content = try {
            factory()
        } catch (e: IOException) {
            return plain(Response.Status.NOT_FOUND, "Gone")
        } catch (e: SecurityException) {
            return plain(Response.Status.FORBIDDEN, "No access")
        }
        val total = content.length
        val response = try {
            when (val range = RangeRequest.parse(session.headers["range"], total)) {
                RangeRequest.Full -> newFixedLengthResponse(Response.Status.OK, content.contentType, body(content, method, 0), total)
                is RangeRequest.Partial -> newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, content.contentType, body(content, method, range.start), range.length)
                    .apply { addHeader("Content-Range", range.contentRange(total)) }
                RangeRequest.Unsatisfiable -> plain(Response.Status.RANGE_NOT_SATISFIABLE, "Range not satisfiable")
                    .apply { addHeader("Content-Range", "bytes */$total") }
            }
        } catch (e: IOException) {
            plain(Response.Status.INTERNAL_ERROR, "Couldn't read it")
        }
        response.addHeader("Accept-Ranges", "bytes")
        // The receiver is a web page on another origin; harmless for <img>/<video>, needed for MSE players.
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Cache-Control", "no-store")
        response.setGzipEncoding(false)
        return response
    }

    /** HEAD sends no body; NanoHTTPD still wants a stream for the length. */
    private fun body(content: ProxyContent, method: Method, offset: Long): InputStream =
        if (method == Method.HEAD) ByteArrayInputStream(ByteArray(0)) else content.open(offset)

    private fun plain(status: Response.Status, text: String): Response = newFixedLengthResponse(status, MIME_PLAINTEXT, text)

    companion object {
        private const val MAX_ITEMS = 64
        private val KEY = Regex("[A-Za-z0-9._-]{1,120}")

        fun newToken(): String {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}

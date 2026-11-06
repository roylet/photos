package com.roylet.photos.cast

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.roylet.photos.immich.await
import com.roylet.photos.local.MediaDao
import com.roylet.photos.local.mediaUri
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** [CastKeyLedger] in the app's private preferences (ids only, not tokens). */
class PrefsCastKeyLedger(context: Context) : CastKeyLedger {
    private val prefs = context.applicationContext.getSharedPreferences("cast", Context.MODE_PRIVATE)

    override fun ids(): Set<String> = prefs.getStringSet(KEY, null)?.toSet().orEmpty()

    override fun replace(ids: Set<String>) {
        prefs.edit().putStringSet(KEY, ids).apply()
    }

    private companion object {
        const val KEY = "childSessionIds"
    }
}

/** [PhoneFiles] over the MediaStore index and ContentResolver. */
class AndroidPhoneFiles(context: Context, private val dao: () -> MediaDao) : PhoneFiles {
    private val resolver = context.applicationContext.contentResolver
    private val app = context.applicationContext

    override suspend fun info(id: Long): PhoneFile? = dao().byId(id)?.takeIf { !it.trashed }?.let {
        PhoneFile(it.id, it.isVideo, it.mimeType, it.width, it.height, it.generation)
    }

    override fun readAll(file: PhoneFile): ByteArray =
        resolver.openInputStream(mediaUri(file.id, file.isVideo))?.use { it.readBytes() } ?: throw IOException("Can't open ${file.id}")

    override fun video(file: PhoneFile): ProxyContent {
        val uri = mediaUri(file.id, true)
        val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { l -> l >= 0 } ?: it.parcelFileDescriptor.statSize }
            ?: throw IOException("Can't open ${file.id}")
        val type = CastUrls.videoContentType(file.mimeType)
        return object : ProxyContent {
            override val contentType = type
            override val length = length
            override fun open(offset: Long): InputStream {
                val afd = resolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("Can't open ${file.id}")
                // Closing the stream closes the descriptor.
                val stream = afd.createInputStream() as FileInputStream
                stream.channel.position(afd.startOffset + offset)
                return stream
            }
        }
    }

    override fun isHevc(file: PhoneFile): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(app, mediaUri(file.id, true), null)
            (0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_HEVC }
        } catch (e: Exception) {
            false
        } finally {
            extractor.release()
        }
    }
}

/**
 * [ImmichRelay] through the shared client: it goes to the LAN address at home and carries the
 * API key, which never leaves the phone (the TV only ever sees the phone's own URL).
 */
class HttpImmichRelay(private val http: OkHttpClient, private val base: String) : ImmichRelay {
    override suspend fun preview(id: String): Pair<ByteArray, String> {
        val req = Request.Builder().url("$base/api/assets/$id/thumbnail?size=preview&edited=true").build()
        return http.newCall(req).await().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val body = res.body ?: throw IOException("empty")
            body.bytes() to (res.header("Content-Type") ?: "image/jpeg")
        }
    }

    override suspend fun video(id: String): ProxyContent {
        val url = "$base/api/assets/$id/video/playback"
        val (length, type) = http.newCall(Request.Builder().url(url).head().build()).await().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            (res.header("Content-Length")?.toLongOrNull() ?: throw IOException("no length")) to (res.header("Content-Type") ?: "video/mp4")
        }
        return object : ProxyContent {
            override val contentType = type
            override val length = length
            override fun open(offset: Long): InputStream {
                val res = http.newCall(Request.Builder().url(url).header("Range", "bytes=$offset-").build()).execute()
                if (res.code != 206 && !(res.code == 200 && offset == 0L)) {
                    res.close()
                    throw IOException("HTTP ${res.code}")
                }
                return res.body?.byteStream() ?: throw IOException("empty")
            }
        }
    }
}

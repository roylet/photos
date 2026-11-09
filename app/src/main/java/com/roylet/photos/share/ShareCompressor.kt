package com.roylet.photos.share

import com.roylet.photos.domain.library.SharedUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

/** What a compressed share hands to Android's share sheet: new files plus originals sent as they are. */
data class ShareBundle(val files: List<File>, val uris: List<SharedUri>)

/** Where a compression run is. */
enum class SharePhase { Preparing, Downloading, Compressing }

/** Progress of a compressed share: item [index] (0-based) of [count], that item's [itemFraction] and the run's [overall]. */
data class ShareProgress(
    val index: Int,
    val count: Int,
    val phase: SharePhase,
    val itemFraction: Float,
    val overall: Float,
    val name: String = "",
)

/** A compressed share that failed; [message] is for the user, who's offered the originals instead. */
class ShareCompressionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A video encode failed; [fallbackMayHelp] when a smaller size or simpler setting might work. */
class ShareEncodeException(message: String, val fallbackMayHelp: Boolean, cause: Throwable? = null) : Exception(message, cause)

/** A source to re-encode: a phone item read in place by its content URI, or a local file. [release] when done. */
sealed interface ShareSource {
    val release: () -> Unit

    data class Content(val uri: String, override val release: () -> Unit = {}) : ShareSource
    data class Local(val file: File, override val release: () -> Unit = {}) : ShareSource
}

/** Gets items' bytes: sources to compress, and originals for items that go as they are. */
interface ShareSources {
    /** [item]'s details for the size estimates; null when it can't be looked up. */
    suspend fun info(id: String): ShareItemInfo?

    /** The original to re-encode, downloading into [work] (or the originals cache) when needed. */
    suspend fun open(item: ShareItemInfo, work: File, onProgress: (Long, Long) -> Unit): ShareSource

    /** The original to send as it is: a phone item's own URI, or a download into [dir]. */
    suspend fun original(item: ShareItemInfo, dir: File, onProgress: (Long, Long) -> Unit): Pair<SharedUri?, File?>
}

/** Re-encodes one video to [preset] (H.264/AAC MP4, SDR, upright size kept in proportion). Throws [ShareEncodeException]. */
fun interface VideoTranscoder {
    suspend fun transcode(source: ShareSource, preset: VideoPreset, output: File, onProgress: (Float) -> Unit)
}

/** Re-encodes one photo to a JPEG per [preset], keeping its EXIF. */
fun interface PhotoTranscoder {
    suspend fun compress(source: ShareSource, preset: PhotoPreset, output: File)
}

/** The share sheet's view of compression: measure a selection, then compress it. */
interface ShareCompression {
    /** Details of [ids] for the estimates (unknown items are left out). */
    suspend fun measure(ids: List<String>): List<ShareItemInfo>

    /** Re-encodes [items] at [quality] (items that wouldn't shrink go as originals). Cancellable. */
    suspend fun compress(items: List<ShareItemInfo>, quality: ShareQuality, onProgress: (ShareProgress) -> Unit): ShareBundle

    /** Deletes share temp files older than a day. */
    fun cleanStale()
}

/**
 * Compressed "Send file": each item is fetched (a phone item in place; an Immich item downloaded
 * through the shared client), re-encoded with [videos]/[photos] into `shareDir/compressed/<n>/`
 * and handed back for the share sheet. Items that wouldn't get smaller go as their originals.
 *
 * A video whose encode fails with a fallback-worthy error is retried once at the next smaller
 * preset; anything else fails the run with a [ShareCompressionException]. Cancelling (leaving the
 * progress sheet) stops the encoder and deletes this run's files. The previous run's files are
 * deleted when the next one starts; [cleanStale] removes any older than a day.
 */
class ShareCompressor(
    private val shareDir: File,
    private val sources: ShareSources,
    private val videos: VideoTranscoder,
    private val photos: PhotoTranscoder,
    private val io: CoroutineContext = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : ShareCompression {
    private val root get() = File(shareDir, ShareTemp.DIR)

    override suspend fun measure(ids: List<String>): List<ShareItemInfo> = withContext(io) {
        val gate = Semaphore(6)
        coroutineScope {
            ids.map { id -> async { gate.withPermit { runCatching { sources.info(id) }.getOrNull() } } }.awaitAll().filterNotNull()
        }
    }

    override fun cleanStale() {
        runCatching { ShareTemp.cleanStale(shareDir, now()) }
    }

    override suspend fun compress(items: List<ShareItemInfo>, quality: ShareQuality, onProgress: (ShareProgress) -> Unit): ShareBundle = withContext(io) {
        val count = items.size
        onProgress(ShareProgress(0, count, SharePhase.Preparing, 0f, 0f, items.firstOrNull()?.fileName.orEmpty()))
        ShareTemp.cleanStale(shareDir, now())
        val out = root
        ShareTemp.clear(out)
        val files = ArrayList<File>()
        val uris = ArrayList<SharedUri>()
        var current: ShareItemInfo? = null
        try {
            items.forEachIndexed { i, item ->
                ensureActive()
                current = item
                val dir = File(out, i.toString()).apply { mkdirs() }
                var downloaded = false
                fun report(phase: SharePhase, f: Float) {
                    val itemShare = when {
                        phase == SharePhase.Downloading -> DOWNLOAD_SHARE * f
                        phase == SharePhase.Compressing && downloaded -> DOWNLOAD_SHARE + (1 - DOWNLOAD_SHARE) * f
                        else -> f
                    }
                    onProgress(ShareProgress(i, count, phase, f.coerceIn(0f, 1f), ((i + itemShare.coerceIn(0f, 1f)) / count), item.fileName))
                }
                val download = { b: Long, t: Long -> downloaded = true; report(SharePhase.Downloading, if (t > 0) b.toFloat() / t else 0f) }
                val plan = ShareEstimator.plan(item, quality)
                if (!plan.compress) {
                    val (uri, file) = sources.original(item, dir, download)
                    uri?.let(uris::add)
                    file?.let(files::add)
                } else {
                    val source = sources.open(item, dir, download)
                    try {
                        report(SharePhase.Compressing, 0f)
                        files += if (item.isVideo) encodeVideo(source, item, quality, dir) { report(SharePhase.Compressing, it) }
                        else encodePhoto(source, item, quality, dir)
                    } finally {
                        runCatching { source.release() }
                    }
                }
                report(SharePhase.Compressing, 1f)
            }
            ShareBundle(files, uris)
        } catch (e: CancellationException) {
            out.deleteRecursively()
            throw e
        } catch (e: ShareCompressionException) {
            out.deleteRecursively()
            throw e
        } catch (e: Throwable) {
            out.deleteRecursively()
            val what = current?.fileName?.let { " $it" }.orEmpty()
            throw ShareCompressionException("Couldn't compress$what: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private suspend fun encodeVideo(source: ShareSource, item: ShareItemInfo, quality: ShareQuality, dir: File, onProgress: (Float) -> Unit): File {
        val output = File(dir, ShareTemp.outputName(item.fileName, "mp4"))
        val preset = quality.video ?: error("No video preset for $quality")
        try {
            videos.transcode(source, preset, output, onProgress)
        } catch (e: ShareEncodeException) {
            val fallback = quality.fallback?.video
            if (!e.fallbackMayHelp || fallback == null) throw e
            // The encoder couldn't do this size or bitrate: once more at the next smaller preset.
            output.delete()
            onProgress(0f)
            videos.transcode(source, fallback, output, onProgress)
        }
        return output
    }

    private suspend fun encodePhoto(source: ShareSource, item: ShareItemInfo, quality: ShareQuality, dir: File): File {
        val output = File(dir, ShareTemp.outputName(item.fileName, "jpg"))
        photos.compress(source, quality.photo ?: error("No photo preset for $quality"), output)
        return output
    }

    companion object {
        /** How much of an item's progress bar a download takes (the encode is the slow part). */
        const val DOWNLOAD_SHARE = 0.3f
    }
}

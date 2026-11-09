package com.roylet.photos.share

import com.roylet.photos.domain.library.SharedUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The compressor with fake sources and a fake exporter: progress, cancel, fallback, failure. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareCompressorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bigVideo = ShareItemInfo("v1", "VID_1.MOV", "video/quicktime", 400_000_000, 3840, 2160, 60_000)
    private val immichVideo = ShareItemInfo("v2", "VID_2.mp4", "video/mp4", 200_000_000, 1920, 1080, 30_000)
    private val bigPhoto = ShareItemInfo("p1", "IMG_1.HEIC", "image/heic", 4_000_000, 4032, 3024, null)
    private val smallPhoto = ShareItemInfo("p2", "IMG_2.jpg", "image/jpeg", 150_000, 1000, 750, null)

    /** v1 and the photos are on the phone; v2 is downloaded. */
    private inner class Sources : ShareSources {
        val released = mutableListOf<String>()
        override suspend fun info(id: String) = listOf(bigVideo, immichVideo, bigPhoto, smallPhoto).firstOrNull { it.id == id }
        override suspend fun open(item: ShareItemInfo, work: File, onProgress: (Long, Long) -> Unit): ShareSource =
            if (item.id == "v2") {
                onProgress(50, 100); onProgress(100, 100)
                ShareSource.Local(File(work, "src/${item.fileName}").apply { parentFile!!.mkdirs(); writeText("src") }) { released += item.id }
            } else {
                ShareSource.Content("content://media/${item.id}") { released += item.id }
            }
        override suspend fun original(item: ShareItemInfo, dir: File, onProgress: (Long, Long) -> Unit) =
            SharedUri("content://media/${item.id}", item.mimeType) to null
    }

    private class FakeVideos(val fail: (VideoPreset) -> ShareEncodeException? = { null }, val hang: Boolean = false) : VideoTranscoder {
        val presets = mutableListOf<VideoPreset>()
        val started = CompletableDeferred<Unit>()
        override suspend fun transcode(source: ShareSource, preset: VideoPreset, output: File, onProgress: (Float) -> Unit) {
            presets += preset
            onProgress(0.5f)
            started.complete(Unit)
            if (hang) awaitCancellation()
            fail(preset)?.let { throw it }
            output.writeText("video ${preset.longEdge}")
            onProgress(1f)
        }
    }

    private val photos = PhotoTranscoder { _, preset, output -> output.writeText("jpeg ${preset.maxEdge}") }

    private fun compressor(videos: VideoTranscoder, sources: ShareSources = Sources(), dispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.Unconfined) =
        ShareCompressor(File(tmp.root, "share"), sources, videos, photos, io = dispatcher)

    @Test fun `compresses what shrinks, sends the rest as is, and reports progress per item and overall`() = runTest {
        val sources = Sources()
        val videos = FakeVideos()
        val progress = mutableListOf<ShareProgress>()
        val bundle = compressor(videos, sources).compress(listOf(bigVideo, smallPhoto, immichVideo, bigPhoto), ShareQuality.SMALLER) { progress += it }

        assertEquals(listOf("VID_1.mp4", "VID_2.mp4", "IMG_1.jpg"), bundle.files.map { it.name })
        assertTrue(bundle.files.all { it.isFile && it.path.contains("share/compressed/") })
        assertEquals(listOf(SharedUri("content://media/p2", "image/jpeg")), bundle.uris)
        assertEquals("jpeg 2560", bundle.files[2].readText())
        assertEquals(setOf("v1", "v2", "p1"), sources.released.toSet())

        // Overall progress never goes back, ends at 100%, and each item's step is reported.
        val overall = progress.map { it.overall }
        assertEquals(overall.sorted(), overall)
        assertEquals(1f, overall.last(), 0.0001f)
        assertEquals(listOf(0, 1, 2, 3), progress.map { it.index }.distinct())
        assertTrue(progress.any { it.index == 2 && it.phase == SharePhase.Downloading && it.itemFraction == 0.5f })
        // Item 3 of 4 half encoded after its download: (2 + 0.3 + 0.7 × 0.5) / 4.
        val mid = progress.first { it.index == 2 && it.phase == SharePhase.Compressing && it.itemFraction == 0.5f }
        assertEquals((2 + 0.3f + 0.7f * 0.5f) / 4, mid.overall, 0.0001f)
    }

    @Test fun `an encoder failure retries once at the smaller preset`() = runTest {
        val videos = FakeVideos(fail = { p -> if (p.longEdge == 1920) ShareEncodeException("no 1080p encoder", fallbackMayHelp = true) else null })
        val bundle = compressor(videos).compress(listOf(bigVideo), ShareQuality.SMALLER) {}
        assertEquals(listOf(1920, 1280), videos.presets.map { it.longEdge })
        assertEquals("video 1280", bundle.files.single().readText())
    }

    @Test fun `a failure that a fallback can't help fails the run and cleans up`() = runTest {
        val videos = FakeVideos(fail = { ShareEncodeException("can't decode", fallbackMayHelp = false) })
        val c = compressor(videos)
        val e = runCatching { c.compress(listOf(bigPhoto, bigVideo), ShareQuality.SMALLEST) {} }.exceptionOrNull()
        assertTrue(e is ShareCompressionException)
        assertTrue(e!!.message!!.contains("VID_1.MOV"))
        assertEquals(1, videos.presets.size)
        assertFalse(File(tmp.root, "share/compressed").exists())
    }

    @Test fun `cancelling stops the encoder and deletes this run's files`() = runTest {
        val sources = Sources()
        val videos = FakeVideos(hang = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val progress = mutableListOf<ShareProgress>()
        val job = launch(dispatcher) { compressor(videos, sources, dispatcher).compress(listOf(bigPhoto, bigVideo), ShareQuality.SMALLER) { progress += it } }
        runCurrent()
        assertTrue(videos.started.isCompleted)
        assertTrue(File(tmp.root, "share/compressed/0/IMG_1.jpg").isFile)
        assertEquals(1, progress.last().index)
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertFalse(File(tmp.root, "share/compressed").exists())
        assertTrue("v1" in sources.released)
    }

    @Test fun `the next share clears the previous one`() = runTest {
        val c = compressor(FakeVideos())
        val first = c.compress(listOf(bigPhoto), ShareQuality.SMALLER) {}
        val second = c.compress(listOf(bigVideo), ShareQuality.SMALLER) {}
        assertFalse(first.files.single().exists())
        assertTrue(second.files.single().exists())
    }
}

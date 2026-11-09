package com.roylet.photos.share

import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.domain.library.SharedUri
import com.roylet.photos.FakeLibrary
import com.roylet.photos.ui.actions.ActionSheet
import com.roylet.photos.ui.actions.CompressState
import com.roylet.photos.ui.actions.LibraryActions
import com.roylet.photos.ui.actions.ShareLauncher
import com.roylet.photos.ui.components.SnackbarController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** "Send file" at a compressed quality through [LibraryActions]: remembered choice, sheet, cancel, failure. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareActionsTest {
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

    /* ---------- LibraryActions flow ---------- */

    private class Launcher : ShareLauncher {
        val sent = mutableListOf<Pair<List<String>, List<String>>>()
        override fun sendFiles(files: List<File>) { sent += files.map { it.name } to emptyList() }
        override fun send(files: List<File>, uris: List<SharedUri>) { sent += files.map { it.name } to uris.map { it.uri } }
        override fun sendText(text: String) {}
        override fun copy(text: String) {}
    }

    private fun actions(compression: ShareCompression, launcher: ShareLauncher, prefs: UiPrefs = UiPrefs(null)): LibraryActions {
        val lib = FakeLibrary()
        return LibraryActions(
            library = { lib }, albums = AlbumStore({ lib }, CoroutineScope(Dispatchers.Unconfined)), snackbar = SnackbarController(),
            scope = CoroutineScope(Dispatchers.Unconfined), launcher = launcher, prefs = prefs,
            shareDir = File(tmp.root, "share"), compression = compression,
        )
    }

    @Test fun `send compressed remembers the choice, runs while the sheet is open, then shares`() = runTest {
        val launcher = Launcher()
        val prefs = UiPrefs(null)
        val a = actions(compressor(FakeVideos()), launcher, prefs)
        a.sendFiles(listOf("v1", "p2"), ShareQuality.SMALLEST, listOf(bigVideo, smallPhoto))
        assertEquals("SMALLEST", prefs.shareQuality)
        val sheet = a.sheet as ActionSheet.Compress
        a.runCompression(sheet)
        assertNull(a.sheet)
        assertEquals(listOf(listOf("VID_1.mp4") to listOf("content://media/p2")), launcher.sent)
    }

    @Test fun `closing the progress sheet cancels`() = runTest {
        val launcher = Launcher()
        val videos = FakeVideos(hang = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val a = actions(compressor(videos, dispatcher = dispatcher), launcher)
        a.sendFiles(listOf("v1"), ShareQuality.SMALLER, listOf(bigVideo))
        val job = launch(dispatcher) { a.runCompression(a.sheet as ActionSheet.Compress) }
        runCurrent()
        assertTrue(a.compressing is CompressState.Running)
        assertEquals(0.5f, (a.compressing as CompressState.Running).progress.itemFraction)
        a.closeSheet()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertNull(a.compressing)
        assertTrue(launcher.sent.isEmpty())
    }

    @Test fun `a failure offers the originals instead`() = runTest {
        val launcher = Launcher()
        val a = actions(compressor(FakeVideos(fail = { ShareEncodeException("broken", false) })), launcher)
        a.sendFiles(listOf("v1"), ShareQuality.SMALLEST, listOf(bigVideo))
        a.runCompression(a.sheet as ActionSheet.Compress)
        assertTrue(a.compressing is CompressState.Failed)
        assertTrue(a.sheet is ActionSheet.Compress)
        a.sendOriginalInstead()
        assertFalse(a.sheet is ActionSheet.Compress) // the share sheet's download ran (and closed it)
        assertNull(a.compressing)
        // FakeLibrary has no phone URIs: the original is downloaded and shared.
        assertEquals(listOf("v1.jpg"), launcher.sent.single().first)
    }

    @Test fun `Original keeps the old path`() = runTest {
        val launcher = Launcher()
        val prefs = UiPrefs(null).apply { shareQuality = "SMALLER" }
        val a = actions(compressor(FakeVideos()), launcher, prefs)
        a.sendFiles(listOf("v1"), ShareQuality.ORIGINAL, listOf(bigVideo))
        assertEquals("ORIGINAL", prefs.shareQuality)
        assertFalse(a.sheet is ActionSheet.Compress)
        assertEquals(listOf("v1.jpg"), launcher.sent.single().first)
    }
}

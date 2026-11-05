package com.roylet.photos.video

import android.graphics.Bitmap
import com.roylet.photos.immich.EditAssetInfo
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.video.io.FrameSource
import com.roylet.photos.video.io.FrameStripProvider
import com.roylet.photos.video.io.VideoOriginals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VideoIoTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun info(id: String = "v1", checksum: String = "c1") =
        EditAssetInfo(id, "VIDEO", "My Clip.MOV", fileCreatedAt = "2024-05-01T10:00:00.000Z", fileModifiedAt = "2024-05-01T10:00:00.000Z", checksum = checksum)

    @Test fun downloadsOnceThenServesFromCache() = runBlocking {
        val body = ByteArray(300_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val originals = VideoOriginals(tmp.newFolder("cache"), freeBytes = { Long.MAX_VALUE })
        val api = ImmichApi(server.url("/").toString(), "k")
        var last = 0L to 0L
        val f = originals.obtain(api, info()) { d, t -> last = d to t }
        assertArrayEquals(body, f.readBytes())
        assertEquals(body.size.toLong() to body.size.toLong(), last)
        assertEquals("My_Clip.MOV", f.name)
        assertEquals("/api/assets/v1/original", server.takeRequest().path)
        // Second time: no request.
        assertEquals(f, originals.obtain(api, info()))
        assertEquals(1, server.requestCount)
        assertNotNull(originals.cached(info()))
        assertNull(originals.cached(info(checksum = "other")))
    }

    @Test fun refusesWhenTheDiskIsTooFull() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10_000))))
        val originals = VideoOriginals(tmp.newFolder("cache"), headroomBytes = 1_000, freeBytes = { 5_000L })
        try {
            originals.obtain(ImmichApi(server.url("/").toString(), "k"), info())
            fail("expected a space error")
        } catch (e: ImmichException) {
            assertTrue(e.message!!.contains("free space"))
        }
        assertNull(originals.cached(info()))
        assertTrue(originals.fileFor(info()).parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun keepsOnlyRecentOriginals() = runBlocking {
        val dir = tmp.newFolder("cache")
        val originals = VideoOriginals(dir, keep = 2, freeBytes = { Long.MAX_VALUE })
        val api = ImmichApi(server.url("/").toString(), "k")
        for ((i, id) in listOf("a", "b", "c").withIndex()) {
            server.enqueue(MockResponse().setBody("x$i"))
            originals.obtain(api, info(id))
            originals.fileFor(info(id)).parentFile!!.setLastModified(1_000_000L * (i + 1))
        }
        assertEquals(2, dir.listFiles()!!.size)
        assertNull(originals.cached(info("a")))
        assertNotNull(originals.cached(info("c")))
    }

    @Test fun pruningSparesHeldOriginalsAndOnesQueuedSavesNeed() = runBlocking {
        val dir = tmp.newFolder("cache")
        var pending = setOf<String>()
        val originals = VideoOriginals(dir, keep = 1, freeBytes = { Long.MAX_VALUE }, pendingSourceIds = { pending })
        val api = ImmichApi(server.url("/").toString(), "k")
        suspend fun open(id: String, at: Long): java.io.File {
            server.enqueue(MockResponse().setBody("x$id"))
            return originals.obtain(api, info(id)).also { it.parentFile!!.setLastModified(at) }
        }
        val a = open("a", 1_000_000L)
        val held = originals.hold(a) // the editor has "a" open
        pending = setOf("b")
        open("b", 2_000_000L) // a queued save will export "b"
        open("c", 3_000_000L) // keep = 1, yet neither "a" nor "b" may go
        assertNotNull(originals.cached(info("a")))
        assertNotNull(originals.cached(info("b")))
        assertNotNull(originals.cached(info("c")))
        held.close()
        held.close() // idempotent
        pending = emptySet()
        open("d", 4_000_000L)
        assertNull(originals.cached(info("a")))
        assertNull(originals.cached(info("b")))
        assertNull(originals.cached(info("c")))
        assertNotNull(originals.cached(info("d")))
    }

    @Test fun concurrentRequestsForOneOriginalShareTheDownload() = runBlocking {
        val body = ByteArray(200_000) { (it % 7).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(body)).throttleBody(50_000, 20, java.util.concurrent.TimeUnit.MILLISECONDS))
        val originals = VideoOriginals(tmp.newFolder("cache"), freeBytes = { Long.MAX_VALUE })
        val api = ImmichApi(server.url("/").toString(), "k")
        val files = kotlinx.coroutines.coroutineScope {
            List(2) { async(Dispatchers.IO) { originals.obtain(api, info()) } }.map { it.await() }
        }
        assertEquals(files[0], files[1])
        assertArrayEquals(body, files[0].readBytes())
        assertEquals(1, server.requestCount)
    }

    private class FakeSource(val log: MutableList<Long>, val failAt: Long? = null) : FrameSource {
        var closed = false
        override fun frameAt(timeMs: Long, maxPx: Int, exact: Boolean): Bitmap? {
            log += timeMs
            if (timeMs == failAt) throw RuntimeException("bad frame")
            return Bitmap.createBitmap(maxPx, maxPx / 2, Bitmap.Config.ARGB_8888)
        }
        override fun close() { closed = true }
    }

    @Test fun stripLoadsCoarseToFineAndCaches() = runBlocking {
        val file = tmp.newFile("v.mp4")
        val decoded = mutableListOf<Long>()
        val sources = mutableListOf<FakeSource>()
        val provider = FrameStripProvider(open = { FakeSource(decoded).also { sources += it } })
        val times = FrameStripProvider.sampleTimes(10_000, 5)
        assertArrayEquals(longArrayOf(1_000, 3_000, 5_000, 7_000, 9_000), times)
        val got = mutableListOf<Int>()
        provider.load(file, times, 64) { i, _ -> got += i }
        assertEquals(listOf(0, 4, 2, 1, 3), got) // coarse to fine
        assertTrue(sources.single().closed)
        // All cached now: no new source, no decodes.
        got.clear()
        provider.load(file, times, 64) { i, _ -> got += i }
        assertEquals(5, got.size)
        assertEquals(1, sources.size)
        assertEquals(5, decoded.size)
        assertNotNull(provider.cached(file, 5_000, 64))
        assertNull(provider.cached(file, 5_000, 128))
    }

    @Test fun stripSkipsBadFramesAndOpenFailures() = runBlocking {
        val file = tmp.newFile("v.mp4")
        val got = mutableListOf<Int>()
        FrameStripProvider(open = { FakeSource(mutableListOf(), failAt = 5_000) })
            .load(file, FrameStripProvider.sampleTimes(10_000, 5), 32) { i, _ -> got += i }
        assertFalse(2 in got)
        assertEquals(4, got.size)
        got.clear()
        FrameStripProvider(open = { throw IllegalArgumentException("not a video") })
            .load(file, longArrayOf(0, 1), 32) { i, _ -> got += i }
        assertTrue(got.isEmpty())
        assertNull(FrameStripProvider(open = { throw IllegalArgumentException() }).frame(File("/nope"), 0))
    }
}

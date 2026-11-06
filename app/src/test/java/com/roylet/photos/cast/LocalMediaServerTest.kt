package com.roylet.photos.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.Proxy

/** The phone's media server on localhost: tokens, Range/206/416, types, the photo plan. */
class LocalMediaServerTest {
    private val body = ByteArray(1000) { (it % 251).toByte() }
    private lateinit var server: LocalMediaServer
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()

    @Before fun setUp() {
        server = LocalMediaServer("127.0.0.1", token = "tok123").apply { startServing() }
        server.register("local-1.mp4") { BytesContent(body, "video/mp4") }
    }

    @After fun tearDown() = server.stop()

    private fun get(path: String, range: String? = null, method: String = "GET") =
        http.newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}$path").method(method, null).apply { range?.let { header("Range", it) } }.build()).execute()

    @Test fun servesTheWholeBodyWithItsType() {
        val url = server.urlFor("local-1.mp4")
        assertTrue(url, url.startsWith("http://127.0.0.1:${server.listeningPort}/tok123/m/local-1.mp4"))
        get("/tok123/m/local-1.mp4").use {
            assertEquals(200, it.code)
            assertEquals("video/mp4", it.header("Content-Type"))
            assertEquals("bytes", it.header("Accept-Ranges"))
            assertEquals("1000", it.header("Content-Length"))
            assertArrayEquals(body, it.body!!.bytes())
        }
    }

    @Test fun partialRange() = get("/tok123/m/local-1.mp4", "bytes=100-199").use {
        assertEquals(206, it.code)
        assertEquals("bytes 100-199/1000", it.header("Content-Range"))
        assertArrayEquals(body.copyOfRange(100, 200), it.body!!.bytes())
    }

    @Test fun openEndedRange() = get("/tok123/m/local-1.mp4", "bytes=990-").use {
        assertEquals(206, it.code)
        assertEquals("bytes 990-999/1000", it.header("Content-Range"))
        assertEquals(10, it.body!!.bytes().size)
    }

    @Test fun suffixRange() = get("/tok123/m/local-1.mp4", "bytes=-50").use {
        assertEquals(206, it.code)
        assertEquals("bytes 950-999/1000", it.header("Content-Range"))
        assertArrayEquals(body.copyOfRange(950, 1000), it.body!!.bytes())
    }

    @Test fun rangePastTheEndIs416() = get("/tok123/m/local-1.mp4", "bytes=1000-").use {
        assertEquals(416, it.code)
        assertEquals("bytes */1000", it.header("Content-Range"))
    }

    @Test fun headHasTheLengthAndNoBody() = get("/tok123/m/local-1.mp4", method = "HEAD").use {
        assertEquals(200, it.code)
        assertEquals("1000", it.header("Content-Length"))
    }

    @Test fun wrongTokenIs403() {
        get("/nope/m/local-1.mp4").use { assertEquals(403, it.code) }
        get("/tok12/m/local-1.mp4").use { assertEquals(403, it.code) }
    }

    @Test fun onlyRegisteredItemsAreServed() {
        get("/tok123/m/local-2.mp4").use { assertEquals(404, it.code) }
        get("/tok123/local-1.mp4").use { assertEquals(404, it.code) }
        http.newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/tok123/m/local-1.mp4").post(okhttp3.RequestBody.create(null, ByteArray(0))).build()).execute().use { assertEquals(405, it.code) }
    }

    @Test fun tokensAreLongAndRandom() {
        val a = LocalMediaServer.newToken()
        val b = LocalMediaServer.newToken()
        assertTrue(a.length >= 32 && a != b && a.matches(Regex("[A-Za-z0-9_-]+")))
    }

    /* ---------------- Range parsing ---------------- */

    @Test fun rangeParsing() {
        assertEquals(RangeRequest.Full, RangeRequest.parse(null, 10))
        assertEquals(RangeRequest.Full, RangeRequest.parse("items=0-1", 10))
        assertEquals(RangeRequest.Full, RangeRequest.parse("bytes=5-2", 10))
        assertEquals(RangeRequest.Partial(0, 9), RangeRequest.parse("bytes=0-", 10))
        assertEquals(RangeRequest.Partial(2, 9), RangeRequest.parse("bytes=2-500", 10))
        assertEquals(RangeRequest.Partial(0, 9), RangeRequest.parse("bytes=-50", 10))
        assertEquals(RangeRequest.Partial(1, 2), RangeRequest.parse("bytes=1-2, 5-6", 10))
        assertEquals(RangeRequest.Unsatisfiable, RangeRequest.parse("bytes=10-", 10))
        assertEquals(RangeRequest.Unsatisfiable, RangeRequest.parse("bytes=-0", 10))
    }

    /* ---------------- The relay: what each phone photo becomes ---------------- */

    private class Files(val files: Map<Long, PhoneFile>) : PhoneFiles {
        override suspend fun info(id: Long) = files[id]
        override fun readAll(file: PhoneFile) = "original-${file.id}".toByteArray()
        override fun video(file: PhoneFile): ProxyContent = BytesContent(ByteArray(10), "video/mp4")
        override fun isHevc(file: PhoneFile) = file.mimeType == "video/hevc-test"
    }

    private fun relay(transcoded: MutableList<Long>, wifi: String? = "127.0.0.1") = PhoneMediaProxy(
        wifiAddress = { wifi },
        phone = Files(
            mapOf(
                1L to PhoneFile(1, false, "image/heic", 4032, 3024, 1),
                2L to PhoneFile(2, false, "image/jpeg", 1600, 1200, 1),
                3L to PhoneFile(3, false, "image/jpeg", 4000, 3000, 1),
                4L to PhoneFile(4, true, "video/quicktime", 1920, 1080, 1),
                5L to PhoneFile(5, true, "video/hevc-test", 1920, 1080, 1),
            ),
        ),
        immich = { null },
        transcoder = { bytes, max -> transcoded += String(bytes).removePrefix("original-").toLong(); assertEquals(1920, max); "jpeg".toByteArray() },
        io = Dispatchers.Unconfined,
    )

    @Test fun heicAndBigJpegsAreTranscodedSmallJpegsPassThrough() = runBlocking {
        val transcoded = mutableListOf<Long>()
        val r = relay(transcoded)
        try {
            val heic = r.prepare(CastRoute.PhoneLocal(1), false)
            assertEquals("image/jpeg", heic.contentType)
            val small = r.prepare(CastRoute.PhoneLocal(2), false)
            r.prepare(CastRoute.PhoneLocal(3), false)
            assertEquals(listOf(1L, 3L), transcoded)
            // Cached: the same photo again isn't transcoded twice.
            r.prepare(CastRoute.PhoneLocal(1), false)
            assertEquals(listOf(1L, 3L), transcoded)
            // What the TV fetches.
            http.newCall(Request.Builder().url(heic.url).build()).execute().use { assertEquals("jpeg", it.body!!.string()) }
            http.newCall(Request.Builder().url(small.url).build()).execute().use { assertEquals("original-2", it.body!!.string()) }
        } finally { r.stop() }
    }

    @Test fun videosAreTheOriginalAndHevcIsFlagged() = runBlocking {
        val r = relay(mutableListOf())
        try {
            val mov = r.prepare(CastRoute.PhoneLocal(4), true)
            assertEquals("video/mp4", mov.contentType)
            assertTrue(mov.url.endsWith(".mp4"))
            assertEquals(false, mov.hevc)
            assertEquals(true, r.prepare(CastRoute.PhoneLocal(5), true).hevc)
        } finally { r.stop() }
    }

    @Test fun noWifiIsWorded() = runBlocking {
        try {
            relay(mutableListOf(), wifi = null).prepare(CastRoute.PhoneLocal(1), false)
            fail()
        } catch (e: ProxyUnavailableException) {
            assertEquals("Phone and TV must be on the same Wi-Fi to cast phone photos", e.message)
        }
    }

    @Test fun stopShutsTheServer() {
        val r = relay(mutableListOf())
        assertTrue(r.start())
        assertTrue(r.running)
        r.stop()
        assertTrue(!r.running)
    }

    @Test fun photoPlan() {
        assertTrue(PhotoPlan.passThrough("image/jpeg", 1920, 1080))
        assertTrue(!PhotoPlan.passThrough("image/jpeg", 1921, 1080))
        assertTrue(!PhotoPlan.passThrough("image/heic", 800, 600))
        assertEquals(1920 to 1440, PhotoPlan.target(4032, 3024))
        assertEquals(1080 to 1920, PhotoPlan.target(3024, 5376))
        assertEquals(800 to 600, PhotoPlan.target(800, 600))
    }
}

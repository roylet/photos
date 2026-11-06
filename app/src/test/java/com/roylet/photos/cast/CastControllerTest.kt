package com.roylet.photos.cast

import com.roylet.photos.data.ServerConfig
import com.roylet.photos.domain.timeline.TimelineAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class CastControllerTest {
    private fun photo(i: Int) = TimelineAsset("local:$i", 1.5f, isImage = true, isFavorite = false)
    private fun video(i: Int, ms: Long) = TimelineAsset("local:$i", 1.5f, isImage = false, isFavorite = false, durationMs = ms)

    private class ListQueue(val items: List<TimelineAsset>) : SlideshowQueue {
        override val total get() = items.size
        override suspend fun assetAt(index: Int) = items.getOrNull(index)
    }

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    @Test fun swipingLoadsTheNewItemOnTheTv() {
        val device = FakeCastDevice()
        val c = fakeCastController(CoroutineScope(Dispatchers.Unconfined), device)
        c.show(photo(1))
        assertTrue("nothing goes out before a session", device.loaded.isEmpty())
        device.connect()
        // The page that was open when the session connected.
        assertEquals(listOf("local:1"), device.loaded.map { it.id })
        c.show(photo(2))
        c.show(photo(2))
        c.show(photo(3))
        assertEquals(listOf("local:1", "local:2", "local:3"), device.loaded.map { it.id })
        assertEquals("local:3", c.state.value.current?.asset?.id)
        assertTrue(c.state.value.current!!.viaPhone)
    }

    @Test fun receiverErrorsAreWorded() {
        val device = FakeCastDevice().apply { connect() }
        val c = fakeCastController(CoroutineScope(Dispatchers.Unconfined), device, proxy = FakeProxy(hevc = true))
        c.show(video(1, 10_000))
        device.fail()
        assertEquals("TV couldn't load this video (HEVC not supported)", c.state.value.error)
        c.show(photo(2))
        assertNull(c.state.value.error)
        device.fail()
        assertEquals("TV couldn't load this photo from your phone. Phone and TV must be on the same Wi-Fi.", c.state.value.error)
    }

    @Test fun transportGoesToTheTv() {
        val device = FakeCastDevice().apply { connect() }
        val c = fakeCastController(CoroutineScope(Dispatchers.Unconfined), device)
        c.show(video(1, 60_000))
        assertTrue(c.state.value.playback.playing)
        c.togglePlay()
        assertTrue(!c.state.value.playback.playing)
        c.seekTo(12_000)
        assertEquals(listOf(12_000L), device.seeks)
        c.setVolume(0.3f)
        assertEquals(0.3f, c.state.value.playback.volume)
    }

    @Test fun slideshowAdvancesOnItsInterval() = runTest {
        val device = FakeCastDevice().apply { connect() }
        val c = fakeCastController(backgroundScope, device)
        runCurrent()
        val queue = ListQueue(listOf(photo(0), photo(1), video(2, 8_000), photo(3)))
        c.startSlideshow(queue, startIndex = 1, intervalMs = 5_000, shuffle = false)
        runCurrent()
        assertEquals(listOf("local:1"), device.loaded.map { it.id })
        assertEquals(1, c.state.value.slideshow?.index)
        advanceTimeBy(4_900); runCurrent()
        assertEquals(1, device.loaded.size)
        advanceTimeBy(200); runCurrent()
        assertEquals("local:2", device.loaded.last().id)
        // A video plays through: not after 5 s, but once the TV says it ended.
        advanceTimeBy(5_100); runCurrent()
        assertEquals("local:2", device.loaded.last().id)
        device.playback.value = device.playback.value.copy(ended = true, playing = false)
        advanceTimeBy(300); runCurrent()
        assertEquals("local:3", device.loaded.last().id)
        // And round again.
        advanceTimeBy(5_000); runCurrent()
        assertEquals("local:0", device.loaded.last().id)
        c.stopSlideshow()
        assertNull(c.state.value.slideshow)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(4, device.loaded.size)
    }

    @Test fun shuffleVisitsEverythingOnceStartingHere() {
        val order = slideshowOrder(50, 7, shuffle = true, random = Random(3))
        assertEquals(7, order.first())
        assertEquals((0 until 50).toSet(), order.toSet())
        assertEquals(50, order.size)
        assertEquals(listOf(3, 4, 0, 1, 2), slideshowOrder(5, 3, shuffle = false))
    }

    @Test fun stoppingDeletesTheSessionAndStopsTheRelayAndService() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"s-9","token":"tok","expiresAt":"2099-01-01T00:00:00Z"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        val keys = CastSessionManager(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { "parent" })
        val resolver = CastResolver({ "https://photos.royle.au" }, { keys }, { "image/jpeg" }, FakeProxy())
        val device = FakeCastDevice()
        val relay = CountingRelay()
        val launcher = CountingLauncher()
        val c = fakeCastController(CoroutineScope(Dispatchers.Unconfined), device, resolver = resolver, keys = { keys }, relay = relay, launcher = launcher)
        device.connect()
        assertEquals(1, launcher.starts)
        c.onServiceStarted()
        assertEquals(1, relay.starts)
        c.show(TimelineAsset("b6f0e1a2-1111-4222-8333-444455556666", 1.5f, isImage = true, isFavorite = false))
        // The key is fetched on OkHttp's thread: wait for the load to land.
        repeat(100) { if (device.loaded.isEmpty()) Thread.sleep(20) }
        assertTrue(device.loaded.single().url.endsWith("sessionKey=tok"))
        c.stop()
        assertEquals(1, device.disconnects)
        assertNull(c.state.value.target)
        assertNull(c.state.value.current)
        assertEquals(1, relay.stops)
        assertEquals(1, launcher.stops)
        server.takeRequest(2, TimeUnit.SECONDS)
        val del = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("DELETE /api/sessions/s-9", "${del.method} ${del.path}")
    }

    @Test fun theRelayStopsWhenTheServiceIsDestroyed() {
        val relay = CountingRelay()
        val c = fakeCastController(CoroutineScope(Dispatchers.Unconfined), FakeCastDevice().apply { connect() }, relay = relay)
        c.onServiceStarted()
        assertEquals(1, relay.starts)
        c.onServiceStopped()
        assertEquals(1, relay.stops)
    }

    @Test fun signInRetriesTheItem() = runBlocking {
        val device = FakeCastDevice().apply { connect() }
        var parent: String? = null
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"s","token":"tok","expiresAt":"2099-01-01T00:00:00Z"}"""))
        val keys = CastSessionManager(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { parent })
        val resolver = CastResolver({ "https://photos.royle.au" }, { keys }, { null }, FakeProxy())
        val c = fakeCastController(CoroutineScope(Dispatchers.Unconfined), device, resolver = resolver, keys = { keys })
        c.show(TimelineAsset("b6f0e1a2-1111-4222-8333-444455556666", 1.5f, isImage = true, isFavorite = false))
        assertTrue(c.state.value.needsSignIn)
        assertTrue(device.loaded.isEmpty())
        parent = "fresh"
        c.onSignedIn()
        repeat(100) { if (device.loaded.isEmpty()) Thread.sleep(20) }
        assertEquals(1, device.loaded.size)
        assertTrue(!c.state.value.needsSignIn)
    }

    /* ---------------- Sign in once to cast ---------------- */

    private var config: ServerConfig? = null

    private fun signIn(stores: Boolean = true, signedIn: () -> Unit = {}) = CastSignIn(
        OkHttpClient(),
        server = { config },
        store = { t -> if (stores) config = config!!.copy(castSessionToken = t) },
        stored = { config?.castSessionToken != null },
        onSignedIn = signedIn,
    )

    @Test fun signInStoresTheLoginSession() = runBlocking {
        config = ServerConfig(publicUrl = server.url("/").toString().trimEnd('/'), apiKey = "k", userId = "u-1", email = "me@example.com")
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"accessToken":"session-1","userId":"u-1","userEmail":"me@example.com","name":"Me"}"""))
        var called = false
        assertNull(signIn { called = true }.signIn("me@example.com", "pw"))
        assertEquals("session-1", config!!.castSessionToken)
        assertEquals("k", config!!.apiKey)
        assertTrue(called)
        val req = server.takeRequest()
        assertEquals("/api/auth/login", req.path)
        assertTrue(req.body.readUtf8().contains("\"password\":\"pw\""))
        // Only the login: no API key is created.
        assertEquals(1, server.requestCount)
    }

    @Test fun signInRefusesAnotherAccountAndWordsErrors() = runBlocking {
        config = ServerConfig(publicUrl = server.url("/").toString().trimEnd('/'), apiKey = "k", userId = "u-1", email = "me@example.com")
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"accessToken":"session-2","userId":"u-2"}"""))
        assertEquals("That's a different Immich account from the one this app uses", signIn().signIn("other@example.com", "pw"))
        assertNull(config!!.castSessionToken)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Incorrect email or password"}"""))
        assertEquals("Wrong email or password", signIn().signIn("me@example.com", "bad"))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"accessToken":"session-3","userId":"u-1"}"""))
        assertTrue(signIn(stores = false).signIn("me@example.com", "pw")!!.contains("couldn't store"))
    }
}

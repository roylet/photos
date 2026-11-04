package com.roylet.photos

import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.data.ServerConfig
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppContainerTest {
    private val container get() = ApplicationProvider.getApplicationContext<PhotosApp>().container
    private lateinit var ours: MockWebServer
    private lateinit var other: MockWebServer

    @Before fun setUp() {
        ours = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        ours.shutdown()
        other.shutdown()
        container.settings.signOut()
    }

    private fun fetch(url: String) = container.http.newCall(Request.Builder().url(url).build()).execute().close()

    @Test fun `shared client adds the api key for our server only`() {
        container.settings.saveServer(ServerConfig(publicUrl = ours.url("/").toString().trimEnd('/'), apiKey = "k-123", userId = "u"))
        ours.enqueue(MockResponse().setBody("ok"))
        other.enqueue(MockResponse().setBody("ok"))

        fetch(ours.url("/api/assets/1/thumbnail").toString())
        fetch(other.url("/tiles/1/2/3.png").toString())

        assertEquals("k-123", ours.takeRequest().getHeader("x-api-key"))
        assertNull("the key must never leak to other hosts", other.takeRequest().getHeader("x-api-key"))
    }

    @Test fun `settings round trip and sign out`() {
        val cfg = ServerConfig(publicUrl = "https://photos.example.com", localUrl = "http://192.168.1.20:2283", apiKey = "k", userId = "u-1", email = "me@example.com")
        container.settings.saveServer(cfg)
        assertEquals(cfg, com.roylet.photos.data.Settings(ApplicationProvider.getApplicationContext()).server.value)
        container.settings.signOut()
        assertNull(container.settings.server.value)
    }

    @Test fun `timelines survive a learned public url but not a new account`() {
        val cfg = ServerConfig(publicUrl = "http://192.168.1.20:2283", apiKey = "k", userId = "u-1")
        container.settings.saveServer(cfg)
        val before = container.timelines.get(com.roylet.photos.domain.timeline.TimelineFilter.Timeline)
        // Signed in by LAN address; the connection manager then learns the public one.
        container.settings.saveServer(cfg.copy(publicUrl = "https://photos.example.com", localUrl = "http://192.168.1.20:2283"))
        org.junit.Assert.assertSame(before, container.timelines.get(com.roylet.photos.domain.timeline.TimelineFilter.Timeline))
        container.settings.saveServer(cfg.copy(userId = "u-2"))
        org.junit.Assert.assertNotSame(before, container.timelines.get(com.roylet.photos.domain.timeline.TimelineFilter.Timeline))
    }
}

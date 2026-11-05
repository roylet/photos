package com.roylet.photos

import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.data.ServerConfig
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test fun `without a server the app runs on the phone`() {
        assertNull(container.settings.server.value)
        assertEquals(AppContainer.DEVICE_SESSION, container.sessionKey())
        assertSame(container.localLibrary, container.library)
        assertEquals(com.roylet.photos.domain.source.ActiveSources.LocalOnly, container.sources())
        // Phone assets go to the local URL provider, with the bare MediaStore id.
        container.localMediaUrls = object : com.roylet.photos.ui.gallery.MediaUrls by com.roylet.photos.ui.gallery.FakeMediaUrls {
            override fun thumbnail(id: String) = "content://media/external/images/media/$id"
        }
        assertEquals("content://media/external/images/media/42", container.mediaUrls().thumbnail("local:42"))
        val empty = kotlinx.coroutines.runBlocking { container.timelines.get(com.roylet.photos.domain.timeline.TimelineFilter.Timeline).let { container.localTimeline.buckets(it.filter) } }
        assertEquals(emptyList<Any>(), empty)
    }

    @Test fun `connecting switches library, capabilities and caches to Immich and back`() {
        val phone = container.timelines.get(com.roylet.photos.domain.timeline.TimelineFilter.Timeline)
        container.settings.saveServer(ServerConfig(publicUrl = "https://photos.example.com", apiKey = "k", userId = "u-1"))
        // Immich, with calls about phone assets and folders routed to the phone (Library → On this device).
        assertTrue(container.library is com.roylet.photos.local.SourceRoutingLibrary)
        assertEquals(com.roylet.photos.domain.source.Capabilities.Immich, container.sources().capabilities)
        assertTrue(container.sources().hasImmich)
        assertEquals("https://photos.example.com/api/assets/abc/thumbnail?size=thumbnail", container.mediaUrls().thumbnail("abc").substringBefore("&"))
        val immich = container.timelines.get(com.roylet.photos.domain.timeline.TimelineFilter.Timeline)
        org.junit.Assert.assertNotSame(phone, immich)
        container.settings.signOut()
        assertSame(container.localLibrary, container.library)
        org.junit.Assert.assertNotSame(immich, container.timelines.get(com.roylet.photos.domain.timeline.TimelineFilter.Timeline))
    }

    @Test fun `onboarding is remembered, and existing servers count as onboarded`() {
        val settings = container.settings
        org.junit.Assert.assertFalse(settings.onboarded.value)
        settings.completeOnboarding()
        assertTrue(settings.onboarded.value)
        assertTrue(com.roylet.photos.data.Settings(ApplicationProvider.getApplicationContext()).onboarded.value)
    }

    @Test fun `signing in marks onboarding done`() {
        container.settings.saveServer(ServerConfig(publicUrl = "https://photos.example.com", apiKey = "k", userId = "u-1"))
        assertTrue(container.settings.onboarded.value)
    }
}

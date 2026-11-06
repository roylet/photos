package com.roylet.photos.cast

import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.domain.timeline.TimelineAsset
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The URL builder, the content-type rule and source routing. */
class CastRoutingTest {
    private val uuid = "b6f0e1a2-1111-4222-8333-444455556666"
    private fun immich(video: Boolean = false) = TimelineAsset(uuid, 1.5f, isImage = !video, isFavorite = false, durationMs = if (video) 30_000 else null)
    private fun local(video: Boolean = false) = TimelineAsset("local:42", 1.5f, isImage = !video, isFavorite = false)
    private fun both() = TimelineAsset("local:42", 1.5f, isImage = true, isFavorite = false, localRef = AssetRef.local(42), remoteRef = AssetRef.immich(uuid))

    /* ---------------- URLs ---------------- */

    @Test fun directBaseIsOnlyPublicHttps() {
        assertEquals("https://photos.royle.au", CastUrls.directBase("https://photos.royle.au/"))
        assertNull(CastUrls.directBase("http://photos.royle.au"))
        assertNull(CastUrls.directBase("https://192.168.1.20:2283"))
        assertNull(CastUrls.directBase("https://8.8.8.8"))
        assertNull(CastUrls.directBase("https://nas.local"))
        assertNull(CastUrls.directBase("https://immich"))
        assertNull(CastUrls.directBase("https://photos.royle.au", usePublic = false))
        assertNull(CastUrls.directBase(null))
    }

    @Test fun urlsAreImmichsOwnWithTheSessionKeyLast() {
        val photo = CastUrls.photo("https://photos.royle.au", uuid, "k+y/=")
        assertTrue(photo, photo.startsWith("https://photos.royle.au/api/assets/$uuid/thumbnail?size=fullsize&edited=true&sessionKey="))
        assertEquals("k+y/=", photo.toHttpUrl().queryParameter("sessionKey"))
        assertEquals("https://photos.royle.au/api/assets/$uuid/video/playback?sessionKey=abc", CastUrls.video("https://photos.royle.au", uuid, "abc"))
        assertTrue(CastUrls.photoPreview("https://x.example", uuid, "k").contains("size=preview"))
    }

    @Test fun contentTypeIsWhatTheUrlReturnsNeverTheOriginalsMime() {
        assertEquals("image/jpeg", CastUrls.photoContentType(null))
        assertEquals("image/jpeg", CastUrls.photoContentType("image/heic"))
        assertEquals("image/webp", CastUrls.photoContentType("image/webp; charset=binary"))
        assertTrue(CastUrls.photoNeedsPreview("image/heic"))
        assertTrue(!CastUrls.photoNeedsPreview("image/jpeg") && !CastUrls.photoNeedsPreview(null))
        assertEquals("video/mp4", CastUrls.videoContentType("video/quicktime"))
        assertEquals("video/webm", CastUrls.videoContentType("video/webm"))
        assertEquals("video/mp4", CastUrls.videoContentType("application/octet-stream"))
    }

    /* ---------------- Routing ---------------- */

    @Test fun routes() {
        assertEquals(CastRoute.PhoneLocal(42), CastRouting.route(local(), direct = true))
        assertEquals(CastRoute.Direct(uuid), CastRouting.route(immich(), direct = true))
        assertEquals(CastRoute.Direct(uuid), CastRouting.route(both(), direct = true))
        assertEquals(CastRoute.PhoneLocal(42), CastRouting.route(both(), direct = false))
        assertEquals(CastRoute.PhoneImmich(uuid), CastRouting.route(immich(), direct = false))
    }

    /* ---------------- Resolver ---------------- */

    private lateinit var server: MockWebServer
    private var parent: String? = "parent"
    private val proxy = FakeProxy()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun resolver(base: String? = "https://photos.royle.au", probe: (String) -> String? = { null }): CastResolver {
        val keys = CastSessionManager(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { parent })
        return CastResolver({ base }, { keys }, { probe(it) }, proxy)
    }

    private fun session() = server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"s","token":"tok","expiresAt":"2099-01-01T00:00:00Z"}"""))

    @Test fun immichPhotoGoesDirectWithTheProbedType() = runBlocking {
        session()
        val r = resolver(probe = { "image/jpeg" }).resolve(immich()) as Resolution.Ready
        assertTrue(r.media.url, r.media.url.startsWith("https://photos.royle.au/api/assets/$uuid/thumbnail?size=fullsize") && r.media.url.endsWith("sessionKey=tok"))
        assertEquals("image/jpeg", r.media.contentType)
        assertEquals(CastRoute.Direct(uuid), r.route)
        assertTrue(proxy.prepared.isEmpty())
    }

    @Test fun heicFullsizeFallsBackToThePreview() = runBlocking {
        session()
        val r = resolver(probe = { if (it.contains("fullsize")) "image/heic" else "image/jpeg" }).resolve(immich()) as Resolution.Ready
        assertTrue(r.media.url.contains("size=preview"))
        assertEquals("image/jpeg", r.media.contentType)
    }

    @Test fun immichVideoAnnouncesMp4ForQuickTime() = runBlocking {
        session()
        val r = resolver(probe = { "video/quicktime" }).resolve(immich(video = true)) as Resolution.Ready
        assertTrue(r.media.url.contains("/video/playback?sessionKey=tok"))
        assertEquals("video/mp4", r.media.contentType)
    }

    @Test fun bothGoesDirect() = runBlocking {
        session()
        val r = resolver().resolve(both()) as Resolution.Ready
        assertEquals(CastRoute.Direct(uuid), r.route)
    }

    @Test fun phoneOnlyGoesThroughThePhone() = runBlocking {
        val r = resolver().resolve(local()) as Resolution.Ready
        assertEquals(CastRoute.PhoneLocal(42), r.route)
        assertTrue(r.media.url.startsWith("http://192.168.1.23"))
        assertEquals(0, server.requestCount)
    }

    @Test fun withoutAPublicAddressImmichGoesThroughThePhoneWithANote() = runBlocking {
        val r = resolver(base = null).resolve(immich()) as Resolution.Ready
        assertEquals(CastRoute.PhoneImmich(uuid), r.route)
        assertEquals(CastResolver.NOTE_NO_PUBLIC, r.note)
    }

    @Test fun noSessionAsksToSignInUnlessThereIsAPhoneCopy() = runBlocking {
        parent = null
        assertEquals(Resolution.NeedsSignIn, resolver().resolve(immich()))
        assertEquals(CastRoute.PhoneLocal(42), (resolver().resolve(both()) as Resolution.Ready).route)
    }

    @Test fun relayFailuresAreWorded() = runBlocking {
        proxy.unavailable = PhoneMediaProxy.NO_WIFI
        assertEquals(Resolution.Failed(PhoneMediaProxy.NO_WIFI), resolver().resolve(local()))
    }
}

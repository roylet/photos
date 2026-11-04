package com.roylet.photos

import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.UploadSpec
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ImmichApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ImmichApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = ImmichApi(server.url("/").toString(), "secret-key")
    }

    @After fun tearDown() = server.shutdown()

    private fun spec(bytes: ByteArray, size: Long = bytes.size.toLong()) = UploadSpec(
        filename = "IMG_0001_edit.jpg",
        mime = "image/jpeg",
        size = size,
        checksum = "da39a3ee5e6b4b0d3255bfef95601890afd80709",
        deviceAssetId = "IMG_0001_edit.jpg-3-0",
        createdAt = "2026-10-23T10:00:00Z",
        modifiedAt = "2026-10-23T10:00:00Z",
        open = { ByteArrayInputStream(bytes) },
    )

    @Test fun `upload streams with a content length, auth and checksum headers`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"asset-1","status":"created"}"""))
        val payload = ByteArray(700_000) { (it % 251).toByte() }
        var lastProgress = 0L

        val result = api.upload(spec(payload)) { lastProgress = it }

        assertEquals("asset-1", result.id)
        assertEquals(payload.size.toLong(), lastProgress)
        val req = server.takeRequest()
        assertEquals("/api/assets", req.path)
        assertEquals("secret-key", req.getHeader("x-api-key"))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", req.getHeader("x-immich-checksum"))
        assertNull("must not use chunked transfer encoding", req.getHeader("Transfer-Encoding"))
        assertEquals(req.bodySize, req.getHeader("Content-Length")!!.toLong())
        val text = String(req.body.readByteArray(), Charsets.ISO_8859_1)
        for (field in listOf("deviceAssetId", "deviceId", "fileCreatedAt", "fileModifiedAt", "filename", "assetData")) {
            assertTrue("missing $field", text.contains("name=\"$field\""))
        }
        assertTrue(text.contains(String(payload, Charsets.ISO_8859_1)))
    }

    @Test fun `duplicate response is reported as such`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"asset-9","status":"duplicate"}"""))
        assertEquals("duplicate", api.upload(spec(byteArrayOf(1, 2, 3))) {}.status)
    }

    @Test fun `413 explains proxy limits and is not retryable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(413).setBody("<html>Request Entity Too Large</html>"))
        try {
            api.upload(spec(byteArrayOf(1, 2, 3))) {}
            fail()
        } catch (e: ImmichException) {
            assertEquals(413, e.status)
            assertFalse(e.retryable)
            assertTrue(e.message!!.contains("Cloudflare"))
        }
    }

    @Test fun `server errors are retryable and carry immich's message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"Database busy"}"""))
        try {
            api.me()
            fail()
        } catch (e: ImmichException) {
            assertTrue(e.retryable)
            assertTrue(e.message!!.contains("Database busy"))
        }
    }

    @Test fun `a source that ends early fails the upload instead of sending a truncated file`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"x","status":"created"}"""))
        try {
            api.upload(spec(byteArrayOf(1, 2, 3), size = 10)) {}
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("ended early"))
        }
    }

    @Test fun `me returns the user id with the api key`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"u-42","email":"me@example.com","name":"Roy","isAdmin":true}"""))
        val me = api.me()
        assertEquals("u-42", me.id)
        assertEquals("me@example.com", me.email)
        assertEquals("Roy", me.name)
        val req = server.takeRequest()
        assertEquals("/api/users/me", req.path)
        assertEquals("secret-key", req.getHeader("x-api-key"))
    }

    @Test fun `login then mint a key named Photos app with every permission the app needs`() = runTest {
        server.enqueue(MockResponse().setBody("""{"accessToken":"tok","userId":"u-1","userEmail":"me@example.com","name":"Roy"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"secret":"new-key","apiKey":{"id":"k1"}}"""))
        val anon = ImmichApi(server.url("/").toString(), null)

        val login = anon.login("me@example.com", "pw")
        val key = anon.createApiKey(login.accessToken)

        assertEquals("new-key", key)
        val loginReq = server.takeRequest()
        assertEquals("/api/auth/login", loginReq.path)
        assertNull(loginReq.getHeader("x-api-key"))
        val keyReq = server.takeRequest()
        assertEquals("/api/api-keys", keyReq.path)
        assertEquals("Bearer tok", keyReq.getHeader("Authorization"))
        val body = keyReq.body.readUtf8()
        assertTrue(body.contains("\"name\":\"Photos app\""))
        for (p in listOf("asset.delete", "asset.copy", "stack.create", "sharedLink.create", "sharedLink.read", "album.update", "album.delete", "memory.update", "user.read")) {
            assertTrue("missing permission $p", body.contains("\"$p\""))
        }
    }

    @Test fun `ping recognises an immich server`() = runTest {
        server.enqueue(MockResponse().setBody("""{"res":"pong"}"""))
        assertTrue(api.ping())
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(api.ping())
    }

    @Test fun `a wrong password is not reported as a bad api key`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Incorrect email or password","statusCode":401}"""))
        try {
            api.login("me@example.com", "nope")
            fail()
        } catch (e: ImmichException) {
            assertEquals("Incorrect email or password", e.message)
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Invalid API key","statusCode":401}"""))
        try {
            api.me()
            fail()
        } catch (e: ImmichException) {
            assertTrue(e.message!!.startsWith("Immich rejected the API key"))
        }
    }

    @Test fun `discovery follows an http to https style redirect to the final address`() = runTest {
        val target = MockWebServer().apply { start() }
        target.enqueue(MockResponse().setBody("""{"res":"pong"}"""))
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", target.url("/api/server/ping")))
        // No scheme typed: https is tried first (nothing listens with TLS here), then http, which redirects.
        val origin = ImmichApi.discoverOrigin("${server.hostName}:${server.port}")
        assertEquals(target.url("/").toString().trimEnd('/'), origin)
        target.shutdown()
    }

    @Test fun `server addresses are normalised`() {
        assertEquals("http://192.168.1.5:2283", ImmichApi.normalizeOrigin("192.168.1.5:2283/"))
        assertEquals("https://photos.example.com", ImmichApi.normalizeOrigin(" https://photos.example.com/api "))
        assertEquals("HTTP://host", ImmichApi.normalizeOrigin("HTTP://host"))
    }
}

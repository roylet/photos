package com.roylet.photos

import com.roylet.photos.domain.library.ShareLinkOptions
import com.roylet.photos.domain.timeline.Visibility
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.ImmichLibrary
import com.roylet.photos.immich.albums
import com.roylet.photos.immich.downloadOriginal
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class LibraryApiTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var api: ImmichApi
    private lateinit var library: ImmichLibrary

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = ImmichApi(server.url("/").toString(), "secret-key")
        library = ImmichLibrary({ api }, userId = { "u-1" }, now = { Instant.parse("2026-11-04T00:00:00Z") })
    }

    @After fun tearDown() = server.shutdown()

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

    private val albumJson = """{"id":"a-1","albumName":"Tasmania","description":"Road trip","albumThumbnailAssetId":"x-1","assetCount":3,
        "shared":false,"hasSharedLink":false,"albumUsers":[{"role":"owner","user":{"id":"u-1","name":"Roy"}}],
        "createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"2026-02-01T00:00:00.000Z","isActivityEnabled":true}"""

    @Test fun `new album is created with its assets in one call`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(albumJson))
        val album = library.createAlbum("Tasmania", listOf("x-1", "x-2"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/albums", req.path)
        assertEquals("secret-key", req.getHeader("x-api-key"))
        val body = req.json()
        assertEquals("Tasmania", body["albumName"]!!.jsonPrimitive.content)
        assertEquals(listOf("x-1", "x-2"), body["assetIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("a-1", album.id)
        assertEquals(3, album.assetCount)
        assertEquals("Roy", album.ownerName)
        assertTrue(album.ownedByMe)
    }

    @Test fun `add, remove, cover, rename and delete hit the album endpoints`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id":"x-1","success":true},{"id":"x-2","success":false,"error":"duplicate"}]"""))
        server.enqueue(MockResponse().setBody("""[{"id":"x-1","success":true}]"""))
        server.enqueue(MockResponse().setBody(albumJson))
        server.enqueue(MockResponse().setBody(albumJson))
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(1, library.addToAlbum("a-1", listOf("x-1", "x-2")))
        assertEquals(1, library.removeFromAlbum("a-1", listOf("x-1")))
        library.updateAlbum("a-1", coverAssetId = "x-9")
        library.updateAlbum("a-1", name = "Tassie")
        library.deleteAlbum("a-1")

        server.takeRequest().let { assertEquals("PUT /api/albums/a-1/assets", "${it.method} ${it.path}"); assertEquals(2, it.json()["ids"]!!.jsonArray.size) }
        server.takeRequest().let { assertEquals("DELETE /api/albums/a-1/assets", "${it.method} ${it.path}") }
        server.takeRequest().let {
            assertEquals("PATCH /api/albums/a-1", "${it.method} ${it.path}")
            val body = it.json()
            assertEquals("x-9", body["albumThumbnailAssetId"]!!.jsonPrimitive.content)
            assertNull("unset fields are omitted", body["albumName"])
        }
        server.takeRequest().let { assertEquals("Tassie", it.json()["albumName"]!!.jsonPrimitive.content) }
        server.takeRequest().let { assertEquals("DELETE /api/albums/a-1", "${it.method} ${it.path}") }
    }

    @Test fun `album membership for one asset uses the assetId filter`() = runTest {
        server.enqueue(MockResponse().setBody("[$albumJson]"))
        assertEquals(setOf("a-1"), library.albumsContaining(listOf("x-1")))
        assertEquals("/api/albums?assetId=x-1", server.takeRequest().path)
    }

    @Test fun `individual shared link has assets, options and a public url`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"l-1","key":"KEY123","type":"INDIVIDUAL"}"""))
        server.enqueue(MockResponse().setBody("""{"externalDomain":"https://share.example.com/","trashDays":30}"""))
        val url = library.createSharedLink(listOf("x-1"), null, ShareLinkOptions(expiresInDays = 7, allowDownload = false, showMetadata = false, password = "hunter2"))
        assertEquals("https://share.example.com/share/KEY123", url)
        val body = server.takeRequest().also { assertEquals("/api/shared-links", it.path) }.json()
        assertEquals("INDIVIDUAL", body["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("x-1"), body["assetIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(body["albumId"])
        assertEquals("2026-11-11T00:00:00Z", body["expiresAt"]!!.jsonPrimitive.content)
        assertFalse(body["allowDownload"]!!.jsonPrimitive.boolean)
        assertFalse(body["showMetadata"]!!.jsonPrimitive.boolean)
        assertEquals("hunter2", body["password"]!!.jsonPrimitive.content)
    }

    @Test fun `album shared link falls back to the server address without an external domain`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"l-2","key":"ALB","type":"ALBUM"}"""))
        server.enqueue(MockResponse().setBody("""{"externalDomain":"","trashDays":30}"""))
        val url = library.createSharedLink(null, "a-1", ShareLinkOptions())
        assertEquals(api.origin + "/share/ALB", url)
        val body = server.takeRequest().json()
        assertEquals("ALBUM", body["type"]!!.jsonPrimitive.content)
        assertEquals("a-1", body["albumId"]!!.jsonPrimitive.content)
        assertNull(body["assetIds"])
        assertNull("never expires", body["expiresAt"])
        assertNull("blank password is not sent", body["password"])
    }

    @Test fun `delete goes to the trash and undo restores`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setBody("""{"count":2}"""))
        library.trash(listOf("x-1", "x-2"))
        library.restore(listOf("x-1", "x-2"))
        server.takeRequest().let {
            assertEquals("DELETE /api/assets", "${it.method} ${it.path}")
            val body = it.json()
            assertFalse(body["force"]!!.jsonPrimitive.boolean)
            assertEquals(2, body["ids"]!!.jsonArray.size)
        }
        server.takeRequest().let {
            assertEquals("POST /api/trash/restore/assets", "${it.method} ${it.path}")
            assertEquals(listOf("x-1", "x-2"), it.json()["ids"]!!.jsonArray.map { e -> e.jsonPrimitive.content })
        }
    }

    @Test fun `restore all, empty trash and delete forever`() = runTest {
        server.enqueue(MockResponse().setBody("""{"count":5}"""))
        server.enqueue(MockResponse().setBody("""{"count":3}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(5, library.restoreAll())
        assertEquals(3, library.emptyTrash())
        library.deleteForever(listOf("x-1"))
        assertEquals("/api/trash/restore", server.takeRequest().path)
        assertEquals("/api/trash/empty", server.takeRequest().path)
        assertTrue(server.takeRequest().json()["force"]!!.jsonPrimitive.boolean)
    }

    @Test fun `archive and unarchive set visibility`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        library.setVisibility(listOf("x-1"), Visibility.ARCHIVE)
        library.setVisibility(listOf("x-1"), Visibility.TIMELINE)
        server.takeRequest().let {
            assertEquals("PUT /api/assets", "${it.method} ${it.path}")
            assertEquals("archive", it.json()["visibility"]!!.jsonPrimitive.content)
        }
        assertEquals("timeline", server.takeRequest().json()["visibility"]!!.jsonPrimitive.content)
    }

    @Test fun `smart search maps assets to grid cells`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"albums":{"items":[],"total":0},"assets":{"total":1,"count":1,"items":[{"id":"x-1","type":"VIDEO","width":1920,"height":1080,
                "duration":"0:00:12.500000","isFavorite":true,"fileCreatedAt":"2025-06-01T02:00:00.000Z","localDateTime":"2025-06-01T12:00:00.000Z",
                "exifInfo":{"city":"Hobart","country":"Australia"}}]}}""",
            ),
        )
        val results = library.smartSearch("beach")
        assertEquals("beach", server.takeRequest().json()["query"]!!.jsonPrimitive.content)
        val a = results.single()
        assertTrue(a.isVideo)
        assertEquals(16f / 9f, a.ratio, 0.001f)
        assertEquals(12_500L, a.durationMs)
        assertEquals(10.0, a.localOffsetHours, 0.001)
        assertEquals("Hobart", a.city)
    }

    @Test fun `download streams the original with progress`() = runTest {
        val bytes = ByteArray(300_000) { (it % 7).toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        var last = 0L
        val f = api.downloadOriginal("x-1", tmp.root.resolve("x.jpg"), edited = true) { done, _ -> last = done }
        assertEquals("/api/assets/x-1/original?edited=true", server.takeRequest().path)
        assertEquals(bytes.size.toLong(), f.length())
        assertEquals(bytes.size.toLong(), last)
    }

    @Test fun `a failed download leaves no partial file`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
        val dest = tmp.root.resolve("y.jpg")
        try { api.downloadOriginal("x-2", dest); throw AssertionError("expected failure") } catch (_: ImmichException) {}
        assertFalse(dest.exists())
    }

    @Test fun `album list parses owners`() = runTest {
        server.enqueue(MockResponse().setBody("[$albumJson]"))
        assertEquals("Tasmania", api.albums().single().albumName)
    }
}

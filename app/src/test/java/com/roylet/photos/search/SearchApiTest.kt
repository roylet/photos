package com.roylet.photos.search

import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.ImmichLibrary
import com.roylet.photos.immich.SearchResponseDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** Search against realistic Immich v3.4 responses (SearchResponseDto, SearchExploreResponseDto, suggestions). */
class SearchApiTest {
    private lateinit var server: MockWebServer
    private lateinit var library: ImmichLibrary

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val api = ImmichApi(server.url("/").toString(), "secret-key")
        library = ImmichLibrary({ api }, userId = { "u-1" }, now = { Instant.parse("2026-11-04T00:00:00Z") })
    }

    @After fun tearDown() = server.shutdown()

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

    @Test fun `smart search sends the v3_4 body and reads a full response`() = runTest {
        server.enqueue(MockResponse().setBody(SearchFixtures.searchResponse(SearchFixtures.image, SearchFixtures.video, SearchFixtures.bare)))
        val results = library.smartSearch("beach")
        val req = server.takeRequest()
        assertEquals("POST /api/search/smart", "${req.method} ${req.path}")
        val body = req.json()
        assertEquals("beach", body["query"]!!.jsonPrimitive.content)
        // Sent explicitly (Immich's own defaults are 100 results and no EXIF).
        assertEquals(200, body["size"]!!.jsonPrimitive.int)
        assertTrue(body["withExif"]!!.jsonPrimitive.boolean)
        assertFalse("page" in body)

        assertEquals(listOf(SearchFixtures.IMAGE_ID, SearchFixtures.VIDEO_ID, SearchFixtures.BARE_ID), results.map { it.id })
        val img = results[0]
        assertEquals(4032f / 3024f, img.ratio, 0.001f)
        assertEquals("Hobart", img.city)
        assertNull(img.durationMs)
        val vid = results[1]
        assertTrue(vid.isVideo)
        assertEquals(12_500L, vid.durationMs)
        // Every nullable field null, the optional ones missing: still a cell.
        assertEquals(1f, results[2].ratio)
    }

    @Test fun `a repeated asset in the results is one cell`() = runTest {
        server.enqueue(MockResponse().setBody(SearchFixtures.searchResponse(SearchFixtures.image, SearchFixtures.image, SearchFixtures.video)))
        assertEquals(listOf(SearchFixtures.IMAGE_ID, SearchFixtures.VIDEO_ID), library.smartSearch("x").map { it.id })
    }

    @Test fun `nulls where Immich documents plain values don't break decoding`() {
        val json = """{"albums":null,"assets":{"items":[{"id":"n-1","type":null,"originalFileName":null,"isFavorite":null,"isArchived":null,
            "isTrashed":null,"visibility":null,"people":null,"exifInfo":null,"duration":null,"width":null,"height":null}],"total":null,"count":null,
            "nextPage":null,"nextCursor":null,"facets":null}}"""
        val dto = ImmichApi.json.decodeFromString<SearchResponseDto>(json)
        val a = dto.assets.items.single()
        assertEquals("IMAGE", a.type)
        assertEquals("", a.originalFileName)
        assertFalse(a.isFavorite)
        assertTrue(a.people.isEmpty())
        assertEquals(0, dto.assets.total)
    }

    @Test fun `an empty or partial response is no results, not a crash`() = runTest {
        server.enqueue(MockResponse().setBody("""{}"""))
        server.enqueue(MockResponse().setBody("""{"albums":{"items":[],"total":0,"count":0,"facets":[]},"assets":{"items":[]}}"""))
        assertTrue(library.smartSearch("a").isEmpty())
        assertTrue(library.smartSearch("b").isEmpty())
    }

    @Test fun `smart search disabled on the server falls back to the file-name search`() = runTest {
        for (status in listOf(400, 404, 501)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"message":"Smart search is not enabled","error":"Bad Request","statusCode":$status}"""))
            server.enqueue(MockResponse().setBody(SearchFixtures.searchResponse(SearchFixtures.image)))
            val results = library.smartSearch("IMG_20")
            assertEquals("/api/search/smart", server.takeRequest().path)
            val fallback = server.takeRequest()
            assertEquals("POST /api/search/metadata", "${fallback.method} ${fallback.path}")
            val body = fallback.json()
            assertEquals("IMG_20", body["originalFileName"]!!.jsonPrimitive.content)
            assertEquals(200, body["size"]!!.jsonPrimitive.int)
            assertEquals(listOf(SearchFixtures.IMAGE_ID), results.map { it.id })
        }
    }

    @Test fun `other server errors are reported, not swallowed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"Internal server error"}"""))
        try {
            library.smartSearch("beach")
            fail("expected an error")
        } catch (e: ImmichException) {
            assertEquals(500, e.status)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `place search is a metadata search by city or country`() = runTest {
        server.enqueue(MockResponse().setBody(SearchFixtures.searchResponse(SearchFixtures.image)))
        server.enqueue(MockResponse().setBody(SearchFixtures.searchResponse()))
        assertEquals(1, library.placeSearch("Hobart", null).size)
        server.takeRequest().json().let {
            assertEquals("Hobart", it["city"]!!.jsonPrimitive.content)
            assertFalse("country" in it) // explicitNulls = false
            assertTrue(it["withExif"]!!.jsonPrimitive.boolean)
        }
        assertTrue(library.placeSearch(null, "Australia").isEmpty())
        assertEquals("Australia", server.takeRequest().json()["country"]!!.jsonPrimitive.content)
    }

    @Test fun `explore maps places and drops items it can't show`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"fieldName":"exifInfo.city","items":[
                    {"value":"Hobart","data":${SearchFixtures.image}},
                    {"value":"Hobart","data":${SearchFixtures.video}},
                    {"value":"","data":${SearchFixtures.bare}},
                    {"value":"Launceston"},
                    {"value":null,"data":${SearchFixtures.bare}},
                    {"value":"Sydney","data":${SearchFixtures.bare}}]},
                  {"fieldName":"smartInfo.tags","items":[]}]""",
            ),
        )
        val sections = library.explore()
        assertEquals("/api/search/explore", server.takeRequest().path)
        val places = sections.single()
        assertTrue(places.isPlaces)
        assertEquals(listOf("Hobart", "Sydney"), places.items.map { it.value })
        assertEquals(SearchFixtures.IMAGE_ID, places.items[0].asset.id)
    }

    @Test fun `suggestions tolerate nulls and blanks`() = runTest {
        server.enqueue(MockResponse().setBody("""["Australia",null,"","New Zealand"]"""))
        assertEquals(listOf("Australia", "New Zealand"), library.suggestions("country"))
        assertEquals("/api/search/suggestions?type=country", server.takeRequest().path)
    }
}

/** Realistic Immich v3.4 `AssetResponseDto` JSON. */
object SearchFixtures {
    const val IMAGE_ID = "3f1c6b2e-8d4a-4c1e-9b7a-2d5e8f0a1b2c"
    const val VIDEO_ID = "7a2b9c4d-1e3f-4a5b-8c6d-9e0f1a2b3c4d"
    const val BARE_ID = "c0ffee00-1234-4abc-8def-001122334455"

    val image = """{"id":"$IMAGE_ID","ownerId":"u-1","owner":{"id":"u-1","email":"roy@example.com","name":"Roy","profileImagePath":"","avatarColor":"primary","profileChangedAt":"2026-01-01T00:00:00.000Z"},
        "libraryId":null,"type":"IMAGE","originalPath":"/data/library/u-1/2024/03/IMG_1234.HEIC","originalFileName":"IMG_1234.HEIC","originalMimeType":"image/heic",
        "thumbhash":"1QcSHQRnh493V4dIh4eXh1h4kJUI","fileCreatedAt":"2024-03-02T03:14:15.000Z","fileModifiedAt":"2024-03-02T03:14:15.000Z",
        "localDateTime":"2024-03-02T14:14:15.000Z","updatedAt":"2024-03-03T00:00:00.000Z","createdAt":"2024-03-03T00:00:00.000Z",
        "isFavorite":false,"isArchived":false,"isTrashed":false,"isOffline":false,"isEdited":false,"hasMetadata":true,"visibility":"timeline",
        "duration":null,"width":4032,"height":3024,"livePhotoVideoId":null,"checksum":"q83vEjRWeJA=","stack":null,"duplicateId":null,"resized":true,
        "exifInfo":{"make":"Apple","model":"iPhone 15","exifImageWidth":4032,"exifImageHeight":3024,"fileSizeInByte":2345678,"orientation":"1",
          "dateTimeOriginal":"2024-03-02T03:14:15.000Z","modifyDate":null,"timeZone":"Australia/Hobart","lensModel":null,"fNumber":1.8,"focalLength":6.9,
          "iso":64,"exposureTime":"1/120","latitude":-42.88,"longitude":147.32,"city":"Hobart","state":"Tasmania","country":"Australia",
          "description":"","projectionType":null,"rating":null},
        "tags":[],"people":[{"id":"p-1","name":"Sam","birthDate":null,"thumbnailPath":"/x.jpeg","isHidden":false,"faces":[]}]}"""

    val video = """{"id":"$VIDEO_ID","ownerId":"u-1","libraryId":null,"type":"VIDEO","originalPath":"/data/v.mp4","originalFileName":"VID_0001.mp4",
        "originalMimeType":"video/mp4","thumbhash":null,"fileCreatedAt":"2025-06-01T02:00:00.000Z","fileModifiedAt":"2025-06-01T02:00:00.000Z",
        "localDateTime":"2025-06-01T12:00:00.000Z","updatedAt":"2025-06-01T02:00:00.000Z","createdAt":"2025-06-01T02:00:00.000Z",
        "isFavorite":true,"isArchived":false,"isTrashed":false,"isOffline":false,"isEdited":false,"hasMetadata":true,"visibility":"archive",
        "duration":12500,"width":1920,"height":1080,"livePhotoVideoId":null,"checksum":"AAAA","stack":null,"duplicateId":null}"""

    /** Only the required fields, every nullable one null, and an asset type the app doesn't know. */
    val bare = """{"id":"$BARE_ID","ownerId":"u-1","type":"OTHER","originalPath":"/x","originalFileName":"x.bin","thumbhash":null,
        "fileCreatedAt":"2023-01-01T00:00:00.000Z","fileModifiedAt":"2023-01-01T00:00:00.000Z","localDateTime":"2023-01-01T00:00:00.000Z",
        "updatedAt":"2023-01-01T00:00:00.000Z","createdAt":"2023-01-01T00:00:00.000Z","isFavorite":false,"isArchived":false,"isTrashed":false,
        "isOffline":false,"isEdited":false,"hasMetadata":false,"visibility":"locked","duration":null,"width":null,"height":null,"checksum":"BBBB"}"""

    fun searchResponse(vararg assets: String): String =
        """{"albums":{"total":0,"count":0,"items":[],"facets":[]},
            "assets":{"total":${assets.size},"count":${assets.size},"items":[${assets.joinToString(",")}],"facets":[],"nextPage":null,"nextCursor":null}}"""
}

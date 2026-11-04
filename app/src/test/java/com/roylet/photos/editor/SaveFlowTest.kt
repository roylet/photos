package com.roylet.photos.editor

import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.save.RecipeMetadata
import com.roylet.photos.editor.save.SaveFlow
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.save.SaveResult
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
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
import java.util.concurrent.TimeUnit

class SaveFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var flow: SaveFlow

    private val old = "11111111-1111-4111-8111-111111111111"
    private val new = "22222222-2222-4222-8222-222222222222"
    private val recipe = Recipe(light = Light(exposure = 0.5f))

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        flow = SaveFlow(ImmichApi(server.url("/").toString(), "k"))
    }

    @After fun tearDown() = server.shutdown()

    private fun assetJson() = """{"id":"$old","type":"IMAGE","originalFileName":"IMG_1.HEIC",
        "fileCreatedAt":"2024-05-01T10:00:00.000Z","fileModifiedAt":"2024-05-02T11:00:00.000Z","isEdited":false,"checksum":"abc"}"""

    private fun ok(body: String = "", code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private suspend fun save(mode: SaveMode, group: Boolean = true): SaveResult {
        val file = tmp.newFile("IMG_1-edited.jpg").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        return flow.run(old, old, file, "IMG_1-edited.jpg", recipe, mode, group)
    }

    private fun take(): RecordedRequest = server.takeRequest(1, TimeUnit.SECONDS) ?: error("missing request")
    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject
    private fun JsonObject.bool(k: String) = this[k]!!.jsonPrimitive.content.toBooleanStrict()

    private fun assertUpload(req: RecordedRequest) {
        assertEquals("POST /api/assets", "${req.method} ${req.path}")
        val text = req.body.readUtf8()
        assertTrue(text.contains("2024-05-01T10:00:00.000Z"))
        assertTrue(text.contains("2024-05-02T11:00:00.000Z"))
        assertTrue(text.contains("filename=\"IMG_1-edited.jpg\""))
        assertNotNull(req.getHeader("x-immich-checksum"))
    }

    private fun assertMetadata(req: RecordedRequest) {
        assertEquals("PUT /api/assets/$new/metadata", "${req.method} ${req.path}")
        val item = req.json()["items"]!!.jsonArray.single().jsonObject
        assertEquals(RecipeMetadata.KEY, item["key"]!!.jsonPrimitive.content)
        val (source, decoded) = RecipeMetadata.decode(item["value"]!!.jsonObject)!!
        assertEquals(old, source)
        assertEquals(recipe, decoded)
    }

    @Test fun `copy uploads, copies albums only, stacks new first, then stores the recipe`() = runTest {
        server.enqueue(ok(assetJson()))
        server.enqueue(ok("""{"id":"$new","status":"created"}""", 201))
        server.enqueue(ok(code = 204))
        server.enqueue(ok("""{"id":"s1","primaryAssetId":"$new","assets":[]}""", 201))
        server.enqueue(ok("[]"))

        val r = save(SaveMode.COPY)

        assertEquals("GET /api/assets/$old", take().let { "${it.method} ${it.path}" })
        assertUpload(take())
        take().let {
            assertEquals("PUT /api/assets/copy", "${it.method} ${it.path}")
            val b = it.json()
            assertEquals(old, b["sourceId"]!!.jsonPrimitive.content)
            assertEquals(new, b["targetId"]!!.jsonPrimitive.content)
            assertTrue(b.bool("albums"))
            for (k in listOf("favorite", "sharedLinks", "stack", "sidecar")) assertFalse(k, b.bool(k))
        }
        take().let {
            assertEquals("POST /api/stacks", "${it.method} ${it.path}")
            assertEquals(listOf(new, old), it.json()["assetIds"]!!.jsonArray.map { e -> e.jsonPrimitive.content })
        }
        assertMetadata(take())
        assertEquals(5, server.requestCount)
        assertEquals(SaveResult(new, old, SaveMode.COPY, grouped = true), r)
    }

    @Test fun `copy without grouping skips the stack`() = runTest {
        server.enqueue(ok(assetJson()))
        server.enqueue(ok("""{"id":"$new","status":"created"}""", 201))
        server.enqueue(ok(code = 204))
        server.enqueue(ok("[]"))
        val r = save(SaveMode.COPY, group = false)
        val paths = List(4) { take().let { "${it.method} ${it.path}" } }
        assertEquals(listOf("GET /api/assets/$old", "POST /api/assets", "PUT /api/assets/copy", "PUT /api/assets/$new/metadata"), paths)
        assertFalse(r.grouped)
    }

    @Test fun `replace copies everything, trashes the original without force, then stores the recipe`() = runTest {
        server.enqueue(ok(assetJson()))
        server.enqueue(ok("""{"id":"$new","status":"created"}""", 201))
        server.enqueue(ok(code = 204))
        server.enqueue(ok(code = 204))
        server.enqueue(ok("[]"))

        val r = save(SaveMode.REPLACE)

        take(); assertUpload(take())
        take().let {
            assertEquals("PUT /api/assets/copy", "${it.method} ${it.path}")
            val b = it.json()
            for (k in listOf("albums", "favorite", "sharedLinks", "stack", "sidecar")) assertTrue(k, b.bool(k))
        }
        take().let {
            assertEquals("DELETE /api/assets", "${it.method} ${it.path}")
            val b = it.json()
            assertEquals(listOf(old), b["ids"]!!.jsonArray.map { e -> e.jsonPrimitive.content })
            assertFalse(b.bool("force"))
        }
        assertMetadata(take())
        assertEquals(SaveResult(new, old, SaveMode.REPLACE, originalTrashed = true), r)
    }

    @Test fun `undo restores the original then trashes the edit`() = runTest {
        server.enqueue(ok("""{"count":1}"""))
        server.enqueue(ok(code = 204))
        flow.undoReplace(SaveResult(new, old, SaveMode.REPLACE, originalTrashed = true))
        take().let {
            assertEquals("POST /api/trash/restore/assets", "${it.method} ${it.path}")
            assertEquals(listOf(old), it.json()["ids"]!!.jsonArray.map { e -> e.jsonPrimitive.content })
        }
        take().let {
            assertEquals("DELETE /api/assets", "${it.method} ${it.path}")
            val b = it.json()
            assertEquals(listOf(new), b["ids"]!!.jsonArray.map { e -> e.jsonPrimitive.content })
            assertFalse(b.bool("force"))
        }
    }

    @Test fun `failed upload deletes nothing`() = runTest {
        server.enqueue(ok(assetJson()))
        server.enqueue(ok("""{"message":"Bad gateway"}""", 502))
        try {
            save(SaveMode.REPLACE)
            fail("expected failure")
        } catch (e: ImmichException) {
            assertTrue(e.message!!.contains("Server error"))
        }
        assertEquals(2, server.requestCount)
        take(); assertEquals("POST", take().method)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `replace keeps the original when the copy fails`() = runTest {
        server.enqueue(ok(assetJson()))
        server.enqueue(ok("""{"id":"$new","status":"created"}""", 201))
        server.enqueue(ok("""{"message":"nope"}""", 400))
        server.enqueue(ok("[]"))
        val r = save(SaveMode.REPLACE)
        val methods = List(4) { take().method }
        assertEquals(listOf("GET", "POST", "PUT", "PUT"), methods)
        assertEquals(SaveMode.COPY, r.mode)
        assertFalse(r.originalTrashed)
        assertTrue(r.warning!!.contains("original was kept"))
    }

    @Test fun `duplicate of the opened asset is a friendly error`() = runTest {
        server.enqueue(ok(assetJson()))
        server.enqueue(ok("""{"id":"$old","status":"duplicate"}"""))
        try {
            save(SaveMode.REPLACE); fail()
        } catch (e: ImmichException) {
            assertTrue(e.message!!.contains("identical"))
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun `duplicate of another asset still links it`() = runTest {
        server.enqueue(ok(assetJson()))
        server.enqueue(ok("""{"id":"$new","status":"duplicate"}"""))
        server.enqueue(ok(code = 204))
        server.enqueue(ok("""{"id":"s1"}""", 201))
        server.enqueue(ok("[]"))
        assertTrue(save(SaveMode.COPY).duplicate)
    }

    @Test fun `edited filenames`() {
        assertEquals("IMG_1-edited.jpg", SaveFlow.editedFilename("IMG_1.HEIC"))
        assertEquals("IMG_1-edited.jpg", SaveFlow.editedFilename("IMG_1-edited.jpg"))
        assertEquals("noext-edited.jpg", SaveFlow.editedFilename("noext"))
    }
}

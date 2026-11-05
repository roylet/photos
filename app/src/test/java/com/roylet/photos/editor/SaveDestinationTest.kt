package com.roylet.photos.editor

import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.save.EditSaveManager
import com.roylet.photos.editor.save.SaveDestination
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.save.SaveOutcome
import com.roylet.photos.editor.save.SaveResult
import com.roylet.photos.editor.save.SaveSink
import com.roylet.photos.editor.save.SinkInput
import com.roylet.photos.editor.save.SinkStage
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.local.LocalSaveCheckpoint
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** Where photo saves go: this phone, Immich or both, and the undo of each. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveDestinationTest {
    private lateinit var server: MockWebServer
    private lateinit var manager: EditSaveManager
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val log = mutableListOf<String>()
    private val remote = "11111111-1111-4111-8111-111111111111"
    private val new = "22222222-2222-4222-8222-222222222222"

    private inner class FakeSink : SaveSink {
        override suspend fun save(input: SinkInput, onProgress: (SinkStage, Float) -> Unit): SaveResult {
            log += "local ${input.targetId} ${input.mode} ${input.mimeType}"
            return SaveResult("local:100", input.targetId, input.mode, originalTrashed = input.mode == SaveMode.REPLACE, local = true)
        }
        override suspend fun undoReplace(result: SaveResult) { log += "undo local ${result.targetId}" }
    }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val api = ImmichApi(server.url("/").toString(), "k")
        manager = EditSaveManager(context, { api }, render = { _, _, out -> out.writeBytes(ByteArray(64)) },
            localSink = { _: LocalSaveCheckpoint, _: (LocalSaveCheckpoint) -> Unit -> FakeSink() })
    }

    @After fun tearDown() {
        server.shutdown()
        File(context.filesDir, "edit-save").deleteRecursively()
    }

    private fun job(mode: SaveMode, destination: SaveDestination, remoteTarget: String? = null): String {
        val source = File(context.cacheDir, "editor/src/IMG_7.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(16)) }
        return manager.createJob("local:7", "local:7", source, "IMG_7.jpg", Recipe(light = Light(exposure = 0.2f)), mode, false, destination, remoteTarget)
    }

    private fun ok(body: String = "", code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Test fun `a save to this phone needs no network and never calls Immich`() = runTest {
        val id = job(SaveMode.COPY, SaveDestination.DEVICE)
        assertEquals(NetworkType.NOT_REQUIRED, manager.request(id).workSpec.constraints.requiredNetworkType)
        val out = manager.execute(id, finalAttempt = true)
        assertTrue(out is SaveOutcome.Saved)
        assertEquals(listOf("local local:7 COPY image/jpeg"), log)
        assertEquals(0, server.requestCount)
        assertTrue((out as SaveOutcome.Saved).result.local)
    }

    @Test fun `both saves on the phone, then uploads to the item's Immich copy`() = runTest {
        val id = job(SaveMode.REPLACE, SaveDestination.BOTH, remoteTarget = remote)
        assertEquals(NetworkType.CONNECTED, manager.request(id).workSpec.constraints.requiredNetworkType)
        server.enqueue(ok("""{"id":"$remote","type":"IMAGE","originalFileName":"IMG_7.jpg","fileCreatedAt":"2024-05-01T10:00:00.000Z","fileModifiedAt":"2024-05-01T10:00:00.000Z"}"""))
        server.enqueue(ok("""{"id":"$new","status":"created"}""", 201))
        server.enqueue(ok(code = 204)) // copy
        server.enqueue(ok(code = 204)) // trash
        server.enqueue(ok("{}")) // metadata
        val out = manager.execute(id, finalAttempt = true) as SaveOutcome.Saved
        assertEquals("local local:7 REPLACE image/jpeg", log.single())
        val requests = List(5) { server.takeRequest(1, TimeUnit.SECONDS)!!.let { "${it.method} ${it.path}" } }
        assertEquals("GET /api/assets/$remote", requests[0])
        assertTrue(requests[4].contains("/assets/$new/metadata"))
        val r = out.result
        assertEquals("local:100", r.newAssetId)
        assertEquals(new, r.companion!!.newAssetId)
        assertTrue(r.anyOriginalTrashed)
        assertEquals(r, manager.lastReplace.value)

        // Undo goes to each side.
        server.enqueue(ok("""{"count":1}"""))
        server.enqueue(ok(code = 204))
        manager.undoReplace(r)
        assertEquals("undo local local:7", log.last())
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.path!!.contains("/trash/restore/assets"))
    }
}

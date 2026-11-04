package com.roylet.photos.editor

import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.save.EditSaveManager
import com.roylet.photos.editor.save.SaveErrors
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.save.SaveOutcome
import com.roylet.photos.editor.save.SaveProgress
import com.roylet.photos.editor.save.SaveStage
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** The save worker's retry policy and resuming a save after a partial success. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveRetryTest {
    private lateinit var server: MockWebServer
    private lateinit var manager: EditSaveManager
    private var renders = 0
    private var renderFails = false
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val old = "11111111-1111-4111-8111-111111111111"
    private val new = "22222222-2222-4222-8222-222222222222"

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val api = ImmichApi(server.url("/").toString(), "k")
        manager = EditSaveManager(context, { api }, render = { _, _, out ->
            renders++
            if (renderFails) throw IllegalStateException("encoder exploded")
            out.writeBytes(ByteArray(2048) { it.toByte() })
        })
    }

    @After fun tearDown() {
        server.shutdown()
        File(context.filesDir, "edit-save").deleteRecursively()
    }

    private fun job(mode: SaveMode = SaveMode.COPY): String {
        val source = File(context.cacheDir, "editor/src/IMG_1.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(16)) }
        return manager.createJob(old, old, source, "IMG_1.HEIC", Recipe(light = Light(exposure = 0.3f)), mode, group = true)
    }

    private fun ok(body: String = "", code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)
    private fun asset(id: String = old, deviceAssetId: String = "", trashed: Boolean = false) = ok(
        """{"id":"$id","type":"IMAGE","originalFileName":"IMG_1.HEIC","fileCreatedAt":"2024-05-01T10:00:00.000Z",
           "fileModifiedAt":"2024-05-02T11:00:00.000Z","checksum":"abc","deviceAssetId":"$deviceAssetId","isTrashed":$trashed}""",
    )

    private fun drain(): List<String> = buildList {
        while (true) add(server.takeRequest(100, TimeUnit.MILLISECONDS)?.let { "${it.method} ${it.path}" } ?: break)
    }

    private fun specExists(jobId: String) = File(context.filesDir, "edit-save/$jobId.json").exists()

    @Test fun `transient errors are classified for retry, permanent ones are not`() {
        assertTrue(SaveErrors.isTransient(IOException("reset")))
        assertTrue(SaveErrors.isTransient(SocketTimeoutException()))
        for (code in listOf(408, 429, 500, 502, 503)) assertTrue("$code", SaveErrors.isTransient(ImmichApi.httpError(code, "")))
        for (code in listOf(400, 401, 403, 404, 413, 422)) assertFalse("$code", SaveErrors.isTransient(ImmichApi.httpError(code, "")))
        assertFalse(SaveErrors.isTransient(ImmichException("Not signed in")))
        assertFalse(SaveErrors.isTransient(IllegalStateException("decode")))
    }

    @Test fun `the work request needs a network and backs off exponentially`() {
        val spec = manager.request("j").workSpec
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(EditSaveManager.RETRY_BACKOFF_SECONDS), spec.backoffDelayDuration)
        assertTrue(spec.expedited)
    }

    @Test fun `a 503 on upload retries and keeps the job, the next attempt saves`() = runTest {
        val id = job()
        server.enqueue(asset())
        server.enqueue(ok("""{"message":"busy"}""", 503))
        val first = manager.execute(id, finalAttempt = false)
        assertTrue(first is SaveOutcome.Retry)
        assertTrue("spec kept for the retry", specExists(id))
        assertEquals(SaveProgress.Running(SaveStage.WAITING, 0f), manager.jobs.value[id])
        drain()

        server.enqueue(asset())
        server.enqueue(ok("""{"id":"$new","status":"created"}""", 201))
        server.enqueue(ok(code = 204))
        server.enqueue(ok("""{"id":"s1","primaryAssetId":"$new"}""", 201))
        server.enqueue(ok("[]"))
        val second = manager.execute(id, finalAttempt = false)
        assertEquals(new, (second as SaveOutcome.Saved).result.newAssetId)
        assertNull(second.result.warning)
        assertEquals("rendered once; the retry reused the JPEG", 1, renders)
        assertFalse("spec deleted when done", specExists(id))
    }

    @Test fun `a dropped connection is retried`() = runTest {
        val id = job()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(manager.execute(id, finalAttempt = false) is SaveOutcome.Retry)
        assertTrue(specExists(id))
    }

    @Test fun `after the upload succeeded a retry never uploads again`() = runTest {
        val id = job()
        server.enqueue(asset())
        server.enqueue(ok("""{"id":"$new","status":"created"}""", 201))
        server.enqueue(ok("""{"message":"gateway"}""", 502)) // PUT /assets/copy
        assertTrue(manager.execute(id, finalAttempt = false) is SaveOutcome.Retry)
        assertEquals(listOf("GET /api/assets/$old", "POST /api/assets", "PUT /api/assets/copy"), drain())
        assertEquals(new, manager.readSpec(id)!!.checkpoint.newAssetId)

        // Also simulate the cache being pruned: the original isn't needed any more.
        File(manager.readSpec(id)!!.sourcePath).delete()
        server.enqueue(ok(code = 204))
        server.enqueue(ok("""{"id":"s1","primaryAssetId":"$new"}""", 201))
        server.enqueue(ok("[]"))
        val r = manager.execute(id, finalAttempt = false) as SaveOutcome.Saved
        assertEquals(listOf("PUT /api/assets/copy", "POST /api/stacks", "PUT /api/assets/$new/metadata"), drain())
        assertTrue(r.result.grouped)
        assertNull(r.result.warning)
        assertEquals(1, renders)
    }

    @Test fun `a replace resumed after the trash step does not trash twice`() = runTest {
        val id = job(SaveMode.REPLACE)
        server.enqueue(asset())
        server.enqueue(ok("""{"id":"$new","status":"created"}""", 201))
        server.enqueue(ok(code = 204)) // copy
        server.enqueue(ok(code = 204)) // trash
        server.enqueue(ok("", 500)) // metadata
        assertTrue(manager.execute(id, finalAttempt = false) is SaveOutcome.Retry)
        drain()
        server.enqueue(ok("[]"))
        val r = manager.execute(id, finalAttempt = false) as SaveOutcome.Saved
        assertEquals(listOf("PUT /api/assets/$new/metadata"), drain())
        assertTrue(r.result.originalTrashed)
        assertEquals(SaveMode.REPLACE, r.result.mode)
    }

    @Test fun `an upload whose response was lost is recognised as our own on retry`() = runTest {
        val id = job(SaveMode.REPLACE)
        server.enqueue(asset())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(manager.execute(id, finalAttempt = false) is SaveOutcome.Retry)
        drain()
        val ours = manager.readSpec(id)!!.checkpoint.deviceAssetId
        assertNotNull(ours)

        server.enqueue(asset())
        server.enqueue(ok("""{"id":"$new","status":"duplicate"}"""))
        server.enqueue(asset(new, deviceAssetId = ours!!))
        server.enqueue(ok(code = 204))
        server.enqueue(ok(code = 204))
        server.enqueue(ok("[]"))
        val r = manager.execute(id, finalAttempt = false) as SaveOutcome.Saved
        assertTrue(r.result.duplicate)
        assertFalse("undo may trash it: this save created it", r.result.preexisting)
    }

    @Test fun `a 4xx fails for good`() = runTest {
        val id = job()
        server.enqueue(asset())
        server.enqueue(ok("""{"message":"Invalid file type"}""", 400))
        val out = manager.execute(id, finalAttempt = false)
        assertTrue(out is SaveOutcome.Failed)
        assertTrue(manager.jobs.value[id] is SaveProgress.Failed)
        assertFalse(specExists(id))
    }

    @Test fun `a render or encode failure fails for good`() = runTest {
        renderFails = true
        val id = job()
        assertTrue(manager.execute(id, finalAttempt = false) is SaveOutcome.Failed)
        assertEquals(0, server.requestCount)
        assertFalse(specExists(id))
    }

    @Test fun `on the last attempt a transient late failure becomes a warning`() = runTest {
        val id = job()
        server.enqueue(asset())
        server.enqueue(ok("""{"id":"$new","status":"created"}""", 201))
        server.enqueue(ok("", 503)) // copy
        server.enqueue(ok("""{"id":"s1","primaryAssetId":"$new"}""", 201))
        server.enqueue(ok("[]"))
        val r = manager.execute(id, finalAttempt = true) as SaveOutcome.Saved
        assertTrue(r.result.warning!!.contains("albums"))
        assertFalse(specExists(id))
    }

    @Test fun `the last attempt of a failing upload reports the failure`() = runTest {
        val id = job()
        server.enqueue(asset())
        server.enqueue(ok("", 503))
        assertTrue(manager.execute(id, finalAttempt = true) is SaveOutcome.Failed)
        assertFalse(specExists(id))
    }
}

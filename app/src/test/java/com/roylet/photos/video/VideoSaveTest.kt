package com.roylet.photos.video

import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.video.export.CompositionPlanner
import com.roylet.photos.video.export.EncoderCaps
import com.roylet.photos.video.export.ExportPlan
import com.roylet.photos.video.export.ExportSummary
import com.roylet.photos.video.export.SourceInfo
import com.roylet.photos.video.export.VideoExportEngine
import com.roylet.photos.video.export.VideoExportException
import com.roylet.photos.video.io.VideoOriginals
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.save.VideoEditMetadata
import com.roylet.photos.video.save.VideoSaveFlow
import com.roylet.photos.video.save.VideoSaveJob
import com.roylet.photos.video.save.VideoSaveJobSpec
import com.roylet.photos.video.save.VideoSaveStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections

/** The video save order and safety rules, against a fake Immich (MockWebServer) and a fake exporter. */
class VideoSaveTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var api: ImmichApi

    private val old = "11111111-1111-4111-8111-111111111111"
    private val orig = "33333333-3333-4333-8333-333333333333"
    private val new = "22222222-2222-4222-8222-222222222222"
    private val edit = VideoEdit.initial(10_000).trimStart(1_000).copy(color = Recipe(light = Light(exposure = 0.5f)))

    /** Recorded "METHOD path" lines, plus bodies. */
    private val log = Collections.synchronizedList(mutableListOf<String>())
    private val bodies = Collections.synchronizedMap(mutableMapOf<String, String>())
    /** Overrides by "METHOD path": a response, or null for the default. */
    private val overrides = Collections.synchronizedMap(mutableMapOf<String, () -> MockResponse>())
    private var uploadStatus = "created"
    private var trashedOnServer = false

    private fun asset(id: String, name: String) = """{"id":"$id","type":"VIDEO","originalFileName":"$name",
        "fileCreatedAt":"2024-05-01T10:00:00.000Z","fileModifiedAt":"2024-05-02T11:00:00.000Z","checksum":"c-$id","isTrashed":$trashedOnServer}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.path}"
                log += key
                bodies[key] = request.body.readUtf8()
                overrides[key]?.let { return it() }
                return when {
                    key == "GET /api/assets/$old" -> json(asset(old, "DJI_0042.MP4"))
                    key == "GET /api/assets/$orig" -> json(asset(orig, "DJI_0001.MOV"))
                    key == "GET /api/assets/$new" -> json(asset(new, "DJI_0042-edited.mp4"))
                    key.endsWith("/original") -> MockResponse().setBody("ORIGINAL-BYTES")
                    key == "POST /api/assets" -> json("""{"id":"$new","status":"$uploadStatus"}""", 201)
                    key == "PUT /api/assets/copy" -> MockResponse().setResponseCode(204)
                    key == "POST /api/stacks" -> json("""{"id":"s1","primaryAssetId":"$new"}""", 201)
                    key == "DELETE /api/assets" -> MockResponse().setResponseCode(204)
                    key == "POST /api/trash/restore/assets" -> json("""{"count":1}""")
                    key.endsWith("/metadata") -> json("[]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        api = ImmichApi(server.url("/").toString(), "k")
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private class FakeEngine(var fail: VideoExportException? = null) : VideoExportEngine {
        val plans = mutableListOf<ExportPlan>()
        var input: String? = null
        override suspend fun export(source: File, plan: ExportPlan, output: File, onProgress: (Float) -> Unit): ExportSummary {
            plans += plan
            input = source.readText()
            fail?.let { throw it }
            onProgress(0.5f)
            output.writeBytes(ByteArray(8_192) { (it * 7).toByte() })
            onProgress(1f)
            return ExportSummary(plan.outputWidth, plan.outputHeight, plan.outputDurationMs, output.length(), plan.videoMime)
        }
    }

    private val engine = FakeEngine()
    private val workDir by lazy { tmp.newFolder("job") }
    private val originals by lazy { VideoOriginals(tmp.newFolder("originals"), freeBytes = { Long.MAX_VALUE }) }
    private val stages = mutableListOf<VideoSaveStage>()

    private fun job() = VideoSaveJob(api, originals, engine, workDir,
        probe = { SourceInfo(1920, 1080, 10_000, "video/avc") }, caps = { EncoderCaps() })

    private suspend fun save(mode: SaveMode, group: Boolean = true, source: String = old, e: VideoEdit = edit) =
        job().run(VideoSaveJobSpec("j1", old, source, e.toJson(), mode, group)) { s, _ -> if (stages.lastOrNull() != s) stages += s }

    private fun JsonObject.bool(k: String) = this[k]!!.jsonPrimitive.content.toBooleanStrict()
    private fun body(key: String): JsonObject = Json.parseToJsonElement(bodies[key]!!).jsonObject

    @Test fun copyDownloadsExportsUploadsCopiesAlbumsStacksAndStoresTheEdit() = runTest {
        val r = save(SaveMode.COPY)
        assertEquals(
            listOf(
                "GET /api/assets/$old", "GET /api/assets/$old/original", "POST /api/assets",
                "PUT /api/assets/copy", "POST /api/stacks", "PUT /api/assets/$new/metadata",
            ),
            log,
        )
        assertEquals("ORIGINAL-BYTES", engine.input)
        // The exporter got the asset's date (the fake probe has none in the container).
        assertEquals(1714557600000L, engine.plans.single().creationTimeMs)
        val upload = bodies["POST /api/assets"]!!
        assertTrue(upload.contains("filename=\"DJI_0042-edited.mp4\""))
        assertTrue(upload.contains("Content-Type: video/mp4"))
        assertTrue(upload.contains("2024-05-01T10:00:00.000Z"))
        assertTrue(upload.contains("2024-05-02T11:00:00.000Z"))
        val copy = body("PUT /api/assets/copy")
        assertTrue(copy.bool("albums"))
        for (k in listOf("favorite", "sharedLinks", "stack", "sidecar")) assertFalse(k, copy.bool(k))
        assertEquals(listOf(new, old), body("POST /api/stacks")["assetIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        val item = body("PUT /api/assets/$new/metadata")["items"]!!.jsonArray.single().jsonObject
        assertEquals(VideoEditMetadata.KEY, item["key"]!!.jsonPrimitive.content)
        val (src, decoded) = VideoEditMetadata.decode(item["value"]!!.jsonObject)!!
        assertEquals(old, src)
        assertEquals(edit.normalized(), decoded)
        assertTrue(r.grouped)
        assertFalse(r.originalTrashed)
        assertEquals(
            listOf(VideoSaveStage.DOWNLOADING, VideoSaveStage.EXPORTING, VideoSaveStage.UPLOADING, VideoSaveStage.LINKING,
                VideoSaveStage.GROUPING, VideoSaveStage.RECIPE, VideoSaveStage.DONE),
            stages,
        )
    }

    @Test fun replaceCopiesEverythingThenTrashesAndUndoReverses() = runTest {
        val r = save(SaveMode.REPLACE)
        assertEquals(
            listOf(
                "GET /api/assets/$old", "GET /api/assets/$old/original", "POST /api/assets",
                "PUT /api/assets/copy", "DELETE /api/assets", "PUT /api/assets/$new/metadata",
            ),
            log,
        )
        val copy = body("PUT /api/assets/copy")
        for (k in listOf("albums", "favorite", "sharedLinks", "stack", "sidecar")) assertTrue(k, copy.bool(k))
        val del = body("DELETE /api/assets")
        assertEquals(listOf(old), del["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(del.bool("force"))
        assertTrue(r.originalTrashed)
        assertEquals(SaveMode.REPLACE, r.mode)

        log.clear()
        VideoSaveFlow(api).undoReplace(r)
        assertEquals(listOf("POST /api/trash/restore/assets", "DELETE /api/assets"), log)
        assertEquals(listOf(old), body("POST /api/trash/restore/assets")["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(new), body("DELETE /api/assets")["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun cancellingAfterTheUploadStillFinishesTheSave() = runTest {
        // Cancelled as the copy starts: stopping now would leave an orphan upload (or, a step
        // later, a half-done replace), so the remaining steps run anyway.
        var self: Job? = null
        val d = async(Dispatchers.IO) {
            job().run(VideoSaveJobSpec("j1", old, old, edit.toJson(), SaveMode.REPLACE, false)) { s, _ ->
                if (s == VideoSaveStage.LINKING) self?.cancel()
            }
        }
        self = d
        d.join()
        assertEquals(
            listOf(
                "GET /api/assets/$old", "GET /api/assets/$old/original", "POST /api/assets",
                "PUT /api/assets/copy", "DELETE /api/assets", "PUT /api/assets/$new/metadata",
            ),
            log,
        )
    }

    @Test fun cancellingTheExportUploadsNothing() = runTest {
        var self: Job? = null
        val d = async(Dispatchers.IO) {
            job().run(VideoSaveJobSpec("j1", old, old, edit.toJson(), SaveMode.REPLACE, false)) { s, _ ->
                if (s == VideoSaveStage.EXPORTING) self?.cancel()
            }
        }
        self = d
        d.join()
        assertTrue(d.isCancelled)
        assertFalse(log.contains("POST /api/assets"))
        assertFalse(log.contains("DELETE /api/assets"))
    }

    @Test fun spaceEstimateOfATrimCopiesTheSourceBitrate() {
        val source = SourceInfo(3840, 2160, 600_000, "video/hevc", bitrate = 50_000_000, frameRate = 30f)
        val trim = CompositionPlanner.plan(VideoEdit.initial(600_000).trimStart(1_000), source, EncoderCaps(hevc = true))
        assertTrue(trim.trimOptimization)
        assertTrue(trim.bitrate < 50_000_000)
        // 599 s at 50 Mbit/s ≈ 3.7 GB, not the plan's lower re-encode bitrate.
        assertTrue(VideoSaveJob.exportSpaceEstimate(trim, source) >= 50_000_000L / 8 * 599)
        val recolor = CompositionPlanner.plan(VideoEdit.initial(600_000).copy(color = Recipe(light = Light(exposure = 0.5f))), source, EncoderCaps(hevc = true))
        assertEquals(recolor.bitrate.toLong() * 600_000 / 8_000 * 11 / 10 + VideoSaveJob.SPACE_MARGIN, VideoSaveJob.exportSpaceEstimate(recolor, source))
    }

    @Test fun replaceNeverTrashesWhenTheCopyFails() = runTest {
        overrides["PUT /api/assets/copy"] = { json("""{"message":"nope"}""", 400) }
        val r = save(SaveMode.REPLACE)
        assertFalse(log.contains("DELETE /api/assets"))
        assertFalse(r.originalTrashed)
        assertEquals(SaveMode.COPY, r.mode)
        assertTrue(r.warning!!.contains("original was kept"))
    }

    @Test fun uploadFailureIsTransientAndTheRetrySkipsTheExport() = runTest {
        overrides["POST /api/assets"] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        try {
            save(SaveMode.REPLACE)
            fail("expected an upload failure")
        } catch (e: IOException) {
            assertTrue(VideoSaveJob.isTransient(e))
        }
        assertFalse(log.contains("DELETE /api/assets"))
        assertTrue(File(workDir, VideoSaveJob.EXPORT_DONE).isFile)

        // The retry: export kept, upload answers "duplicate" (the first one did land), and since this
        // job uploaded it, it isn't "preexisting" (undo may trash it).
        overrides.remove("POST /api/assets")
        uploadStatus = "duplicate"
        log.clear()
        val r = save(SaveMode.REPLACE)
        assertEquals(1, engine.plans.size)
        assertFalse(log.any { it.endsWith("/original") }) // nor re-downloaded
        assertTrue(r.duplicate)
        assertFalse(r.preexisting)
        assertTrue(r.originalTrashed)
    }

    @Test fun duplicateOfAnUnrelatedAssetIsPreexisting() = runTest {
        uploadStatus = "duplicate"
        val r = save(SaveMode.REPLACE)
        assertTrue(r.preexisting)
        log.clear()
        VideoSaveFlow(api).undoReplace(r)
        assertEquals(listOf("POST /api/trash/restore/assets"), log) // the edit predates us: keep it
    }

    @Test fun duplicateInTrashIsRestored() = runTest {
        uploadStatus = "duplicate"
        trashedOnServer = true
        val r = save(SaveMode.COPY, group = false)
        assertTrue(log.indexOf("POST /api/trash/restore/assets") in (log.indexOf("POST /api/assets") + 1) until log.indexOf("PUT /api/assets/copy"))
        assertFalse(r.preexisting)
    }

    @Test fun exportFailureIsPermanentAndNothingIsUploaded() = runTest {
        engine.fail = VideoExportException("This phone can't decode this video.")
        try {
            save(SaveMode.REPLACE)
            fail("expected an export failure")
        } catch (e: VideoExportException) {
            assertFalse(VideoSaveJob.isTransient(e))
        }
        assertFalse(log.contains("POST /api/assets"))
        assertFalse(log.contains("DELETE /api/assets"))
    }

    @Test fun serverErrorsAreRetriedButClientErrorsAreNot() = runTest {
        overrides["POST /api/assets"] = { json("""{"message":"busy"}""", 503) }
        try { save(SaveMode.COPY); fail() } catch (e: ImmichException) { assertTrue(VideoSaveJob.isTransient(e)) }
        overrides["POST /api/assets"] = { json("""{"message":"too big"}""", 413) }
        try { save(SaveMode.COPY); fail() } catch (e: ImmichException) { assertFalse(VideoSaveJob.isTransient(e)) }
    }

    @Test fun identityEditIsRefusedBeforeAnyRequest() = runTest {
        try { save(SaveMode.COPY, e = VideoEdit.initial(10_000)); fail() } catch (e: ImmichException) { assertTrue(e.message!!.contains("no changes")) }
        assertTrue(log.isEmpty())
    }

    @Test fun reEditFromTheOriginalDownloadsTheSource() = runTest {
        save(SaveMode.COPY, group = false, source = orig)
        assertEquals(listOf("GET /api/assets/$old", "GET /api/assets/$orig", "GET /api/assets/$orig/original"), log.take(3))
        val item = body("PUT /api/assets/$new/metadata")["items"]!!.jsonArray.single().jsonObject
        assertEquals(orig, VideoEditMetadata.decode(item["value"]!!.jsonObject)!!.first)
        // Named after the asset being replaced/copied, not the source.
        assertTrue(bodies["POST /api/assets"]!!.contains("DJI_0042-edited.mp4"))
    }

    @Test fun filenamesAndOverallProgress() {
        assertEquals("IMG_1-edited.mp4", VideoSaveFlow.editedFilename("IMG_1.MOV"))
        assertEquals("IMG_1-edited.mp4", VideoSaveFlow.editedFilename("IMG_1-edited.mp4"))
        var last = -1f
        for (s in VideoSaveStage.entries.filter { it != VideoSaveStage.WAITING_FOR_NETWORK }) {
            for (f in listOf(0f, 1f)) {
                val o = VideoSaveStage.overall(s, f)
                assertTrue("$s $f", o >= last)
                last = o
            }
        }
        assertEquals(1f, last)
    }
}

package com.roylet.photos.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.roylet.photos.editor.recipe.ColorAdjust
import com.roylet.photos.editor.recipe.CropRect
import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.recipe.Presets
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.ui.AspectPreset
import com.roylet.photos.ui.theme.PhotosTheme
import com.roylet.photos.ui.viewer.VideoFrameStrip
import com.roylet.photos.video.export.SourceInfo
import com.roylet.photos.video.io.FrameStripProvider
import com.roylet.photos.video.model.AudioSettings
import com.roylet.photos.video.model.OutputQuality
import com.roylet.photos.video.model.OutputResolution
import com.roylet.photos.video.model.OutputSettings
import com.roylet.photos.video.model.TimeRange
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.model.VideoGeometry
import com.roylet.photos.video.save.VideoSaveProgress
import com.roylet.photos.video.save.VideoSaveStage
import com.roylet.photos.video.preview.PreviewNotice
import com.roylet.photos.video.ui.AdjustPage
import com.roylet.photos.video.ui.TrimEdge
import com.roylet.photos.video.ui.TrimHandle
import com.roylet.photos.video.ui.StillPlayback
import com.roylet.photos.video.ui.VideoEditorLoading
import com.roylet.photos.video.ui.VideoEditorPhase
import com.roylet.photos.video.ui.VideoEditorSession
import com.roylet.photos.video.ui.VideoEditorWorkspace
import com.roylet.photos.video.ui.VideoReEditChoice
import com.roylet.photos.video.ui.VideoTool
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/**
 * Video editor screenshots with synthetic frames and a still player (no ExoPlayer/GL under
 * Robolectric): ./gradlew testDebugUnitTest -Pscreenshots --tests '*VideoEditorScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class VideoEditorScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")

    @Before fun only() = assumeTrue(System.getProperty("screenshots") == "true")

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { PhotosTheme(dark = true) { content() } }
        }
        compose.mainClock.advanceTimeBy(1500)
        val view = compose.activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val durationMs = 24_000L
    private val source = SourceInfo(1920, 1080, durationMs, videoMime = "video/avc", bitrate = 18_000_000, frameRate = 30f)

    private fun session(edit: VideoEdit = VideoEdit.initial(durationMs), selected: Int? = null, thumbs: Boolean = false): VideoEditorSession {
        val strip = VideoFrameStrip(FrameStripProvider.sampleTimes(durationMs, VideoEditorSession.STRIP_FRAMES))
        for (i in 0 until strip.count) strip.frames[i] = frame(strip.timesMs[i], 160, 90).asImageBitmap()
        val s = VideoEditorSession(null, source, initial = VideoEdit.initial(durationMs), strip = strip, poster = frame(6_000, 640, 360))
        s.history.set(edit)
        s.selected = selected
        if (thumbs) s.renderFilterThumbs()
        return s
    }

    private val trimmed = VideoEdit.initial(durationMs).copy(segments = listOf(TimeRange(1_400, 9_200), TimeRange(13_600, 21_800)))
    private val graded = trimmed.copy(color = Recipe(light = Light(exposure = 0.2f, contrast = 16f, shadows = 24f), color = ColorAdjust(temperature = 14f, vibrance = 18f)))

    private fun workspace(
        s: VideoEditorSession,
        tool: VideoTool,
        position: Long = 5_200,
        sheet: Boolean = false,
        progress: VideoSaveProgress? = null,
        page: AdjustPage = AdjustPage.LIGHT,
        notice: PreviewNotice? = null,
    ): @Composable () -> Unit = {
        VideoEditorWorkspace(s, StillPlayback(position, notice = notice), null, {}, { _, _ -> }, progress, initialTool = tool, initialSheet = sheet, initialAdjustPage = page)
    }

    @Test fun trim() = shoot("video-01-trim", workspace(session(graded, selected = 1), VideoTool.TRIM, position = 9_000))

    /** Dragging clip 2's OUT handle: the preview shows that frame with a time bubble. */
    @Test fun trimScrubHandle() {
        val s = session(graded, selected = 1)
        s.scrubHandle = TrimHandle(1, TrimEdge.END)
        s.scrubMs = 21_800
        shoot("video-01b-trim-scrub-handle", workspace(s, VideoTool.TRIM, position = 15_000))
    }

    /** Dragging the playhead, on a phone whose effect pipeline failed (preview without filters). */
    @Test fun trimScrubPlayhead() {
        val s = session(graded)
        s.scrubPlayhead = true
        shoot("video-01c-trim-scrub-playhead", workspace(s, VideoTool.TRIM, position = 11_300, notice = PreviewNotice.NoFilters))
    }

    @Test fun previewFailed() = shoot(
        "video-01d-preview-failed",
        workspace(session(graded), VideoTool.TRIM, notice = PreviewNotice.Failed("This phone couldn't decode the video for the preview.")),
    )

    @Test fun crop() {
        val s = session(graded.copy(geometry = VideoGeometry(crop = CropRect(0.341797f, 0f, 0.658203f, 1f))))
        s.cropAspect = AspectPreset.P9_16
        shoot("video-02-crop", workspace(s, VideoTool.CROP))
    }

    @Test fun speed() = shoot("video-03-speed", workspace(session(graded.withSpeed(1.5f)), VideoTool.SPEED))

    @Test fun audio() = shoot("video-04-audio", workspace(session(graded.copy(audio = AudioSettings(volume = 140f))), VideoTool.AUDIO))

    @Test fun adjust() = shoot("video-05-adjust", workspace(session(graded), VideoTool.ADJUST))

    @Test fun adjustEffects() = shoot("video-05b-adjust-effects", workspace(session(graded), VideoTool.ADJUST, page = AdjustPage.EFFECTS))

    @Test fun filters() = shoot("video-06-filters", workspace(session(trimmed.copy(color = Recipe(filter = Presets.Film.filter(70f))), thumbs = true), VideoTool.FILTERS))

    @Test fun export() = shoot(
        "video-07-export",
        workspace(session(graded.copy(output = OutputSettings(OutputResolution.P720, OutputQuality.STANDARD))), VideoTool.EXPORT),
    )

    @Test fun saveSheet() = shoot("video-08-save-sheet", workspace(session(graded), VideoTool.TRIM, sheet = true))

    @Test fun saving() = shoot(
        "video-09-saving",
        workspace(session(graded), VideoTool.TRIM, progress = VideoSaveProgress.Running(VideoSaveStage.EXPORTING, 0.46f)),
    )

    @Test fun loading() = shoot("video-10-loading") {
        val strip = VideoFrameStrip(FrameStripProvider.sampleTimes(durationMs, VideoEditorSession.STRIP_FRAMES))
        for (i in 0 until strip.count step 3) strip.frames[i] = frame(strip.timesMs[i], 160, 90).asImageBitmap()
        VideoEditorLoading(VideoEditorPhase.Loading("Downloading original", "PXL_20261012_183301.mp4 · 84 MB of 212 MB", 0.4f, strip), {})
    }

    @Test fun reEdit() = shoot("video-11-re-edit") { VideoReEditChoice({}, {}, {}) }

    @Test @Config(qualifiers = "w960dp-h600dp-xhdpi")
    fun tablet() = shoot("video-12-tablet", workspace(session(graded, selected = 0), VideoTool.TRIM, position = 3_000))

    /** A synthetic "video" frame at [t]: a sunset whose sun and boat move over time. */
    private fun frame(t: Long, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val k = t / durationMs.toFloat()
        val sky = intArrayOf(0xFF22305E.toInt(), blend(0xFFE07A5F.toInt(), 0xFF7FB2E5.toInt(), k), 0xFFF2CC8F.toInt())
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.62f, sky, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0xFFFFF1C9.toInt()
        c.drawCircle(w * (0.2f + 0.6f * k), h * (0.5f - 0.25f * sin(k * 3.14f)), h * 0.11f, p)
        p.color = 0xFF3D405B.toInt()
        val hills = Path().apply {
            moveTo(0f, h * 0.62f)
            for (x in 0..w step 8) lineTo(x.toFloat(), h * (0.55f + 0.05f * sin(x / (w / 6f) + 1f)))
            lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close()
        }
        c.drawPath(hills, p)
        p.shader = LinearGradient(0f, h * 0.68f, 0f, h.toFloat(), 0xFF2E6F8E.toInt(), 0xFF173B57.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.68f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0xFF1B1B1B.toInt()
        val bx = w * (0.85f - 0.7f * k)
        c.drawRect(bx - w * 0.05f, h * 0.76f, bx + w * 0.05f, h * 0.8f, p)
        return b
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}

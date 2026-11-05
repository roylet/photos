package com.roylet.photos.editor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.roylet.photos.editor.recipe.ColorAdjust
import com.roylet.photos.editor.recipe.CropRect
import com.roylet.photos.editor.recipe.CurvePoint
import com.roylet.photos.editor.recipe.Curves
import com.roylet.photos.editor.recipe.Effects
import com.roylet.photos.editor.recipe.Geometry
import com.roylet.photos.editor.recipe.HslAdjust
import com.roylet.photos.editor.recipe.HslBand
import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.recipe.Presets
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.save.SaveProgress
import com.roylet.photos.editor.save.SaveStage
import com.roylet.photos.editor.ui.AspectPreset
import com.roylet.photos.editor.ui.EditorPhase
import com.roylet.photos.editor.ui.EditorSession
import com.roylet.photos.editor.ui.EditorTool
import com.roylet.photos.editor.ui.EditorWorkspace
import com.roylet.photos.editor.ui.EditorLoading
import com.roylet.photos.editor.ui.ReEditChoice
import com.roylet.photos.immich.EditAssetInfo
import com.roylet.photos.ui.theme.PhotosTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Editor screenshots with a synthetic photo. GL doesn't run under Robolectric, so the canvas is
 * the CPU [com.roylet.photos.editor.ui.StaticPreview] (the reference renderer: same maths).
 *   ./gradlew testDebugUnitTest -Pscreenshots --tests '*EditorScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class EditorScreenshotTest {
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

    private fun session(recipe: Recipe = Recipe()) = EditorSession(fakePhoto(), 4032, 3024, Recipe(), renderer = null).also {
        it.history.set(recipe)
    }

    private val edited = Recipe(
        light = Light(exposure = 0.35f, contrast = 18f, highlights = -42f, shadows = 30f),
        color = ColorAdjust(temperature = 12f, vibrance = 20f),
    )

    @Test fun light() = shoot("editor-01-light") {
        EditorWorkspace(session(edited), {}, { _, _ -> }, null, initialTool = EditorTool.LIGHT)
    }

    /** Hold-to-compare halfway through its crossfade: the cropped edit fading into the full original. */
    @Test fun compareFade() {
        val s = session(edited.copy(geometry = Geometry(crop = CropRect(0.15f, 0.1f, 0.85f, 0.9f))))
        shoot("editor-12-compare-fade") {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().background(com.roylet.photos.ui.theme.BrutalDark.Canvas),
            ) { com.roylet.photos.editor.ui.StaticPreview(s, compareMix = 0.5f) }
        }
    }

    @Test fun crop() {
        val s = session(edited.copy(geometry = Geometry(crop = CropRect(0.08f, 0.1f, 0.86f, 0.88f), straighten = 3.5f)))
        s.cropAspect = AspectPreset.FREE
        shoot("editor-02-crop") { EditorWorkspace(s, {}, { _, _ -> }, null, initialTool = EditorTool.CROP) }
    }

    @Test fun curve() {
        val curves = Curves(
            rgb = listOf(CurvePoint(0f, 0.06f), CurvePoint(0.28f, 0.22f), CurvePoint(0.72f, 0.82f), CurvePoint(1f, 0.97f)),
            blue = listOf(CurvePoint(0f, 0.08f), CurvePoint(1f, 0.92f)),
        )
        shoot("editor-03-curve") { EditorWorkspace(session(edited.copy(curves = curves)), {}, { _, _ -> }, null, initialTool = EditorTool.CURVE) }
    }

    @Test fun hsl() {
        val color = ColorAdjust().withHsl(HslBand.RED, HslAdjust(hue = 10f, saturation = 25f, luminance = -8f))
            .withHsl(HslBand.BLUE, HslAdjust(saturation = 30f))
        shoot("editor-04-hsl") { EditorWorkspace(session(edited.copy(color = color)), {}, { _, _ -> }, null, initialTool = EditorTool.HSL) }
    }

    @Test fun filters() {
        val s = session(Recipe(filter = Presets.Film.filter(70f)))
        s.renderFilterThumbs()
        shoot("editor-05-filters") { EditorWorkspace(s, {}, { _, _ -> }, null, initialTool = EditorTool.FILTERS) }
    }

    @Test fun effects() = shoot("editor-06-effects") {
        EditorWorkspace(session(edited.copy(effects = Effects(clarity = 25f, vignette = -30f, grain = 12f))), {}, { _, _ -> }, null, initialTool = EditorTool.EFFECTS)
    }

    /** A photo on the phone and in Immich: "Save to: This device · Immich · Both" (the editor is always dark). */
    @Test fun saveSheetDestinations() = shoot("editor-07b-save-sheet-destination") {
        val all = listOf(com.roylet.photos.editor.save.SaveDestination.BOTH, com.roylet.photos.editor.save.SaveDestination.DEVICE, com.roylet.photos.editor.save.SaveDestination.IMMICH)
        EditorWorkspace(session(edited), {}, { _, _ -> }, null, initialSheet = true, destinations = all, destination = all.first())
    }

    /** A photo only on the phone: no destination row, no grouping (stacks are Immich's). */
    @Test fun saveSheetPhone() = shoot("editor-07c-save-sheet-phone") {
        EditorWorkspace(session(edited), {}, { _, _ -> }, null, initialSheet = true, destinations = listOf(com.roylet.photos.editor.save.SaveDestination.DEVICE))
    }

    @Test fun saveSheet() = shoot("editor-07-save-sheet") {
        EditorWorkspace(session(edited), {}, { _, _ -> }, null, initialSheet = true)
    }

    @Test fun saving() = shoot("editor-08-saving") {
        EditorWorkspace(session(edited), {}, { _, _ -> }, SaveProgress.Running(SaveStage.UPLOADING, 0.45f))
    }

    @Test fun loading() = shoot("editor-09-loading") {
        EditorLoading(EditorPhase.Loading("Downloading original", "IMG_4821.HEIC · 3.1 MB of 7.4 MB", 0.42f), {})
    }

    @Test fun reEdit() = shoot("editor-10-re-edit") {
        ReEditChoice(
            EditorPhase.Choose(EditAssetInfo("a", fileCreatedAt = "", fileModifiedAt = ""), "b", Recipe()),
            {}, {}, {},
        )
    }

    @Test @Config(qualifiers = "w960dp-h600dp-xhdpi")
    fun tablet() = shoot("editor-11-tablet") {
        EditorWorkspace(session(edited), {}, { _, _ -> }, null, initialTool = EditorTool.LIGHT)
    }

    /** A sunset-ish landscape: sky gradient, sun, layered hills, a lake. */
    private fun fakePhoto(): Bitmap {
        val w = 800; val h = 600
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.62f, intArrayOf(0xFF2B3A67.toInt(), 0xFFE07A5F.toInt(), 0xFFF2CC8F.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.68f, h * 0.48f, 120f, intArrayOf(0xFFFFF4D6.toInt(), 0x00FFE0A0), null, Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.68f, h * 0.48f, 120f, p)
        p.shader = null
        p.color = 0xFFFFF1C9.toInt(); c.drawCircle(w * 0.68f, h * 0.48f, 34f, p)
        fun hill(y: Float, amp: Float, color: Int, phase: Float) {
            val path = Path().apply {
                moveTo(0f, h.toFloat())
                var x = 0f
                while (x <= w) { lineTo(x, y - amp * kotlin.math.sin(x / w * 6.28f * 1.3f + phase).toFloat()); x += 8f }
                lineTo(w.toFloat(), h.toFloat()); close()
            }
            p.color = color; c.drawPath(path, p)
        }
        hill(h * 0.58f, 34f, 0xFF7A4E6B.toInt(), 0.4f)
        hill(h * 0.66f, 26f, 0xFF4A3B5C.toInt(), 2.1f)
        p.shader = LinearGradient(0f, h * 0.72f, 0f, h.toFloat(), intArrayOf(0xFFE9A17A.toInt(), 0xFF22304F.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.74f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        hill(h * 0.86f, 18f, 0xFF1D2B2A.toInt(), 4.0f)
        return b
    }
}

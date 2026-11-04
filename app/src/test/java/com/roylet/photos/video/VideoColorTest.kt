package com.roylet.photos.video

import android.net.Uri
import androidx.media3.common.audio.GainProcessor
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import com.roylet.photos.editor.gl.EditShaders
import com.roylet.photos.editor.recipe.ColorAdjust
import com.roylet.photos.editor.recipe.CurvePoint
import com.roylet.photos.editor.recipe.Curves
import com.roylet.photos.editor.recipe.Effects
import com.roylet.photos.editor.recipe.Filter
import com.roylet.photos.editor.recipe.HslAdjust
import com.roylet.photos.editor.recipe.HslBand
import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.recipe.RenderParams
import com.roylet.photos.video.export.ColorParamsHolder
import com.roylet.photos.video.export.ColorUniforms
import com.roylet.photos.video.export.CompositionPlanner
import com.roylet.photos.video.export.ConstantSpeed
import com.roylet.photos.video.export.EncoderCaps
import com.roylet.photos.video.export.HdrHandling
import com.roylet.photos.video.export.MIME_HEVC
import com.roylet.photos.video.export.Media3Compositions
import com.roylet.photos.video.export.SourceInfo
import com.roylet.photos.video.export.VideoColorEffect
import com.roylet.photos.video.export.VideoColorShaders
import com.roylet.photos.video.model.AudioSettings
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.model.VideoGeometry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoColorTest {
    private val recipe = Recipe(
        light = Light(exposure = 0.5f, contrast = 20f, highlights = -30f, shadows = 40f, whites = 10f, blacks = -10f),
        color = ColorAdjust(temperature = 15f, tint = -5f, vibrance = 25f, saturation = -10f)
            .withHsl(HslBand.BLUE, HslAdjust(hue = 10f, saturation = 20f, luminance = -30f)),
        curves = Curves(rgb = listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.55f), CurvePoint(1f, 1f))),
        effects = Effects(clarity = 40f, dehaze = 30f, vignette = -20f, grain = 15f, sharpen = 50f),
        filter = Filter("vivid", 70f),
    )

    @Test fun setsExactlyThePhotoPipelinesColourUniforms() {
        val u = ColorUniforms.from(RenderParams.from(recipe), 1920, 1080)
        assertEquals(EditShaders.COLOR_UNIFORMS.toSet(), u.names - VideoColorShaders.U_INPUT)
        assertTrue(VideoColorShaders.FRAGMENT.contains(EditShaders.COLOR_PIPELINE))
        for (name in EditShaders.COLOR_UNIFORMS + VideoColorShaders.U_INPUT + VideoColorShaders.U_BYPASS) {
            assertTrue(name, VideoColorShaders.FRAGMENT.contains(name))
        }
        assertTrue(VideoColorShaders.FRAGMENT.startsWith("#version 300 es"))
    }

    @Test fun valuesMatchRenderParamsLikeThePhotoRenderer() {
        val p = RenderParams.from(recipe)
        val u = ColorUniforms.from(p, 1920, 1080)
        assertEquals(p.exposure, u.floats[EditShaders.U_EXPOSURE])
        assertEquals(p.contrast, u.floats[EditShaders.U_CONTRAST])
        assertEquals(p.highlights, u.floats[EditShaders.U_HIGHLIGHTS])
        assertEquals(p.shadows, u.floats[EditShaders.U_SHADOWS])
        assertEquals(p.whites, u.floats[EditShaders.U_WHITES])
        assertEquals(p.blacks, u.floats[EditShaders.U_BLACKS])
        assertEquals(p.temperature, u.floats[EditShaders.U_TEMPERATURE])
        assertEquals(p.tint, u.floats[EditShaders.U_TINT])
        assertEquals(p.vibrance, u.floats[EditShaders.U_VIBRANCE])
        assertEquals(p.saturation, u.floats[EditShaders.U_SATURATION])
        assertEquals(p.vignette, u.floats[EditShaders.U_VIGNETTE])
        assertEquals(p.grain, u.floats[EditShaders.U_GRAIN])
        assertEquals(0.7f, u.floats[EditShaders.U_LUT_AMOUNT]!!, 1e-6f)
        assertEquals(33f, u.floats[EditShaders.U_LUT_SIZE])
        assertEquals(1, u.ints[EditShaders.U_HSL_ON])
        assertEquals(1, u.ints[EditShaders.U_CURVE_ON])
        assertArrayEquals(p.hsl, u.hsl, 0f)
        assertSame(p.curveLut, u.curveLut)
        assertSame(p.lut, u.lut)
        // Local contrast is off for video.
        assertEquals(0f, u.floats[EditShaders.U_CLARITY])
        assertEquals(0f, u.floats[EditShaders.U_DEHAZE])
        assertEquals(0f, u.floats[EditShaders.U_SHARPEN])
        // Grain lattice follows the frame aspect, as on photos.
        val g = RenderParams.grainScale(1920f / 1080f)
        assertArrayEquals(floatArrayOf(g.first, g.second), u.vec2s[EditShaders.U_GRAIN_SCALE], 0f)
        assertEquals(setOf(0, 1, 2), u.samplers.values.toSet())
    }

    @Test fun identityRecipeDisablesOptionalStages() {
        val u = ColorUniforms.from(RenderParams.from(Recipe()), 100, 100)
        assertEquals(0, u.ints[EditShaders.U_HSL_ON])
        assertEquals(0, u.ints[EditShaders.U_CURVE_ON])
        assertEquals(0f, u.floats[EditShaders.U_LUT_AMOUNT])
        assertNull(u.lut)
    }

    @Test fun holderTracksRecipe() {
        val h = ColorParamsHolder()
        assertTrue(h.isIdentity)
        h.set(recipe)
        assertFalse(h.isIdentity)
        assertEquals(0.5f, h.params.exposure)
        h.set(Recipe(effects = Effects(clarity = 10f)))
        assertTrue(h.isIdentity)
    }

    @Test fun media3CompositionFromPlan() {
        val src = SourceInfo(3840, 2160, 10_000, MIME_HEVC, colorTransfer = SourceInfo.TRANSFER_HLG)
        val edit = VideoEdit.initial(10_000).splitAt(4_000).withSpeed(2f)
            .copy(geometry = VideoGeometry(rotate90 = 1), audio = AudioSettings(volume = 50f), color = Recipe(light = Light(exposure = 1f)))
        val plan = CompositionPlanner.plan(edit, src, EncoderCaps(hevc = true, hevcHdr = true))
        assertEquals(HdrHandling.TONE_MAP, plan.hdr)
        val c = Media3Compositions.composition(plan, Uri.parse("file:///x.mp4"))
        assertEquals(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL, c.hdrMode)
        val items = c.sequences.single().editedMediaItems
        assertEquals(2, items.size)
        assertEquals(0L, items[0].mediaItem.clippingConfiguration.startPositionMs)
        assertEquals(4_000L, items[0].mediaItem.clippingConfiguration.endPositionMs)
        assertEquals(4_000L, items[1].mediaItem.clippingConfiguration.startPositionMs)
        assertEquals(10_000L, items[1].mediaItem.clippingConfiguration.endPositionMs)
        val fx = items[0].effects.videoEffects
        assertTrue(fx[0] is ScaleAndRotateTransformation)
        assertFalse(fx.any { it is Presentation }) // a quarter turn needs no resize
        assertTrue(fx.last() is VideoColorEffect)
        assertTrue(items[0].effects.audioProcessors.single() is GainProcessor)
        // Separate processor instances per item (they are stateful).
        assertFalse(items[0].effects.audioProcessors[0] === items[1].effects.audioProcessors[0])
        assertEquals(2f, (items[0].speedProvider as ConstantSpeed).speed)
        assertNotNull(Media3Compositions.videoEffects(listOf(com.roylet.photos.video.export.VideoEffectSpec.Crop(-1f, 1f, -1f, 1f))).single() as Crop)
    }

    @Test fun muteRemovesAudio() {
        val plan = CompositionPlanner.plan(VideoEdit.initial(5_000).copy(audio = AudioSettings(mute = true)), SourceInfo(1280, 720, 5_000), EncoderCaps())
        val item = Media3Compositions.composition(plan, Uri.parse("file:///x.mp4")).sequences.single().editedMediaItems.single()
        assertTrue(item.removeAudio)
        assertTrue(item.effects.audioProcessors.isEmpty())
    }
}

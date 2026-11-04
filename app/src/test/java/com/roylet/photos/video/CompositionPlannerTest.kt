package com.roylet.photos.video

import androidx.media3.transformer.ExportException
import com.roylet.photos.editor.recipe.CropRect
import com.roylet.photos.editor.recipe.Effects
import com.roylet.photos.editor.recipe.Filter
import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.video.export.ClipSpec
import com.roylet.photos.video.export.CompositionPlanner
import com.roylet.photos.video.export.EncoderCaps
import com.roylet.photos.video.export.HdrHandling
import com.roylet.photos.video.export.MIME_H264
import com.roylet.photos.video.export.MIME_HEVC
import com.roylet.photos.video.export.SourceInfo
import com.roylet.photos.video.export.SourceProbe
import com.roylet.photos.video.export.VideoEffectSpec
import com.roylet.photos.video.export.describeExportError
import com.roylet.photos.video.export.isVideoColorIdentity
import com.roylet.photos.video.model.AudioSettings
import com.roylet.photos.video.model.OutputCodec
import com.roylet.photos.video.model.OutputQuality
import com.roylet.photos.video.model.OutputResolution
import com.roylet.photos.video.model.OutputSettings
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.model.VideoGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionPlannerTest {
    private val phone = SourceInfo(1920, 1080, 10_000, MIME_H264, frameRate = 30f, bitrate = 17_000_000)
    private val drone4k = SourceInfo(3840, 2160, 600_000, MIME_HEVC, colorTransfer = SourceInfo.TRANSFER_HLG, frameRate = 30f, bitrate = 100_000_000)
    private val caps = EncoderCaps(hevc = true, hevcHdr = true)
    private val edit = VideoEdit.initial(10_000)

    @Test fun clipsFollowSegments() {
        val e = edit.splitAt(3_000).splitAt(6_000).deleteSegment(1)
        val plan = CompositionPlanner.plan(e, phone, caps)
        assertEquals(listOf(ClipSpec(0, 3_000), ClipSpec(6_000, 10_000)), plan.clips)
        assertEquals(7_000, plan.outputDurationMs)
        assertTrue(plan.videoEffects.isEmpty())
        assertFalse(plan.trimOptimization) // two clips
    }

    @Test fun plainTrimUsesTrimOptimization() {
        val plan = CompositionPlanner.plan(edit.trimStart(2_000), phone, caps)
        assertTrue(plan.trimOptimization)
        assertEquals(MIME_H264, plan.videoMime)
        assertEquals(1920, plan.outputWidth)
    }

    @Test fun geometryEffectsInPhotoOrder() {
        val e = edit.copy(geometry = VideoGeometry(crop = CropRect(0.25f, 0f, 0.75f, 0.5f), rotate90 = 1, flipH = true))
        val plan = CompositionPlanner.plan(e, phone, caps)
        val fx = plan.videoEffects
        assertEquals(VideoEffectSpec.Rotate(270f), fx[0]) // 90° clockwise = 270° counter-clockwise
        assertEquals(VideoEffectSpec.Flip(true, false), fx[1])
        assertEquals(VideoEffectSpec.Crop(-0.5f, 0.5f, 0f, 1f), fx[2])
        // Rotated frame is 1080x1920; crop keeps half of each side.
        assertEquals(VideoEffectSpec.Resize(540, 960, downscale = false), fx[3])
        assertEquals(540 to 960, plan.outputWidth to plan.outputHeight)
        assertFalse(plan.trimOptimization)
    }

    @Test fun rotationAloneNeedsNoResize() {
        val plan = CompositionPlanner.plan(edit.copy(geometry = VideoGeometry(rotate90 = 2)), phone, caps)
        assertEquals(listOf<VideoEffectSpec>(VideoEffectSpec.Rotate(180f)), plan.videoEffects)
    }

    @Test fun resolutionNeverUpscalesAndKeepsEvenSides() {
        val p720 = CompositionPlanner.plan(edit.copy(output = OutputSettings(OutputResolution.P720)), drone4k, caps)
        assertEquals(1280 to 720, p720.outputWidth to p720.outputHeight)
        assertEquals(VideoEffectSpec.Resize(1280, 720, downscale = true), p720.videoEffects.single())
        val small = SourceInfo(640, 360, 10_000, MIME_H264)
        val up = CompositionPlanner.plan(edit.copy(output = OutputSettings(OutputResolution.P1080)), small, caps)
        assertEquals(640 to 360, up.outputWidth to up.outputHeight)
        assertTrue(up.videoEffects.isEmpty())
        val odd = CompositionPlanner.plan(edit.copy(geometry = VideoGeometry(crop = CropRect(0f, 0f, 0.3331f, 1f))), phone, caps)
        assertEquals(0, odd.outputWidth % 2)
        assertEquals(0, odd.outputHeight % 2)
    }

    @Test fun colourEffectOnlyForWhatVideoRenders() {
        val withLight = CompositionPlanner.plan(edit.copy(color = Recipe(light = Light(exposure = 1f))), phone, caps)
        assertTrue(withLight.videoEffects.last() is VideoEffectSpec.Color)
        // Clarity/dehaze/sharpen are skipped for video in v1, so they don't trigger the pass.
        val localOnly = Recipe(effects = Effects(clarity = 50f, dehaze = 20f, sharpen = 40f))
        assertTrue(isVideoColorIdentity(localOnly))
        assertTrue(CompositionPlanner.plan(edit.copy(color = localOnly), phone, caps).videoEffects.isEmpty())
        assertFalse(isVideoColorIdentity(Recipe(filter = Filter("mono"))))
        assertFalse(isVideoColorIdentity(Recipe(effects = Effects(vignette = -30f))))
    }

    @Test fun speedAndAudio() {
        val plan = CompositionPlanner.plan(edit.withSpeed(2f).copy(audio = AudioSettings(volume = 150f)), phone, caps)
        assertEquals(2f, plan.speed)
        assertEquals(5_000, plan.outputDurationMs)
        assertEquals(1.5f, plan.audioGain)
        assertFalse(plan.removeAudio)
        val muted = CompositionPlanner.plan(edit.copy(audio = AudioSettings(mute = true, volume = 150f)), phone, caps)
        assertTrue(muted.removeAudio)
        assertNull(muted.audioGain)
        assertFalse(muted.trimOptimization)
        val silent = CompositionPlanner.plan(edit.trimStart(100), phone.copy(hasAudio = false), caps)
        assertTrue(silent.removeAudio)
    }

    @Test fun codecChoice() {
        assertEquals(MIME_H264, CompositionPlanner.plan(edit, phone, caps).videoMime) // AUTO, H.264 source
        assertEquals(MIME_HEVC, CompositionPlanner.plan(edit, drone4k, caps).videoMime) // AUTO, HEVC source
        assertEquals(MIME_H264, CompositionPlanner.plan(edit, drone4k, EncoderCaps()).videoMime) // no HEVC encoder
        val forced = edit.copy(output = OutputSettings(codec = OutputCodec.H264))
        assertEquals(MIME_H264, CompositionPlanner.plan(forced, drone4k, caps).videoMime)
        val hevc = edit.copy(output = OutputSettings(codec = OutputCodec.HEVC))
        assertEquals(MIME_HEVC, CompositionPlanner.plan(hevc, phone, caps).videoMime)
    }

    @Test fun hdrKeptOnlyWithoutColourEditsAndWithEncoderSupport() {
        assertEquals(HdrHandling.NONE, CompositionPlanner.plan(edit, phone, caps).hdr)
        assertEquals(HdrHandling.KEEP, CompositionPlanner.plan(edit.trimStart(1_000), drone4k, caps).hdr)
        val graded = edit.copy(color = Recipe(filter = Filter("vivid")))
        assertEquals(HdrHandling.TONE_MAP, CompositionPlanner.plan(graded, drone4k, caps).hdr)
        assertEquals(HdrHandling.TONE_MAP, CompositionPlanner.plan(edit, drone4k, EncoderCaps(hevc = true)).hdr)
        val h264 = edit.copy(output = OutputSettings(codec = OutputCodec.H264))
        assertEquals(HdrHandling.TONE_MAP, CompositionPlanner.plan(h264, drone4k, caps).hdr)
    }

    @Test fun fallbackIsH264Sdr() {
        val plan = CompositionPlanner.plan(edit.trimStart(1_000), drone4k, caps)
        val fb = plan.fallback()!!
        assertEquals(MIME_H264, fb.videoMime)
        assertEquals(HdrHandling.TONE_MAP, fb.hdr)
        assertFalse(fb.trimOptimization)
        assertTrue(fb.bitrate > plan.bitrate)
        assertNull(CompositionPlanner.plan(edit.copy(color = Recipe(light = Light(exposure = 1f))), phone, caps).fallback())
    }

    @Test fun bitrateScalesWithQualityAndIsCappedBySource() {
        val high = CompositionPlanner.bitrate(1920, 1080, phone.copy(bitrate = null), OutputQuality.HIGH, hevc = false)
        assertEquals((1920 * 1080 * 30 * 0.2).toInt(), high)
        val small = CompositionPlanner.bitrate(1920, 1080, phone.copy(bitrate = null), OutputQuality.SMALL, hevc = false)
        assertTrue(small < high)
        val hevc = CompositionPlanner.bitrate(1920, 1080, phone.copy(bitrate = null), OutputQuality.HIGH, hevc = true)
        assertTrue(hevc < high)
        // A 4 Mbps source isn't blown up to 12 Mbps.
        assertEquals((4_000_000 * 1.2).toInt(), CompositionPlanner.bitrate(1920, 1080, phone.copy(bitrate = 4_000_000), OutputQuality.HIGH, false))
        // 240 fps slow motion is budgeted as 60.
        val slowMo = CompositionPlanner.bitrate(1920, 1080, phone.copy(frameRate = 240f, bitrate = null), OutputQuality.HIGH, false)
        assertEquals((1920 * 1080 * 60 * 0.2).toInt(), slowMo)
        assertEquals(CompositionPlanner.MIN_BITRATE, CompositionPlanner.bitrate(16, 16, phone, OutputQuality.SMALL, false))
    }

    @Test fun creationTimeFallsBackToTheAsset() {
        assertEquals(42L, CompositionPlanner.plan(edit, phone, caps, fallbackCreationTimeMs = 42L).creationTimeMs)
        assertEquals(7L, CompositionPlanner.plan(edit, phone.copy(creationTimeMs = 7L), caps, 42L).creationTimeMs)
    }

    @Test fun exportErrorsAreFriendly() {
        val (msg, fallback) = describeExportError(ExportException.ERROR_CODE_ENCODER_INIT_FAILED)
        assertTrue(msg.contains("encoder"))
        assertTrue(fallback)
        assertFalse(describeExportError(ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED).second)
        assertTrue(describeExportError(ExportException.ERROR_CODE_MUXING_FAILED).first.contains("storage"))
        assertTrue(describeExportError(-12345).first.isNotBlank())
    }

    @Test fun probeParsers() {
        assertEquals(37.4219f to -122.084f, SourceProbe.parseIso6709("+37.4219-122.0840/"))
        assertEquals(-33.8688f to 151.2093f, SourceProbe.parseIso6709("-33.8688+151.2093+012.000/"))
        assertNull(SourceProbe.parseIso6709("+00.0000+000.0000/"))
        assertNull(SourceProbe.parseIso6709("garbage"))
        assertEquals(1714557600000L, SourceProbe.parseMp4Date("20240501T100000.000Z"))
        assertNull(SourceProbe.parseMp4Date("19040101T000000.000Z"))
        assertNull(SourceProbe.parseMp4Date(null))
    }
}

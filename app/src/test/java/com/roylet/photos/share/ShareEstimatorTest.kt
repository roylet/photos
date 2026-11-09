package com.roylet.photos.share

import androidx.media3.common.C
import com.roylet.photos.video.export.HdrHandling
import com.roylet.photos.video.export.MIME_H264
import com.roylet.photos.video.export.VideoEffectSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareEstimatorTest {
    private val smaller = ShareQuality.SMALLER.video!!
    private val smallest = ShareQuality.SMALLEST.video!!

    private fun video(w: Int, h: Int, seconds: Int, mb: Double?, id: String = "v") =
        ShareItemInfo(id, "$id.mov", "video/quicktime", mb?.let { (it * 1_000_000).toLong() }, w, h, seconds * 1000L)

    private fun photo(w: Int, h: Int, mb: Double?, mime: String = "image/jpeg", id: String = "p") =
        ShareItemInfo(id, "$id.jpg", mime, mb?.let { (it * 1_000_000).toLong() }, w, h, null)

    /* ---------- output settings ---------- */

    @Test fun `4K landscape fits 1080p and 720p`() {
        val s = ShareEstimator.videoSettings(3840, 2160, 0, smaller)
        assertEquals(1920 to 1080, s.width to s.height)
        assertEquals(7_000_000, s.videoBitrate)
        assertEquals(128_000, s.audioBitrate)
        assertTrue(s.resized)
        val t = ShareEstimator.videoSettings(3840, 2160, 0, smallest)
        assertEquals(1280 to 720, t.width to t.height)
        assertEquals(2_800_000, t.videoBitrate)
        assertEquals(96_000, t.audioBitrate)
    }

    @Test fun `portrait stays portrait`() {
        val s = ShareEstimator.videoSettings(2160, 3840, 0, smaller)
        assertEquals(1080 to 1920, s.width to s.height)
    }

    @Test fun `container rotation gives the upright size`() {
        // Phones store portrait video as landscape pixels with a 90° rotation.
        val s = ShareEstimator.videoSettings(3840, 2160, 90, smaller)
        assertEquals(1080 to 1920, s.width to s.height)
        val t = ShareEstimator.videoSettings(1920, 1080, 270, smallest)
        assertEquals(720 to 1280, t.width to t.height)
        val u = ShareEstimator.videoSettings(1920, 1080, 180, smallest)
        assertEquals(1280 to 720, u.width to u.height)
    }

    @Test fun `never upscales, and small frames get a smaller bitrate`() {
        val s = ShareEstimator.videoSettings(1280, 720, 0, smaller)
        assertEquals(1280 to 720, s.width to s.height)
        assertFalse(s.resized)
        assertTrue(s.videoBitrate < smaller.videoBitrate)
        val tiny = ShareEstimator.videoSettings(320, 240, 0, smaller)
        assertEquals(320 to 240, tiny.width to tiny.height)
        assertEquals(2_450_000, tiny.videoBitrate)
    }

    @Test fun `ultra-wide is limited by its long edge`() {
        val s = ShareEstimator.videoSettings(2560, 1080, 0, smaller)
        assertEquals(1920, s.width)
        assertEquals(810, s.height)
    }

    @Test fun `sizes are even`() {
        val s = ShareEstimator.videoSettings(1999, 1125, 0, smallest)
        assertEquals(0, s.width % 2)
        assertEquals(0, s.height % 2)
        assertTrue(s.width <= 1280 && s.height <= 720)
    }

    @Test fun `photo presets cap the long edge without upscaling`() {
        assertEquals(PhotoOutputSettings(2560, 1920, 85), ShareEstimator.photoSettings(4032, 3024, ShareQuality.SMALLER.photo!!))
        assertEquals(PhotoOutputSettings(1200, 1600, 80), ShareEstimator.photoSettings(3024, 4032, ShareQuality.SMALLEST.photo!!))
        assertEquals(PhotoOutputSettings(1000, 800, 85), ShareEstimator.photoSettings(1000, 800, ShareQuality.SMALLER.photo!!))
    }

    @Test fun `the Media3 plan resizes upright, tone-maps and sets both bitrates`() {
        val plan = Media3ShareTranscoder.plan(Media3ShareTranscoder.Probe(3840, 2160, 90, 10_000, hasAudio = true), smaller)
        assertEquals(listOf(VideoEffectSpec.Resize(1080, 1920, downscale = true)), plan.videoEffects)
        assertEquals(1080 to 1920, plan.outputWidth to plan.outputHeight)
        assertEquals(MIME_H264, plan.videoMime)
        assertEquals(HdrHandling.TONE_MAP, plan.hdr)
        assertEquals(7_000_000, plan.bitrate)
        assertEquals(128_000, plan.audioBitrate)
        assertEquals(C.TIME_END_OF_SOURCE, plan.clips.single().endMs)
        assertFalse(plan.removeAudio)
        assertFalse(plan.trimOptimization)
        assertNull(plan.fallback()) // already the simplest: the share compressor retries smaller instead
    }

    @Test fun `the Media3 plan skips the resize when the size is kept, and drops missing audio`() {
        val plan = Media3ShareTranscoder.plan(Media3ShareTranscoder.Probe(1280, 720, 0, 5_000, hasAudio = false), smallest)
        assertTrue(plan.videoEffects.isEmpty())
        assertTrue(plan.removeAudio)
        assertEquals(96_000, plan.audioBitrate)
    }

    /* ---------- estimates and enabling ---------- */

    @Test fun `video estimate is duration times bitrate plus audio`() {
        val v = video(3840, 2160, 60, 400.0)
        // 60 s × (7 Mbps + 128 kbps) / 8 × 1.02
        assertEquals(54_529_200L, ShareEstimator.estimate(v, ShareQuality.SMALLER))
        assertEquals(22_154_400L, ShareEstimator.estimate(v, ShareQuality.SMALLEST))
        assertEquals(400_000_000L, ShareEstimator.estimate(v, ShareQuality.ORIGINAL))
    }

    @Test fun `a video without a duration has no estimate but is still compressed`() {
        val v = video(1920, 1080, 0, 50.0).copy(durationMs = null, mimeType = "video/mp4")
        assertNull(ShareEstimator.estimate(v, ShareQuality.SMALLER))
        assertTrue(ShareEstimator.plan(v, ShareQuality.SMALLER).compress)
    }

    @Test fun `photo estimate uses the output pixels`() {
        val p = photo(4032, 3024, 3.5)
        assertEquals((2560 * 1920 * 0.30).toLong(), ShareEstimator.estimate(p, ShareQuality.SMALLER))
        assertEquals((1600 * 1200 * 0.22).toLong(), ShareEstimator.estimate(p, ShareQuality.SMALLEST))
    }

    @Test fun `options that wouldn't make it smaller are greyed`() {
        // A low-bitrate 360p clip: re-encoding at the preset would grow it.
        val clip = video(640, 360, 10, 1.0)
        assertFalse(ShareEstimator.summary(listOf(clip), ShareQuality.SMALLER).enabled)
        assertFalse(ShareEstimator.summary(listOf(clip), ShareQuality.SMALLEST).enabled)
        assertTrue(ShareEstimator.summary(listOf(clip), ShareQuality.ORIGINAL).enabled)
        // A small JPEG already under the size.
        val small = photo(1200, 900, 0.25)
        assertFalse(ShareEstimator.summary(listOf(small), ShareQuality.SMALLER).enabled)
        // Saving under 10% doesn't count.
        val close = photo(2560, 1920, 1.6)
        assertFalse(ShareEstimator.plan(close, ShareQuality.SMALLER).compress)
    }

    @Test fun `GIFs, PNGs and raw files go as they are`() {
        listOf("image/gif", "image/png", "image/x-adobe-dng").forEach { mime ->
            val p = photo(4000, 3000, 20.0, mime)
            assertFalse(mime, ShareEstimator.plan(p, ShareQuality.SMALLEST).compress)
            assertFalse(mime, ShareEstimator.summary(listOf(p), ShareQuality.SMALLEST).enabled)
        }
    }

    @Test fun `multi-select totals mix estimates and originals`() {
        val big = video(3840, 2160, 60, 400.0, "a")
        val small = photo(1200, 900, 0.25, id = "b")
        val heic = photo(4032, 3024, 2.5, "image/heic", "c")
        val items = listOf(big, small, heic)
        val original = ShareEstimator.summary(items, ShareQuality.ORIGINAL)
        assertEquals(402_750_000L, original.totalBytes)
        assertFalse(original.partial)
        val s = ShareEstimator.summary(items, ShareQuality.SMALLER)
        assertTrue(s.enabled)
        assertEquals(2, s.compressCount)
        assertEquals(54_529_200L + 250_000L + (2560 * 1920 * 0.30).toLong(), s.totalBytes)
    }

    @Test fun `unknown sizes make the total partial`() {
        val items = listOf(video(1920, 1080, 30, null, "a"), photo(4000, 3000, 4.0, id = "b"))
        val o = ShareEstimator.summary(items, ShareQuality.ORIGINAL)
        assertTrue(o.partial)
        assertEquals(4_000_000L, o.totalBytes)
        val s = ShareEstimator.summary(items, ShareQuality.SMALLER)
        assertTrue(s.enabled)
        assertEquals(2, s.compressCount)
        assertFalse(s.partial)
    }

    @Test fun `remembered names map back, unknown ones to Original`() {
        assertEquals(ShareQuality.SMALLEST, ShareQuality.of("SMALLEST"))
        assertEquals(ShareQuality.ORIGINAL, ShareQuality.of("nonsense"))
        assertEquals(ShareQuality.SMALLEST, ShareQuality.SMALLER.fallback)
        assertNull(ShareQuality.SMALLEST.fallback)
    }
}

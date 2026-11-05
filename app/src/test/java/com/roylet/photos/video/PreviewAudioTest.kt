package com.roylet.photos.video

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.GainProcessor
import com.roylet.photos.video.model.AudioSettings
import com.roylet.photos.video.preview.LiveGain
import com.roylet.photos.video.preview.PreviewAudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The preview's audio path: the real Media3 GainProcessor fed by our [LiveGain]. */
@RunWith(RobolectricTestRunner::class)
class PreviewAudioTest {
    private fun pcm(vararg samples: Short): ByteBuffer {
        val b = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { b.putShort(it) }
        b.flip()
        return b
    }

    private fun run(gain: Float, input: ShortArray): ShortArray {
        val live = LiveGain().apply { this.gain = gain }
        val p = GainProcessor(live)
        p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertTrue("stays active so live volume changes apply", p.isActive)
        val out = ArrayList<Short>()
        val buf = pcm(*input)
        while (buf.hasRemaining()) {
            p.queueInput(buf)
            val o = p.output
            while (o.hasRemaining()) out += o.short
        }
        return out.toShortArray()
    }

    /**
     * The v0.2.1 crash: at 100 % (gain exactly 1) GainProcessor asks how long the unity region
     * lasts and checkState()s that the answer isn't TIME_UNSET; LiveGain answered TIME_UNSET, an
     * IllegalStateException on the playback thread, i.e. ERROR_CODE_FAILED_RUNTIME_CHECK.
     */
    @Test fun unityGainPassesAudioThroughWithoutARuntimeCheckFailure() {
        val input = ShortArray(48_000 * 2) { (it % 2000 - 1000).toShort() } // 1 s of stereo, longer than one unity chunk
        assertEquals(input.toList(), run(1f, input).toList())
    }

    @Test fun otherGainsScaleTheSamples() {
        assertEquals(listOf<Short>(50, -50), run(0.5f, shortArrayOf(100, -100)).toList())
        assertEquals(listOf<Short>(200, -200), run(2f, shortArrayOf(100, -100)).toList())
        assertEquals(listOf<Short>(0, 0), run(0f, shortArrayOf(100, -100)).toList())
    }

    @Test fun unityRegionIsAlwaysAFiniteRegionAhead() {
        val g = LiveGain()
        val end = g.isUnityUntil(1_000, 48_000)
        assertTrue(end > 1_000 && end != C.TIME_UNSET && end != C.TIME_END_OF_SOURCE)
    }


    @Test fun volumeMapsToPlayerVolumeInRangeAndGainUpTo200Percent() {
        assertEquals(PreviewAudio(1f, 1f), PreviewAudio.of(AudioSettings()))
        assertEquals(PreviewAudio(1f, 2f), PreviewAudio.of(AudioSettings(volume = 200f)))
        assertEquals(PreviewAudio(1f, 0.5f), PreviewAudio.of(AudioSettings(volume = 50f)))
        assertEquals(PreviewAudio(0f, 0f), PreviewAudio.of(AudioSettings(mute = true, volume = 150f)))
        // Out of range or NaN never reaches the player: player volume 0..1, gain 0..2.
        assertEquals(PreviewAudio(1f, 2f), PreviewAudio.of(AudioSettings(volume = 900f)))
        assertEquals(PreviewAudio(1f, 0f), PreviewAudio.of(AudioSettings(volume = -5f)))
        assertEquals(PreviewAudio(1f, 1f), PreviewAudio.of(AudioSettings(volume = Float.NaN)))
    }
}

package com.roylet.photos.cast

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** The real transcoder on Robolectric's native graphics: a big non-JPEG becomes a ≤1920 px JPEG. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class JpegTranscoderTest {
    private fun png(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 40)) }
        return ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    @Test fun bigImageBecomesATvSizedJpeg() {
        val out = AndroidJpegTranscoder().transcode(png(4000, 3000), PhotoPlan.MAX_EDGE)
        assertEquals(0xFF.toByte(), out[0])
        assertEquals(0xD8.toByte(), out[1])
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(out, 0, out.size, bounds)
        assertEquals(1920, bounds.outWidth)
        assertEquals(1440, bounds.outHeight)
        assertEquals("image/jpeg", bounds.outMimeType)
    }

    @Test fun smallImageKeepsItsSize() {
        val out = AndroidJpegTranscoder().transcode(png(640, 480), PhotoPlan.MAX_EDGE)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(out, 0, out.size, bounds)
        assertEquals(640, bounds.outWidth)
        assertEquals(480, bounds.outHeight)
    }
}

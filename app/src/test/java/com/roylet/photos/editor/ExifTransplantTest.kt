package com.roylet.photos.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import com.roylet.photos.editor.io.JpegExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExifTransplantTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun jpeg(file: File, w: Int, h: Int): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 40)) }
        file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    @Test fun copiesMetadataAndResetsLayoutTags() {
        val original = jpeg(tmp.newFile("original.jpg"), 64, 48)
        ExifInterface(original).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:05:06 07:08:09")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+10:00")
            setAttribute(ExifInterface.TAG_MAKE, "Pixel Maker")
            setAttribute(ExifInterface.TAG_MODEL, "Phone 9")
            setAttribute(ExifInterface.TAG_F_NUMBER, "1.8")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "200")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(-33.8688, 151.2093)
            setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, "64")
            setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, "48")
            saveAttributes()
        }

        val edited = Bitmap.createBitmap(30, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val out = File(tmp.root, "out/edited.jpg")
        val result = JpegExporter.export(edited, out, original)

        assertEquals(out, result.file)
        assertEquals(30, result.width); assertEquals(50, result.height)
        assertEquals("image/jpeg", result.mimeType)
        assertTrue(result.exifCopied)
        assertTrue(out.length() > 0)

        val exif = ExifInterface(out)
        assertEquals("2024:05:06 07:08:09", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("+10:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        assertEquals("Pixel Maker", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("Phone 9", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals(1.8, exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0), 1e-6)
        assertEquals(200, exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals(30, exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0))
        assertEquals(50, exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0))
        val latLong = exif.latLong!!
        assertEquals(-33.8688, latLong[0], 1e-4)
        assertEquals(151.2093, latLong[1], 1e-4)
    }

    @Test fun exportWithoutOriginalWritesPlainJpeg() {
        val edited = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val result = JpegExporter.export(edited, tmp.newFile("plain.jpg"), original = null)
        assertTrue(result.exifCopied)
        assertNull(ExifInterface(result.file).getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test fun unreadableOriginalStillExports() {
        val bogus = tmp.newFile("bogus.jpg").apply { writeText("not a jpeg") }
        val result = JpegExporter.export(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), tmp.newFile("x.jpg"), bogus)
        assertTrue(result.file.length() > 0)
    }
}

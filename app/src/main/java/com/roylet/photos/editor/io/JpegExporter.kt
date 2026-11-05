package com.roylet.photos.editor.io

import android.graphics.Bitmap
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.roylet.photos.editor.gl.EditRenderer
import com.roylet.photos.editor.recipe.LutResolver
import com.roylet.photos.editor.recipe.Recipe
import java.io.File
import java.io.FileOutputStream

/** The file written by an export. */
data class ExportResult(
    val file: File,
    val width: Int,
    val height: Int,
    val mimeType: String = "image/jpeg",
    /** False if the original's metadata could not be copied (the image itself is fine). */
    val exifCopied: Boolean = true,
)

/** Encodes edited bitmaps as JPEG and carries the original's metadata over. */
object JpegExporter {
    const val DEFAULT_QUALITY = 95

    /**
     * Writes [bitmap] to [outFile] at [quality], then copies the EXIF of [original] (when given)
     * with orientation reset to normal and the dimensions set to the new size. Blocking.
     */
    fun export(bitmap: Bitmap, outFile: File, original: File?, quality: Int = DEFAULT_QUALITY): ExportResult {
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encode failed" }
        }
        val copied = original == null || runCatching {
            ExifTransplant.copy(original, outFile, bitmap.width, bitmap.height)
        }.onFailure { Log.w("JpegExporter", "EXIF copy failed", it) }.isSuccess
        return ExportResult(outFile, bitmap.width, bitmap.height, "image/jpeg", copied)
    }
}

/** Decode → render → encode for one edit, the whole save path in one blocking call. */
object EditExporter {
    /**
     * Renders [recipe] over [source] at full resolution and writes a JPEG to [outFile] with the
     * original's EXIF. Call from a background thread (e.g. a WorkManager worker).
     */
    fun export(
        source: LoadedEdit,
        recipe: Recipe,
        outFile: File,
        quality: Int = JpegExporter.DEFAULT_QUALITY,
        lutResolver: LutResolver = LutResolver.Default,
        maxOutputPixels: Long? = null,
    ): ExportResult {
        val rendered = source.openFullRes().use { full ->
            EditRenderer.exportFullRes(full, recipe, lutResolver, maxOutputPixels = maxOutputPixels, blurBasis = source.proxy)
        }
        try {
            return JpegExporter.export(rendered, outFile, source.file, quality)
        } finally {
            rendered.recycle()
        }
    }
}

/** Copies EXIF metadata between files with androidx [ExifInterface]. */
object ExifTransplant {
    /** Tags carried over verbatim. Thumbnails, maker notes and layout tags are left out. */
    val TAGS: List<String> = listOf(
        // Dates
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        // Camera
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION, ExifInterface.TAG_LENS_SERIAL_NUMBER, ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_SOFTWARE,
        // Exposure
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_EXPOSURE_INDEX, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_SENSITIVITY_TYPE,
        ExifInterface.TAG_ISO_SPEED, ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX, ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY,
        ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_LIGHT_SOURCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_FLASH_ENERGY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_SUBJECT_DISTANCE_RANGE, ExifInterface.TAG_SUBJECT_AREA, ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_SCENE_CAPTURE_TYPE, ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_GAIN_CONTROL, ExifInterface.TAG_CONTRAST, ExifInterface.TAG_SATURATION, ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_SENSING_METHOD, ExifInterface.TAG_CUSTOM_RENDERED, ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_X_RESOLUTION, ExifInterface.TAG_Y_RESOLUTION, ExifInterface.TAG_RESOLUTION_UNIT,
        // GPS
        ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_BEARING_REF, ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF, ExifInterface.TAG_GPS_DEST_DISTANCE,
    )

    /**
     * Copies [TAGS] from [from] into the JPEG [to], then sets orientation = normal and the pixel
     * dimensions to [width] × [height]. Throws if either file cannot be read/written.
     */
    fun copy(from: File, to: File, width: Int, height: Int) {
        val src = ExifInterface(from)
        val dst = ExifInterface(to)
        for (tag in TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
        dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        dst.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
        dst.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
        dst.saveAttributes()
    }
}

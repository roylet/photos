package com.roylet.photos.domain.library

import com.roylet.photos.domain.source.MediaSourceKind

/**
 * Everything the viewer's info sheet shows about one asset, whatever its source. Every field is
 * optional: Immich fills most of them from `GET /assets/{id}`, a MediaStore row fewer (agent B
 * reads EXIF with ExifInterface for the camera fields). The timeline's own fields (date, city)
 * stand in until this loads.
 */
data class AssetDetails(
    val id: String,
    val source: MediaSourceKind,
    val fileName: String? = null,
    val mimeType: String? = null,
    /** When it was taken, UTC epoch millis. */
    val takenAt: Long? = null,
    /** An IANA zone ("Australia/Melbourne") or offset label, when known. */
    val timeZone: String? = null,
    val sizeBytes: Long? = null,
    /** Pixel size as stored (before orientation). */
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val description: String? = null,
    val camera: CameraInfo? = null,
    val location: AssetLocation? = null,
    /** Named people in the photo (Immich face recognition); empty when unknown. */
    val people: List<String> = emptyList(),
    /** Where the file lives on the phone, e.g. "DCIM/Camera" (local assets). */
    val folder: String? = null,
) {
    val megapixels: Double? get() = if (width != null && height != null) width * height / 1e6 else null
}

data class CameraInfo(
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    val fNumber: Double? = null,
    /** As written in EXIF, e.g. "1/1200". */
    val exposureTime: String? = null,
    val iso: Int? = null,
    val focalLengthMm: Double? = null,
) {
    val hasSettings: Boolean get() = fNumber != null || exposureTime != null || iso != null || focalLengthMm != null
}

data class AssetLocation(
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

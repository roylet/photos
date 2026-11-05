package com.roylet.photos.immich

import com.roylet.photos.domain.library.AssetDetails
import com.roylet.photos.domain.library.AssetLocation
import com.roylet.photos.domain.library.CameraInfo
import com.roylet.photos.domain.source.MediaSourceKind

/** Maps `GET /assets/{id}` onto the source-neutral info-sheet model. */
fun AssetDto.toDetails(): AssetDetails {
    val exif = exifInfo
    val camera = exif?.let {
        CameraInfo(
            make = it.make?.takeIf(String::isNotBlank),
            model = it.model?.takeIf(String::isNotBlank),
            lens = it.lensModel?.takeIf(String::isNotBlank),
            fNumber = it.fNumber,
            exposureTime = it.exposureTime?.takeIf(String::isNotBlank),
            iso = it.iso,
            focalLengthMm = it.focalLength,
        )
    }?.takeIf { it.make != null || it.model != null || it.lens != null || it.hasSettings }
    val location = exif?.let { AssetLocation(it.city, it.state, it.country, it.latitude, it.longitude) }
        ?.takeIf { it.city != null || it.state != null || it.country != null || it.latitude != null }
    return AssetDetails(
        id = id,
        source = MediaSourceKind.Immich,
        fileName = originalFileName.takeIf { it.isNotBlank() },
        mimeType = originalMimeType,
        takenAt = (exif?.dateTimeOriginal ?: fileCreatedAt)?.let(TimeBucketAssetsDto::parseInstantMillis),
        timeZone = exif?.timeZone,
        sizeBytes = exif?.fileSizeInByte,
        width = exif?.exifImageWidth ?: width,
        height = exif?.exifImageHeight ?: height,
        durationMs = TimeBucketAssetsDto.parseDurationMs(duration)?.takeIf { it > 0 },
        description = exif?.description?.takeIf { it.isNotBlank() },
        camera = camera,
        location = location,
        people = people.filter { !it.isHidden }.map { it.name }.filter { it.isNotBlank() },
    )
}

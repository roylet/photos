package com.roylet.photos.cast

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem

/**
 * Media3 1.9's [DefaultMediaItemConverter] has no photo type: an `image/jpeg` item goes to the
 * receiver as MEDIA_TYPE_MOVIE. This sends image items as Cast MEDIA_TYPE_PHOTO (what the Default
 * Media Receiver shows as a still), keeping the default's custom data so RemoteCastPlayer can
 * turn the queue item back into a MediaItem. Videos go through the default unchanged.
 */
@OptIn(UnstableApi::class)
class PhotoMediaItemConverter(private val base: MediaItemConverter = DefaultMediaItemConverter()) : MediaItemConverter {
    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val item = base.toMediaQueueItem(mediaItem)
        val mime = mediaItem.localConfiguration?.mimeType ?: return item
        if (!isPhoto(mime)) return item
        val info = item.media ?: return item
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO).apply {
            mediaItem.mediaMetadata.title?.let { putString(MediaMetadata.KEY_TITLE, it.toString()) }
            mediaItem.mediaMetadata.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it.toString()) }
        }
        val photo = MediaInfo.Builder(info.contentId)
            .setContentUrl(info.contentUrl ?: info.contentId)
            .setContentType(mime)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED) // What Immich sends; the Default Media Receiver shows it as a still.
            .setMetadata(metadata)
            .setCustomData(info.customData)
            .build()
        return MediaQueueItem.Builder(photo).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem = base.toMediaItem(mediaQueueItem)

    companion object {
        fun isPhoto(mime: String) = mime.startsWith("image/")
    }
}

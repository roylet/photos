package com.roylet.photos.local

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.roylet.photos.domain.library.AssetDetails
import com.roylet.photos.domain.library.AssetLocation
import com.roylet.photos.domain.library.CameraInfo
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.domain.source.MediaSourceKind
import com.roylet.photos.ui.components.SnackbarController
import com.roylet.photos.ui.permissions.ConsentLauncher
import com.roylet.photos.ui.permissions.MediaPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.ZoneId
import kotlin.math.roundToLong

/** Android 11+: turns [MediaRequest]s into MediaStore PendingIntents and asks through [consent]. */
@RequiresApi(Build.VERSION_CODES.R)
class ConsentRequestRunner(
    private val context: Context,
    private val consent: ConsentLauncher,
    /** After a dialog was (or would have been) shown: the one-time "skip confirmation dialogs" offer. */
    private val afterDialog: () -> Unit = {},
) : MediaRequestRunner {
    override suspend fun run(request: MediaRequest): Boolean {
        val resolver = context.contentResolver
        val uris = request.uris.map(Uri::parse)
        val intent = when (request) {
            is MediaRequest.Favorite -> MediaStore.createFavoriteRequest(resolver, uris, request.value)
            is MediaRequest.Trash -> MediaStore.createTrashRequest(resolver, uris, request.value)
            is MediaRequest.Delete -> MediaStore.createDeleteRequest(resolver, uris)
        }
        val ok = consent.requestConsent(intent)
        afterDialog()
        return ok
    }
}

/**
 * After the first MediaStore consent dialog, offers once (snackbar) to skip them from now on via
 * "Media management apps" (ACTION_REQUEST_MANAGE_MEDIA, Android 12+). Waits for the action's own
 * snackbar ("Moved to Trash · Undo") to go first.
 */
class ManageMediaOffer(
    private val context: Context,
    private val snackbar: SnackbarController,
    private val scope: CoroutineScope,
    private val alreadyOffered: () -> Boolean,
    private val markOffered: () -> Unit,
) {
    fun maybeOffer() {
        if (!MediaPermissions.manageMediaSupported || alreadyOffered() || MediaPermissions.canManageMedia(context)) return
        val intent = MediaPermissions.manageMediaIntent(context) ?: return
        markOffered()
        scope.launch(Dispatchers.Main) {
            // Let the action's own snackbar (with its Undo) show first, then wait for it to go.
            delay(400)
            var waited = 0
            while (snackbar.current != null && waited < 12_000) { delay(250); waited += 250 }
            snackbar.show("Tired of confirming? Let Photos skip these dialogs.", actionLabel = "Skip dialogs", durationMs = 8000) {
                runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
}

/** Android 10's app-private trash: `files/trash/<n>.<ext>`. */
class AndroidLegacyFiles(private val context: Context, private val consent: ConsentLauncher) : LegacyFiles {
    private val resolver = context.contentResolver
    private val dir get() = File(context.filesDir, "trash").apply { mkdirs() }

    override suspend fun copyToTrash(item: MediaEntity): String = withContext(Dispatchers.IO) {
        val ext = item.displayName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 } ?: if (item.isVideo) "mp4" else "jpg"
        val file = File(dir, "${System.currentTimeMillis()}-${item.id}.$ext")
        val input = resolver.openInputStream(mediaUri(item.id, item.isVideo)) ?: throw IOException("Couldn't read ${item.displayName}")
        input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
        file.absolutePath
    }

    override suspend fun deleteOriginal(item: MediaEntity): Boolean {
        val uri = mediaUri(item.id, item.isVideo)
        return try {
            withContext(Dispatchers.IO) { resolver.delete(uri, null, null) } > 0
        } catch (e: RecoverableSecurityException) {
            // Another app's photo: Android 10 asks once per item.
            if (!consent.requestConsent(e.userAction.actionIntent.intentSender)) return false
            withContext(Dispatchers.IO) { resolver.delete(uri, null, null) } > 0
        }
    }

    override suspend fun restore(row: LegacyTrashEntity): Long = withContext(Dispatchers.IO) {
        val collection = if (row.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, row.displayName ?: File(row.path).name)
            put(MediaStore.MediaColumns.MIME_TYPE, row.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, row.relativePath ?: if (row.isVideo) "Movies/" else "Pictures/")
            put(MediaStore.MediaColumns.DATE_TAKEN, row.takenAt)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't restore ${row.displayName}")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Couldn't write ${row.displayName}")
            out.use { o -> File(row.path).inputStream().use { it.copyTo(o) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        ContentUris.parseId(uri)
    }

    override fun deleteFile(path: String) { File(path).delete() }
}

/** Info-sheet details (MediaStore columns + EXIF) and file copies through the ContentResolver. */
class AndroidLocalIo(private val context: Context, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : LocalIo {
    private val resolver = context.contentResolver

    private val canReadLocation: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    override suspend fun details(item: MediaEntity): AssetDetails = withContext(Dispatchers.IO) {
        val uri = mediaUri(item.id, item.isVideo)
        var camera: CameraInfo? = null
        var location: AssetLocation? = null
        var timeZone: String? = null
        if (!item.isVideo) {
            exif(uri)?.let { exif ->
                camera = cameraOf(exif)
                exif.latLong?.let { (lat, lon) -> location = AssetLocation(latitude = lat, longitude = lon) }
                timeZone = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.let { "UTC$it" }
            }
        } else {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, uri)
                    parseIso6709(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))?.let { (lat, lon) -> location = AssetLocation(latitude = lat, longitude = lon) }
                } finally { r.release() }
            }
        }
        AssetDetails(
            id = AssetRef.local(item.id).id,
            source = MediaSourceKind.Local,
            fileName = item.displayName,
            mimeType = item.mimeType,
            takenAt = item.takenAt,
            timeZone = timeZone ?: zone().id,
            sizeBytes = item.sizeBytes,
            width = item.width.takeIf { it > 0 },
            height = item.height.takeIf { it > 0 },
            durationMs = item.durationMs,
            camera = camera,
            location = location,
            folder = item.relativePath?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: item.bucketName,
        )
    }

    /** EXIF from the original when ACCESS_MEDIA_LOCATION allows (else Android redacts the GPS). */
    private fun exif(uri: Uri): ExifInterface? {
        if (canReadLocation) {
            runCatching { resolver.openInputStream(MediaStore.setRequireOriginal(uri))?.use { ExifInterface(it) } }.getOrNull()?.let { return it }
        }
        return runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
    }

    private fun cameraOf(exif: ExifInterface): CameraInfo? {
        val c = CameraInfo(
            make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() },
            model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
            lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
            fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 },
            exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let(::exposureLabel),
            iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
            focalLengthMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 },
        )
        return c.takeIf { it.make != null || it.model != null || it.hasSettings }
    }

    override suspend fun copy(item: MediaEntity, dest: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val total = item.sizeBytes ?: -1
        val input = resolver.openInputStream(mediaUri(item.id, item.isVideo)) ?: throw IOException("Couldn't read ${item.displayName}")
        input.use { i ->
            dest.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
        }
    }
}

/** "1/1200" for short exposures, "2.5" (seconds) for long ones. */
fun exposureLabel(seconds: Double): String =
    if (seconds < 1.0) "1/${(1.0 / seconds).roundToLong()}"
    else if (seconds == seconds.toLong().toDouble()) seconds.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, seconds)

/** A video's ISO 6709 location ("+37.7749-122.4194/") as (lat, lon). */
fun parseIso6709(s: String?): Pair<Double, Double>? {
    val m = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(s ?: return null) ?: return null
    val lat = m.groupValues[1].toDoubleOrNull() ?: return null
    val lon = m.groupValues[2].toDoubleOrNull() ?: return null
    return if (lat == 0.0 && lon == 0.0) null else lat to lon
}

/**
 * Watches MediaStore's images and videos while the app is in the foreground and asks the indexer
 * for a (debounced) sync on every change.
 */
class MediaObserver(private val context: Context, private val onChange: () -> Unit) {
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = onChange()
        override fun onChange(selfChange: Boolean, uri: Uri?) = onChange()
    }
    private var registered = false

    fun register() {
        if (registered) return
        registered = runCatching {
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        }.isSuccess
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        registered = false
    }
}

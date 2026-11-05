package com.roylet.photos.domain.library

import com.roylet.photos.domain.timeline.TimelineFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * Library → On this device: the phone's folders (MediaStore BUCKET_IDs), Google Photos style.
 * Pure Kotlin: the index hands in rows, this names, orders and filters them.
 */

/** Folders the app knows by name, each with a friendly label (and an icon in the UI). */
enum class FolderKind(val label: String) {
    Camera("Camera"),
    Screenshots("Screenshots"),
    ScreenRecordings("Screen recordings"),
    Downloads("Downloads"),
    WhatsAppImages("WhatsApp Images"),
    WhatsAppVideo("WhatsApp Video"),
    Telegram("Telegram"),
    Instagram("Instagram"),
    Snapchat("Snapchat"),
    Pictures("Pictures"),
    Movies("Movies"),
    /** This app's own editor output (`Pictures/Photos Edited/`, `Movies/Photos Edited/`). */
    Edited("Edited"),
    Other(""),
}

/** One folder on the phone: [count] live items, the newest ([coverId], a `local:` id) as its cover. */
data class DeviceFolder(
    val bucketId: Long,
    /** What the UI shows: the friendly name for well-known folders, else the folder's own. */
    val name: String,
    /** RELATIVE_PATH without the trailing slash, e.g. `DCIM/Camera`. */
    val path: String,
    val count: Int,
    /** Newest item's time (UTC millis). */
    val latest: Long,
    val coverId: String?,
    val kind: FolderKind = FolderKind.Other,
    /** Not on the phone's main storage (an SD card or USB drive). */
    val removable: Boolean = false,
) {
    /** Its album id (`folder:<BUCKET_ID>`), for [TimelineFilter.album]. */
    val id: String get() = TimelineFilter.FOLDER_PREFIX + bucketId
}

/** Settings → "Show device folders in Photos": every folder, or only Camera plus the ones turned on. */
enum class PhotosFolderMode { All, CameraAndSelected }

/**
 * What the user chose per folder: [hidden] from Library → On this device, and whether each shows in
 * the Photos timeline ([inPhotos] / [notInPhotos] override [mode]'s default). Bucket ids.
 */
data class FolderPrefs(
    val hidden: Set<Long> = emptySet(),
    val inPhotos: Set<Long> = emptySet(),
    val notInPhotos: Set<Long> = emptySet(),
    val mode: PhotosFolderMode = PhotosFolderMode.All,
) {
    fun isHidden(f: DeviceFolder) = f.bucketId in hidden

    /** Whether [f]'s photos appear in the Photos timeline. */
    fun showsInPhotos(f: DeviceFolder): Boolean = when (f.bucketId) {
        in notInPhotos -> false
        in inPhotos -> true
        else -> mode == PhotosFolderMode.All || f.kind == FolderKind.Camera
    }

    /** Bucket ids of [folders] the Photos timeline leaves out. */
    fun excludedFromPhotos(folders: List<DeviceFolder>): Set<Long> =
        if (mode == PhotosFolderMode.All && notInPhotos.isEmpty()) emptySet()
        else folders.filterNot(::showsInPhotos).mapTo(HashSet()) { it.bucketId }

    fun withHidden(bucketId: Long, hide: Boolean) = copy(hidden = if (hide) hidden + bucketId else hidden - bucketId)

    fun withInPhotos(bucketId: Long, show: Boolean) =
        if (show) copy(inPhotos = inPhotos + bucketId, notInPhotos = notInPhotos - bucketId)
        else copy(inPhotos = inPhotos - bucketId, notInPhotos = notInPhotos + bucketId)
}

object DeviceFolders {
    private const val EDITED_DIR = "Photos Edited"

    /** Which well-known folder this is, from its BUCKET_DISPLAY_NAME and RELATIVE_PATH. */
    fun kindOf(bucketName: String?, relativePath: String?): FolderKind {
        val path = relativePath.orEmpty().trim('/').lowercase()
        val name = (bucketName?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')).trim().lowercase()
        val parent = path.substringBeforeLast('/', "")
        return when {
            path == "dcim/camera" || (name == "camera" && parent == "dcim") -> FolderKind.Camera
            name == "screenshots" -> FolderKind.Screenshots
            name in setOf("screen recordings", "screenrecords", "screenrecorder", "screen recorder", "screenrecord") -> FolderKind.ScreenRecordings
            name == "download" || name == "downloads" -> FolderKind.Downloads
            name == "whatsapp images" || name == "whatsapp animated gifs" -> FolderKind.WhatsAppImages
            name == "whatsapp video" -> FolderKind.WhatsAppVideo
            name.startsWith("telegram") -> FolderKind.Telegram
            name == "instagram" -> FolderKind.Instagram
            name == "snapchat" -> FolderKind.Snapchat
            name == EDITED_DIR.lowercase() -> FolderKind.Edited
            path == "pictures" -> FolderKind.Pictures
            path == "movies" -> FolderKind.Movies
            else -> FolderKind.Other
        }
    }

    /** The name to show: the friendly label for well-known folders, else the folder's own name. */
    fun displayName(bucketName: String?, relativePath: String?): String {
        val own = bucketName?.takeIf { it.isNotBlank() } ?: relativePath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Folder"
        return when (val k = kindOf(bucketName, relativePath)) {
            FolderKind.Other -> own
            // Telegram keeps "Telegram Images" and "Telegram Video" apart.
            FolderKind.Telegram -> if (own.contains("video", ignoreCase = true)) "Telegram Video" else k.label
            else -> k.label
        }
    }

    /** A folder from an index row. */
    fun of(bucketId: Long, bucketName: String?, relativePath: String?, volume: String?, count: Int, latest: Long, coverId: String?) = DeviceFolder(
        bucketId = bucketId,
        name = displayName(bucketName, relativePath),
        path = relativePath.orEmpty().trim('/'),
        count = count,
        latest = latest,
        coverId = coverId,
        kind = kindOf(bucketName, relativePath),
        removable = volume != null && volume != "external_primary" && volume != "external",
    )

    /** Camera first, then Screenshots, then the rest by their newest item (Google Photos' order). */
    fun order(folders: List<DeviceFolder>): List<DeviceFolder> = folders.sortedWith(
        compareBy<DeviceFolder> { rank(it.kind) }
            .thenByDescending { it.latest }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.bucketId },
    )

    private fun rank(kind: FolderKind) = when (kind) {
        FolderKind.Camera -> 0
        FolderKind.Screenshots -> 1
        else -> 2
    }

    /**
     * A short hint for folders whose name another folder also has (two "Camera"s): where each lives,
     * e.g. `DCIM/Camera` and `SD card · DCIM/Camera`. Folders with a unique name get none.
     */
    fun pathHints(folders: List<DeviceFolder>): Map<Long, String> {
        val dupes = folders.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.values.flatten()
        return dupes.associate { it.bucketId to pathHint(it) }
    }

    fun pathHint(f: DeviceFolder): String {
        // Deep app folders keep their last two levels: `…/Media/WhatsApp Images`.
        val parts = f.path.split('/').filter { it.isNotBlank() }
        val path = when {
            parts.isEmpty() -> "Device"
            parts.size > 3 -> "…/" + parts.takeLast(2).joinToString("/")
            else -> parts.joinToString("/")
        }
        return if (f.removable) "SD card · $path" else path
    }
}

/**
 * The phone's folder list for Library → On this device, ordered ([DeviceFolders.order]), with the
 * user's choices ([FolderPrefs]) kept in [save]. [onPhotosChanged] runs when what the Photos
 * timeline shows changed (a folder turned on or off, or the mode), so it can reload.
 */
class DeviceFolderStore(
    private val load: suspend () -> List<DeviceFolder>,
    initialPrefs: FolderPrefs = FolderPrefs(),
    private val save: (FolderPrefs) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _folders = MutableStateFlow<List<DeviceFolder>?>(null)
    /** Every folder, ordered; null until the first load. */
    val folders: StateFlow<List<DeviceFolder>?> = _folders.asStateFlow()

    private val _prefs = MutableStateFlow(initialPrefs)
    val prefs: StateFlow<FolderPrefs> = _prefs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    var onPhotosChanged: () -> Unit = {}

    private val mutex = Mutex()

    fun start() { if (_folders.value == null) refresh() }

    fun refresh() { scope.launch { reload() } }

    /** Loads the list now (the timeline needs it to know what to leave out). */
    suspend fun reload(): List<DeviceFolder>? = mutex.withLock {
        runCatching { DeviceFolders.order(load()) }
            .onSuccess { _folders.value = it; _error.value = null }
            .onFailure { _error.value = it.message ?: it.javaClass.simpleName }
            .getOrNull()
    }

    fun find(bucketId: Long): DeviceFolder? = _folders.value?.firstOrNull { it.bucketId == bucketId }

    fun setHidden(bucketId: Long, hidden: Boolean) = update { it.withHidden(bucketId, hidden) }

    fun setShowInPhotos(bucketId: Long, show: Boolean) = update(photos = true) { it.withInPhotos(bucketId, show) }

    fun setMode(mode: PhotosFolderMode) = update(photos = true) { it.copy(mode = mode) }

    /** Bucket ids the Photos timeline leaves out right now. */
    suspend fun excludedFromPhotos(): Set<Long> {
        val p = _prefs.value
        if (p.mode == PhotosFolderMode.All && p.notInPhotos.isEmpty()) return emptySet()
        val list = _folders.value ?: reload() ?: return p.notInPhotos
        return p.excludedFromPhotos(list)
    }

    private fun update(photos: Boolean = false, change: (FolderPrefs) -> FolderPrefs) {
        val before = _prefs.value
        val after = change(before)
        if (after == before) return
        _prefs.value = after
        save(after)
        if (photos) onPhotosChanged()
    }
}

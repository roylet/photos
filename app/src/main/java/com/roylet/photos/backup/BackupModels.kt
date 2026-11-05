package com.roylet.photos.backup

/*
 * Phone → Immich backup (docs/SOURCES.md §10): the user picks device folders; new photos and
 * videos in them upload in the background. Nothing is ever deleted by backup, on either side.
 */

/** What the backup is doing right now (`backup.state`). */
sealed interface BackupState {
    /** The master switch is off. */
    data object Off : BackupState
    /** On, but there's no Immich server to back up to. */
    data object NoServer : BackupState
    /** Everything selected is backed up (or nothing is selected). [pending] waits for the next run. */
    data class Idle(val pending: Int = 0, val lastRunAt: Long? = null) : BackupState
    /** Hashing and asking Immich what it already has. */
    data object Scanning : BackupState
    /** Uploading item [current] (1-based) of [total]; [fraction] is the current file's progress. */
    data class Uploading(val current: Int, val total: Int, val name: String? = null, val fraction: Float = 0f, val localId: Long? = null) : BackupState
    data class WaitingForWifi(val pending: Int) : BackupState
    data class WaitingForCharging(val pending: Int) : BackupState
    /** The user paused it (Settings or the notification). */
    data object Paused : BackupState
    /** The last run failed. [signIn]: Immich refused the key (401/403); backup is paused until the user signs in again. */
    data class Error(val message: String, val signIn: Boolean = false) : BackupState
}

/** The options on the Backup screen. Defaults: off, Wi-Fi only, any battery, videos included, everything. */
data class BackupOptions(
    val enabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val whileChargingOnly: Boolean = false,
    val includeVideos: Boolean = true,
    /** Only items taken at or after this time (UTC ms); null backs up everything. "From now on" sets it to the moment it was chosen. */
    val newerThan: Long? = null,
    val paused: Boolean = false,
)

/** One folder's backup counts (`backup.folderStatus(bucketId)`). [pending] excludes failures and "not backing up" items. */
data class FolderBackupStatus(
    val enabled: Boolean,
    /** Items the options cover (videos, "newer than"). */
    val eligible: Int = 0,
    val backedUp: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val excluded: Int = 0,
)

/** A phone folder on the Backup screen. */
data class BackupFolder(
    val bucketId: Long,
    val name: String,
    val relativePath: String?,
    /** All live items in the folder. */
    val count: Int,
    /** MediaStore id of the newest item (the cover). */
    val coverId: Long,
    val coverIsVideo: Boolean = false,
    val status: FolderBackupStatus,
)

/** "Couldn't back up (n)": an item that failed for good at its current version. */
data class BackupFailure(val localId: Long, val name: String?, val reason: String, val failedAt: Long)

/** "Not backing up (n)": an item the user deleted from Immich (or that sits in Immich's Trash); it's never re-uploaded. */
data class BackupExclusion(val localId: Long, val name: String?, val reason: String, val excludedAt: Long)

object BackupReasons {
    const val DELETED_FROM_IMMICH = "Deleted from Immich"
    const val IN_IMMICH_TRASH = "In Immich's Trash"
}

package com.roylet.photos.domain.library

/**
 * Hears about Immich copies deleted while the phone copy stays (the "Immich" delete choice), and
 * about their restore (undo, Immich's Trash). Backup keeps these as exclusions (docs/SOURCES.md §10).
 */
interface RemoteDeletionListener {
    /** Phone MediaStore id → the Immich asset that was moved to Immich's Trash. */
    suspend fun remoteCopiesDeleted(kept: Map<Long, String>)
    suspend fun remoteCopiesRestored(remoteIds: List<String>)
}

package com.roylet.photos.immich

import kotlinx.serialization.Serializable

/*
 * The two calls that tell which phone photos Immich already has (docs/SOURCES.md §9).
 */

@Serializable data class BulkCheckItem(val id: String, val checksum: String)
@Serializable private data class BulkCheckBody(val assets: List<BulkCheckItem>)
@Serializable data class BulkCheckResult(
    val id: String,
    val action: String = "accept",
    val assetId: String? = null,
    val isTrashed: Boolean = false,
    val reason: String? = null,
) {
    /** The Immich asset holding this exact file (outside its Trash), or null. */
    val existingAssetId: String? get() = assetId?.takeIf { action == "reject" && reason == "duplicate" && !isTrashed }
}
@Serializable private data class BulkCheckResponse(val results: List<BulkCheckResult> = emptyList())

/**
 * `POST /assets/bulk-upload-check`: for each (client id, SHA-1 hex or base64), whether Immich
 * already has that file and as which asset. Sent in batches of [BULK_CHECK_BATCH].
 */
suspend fun ImmichApi.bulkUploadCheck(items: List<BulkCheckItem>): List<BulkCheckResult> =
    items.chunked(BULK_CHECK_BATCH).flatMap { part -> call<BulkCheckResponse>(post("/assets/bulk-upload-check", BulkCheckBody(part))).results }

const val BULK_CHECK_BATCH = 500

@Serializable data class TakenBetweenBody(
    val takenAfter: String,
    val takenBefore: String,
    val size: Int = 1000,
    val page: Int = 1,
    val withExif: Boolean = true,
)

/**
 * Every asset taken between [after] and [before] (ISO instants), with file name, size and
 * checksum: the details the timeline bucket leaves out. Pages through `POST /search/metadata`.
 */
suspend fun ImmichApi.assetsTakenBetween(after: String, before: String, maxPages: Int = 20): List<AssetDto> {
    val out = ArrayList<AssetDto>()
    var page = 1
    while (page <= maxPages) {
        val res: SearchResponseDto = call(post("/search/metadata", TakenBetweenBody(after, before, page = page)))
        out += res.assets.items
        page = res.assets.nextPage?.toIntOrNull() ?: break
    }
    return out
}

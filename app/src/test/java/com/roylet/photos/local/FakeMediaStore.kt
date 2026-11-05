package com.roylet.photos.local

import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * An in-memory MediaStore for the indexer tests: rows with a generation counter that bumps on
 * every insert, update and delete, like MediaStore's GENERATION_MODIFIED.
 */
class FakeMediaStore(var hasGenerationsFlag: Boolean = true) : MediaStoreReader {
    var version = "v1"
    var generation = 0L
    private val rows = LinkedHashMap<Long, MediaRow>()
    /** The `sinceGeneration` of every rows() query (null = full). */
    val queries = mutableListOf<Long?>()
    /** Sizes of the batches handed out. */
    val batches = mutableListOf<Int>()
    var idQueries = 0

    override val hasGenerations: Boolean get() = hasGenerationsFlag

    fun put(row: MediaRow): MediaRow {
        generation++
        val r = row.copy(generationModified = if (hasGenerationsFlag) generation else null, volume = "external_primary")
        rows[r.id] = r
        return r
    }

    fun update(id: Long, change: (MediaRow) -> MediaRow) = put(change(rows.getValue(id)))

    fun remove(id: Long) { rows.remove(id); generation++ }

    override fun version(): String = version
    override fun volumes(): Set<String> = setOf("external_primary")
    override fun generation(volume: String): Long? = if (hasGenerationsFlag) generation else null

    override suspend fun rows(volume: String?, sinceGeneration: Long?, batch: Int, onBatch: suspend (List<MediaRow>) -> Unit) {
        queries += sinceGeneration
        rows.values.filter { sinceGeneration == null || (it.generationModified ?: 0) > sinceGeneration }
            .chunked(batch).forEach { batches += it.size; onBatch(it) }
    }

    override fun ids(): LongArray { idQueries++; return rows.keys.toLongArray() }

    companion object {
        /** UTC millis of a wall-clock time in UTC. */
        fun utc(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long = LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

        fun photo(id: Long, taken: Long = utc(2026, 10, 1), bucket: Long = 1, bucketName: String = "Camera", name: String = "IMG_$id.jpg") = MediaRow(
            id = id, isVideo = false, dateTaken = taken, dateAdded = taken / 1000, dateModified = taken / 1000,
            width = 4000, height = 3000, mimeType = "image/jpeg", displayName = name, sizeBytes = 2_000_000,
            bucketId = bucket, bucketName = bucketName, relativePath = "DCIM/$bucketName/",
        )

        fun video(id: Long, taken: Long = utc(2026, 10, 1)) = photo(id, taken, name = "VID_$id.mp4").copy(isVideo = true, mimeType = "video/mp4", durationMs = 12_000)
    }
}

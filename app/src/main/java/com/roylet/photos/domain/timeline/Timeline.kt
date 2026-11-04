package com.roylet.photos.domain.timeline

/*
 * Pure-Kotlin timeline model (no Android imports, so it can move to a multiplatform module).
 */

/** Immich's `AssetVisibility`. */
enum class Visibility(val wire: String) { TIMELINE("timeline"), ARCHIVE("archive"), HIDDEN("hidden"), LOCKED("locked") }

/**
 * What a timeline shows. One grid + viewer implementation serves every screen that lists assets
 * by month: the main timeline, favourites, archive, trash, an album or a person.
 * Maps 1:1 onto the query parameters of `GET /timeline/buckets` and `/timeline/bucket`.
 */
data class TimelineFilter(
    val visibility: Visibility? = Visibility.TIMELINE,
    val isFavorite: Boolean? = null,
    val isTrashed: Boolean? = null,
    val albumId: String? = null,
    val personId: String? = null,
    val tagId: String? = null,
    val withStacked: Boolean = true,
    val withPartners: Boolean? = null,
    /** Newest first by default. */
    val newestFirst: Boolean = true,
) {
    fun toQuery(): List<Pair<String, String>> = buildList {
        visibility?.let { add("visibility" to it.wire) }
        isFavorite?.let { add("isFavorite" to it.toString()) }
        isTrashed?.let { add("isTrashed" to it.toString()) }
        albumId?.let { add("albumId" to it) }
        personId?.let { add("personId" to it) }
        tagId?.let { add("tagId" to it) }
        if (withStacked) add("withStacked" to "true")
        withPartners?.let { add("withPartners" to it.toString()) }
        add("order" to if (newestFirst) "desc" else "asc")
    }

    /** A compact, URL-safe form for navigation arguments; [decode] reverses it. */
    fun encode(): String = toQuery().joinToString("~") { (k, v) -> "$k.$v" }

    companion object {
        val Timeline = TimelineFilter()
        val Favorites = TimelineFilter(isFavorite = true)
        val Archive = TimelineFilter(visibility = Visibility.ARCHIVE)
        val Trash = TimelineFilter(visibility = null, isTrashed = true)
        fun album(id: String, newestFirst: Boolean = true) = TimelineFilter(visibility = null, albumId = id, newestFirst = newestFirst)
        fun person(id: String) = TimelineFilter(personId = id)
        fun tag(id: String) = TimelineFilter(tagId = id)

        fun decode(s: String): TimelineFilter {
            if (s.isBlank()) return Timeline
            val m = s.split("~").mapNotNull { part -> part.split(".", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            return TimelineFilter(
                visibility = m["visibility"]?.let { v -> Visibility.entries.firstOrNull { it.wire == v } },
                isFavorite = m["isFavorite"]?.toBooleanStrictOrNull(),
                isTrashed = m["isTrashed"]?.toBooleanStrictOrNull(),
                albumId = m["albumId"],
                personId = m["personId"],
                tagId = m["tagId"],
                withStacked = m["withStacked"] == "true",
                withPartners = m["withPartners"]?.toBooleanStrictOrNull(),
                newestFirst = m["order"] != "asc",
            )
        }
    }
}

/** One month in the timeline, from `GET /timeline/buckets`. [key] is passed back verbatim to fetch its assets. */
data class TimeBucket(val key: String, val count: Int) {
    val year: Int = key.take(4).toIntOrNull() ?: 1970
    /** 1-12. */
    val month: Int = key.drop(5).take(2).toIntOrNull()?.coerceIn(1, 12) ?: 1
}

/** One cell in the grid; the columnar `/timeline/bucket` response maps to a list of these. */
data class TimelineAsset(
    val id: String,
    /** Width / height. */
    val ratio: Float,
    val isImage: Boolean,
    val isFavorite: Boolean,
    val isTrashed: Boolean = false,
    val thumbhash: String? = null,
    /** Video length in ms; null for still images. */
    val durationMs: Long? = null,
    /** UTC epoch millis. */
    val fileCreatedAt: Long = 0,
    val localOffsetHours: Double = 0.0,
    val livePhotoVideoId: String? = null,
    val city: String? = null,
    val country: String? = null,
    /** Number of assets in this asset's stack, or 0 when it isn't stacked. */
    val stackCount: Int = 0,
    val visibility: Visibility = Visibility.TIMELINE,
) {
    val isVideo: Boolean get() = !isImage
    val isLivePhoto: Boolean get() = livePhotoVideoId != null
    /** When the photo was taken, in the photographer's local wall-clock time (as epoch millis "in UTC"). */
    val localTakenAt: Long get() = fileCreatedAt + (localOffsetHours * 3_600_000).toLong()
}

/**
 * An immutable view of a timeline: every bucket (with its count) and the assets of those buckets
 * that have been loaded so far. Assets are addressed by a stable *global index* across buckets,
 * so the viewer can page over the whole timeline before every month is loaded.
 */
class TimelineSnapshot(
    val buckets: List<TimeBucket>,
    val loaded: Map<String, List<TimelineAsset>>,
) {
    /** offsets[i] = global index of bucket i's first asset; offsets[size] = total. */
    val offsets: IntArray = IntArray(buckets.size + 1).also { o ->
        for (i in buckets.indices) o[i + 1] = o[i] + countOf(i)
    }
    val total: Int get() = offsets[buckets.size]

    /** A loaded bucket's real size can differ from the summary count (assets added or removed since). */
    fun countOf(bucketIndex: Int): Int = buckets[bucketIndex].let { b -> loaded[b.key]?.size ?: b.count }

    fun isLoaded(bucketIndex: Int) = buckets[bucketIndex].key in loaded

    fun assetsOf(bucketIndex: Int): List<TimelineAsset>? = loaded[buckets[bucketIndex].key]

    /** Which bucket holds global index [index]. */
    fun bucketIndexOf(index: Int): Int {
        if (buckets.isEmpty()) return -1
        var lo = 0
        var hi = buckets.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (offsets[mid] <= index) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The asset at a global index, or null while its bucket is still loading. */
    fun assetAt(index: Int): TimelineAsset? {
        if (index < 0 || index >= total) return null
        val b = bucketIndexOf(index)
        return assetsOf(b)?.getOrNull(index - offsets[b])
    }

    /** Global index of a loaded asset, or -1. */
    fun indexOf(id: String): Int {
        for (b in buckets.indices) {
            val list = assetsOf(b) ?: continue
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) return offsets[b] + i
        }
        return -1
    }

    companion object {
        val Empty = TimelineSnapshot(emptyList(), emptyMap())
    }
}

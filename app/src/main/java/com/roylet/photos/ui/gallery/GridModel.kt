package com.roylet.photos.ui.gallery

import com.roylet.photos.domain.timeline.JustifiedLayout
import com.roylet.photos.domain.timeline.JustifiedRow
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineSnapshot

/** Pinch levels, from biggest cells to the compact month overview. */
enum class GridDensity(val columns: Int, val squares: Boolean) {
    Large(3, false),
    Medium(5, false),
    Small(7, false),
    Months(11, true),
}

/** One LazyColumn item. */
sealed class GridItem(val bucket: Int, val key: String, val contentType: Int) {
    class Header(bucket: Int, bucketKey: String) : GridItem(bucket, "h:$bucketKey", 0)
    class Row(bucket: Int, bucketKey: String, val row: JustifiedRow, val assets: List<TimelineAsset>) : GridItem(bucket, "r:$bucketKey:${row.start}", 1)
    /** A month that hasn't been fetched, drawn at its estimated height; composing it triggers the fetch. */
    class Pending(bucket: Int, bucketKey: String, val heightPx: Float) : GridItem(bucket, "p:$bucketKey", 2)
}

class GridModel(val items: List<GridItem>, val headerIndex: IntArray, val snapshot: TimelineSnapshot, val spacingPx: Float) {
    /** LazyColumn index of the row holding global asset [index] (or its month's header). */
    fun itemIndexOfAsset(index: Int): Int {
        val b = snapshot.bucketIndexOf(index)
        if (b < 0) return 0
        val inBucket = index - snapshot.offsets[b]
        var i = headerIndex[b] + 1
        while (i < items.size) {
            val it = items[i]
            if (it.bucket != b) break
            if (it is GridItem.Row && inBucket < it.row.end) return i
            if (it is GridItem.Pending) return i
            i++
        }
        return headerIndex[b]
    }
}

/**
 * Builds the grid one month at a time. Row layouts are memoised per (month, asset list), so a
 * bucket finishing loading only lays out that bucket.
 */
class GridLayoutCache {
    private var width = 0f
    private var density: GridDensity? = null
    private var columnScale = 1f
    private val rows = HashMap<String, Pair<List<TimelineAsset>, List<JustifiedRow>>>()

    /**
     * @param columnScale widens the density on big screens (1 on a phone; e.g. 2.2 on a 900dp tablet),
     *   so "3 per row" means phone-sized cells rather than giant ones.
     */
    fun build(snapshot: TimelineSnapshot, widthPx: Float, density: GridDensity, spacingPx: Float, columnScale: Float = 1f): GridModel {
        if (widthPx != width || density != this.density || columnScale != this.columnScale) {
            rows.clear(); width = widthPx; this.density = density; this.columnScale = columnScale
        }
        val columns = columnsFor(density, columnScale)
        val target = targetHeight(widthPx, columns, spacingPx)
        val items = ArrayList<GridItem>(snapshot.buckets.size * 4)
        val headers = IntArray(snapshot.buckets.size)
        snapshot.buckets.forEachIndexed { b, bucket ->
            headers[b] = items.size
            items += GridItem.Header(b, bucket.key)
            val assets = snapshot.assetsOf(b)
            if (assets == null) {
                val h = if (density.squares) {
                    val side = (widthPx - spacingPx * (columns - 1)) / columns
                    val n = (bucket.count + columns - 1) / columns
                    n * side + (n - 1).coerceAtLeast(0) * spacingPx
                } else JustifiedLayout.estimateHeight(bucket.count, widthPx, target, spacingPx)
                items += GridItem.Pending(b, bucket.key, h)
            } else {
                val cached = rows[bucket.key]
                val laid = if (cached != null && cached.first === assets) cached.second else {
                    val r = if (density.squares) JustifiedLayout.squares(assets.size, widthPx, columns, spacingPx)
                    else JustifiedLayout.layout(FloatArray(assets.size) { assets[it].ratio }, widthPx, target, spacingPx)
                    rows[bucket.key] = assets to r
                    r
                }
                laid.forEach { items += GridItem.Row(b, bucket.key, it, assets) }
            }
        }
        return GridModel(items, headers, snapshot, spacingPx)
    }

    companion object {
        fun columnsFor(density: GridDensity, columnScale: Float): Int = (density.columns * columnScale.coerceAtLeast(1f)).toInt().coerceAtLeast(1)

        /** Row height that gives roughly [columns] typical (4:3-ish) photos per row. */
        fun targetHeight(widthPx: Float, columns: Int, spacingPx: Float): Float =
            (widthPx - spacingPx * (columns - 1)) / columns * 0.8f
    }
}

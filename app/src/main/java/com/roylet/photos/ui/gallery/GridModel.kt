package com.roylet.photos.ui.gallery

import com.roylet.photos.domain.timeline.JustifiedLayout
import com.roylet.photos.domain.timeline.JustifiedRow
import androidx.compose.runtime.Immutable
import kotlin.math.ceil
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineSnapshot

/**
 * Pinch levels, from biggest cells to the compact month overview. [columns] is the number of typical
 * photos per row on a phone (scaled up by width on bigger screens).
 *
 * @param closeUp the one- and two-up levels: rows hold at most [columns] photos, are capped at
 *   [viewportCap] of the viewport height (so a portrait can't become a wall), and load the sharper
 *   preview rendition instead of the thumbnail.
 */
enum class GridDensity(val columns: Int, val squares: Boolean, val closeUp: Boolean = false, val viewportCap: Float = 0f) {
    /** One photo per row: a full-width feed. */
    Feed(1, false, closeUp = true, viewportCap = 0.7f),
    Duo(2, false, closeUp = true, viewportCap = 0.5f),
    Large(3, false),
    Medium(5, false),
    Small(7, false),
    Months(11, true),
}

/** One LazyColumn item. Never mutated once built. */
@Immutable
sealed class GridItem(val bucket: Int, val key: String, val contentType: Int) {
    class Header(bucket: Int, bucketKey: String) : GridItem(bucket, "h:$bucketKey", 0)
    class Row(bucket: Int, bucketKey: String, val row: JustifiedRow, val assets: List<TimelineAsset>) : GridItem(bucket, "r:$bucketKey:${row.start}", 1)
    /**
     * A month that hasn't been fetched, drawn at its estimated height; composing it triggers the
     * fetch. A big month is split into [parts] placeholders of [heightPx] each (this is part
     * [part]), since one LazyColumn item can't be taller than Compose's Constraints allow
     * (~262k px): a 400-photo month in the one-up feed would be ~350k px and crash the grid.
     */
    class Pending(bucket: Int, bucketKey: String, val heightPx: Float, val part: Int = 0, val parts: Int = 1) :
        GridItem(bucket, if (part == 0) "p:$bucketKey" else "p:$bucketKey:$part", 2) {
        /** The whole month's estimated height. */
        val monthHeightPx: Float get() = heightPx * parts
    }
}

class GridModel(val items: List<GridItem>, val headerIndex: IntArray, val snapshot: TimelineSnapshot, val spacingPx: Float) {
    /** Which of a pending month's [parts] placeholders holds its asset [inBucket] (of [count]), by estimated position. */
    private fun pendingPartOf(inBucket: Int, count: Int, parts: Int): Int =
        if (parts <= 1 || count <= 0) 0 else (inBucket.toLong() * parts / count).toInt().coerceIn(0, parts - 1)

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
            if (it is GridItem.Pending) return i + pendingPartOf(inBucket, snapshot.countOf(b), it.parts)
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
    private var viewportHeight = 0f
    private val rows = HashMap<String, Laid>()

    /**
     * A month's laid-out rows, as the very [GridItem.Row] instances handed to the grid: reusing them
     * when another month loads (or a favourite changes elsewhere) lets each visible `GridRow` skip
     * recomposition, since its item is the same object.
     */
    private class Laid(val assets: List<TimelineAsset>, val bucket: Int, val items: List<GridItem.Row>)

    /**
     * @param columnScale widens the density on big screens (1 on a phone; e.g. 2.2 on a 900dp tablet),
     *   so "3 per row" means phone-sized cells rather than giant ones.
     */
    fun build(
        snapshot: TimelineSnapshot,
        widthPx: Float,
        density: GridDensity,
        spacingPx: Float,
        columnScale: Float = 1f,
        /** The grid's visible height; caps the close-up levels' row height. 0 = unknown (no cap beyond the default). */
        viewportHeightPx: Float = 0f,
    ): GridModel {
        if (widthPx != width || density != this.density || columnScale != this.columnScale || viewportHeightPx != viewportHeight) {
            rows.clear(); width = widthPx; this.density = density; this.columnScale = columnScale; viewportHeight = viewportHeightPx
        }
        val columns = columnsFor(density, columnScale)
        val target = targetHeight(widthPx, columns, spacingPx)
        val maxPerRow = if (density.closeUp) columns else Int.MAX_VALUE
        val maxHeight = maxRowHeight(density, target, viewportHeightPx)
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
                val parts = ceil(h / MAX_PENDING_PX).toInt().coerceAtLeast(1)
                for (p in 0 until parts) items += GridItem.Pending(b, bucket.key, h / parts, p, parts)
            } else {
                val cached = rows[bucket.key]
                val laid = if (cached != null && cached.assets === assets && cached.bucket == b) cached.items else {
                    val r = if (density.squares) JustifiedLayout.squares(assets.size, widthPx, columns, spacingPx)
                    else JustifiedLayout.layout(FloatArray(assets.size) { assets[it].ratio }, widthPx, target, spacingPx, maxHeight, maxPerRow)
                    r.map { GridItem.Row(b, bucket.key, it, assets) }.also { rows[bucket.key] = Laid(assets, b, it) }
                }
                items.addAll(laid)
            }
        }
        return GridModel(items, headers, snapshot, spacingPx)
    }

    companion object {
        /**
         * The tallest a single pending placeholder gets. Compose can't measure an item much taller
         * than 262,142 px (at phone widths), so a big unloaded month is split well below that.
         */
        const val MAX_PENDING_PX = 32_768f

        fun columnsFor(density: GridDensity, columnScale: Float): Int = (density.columns * columnScale.coerceAtLeast(1f)).toInt().coerceAtLeast(1)

        /** Row height that gives roughly [columns] typical (3:2-ish) photos per row. */
        fun targetHeight(widthPx: Float, columns: Int, spacingPx: Float): Float =
            JustifiedLayout.targetHeight(widthPx, columns, spacingPx)

        /**
         * The tallest a row may get. Close-up levels let a pair of portraits grow past the target to
         * fill the width, but never past [GridDensity.viewportCap] of the viewport.
         */
        fun maxRowHeight(density: GridDensity, target: Float, viewportHeightPx: Float): Float = when {
            !density.closeUp -> target * 1.6f
            viewportHeightPx > 0f -> viewportHeightPx * density.viewportCap
            else -> target * 1.6f
        }
    }
}

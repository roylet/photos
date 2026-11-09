package com.roylet.photos.gallery

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import coil3.compose.LocalAsyncImagePreviewHandler
import android.graphics.Bitmap
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import com.roylet.photos.domain.timeline.TimeBucket
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.domain.timeline.TimelineSource
import com.roylet.photos.ui.gallery.GridDensity
import com.roylet.photos.ui.gallery.TimelineGrid
import com.roylet.photos.ui.theme.PhotosTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Keeps scrolling down a big timeline (50 months x 500 items) while months load on a background
 * thread, merged-style: a loaded month's size differs from the count the month list announced.
 * Before the fix the one-up feed crashed as soon as an unloaded ~400-photo month came into view:
 * its placeholder was one ~320k px item, past what Compose can measure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class TimelineScrollStressTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** One shared bitmap: the screenshot fake paints (and keeps) one per asset, which a 25k-item timeline can't afford. */
    private val tinyBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private val tiny = AsyncImagePreviewHandler { tinyBitmap.asImage() }

    private class BigSource(val months: Int = 50, val perMonth: Int = 500, val loadDelayMs: Long = 5) : TimelineSource {
        val buckets = List(months) { i ->
            val y = 2026 - i / 12
            val m = 12 - i % 12
            TimeBucket("%04d-%02d-01T00:00:00.000Z".format(y, m), perMonth)
        }
        override suspend fun buckets(filter: TimelineFilter) = buckets
        override suspend fun bucket(filter: TimelineFilter, key: String): List<TimelineAsset> {
            delay(loadDelayMs)
            val rnd = Random(key.hashCode())
            // Merged months come back bigger or smaller than estimated.
            val n = perMonth + rnd.nextInt(-120, 121)
            return List(n) { i -> TimelineAsset(id = "${key.take(7)}-$i", ratio = if (i % 3 == 0) 0.75f else 1.5f, isImage = true, isFavorite = false) }
        }
    }

    private fun scrollThrough(density: GridDensity, swipes: Int, jumps: Int = 0) {
        val source = BigSource()
        val repo = TimelineRepository(source, TimelineFilter.Timeline, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides tiny) {
                PhotosTheme(dark = false) { TimelineGrid(repo, onOpen = { _, _ -> }, state = state, initialDensity = density) }
            }
        }
        compose.waitUntil(5_000) { repo.snapshot.value.buckets.isNotEmpty() }
        repeat(swipes) {
            compose.onNodeWithTag("timeline-grid").performTouchInput { swipeUp(durationMillis = 120) }
            compose.waitForIdle()
        }
        // Scrolling down got somewhere (and didn't throw).
        assertTrue(compose.runOnIdle { state.firstVisibleItemIndex } > 0)
        // Scrubber-style jumps deep into months that are still loading.
        val rnd = Random(7)
        repeat(jumps) {
            compose.runOnIdle { state.requestScrollToItem(rnd.nextInt(state.layoutInfo.totalItemsCount.coerceAtLeast(1)), rnd.nextInt(0, 4000)) }
            compose.waitForIdle()
            compose.onNodeWithTag("timeline-grid").performTouchInput { swipeUp(durationMillis = 120) }
            compose.waitForIdle()
        }
    }

    @Test fun feedLevelScrollsThroughUnloadedMonths() = scrollThrough(GridDensity.Feed, 40, jumps = 20)

    @Test fun duoLevelScrollsThroughUnloadedMonths() = scrollThrough(GridDensity.Duo, 40)

    @Test fun mediumLevelScrollsWhileMonthsLoad() = scrollThrough(GridDensity.Medium, 60, jumps = 20)
}

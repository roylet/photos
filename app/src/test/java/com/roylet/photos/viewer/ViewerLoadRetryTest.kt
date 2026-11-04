package com.roylet.photos.viewer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.request.ErrorResult
import com.roylet.photos.FakeGallery
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.theme.PhotosTheme
import com.roylet.photos.ui.viewer.ViewerScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A preview that fails (timeout, 5xx, address switch) must not leave the page blank: the thumbnail
 * stays up with a visible error, and an automatic retry (or a tap) brings the preview in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class ViewerLoadRetryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = ConcurrentHashMap<String, AtomicInteger>()

    /** Previews fail the first [failures] times; everything else comes from the fake gallery. */
    private fun handler(failures: Int) = AsyncImagePreviewHandler { loader, request ->
        val url = request.data.toString()
        val n = calls.getOrPut(url) { AtomicInteger() }.incrementAndGet()
        if (url.startsWith("fake://preview/") && n <= failures) {
            AsyncImagePainter.State.Error(null, ErrorResult(null, request, IOException("timeout")))
        } else {
            FakeGallery.previewHandler.handle(loader, request)
        }
    }

    private fun open(failures: Int): String {
        val repo = FakeGallery.repository(loadAll = true)
        val s = repo.snapshot.value
        fun photo(i: Int) = s.assetAt(i)?.let { a -> a.isImage && !a.isLivePhoto } == true
        // A photo with photos either side, so all three composed pages are photo pages.
        val index = (3 until s.total - 1).first { photo(it - 1) && photo(it) && photo(it + 1) }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides handler(failures), LocalReducedMotion provides true) {
                PhotosTheme(dark = true) { ViewerScreen(repo, startIndex = index, onBack = {}, onEdit = {}) }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        return s.assetAt(index)!!.id
    }

    private fun previewCalls(id: String) = calls["fake://preview/$id"]?.get() ?: 0

    @Test fun failedPreviewShowsThumbnailAndErrorThenRetriesAutomatically() {
        val id = open(failures = 1)
        assertEquals(1, previewCalls(id))
        assertTrue("thumbnail floor requested", (calls["fake://thumbnail/$id"]?.get() ?: 0) >= 1)
        compose.onNodeWithTag("viewer-photo-$id", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("viewer-load-error-$id", useUnmergedTree = true).assertIsDisplayed()

        compose.mainClock.advanceTimeBy(1_200) // first backoff step
        assertEquals(2, previewCalls(id))
        compose.onNodeWithTag("viewer-load-error-$id", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun neighbourPagesRetryToo() {
        open(failures = 1)
        compose.mainClock.advanceTimeBy(1_200)
        // The pager composes one page either side; their previews were retried as well.
        val retried = calls.filterKeys { it.startsWith("fake://preview/") }.values.count { it.get() >= 2 }
        assertTrue("current and neighbour previews retried: $retried", retried >= 3)
    }

    @Test fun tapRetriesOnceAutomaticRetriesRunOut() {
        val id = open(failures = 5)
        compose.mainClock.advanceTimeBy(1_000 + 2_000 + 4_000 + 8_000 + 500)
        assertEquals(5, previewCalls(id))
        val error = compose.onNodeWithTag("viewer-load-error-$id", useUnmergedTree = true)
        error.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(20_000) // no more automatic attempts
        assertEquals(5, previewCalls(id))
        error.performClick()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(6, previewCalls(id))
        compose.onNodeWithTag("viewer-load-error-$id", useUnmergedTree = true).assertDoesNotExist()
    }
}

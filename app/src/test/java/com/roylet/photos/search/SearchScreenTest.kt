package com.roylet.photos.search

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import com.roylet.photos.ui.Routes
import com.roylet.photos.ui.components.LocalTabReselects
import com.roylet.photos.ui.components.LocalTabRoot
import com.roylet.photos.ui.components.TabReselects
import androidx.compose.ui.test.performClick
import coil3.compose.LocalAsyncImagePreviewHandler
import com.roylet.photos.FakeGallery
import com.roylet.photos.FakeLibrary
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.ExploreItem
import com.roylet.photos.domain.library.ExploreSection
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineRegistry
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.ui.search.SearchModel
import com.roylet.photos.ui.search.SearchQuery
import com.roylet.photos.ui.search.SearchResult
import com.roylet.photos.ui.search.SearchScreen
import com.roylet.photos.ui.theme.PhotosTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Search tab with results and discover rows that repeat ids, and with failing searches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val registry = TimelineRegistry(FakeGallery.source).apply { context = Dispatchers.Unconfined }
    private val fake = FakeLibrary()
    private val some = FakeGallery.assetsOf(FakeGallery.buckets[0].key).take(3)

    /** Smart search answers [answer]; explore repeats a place. */
    private fun library(answer: suspend () -> List<TimelineAsset>): LibrarySource = object : LibrarySource by fake {
        override suspend fun smartSearch(query: String): List<TimelineAsset> = answer()
        override suspend fun explore(): List<ExploreSection> = listOf(
            ExploreSection("exifInfo.city", listOf(ExploreItem("Hobart", some[0]), ExploreItem("Hobart", some[1]), ExploreItem("Sydney", some[2]))),
        )
    }

    private val reselects = TabReselects()

    private fun show(model: SearchModel) = compose.setContent {
        CompositionLocalProvider(
            LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides FakeGallery.previewHandler,
            LocalTabReselects provides reselects, LocalTabRoot provides Routes.SEARCH,
        ) {
            PhotosTheme(dark = false) { SearchScreen(model, { registry.get(it) }, { _, _, _ -> }, {}, {}) }
        }
    }

    @Test fun `results with repeated ids show once each and don't crash`() {
        val lib = library { some + some + listOf(some[0]) }
        val model = SearchModel({ lib }, registry, UiPrefs(null), CoroutineScope(Dispatchers.Unconfined))
        show(model)
        compose.waitForIdle()
        compose.onNodeWithText("Sydney").assertExists() // the repeated "Hobart" place didn't crash the row
        compose.runOnUiThread { model.submit(SearchQuery.Text("beach")) }
        compose.waitForIdle()
        val done = model.result as SearchResult.Done
        assertEquals(3, done.count)
        compose.onNodeWithText("3 results", substring = true).assertExists()
    }

    @Test fun `a failed search shows the error, copies its details, and retries`() {
        var calls = 0
        val lib = library {
            calls++
            if (calls == 1) throw ImmichException("Immich had a problem (HTTP 500): boom", 500) else some
        }
        val model = SearchModel({ lib }, registry, UiPrefs(null), CoroutineScope(Dispatchers.Unconfined))
        show(model)
        compose.runOnUiThread { model.submit(SearchQuery.Text("beach")) }
        compose.waitForIdle()
        compose.onNodeWithText("Search failed").assertExists()
        compose.onNodeWithText("COPY DETAILS").performClick()
        compose.waitForIdle()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(clip, clip.contains("HTTP 500") && clip.contains("ImmichException") && clip.contains("beach"))
        compose.onNodeWithText("TRY AGAIN").performClick()
        compose.waitForIdle()
        assertEquals(3, (model.result as SearchResult.Done).count)
    }

    @Test fun `a decoding error or a crash in the source is an inline error, not a crash`() {
        val errors = listOf<Throwable>(
            kotlinx.serialization.SerializationException("Field 'items' is required"),
            IllegalStateException("bad state"),
            NullPointerException(),
        )
        for (e in errors) {
            val lib = library { throw e }
            val model = SearchModel({ lib }, registry, UiPrefs(null), CoroutineScope(Dispatchers.Unconfined))
            model.submit(SearchQuery.Text("x"))
            val failed = model.result as SearchResult.Failed
            assertTrue(failed.message.isNotBlank())
            assertTrue(failed.details, failed.details.contains(e.javaClass.name))
        }
    }

    @Test fun `re-tapping the Search tab at the top focuses the search field`() {
        val lib = library { some }
        val model = SearchModel({ lib }, registry, UiPrefs(null), CoroutineScope(Dispatchers.Unconfined))
        show(model)
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnUiThread { reselects.reselect(Routes.SEARCH) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        // With results showing (already at their top) it focuses too, and never crashes.
        compose.runOnUiThread { model.submit(SearchQuery.Text("beach")) }
        compose.waitForIdle()
        compose.runOnUiThread { reselects.reselect(Routes.SEARCH) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test fun `discover rows that fail are left out`() {
        val lib = object : LibrarySource by fake {
            override suspend fun people() = throw ImmichException("nope", 500)
            override suspend fun explore() = throw kotlinx.serialization.SerializationException("bad")
            override suspend fun suggestions(type: String) = throw java.io.IOException("offline")
        }
        val model = SearchModel({ lib }, registry, UiPrefs(null), CoroutineScope(Dispatchers.Unconfined))
        model.loadDiscover()
        assertTrue(model.people.isEmpty() && model.explore.isEmpty() && model.countries.isEmpty())
    }
}

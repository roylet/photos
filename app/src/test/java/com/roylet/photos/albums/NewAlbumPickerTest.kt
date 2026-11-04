package com.roylet.photos.albums

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onFirst
import com.roylet.photos.ui.actions.LibraryActions
import com.roylet.photos.ui.actions.ShareLauncher
import com.roylet.photos.ui.components.SnackbarController
import java.io.File
import coil3.compose.LocalAsyncImagePreviewHandler
import com.roylet.photos.FakeGallery
import com.roylet.photos.FakeLibrary
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.ui.albums.AlbumPhotoPicker
import com.roylet.photos.ui.albums.AlbumsScreen
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.gallery.GridSelection
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
import org.robolectric.annotation.GraphicsMode

/** "New album" from the Albums tab: name it, then pick photos (or skip). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class NewAlbumPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun picker(selection: GridSelection, onAdd: (List<String>) -> Unit = {}, onSkip: () -> Unit = {}, onBack: () -> Unit = {}) {
        val repo = FakeGallery.repository(loadAll = true)
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides FakeGallery.previewHandler,
                LocalReducedMotion provides true,
            ) { PhotosTheme { AlbumPhotoPicker("Beach day", repo, onAdd, onSkip, onBack, selection = selection) } }
        }
        compose.waitForIdle()
    }

    @Test fun namingAnAlbumOpensThePickerInsteadOfCreatingItEmpty() {
        val library = FakeLibrary()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val store = AlbumStore({ library }, scope)
        val actions = LibraryActions(
            { library }, store, SnackbarController(), scope, favoriteCall = { _, _ -> },
            launcher = object : ShareLauncher {
                override fun sendFiles(files: List<File>) {}
                override fun sendText(text: String) {}
                override fun copy(text: String) {}
            },
            prefs = UiPrefs(null), shareDir = File(System.getProperty("java.io.tmpdir"), "share-picker-test"),
        )
        var named: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                PhotosTheme { AlbumsScreen(store, actions, UiPrefs(null), onOpen = {}, onNewAlbum = { named = it }) }
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(hasText("NEW ALBUM") and hasClickAction()).onFirst().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("  Beach day ")
        compose.onNodeWithText("NEXT").performClick()
        compose.waitForIdle()
        assertEquals("Beach day", named)
        assertTrue(library.calls.none { it.startsWith("createAlbum") })
    }

    @Test fun tappingCellsSelectsWithoutOpeningAndAddPassesTheIds() {
        val selection = GridSelection(picking = true)
        var added: List<String>? = null
        picker(selection, onAdd = { added = it })
        compose.onNodeWithText("ADD").assertIsNotEnabled()
        // Below the first month header (52dp), on the first row's first cell.
        compose.onNodeWithTag("album-picker-grid").performTouchInput { click(Offset(30f * density, 90f * density)) }
        compose.waitForIdle()
        assertEquals(1, selection.ids.size)
        compose.onNodeWithText("ADD (1)").assertIsEnabled().performClick()
        assertEquals(selection.ids.toList(), added)
    }

    @Test fun preselectedCountShowsOnTheButton() {
        val first = FakeGallery.assetsOf(FakeGallery.buckets[0].key)
        val selection = GridSelection(picking = true).apply { set(first.take(3).map { it.id }.toSet()) }
        var added: List<String>? = null
        picker(selection, onAdd = { added = it })
        compose.onNodeWithText("ADD (3)").performClick()
        assertEquals(first.take(3).map { it.id }, added)
    }

    @Test fun skipCreatesItEmpty() {
        var skipped = false
        picker(GridSelection(picking = true), onSkip = { skipped = true })
        compose.onNodeWithText("SKIP").performClick()
        assertTrue(skipped)
    }
}

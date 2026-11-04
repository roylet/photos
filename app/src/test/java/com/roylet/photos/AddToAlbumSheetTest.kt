package com.roylet.photos

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.ui.actions.ActionSheets
import com.roylet.photos.ui.actions.LibraryActions
import com.roylet.photos.ui.actions.ShareLauncher
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.components.SnackbarController
import com.roylet.photos.ui.theme.PhotosTheme
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The add-to-album sheet: toggling an album and creating a new album inline with the selection. */
@RunWith(RobolectricTestRunner::class)
// Native graphics: hit testing inside rounded clips needs real Path.contains.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class AddToAlbumSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val library = FakeLibrary()
    private val snackbar = SnackbarController()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val actions = LibraryActions(
        { library }, AlbumStore({ library }, scope), snackbar, scope, favoriteCall = { _, _ -> },
        launcher = object : ShareLauncher {
            override fun sendFiles(files: List<File>) {}
            override fun sendText(text: String) {}
            override fun copy(text: String) {}
        },
        prefs = UiPrefs(null), shareDir = File(System.getProperty("java.io.tmpdir"), "share-ui-test"),
    )

    private fun show(ids: List<String>) {
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) { PhotosTheme { ActionSheets(actions) } }
        }
        compose.runOnIdle { actions.addToAlbum(ids, null) }
        compose.waitForIdle()
    }

    @Test fun `inline new album is created with the selected assets`() {
        show(listOf("x-1", "x-2"))
        compose.onNodeWithText("Add to album").assertExists()
        compose.onNodeWithTag("newAlbum").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("newAlbumName"))).performTextInput("Beach day")
        compose.onNodeWithTag("createAlbum").performClick()
        compose.waitForIdle()

        assertTrue(library.calls.contains("createAlbum Beach day x-1,x-2"))
        assertNull("sheet closes", actions.sheet)
        assertEquals("Added 2 items to new album Beach day", snackbar.current?.message)
        assertEquals("Beach day", actions.albums.albums.value!!.first().name)
    }

    @Test fun `tapping an album adds the selection to it`() {
        show(listOf("x-1"))
        compose.onNodeWithText("Kyoto").performClick()
        compose.waitForIdle()
        assertEquals("addToAlbum al-2 x-1", library.calls.last())
        assertEquals("Added 1 item to Kyoto", snackbar.current?.message)
        assertEquals(97, actions.albums.find("al-2")!!.assetCount)
    }
}

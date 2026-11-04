package com.roylet.photos.gallery

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.gallery.SelectionAction
import com.roylet.photos.ui.gallery.SelectionScope
import com.roylet.photos.ui.gallery.SelectionTopBar
import com.roylet.photos.ui.gallery.defaultSelectionActions
import com.roylet.photos.ui.gallery.planSelectionBar
import com.roylet.photos.ui.theme.PhotosTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Native graphics: the bar measures its label with real font metrics.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class SelectionBarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun everythingFitsWhenThereIsRoom() {
        val plan = planSelectionBar(listOf(true, false, true, false, true), availablePx = 500f, buttonPx = 43f, gapPx = 2f)
        assertEquals(listOf(0, 1, 2, 3, 4), plan.inline)
        assertTrue(plan.overflow.isEmpty())
    }

    @Test fun secondariesMoveToTheMenuFirstAndOrderIsKept() {
        // Room for four buttons: three primaries plus the ⋯ button.
        val plan = planSelectionBar(listOf(true, false, true, false, true), availablePx = 4 * 43f + 3 * 2f, buttonPx = 43f, gapPx = 2f)
        assertEquals(listOf(0, 2, 4), plan.inline)
        assertEquals(listOf(1, 3), plan.overflow)
    }

    @Test fun primariesGoLastFirstWhenEvenTheyDontFit() {
        val plan = planSelectionBar(listOf(true, false, true, false, true), availablePx = 2 * 43f + 2f, buttonPx = 43f, gapPx = 2f)
        assertEquals(listOf(0), plan.inline)
        assertEquals(listOf(1, 2, 3, 4), plan.overflow)
    }

    @Test fun noRoomAtAllPutsEverythingInTheMenu() {
        val plan = planSelectionBar(listOf(true, true), availablePx = 10f, buttonPx = 43f, gapPx = 2f)
        assertTrue(plan.inline.isEmpty())
        assertEquals(listOf(0, 1), plan.overflow)
    }

    @Test fun phoneBarReadsSixSelectedAndOffersTheRestInAMenu() {
        var shared = false
        var archived = false
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                PhotosTheme(dark = false) {
                    val defaults = defaultSelectionActions(SelectionScope(List(6) { "a$it" }, null, clear = {}))
                    val actions = defaults.map { a ->
                        when (a.label) {
                            "Share" -> SelectionAction(Icons.Rounded.Share, a.label, { shared = true })
                            "Archive" -> SelectionAction(a.icon, a.label, { archived = true }, primary = false)
                            else -> a
                        }
                    }
                    SelectionTopBar(6, onClose = {}, actions = actions)
                }
            }
        }
        compose.onNodeWithText("6 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Share").performClick()
        assertTrue(shared)
        compose.onNodeWithContentDescription("Archive").assertDoesNotExist()
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("ARCHIVE").performClick()
        assertTrue(archived)
    }
}

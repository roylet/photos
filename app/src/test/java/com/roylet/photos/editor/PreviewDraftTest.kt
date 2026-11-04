package com.roylet.photos.editor

import android.graphics.Bitmap
import com.roylet.photos.editor.gl.EditRenderer
import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.ui.EditorSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Draft-resolution frames during gestures, and the compare crossfade state, without a GPU. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewDraftTest {
    private fun session() = EditorSession(Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888))

    @Test fun draftScaleCapsTheLongEdge() {
        assertEquals(1f, EditRenderer.draftScale(800, 600), 0f)
        assertEquals(1024f / 2048f, EditRenderer.draftScale(1080, 2048), 1e-6f)
        assertEquals(1024f / 1600f, EditRenderer.draftScale(1600, 1000), 1e-6f)
    }

    @Test fun framesDuringAGestureAreDraftsUntilItSettles() {
        val s = session()
        val r = s.renderer!!
        var frames = 0
        s.invalidate = { frames++ }

        s.history.beginGesture()
        assertTrue(s.history.gesturing.value)
        s.history.update { it.copy(light = Light(exposure = 0.4f)) }
        s.push(s.history.recipe)
        assertTrue("slider frames render at draft resolution", r.isDraft)

        s.history.endGesture()
        assertFalse(s.history.gesturing.value)
        val before = frames
        s.endDraft()
        assertFalse(r.isDraft)
        assertEquals("one full-quality frame after the gesture", before + 1, frames)
        s.endDraft()
        assertEquals("no extra frame when already at full quality", before + 1, frames)

        // A change outside a gesture (a tap, undo) is full quality straight away.
        s.history.update { it.copy(light = Light(exposure = -0.2f)) }
        s.push(s.history.recipe)
        assertFalse(r.isDraft)
    }

    @Test fun cancelledGestureAlsoEndsDrafting() {
        val s = session()
        s.history.beginGesture()
        s.history.cancelGesture()
        assertFalse(s.history.gesturing.value)
    }

    @Test fun compareMixReachesTheRenderer() {
        val s = session()
        val r = s.renderer!!
        s.setCompareMix(0.5f)
        assertEquals(0.5f, r.compareMix, 0f)
        s.setShowOriginal(true)
        assertEquals(1f, r.compareMix, 0f)
        s.setShowOriginal(false)
        assertEquals(0f, r.compareMix, 0f)
    }
}

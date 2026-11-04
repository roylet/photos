package com.roylet.photos.editor

import com.roylet.photos.editor.recipe.History
import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.recipe.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private fun exp(v: Float) = Recipe(light = Light(exposure = v))

    @Test fun discreteStepsUndoAndRedo() {
        val h = History()
        assertFalse(h.canUndo.value)
        h.set(exp(1f)); h.set(exp(2f))
        assertTrue(h.canUndo.value)
        assertTrue(h.undo()); assertEquals(exp(1f), h.current.value)
        assertTrue(h.canRedo.value)
        assertTrue(h.undo()); assertEquals(Recipe(), h.current.value)
        assertFalse(h.undo())
        assertTrue(h.redo()); assertTrue(h.redo())
        assertEquals(exp(2f), h.current.value)
        assertFalse(h.canRedo.value)
    }

    @Test fun gestureCoalescesIntoOneStep() {
        val h = History()
        h.beginGesture()
        for (i in 1..50) h.set(exp(i / 10f))
        assertEquals(exp(5f), h.current.value)
        assertFalse(h.canUndo.value) // nothing recorded mid-drag
        h.endGesture()
        assertTrue(h.canUndo.value)
        h.undo()
        assertEquals(Recipe(), h.current.value)
        assertFalse(h.canUndo.value)
    }

    @Test fun gestureWithoutNetChangeRecordsNothing() {
        val h = History()
        h.beginGesture(); h.set(exp(1f)); h.set(Recipe()); h.endGesture()
        assertFalse(h.canUndo.value)
    }

    @Test fun newStepClearsRedo() {
        val h = History()
        h.set(exp(1f)); h.undo()
        assertTrue(h.canRedo.value)
        h.update { it.copy(light = it.light.copy(contrast = 10f)) }
        assertFalse(h.canRedo.value)
    }

    @Test fun cancelGestureRestores() {
        val h = History(exp(1f))
        h.beginGesture(); h.set(exp(3f)); h.cancelGesture()
        assertEquals(exp(1f), h.current.value)
        assertFalse(h.canUndo.value)
    }

    @Test fun undoDuringGestureCommitsItFirst() {
        val h = History()
        h.beginGesture(); h.set(exp(2f))
        h.undo()
        assertEquals(Recipe(), h.current.value)
        assertTrue(h.canRedo.value)
    }

    @Test fun boundedDepth() {
        val h = History(maxSteps = 3)
        for (i in 1..10) h.set(exp(i.toFloat()))
        var n = 0
        while (h.undo()) n++
        assertEquals(3, n)
        assertEquals(exp(7f), h.current.value)
    }
}

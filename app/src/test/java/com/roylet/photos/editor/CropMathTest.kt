package com.roylet.photos.editor

import com.roylet.photos.editor.recipe.CropRect
import com.roylet.photos.editor.ui.CropMath
import com.roylet.photos.editor.ui.CropMath.Handle
import org.junit.Assert.assertEquals
import org.junit.Test

class CropMathTest {
    private val eps = 1e-4f
    private fun assertRect(e: CropRect, a: CropRect) {
        assertEquals(e.left, a.left, eps); assertEquals(e.top, a.top, eps)
        assertEquals(e.right, a.right, eps); assertEquals(e.bottom, a.bottom, eps)
    }

    @Test fun `free corner drag moves two edges and clamps to the frame`() {
        assertRect(CropRect(0.2f, 0.1f, 1f, 1f), CropMath.drag(CropRect(), Handle.TOP_LEFT, 0.2f, 0.1f, null))
        assertRect(CropRect(0f, 0f, 1f, 1f), CropMath.drag(CropRect(), Handle.TOP_LEFT, -0.5f, -0.5f, null))
    }

    @Test fun `edges snap to the frame`() {
        val c = CropMath.drag(CropRect(0.2f, 0.2f, 0.8f, 0.8f), Handle.LEFT, -0.19f, 0f, null, snap = 0.02f)
        assertEquals(0f, c.left, 0f)
        assertEquals(1, CropMath.edgeMask(c))
    }

    @Test fun `move keeps size and stays inside`() {
        assertRect(CropRect(0.5f, 0f, 1f, 0.5f), CropMath.drag(CropRect(0.2f, 0.2f, 0.7f, 0.7f), Handle.MOVE, 0.9f, -0.9f, null))
    }

    @Test fun `locked corner keeps the ratio`() {
        val c = CropMath.drag(CropRect(), Handle.BOTTOM_RIGHT, -0.5f, -0.1f, ratio = 1f)
        assertEquals(c.width, c.height, eps)
    }

    @Test fun `fit aspect is the largest centred rect`() {
        assertRect(CropRect(0.25f, 0f, 0.75f, 1f), CropMath.fitAspect(CropRect(), 0.5f))
        assertRect(CropRect(0f, 0.25f, 1f, 0.75f), CropMath.fitAspect(CropRect(), 2f))
    }
}

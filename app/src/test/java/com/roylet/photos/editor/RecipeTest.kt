package com.roylet.photos.editor

import com.roylet.photos.editor.recipe.ColorAdjust
import com.roylet.photos.editor.recipe.CropRect
import com.roylet.photos.editor.recipe.CurvePoint
import com.roylet.photos.editor.recipe.Curves
import com.roylet.photos.editor.recipe.Effects
import com.roylet.photos.editor.recipe.Filter
import com.roylet.photos.editor.recipe.Geometry
import com.roylet.photos.editor.recipe.HslAdjust
import com.roylet.photos.editor.recipe.HslBand
import com.roylet.photos.editor.recipe.Light
import com.roylet.photos.editor.recipe.Params
import com.roylet.photos.editor.recipe.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeTest {
    private val full = Recipe(
        geometry = Geometry(CropRect(0.1f, 0.2f, 0.9f, 0.8f), rotate90 = 1, straighten = 3.5f, flipH = true,
            perspectiveVertical = -20f, perspectiveHorizontal = 10f),
        light = Light(0.7f, 10f, -40f, 35f, 5f, -5f),
        color = ColorAdjust(12f, -3f, 20f, -10f).withHsl(HslBand.BLUE, HslAdjust(5f, -30f, 10f)),
        curves = Curves(rgb = listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.55f), CurvePoint(1f, 1f))),
        effects = Effects(clarity = 20f, dehaze = 10f, vignette = -30f, grain = 15f, sharpen = 40f),
        filter = Filter("film", 70f),
    )

    @Test fun defaultIsIdentity() {
        assertTrue(Recipe().isIdentity)
        assertTrue(Recipe().isColorIdentity)
        assertFalse(full.isIdentity)
        assertTrue(Recipe(filter = Filter("vivid", 0f)).isIdentity)
        assertFalse(Recipe(geometry = Geometry(rotate90 = 1)).isIdentity)
        assertTrue(Recipe(geometry = Geometry(rotate90 = 1)).isColorIdentity)
        assertTrue(Recipe(curves = Curves(rgb = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f)))).isIdentity)
    }

    @Test fun jsonRoundTrip() {
        val json = full.toJson()
        assertEquals(full, Recipe.fromJson(json))
    }

    @Test fun defaultJsonIsCompactButVersioned() {
        val json = Recipe().toJson()
        assertEquals("""{"version":1}""", json)
        assertEquals(Recipe(), Recipe.fromJson(json))
    }

    @Test fun decodeIgnoresUnknownKeysAndClamps() {
        val r = Recipe.fromJson("""{"version":1,"future":{"x":1},"light":{"exposure":12,"contrast":-400},
            "color":{"hsl":[{"hue":5}]},"geometry":{"rotate90":-1}}""")
        assertEquals(5f, r.light.exposure)
        assertEquals(-100f, r.light.contrast)
        assertEquals(8, r.color.hsl.size)
        assertEquals(5f, r.color[HslBand.RED].hue)
        assertEquals(3, r.geometry.rotate90)
    }

    @Test fun normalizedRepairsCrop() {
        val g = Geometry(crop = CropRect(0.8f, 0.5f, 0.2f, 1.4f)).normalized()
        assertTrue(g.crop.right > g.crop.left)
        assertTrue(g.crop.bottom <= 1f)
    }

    @Test fun everySliderRoundTrips() {
        for (p in Params.all + HslBand.entries.flatMap { Params.hsl(it) }) {
            val v = (p.range.start + p.range.endInclusive) / 2f + (p.range.endInclusive - p.range.start) / 4f
            val r = p.set(Recipe(), v)
            assertEquals(p.id, v, p.get(r), 0f)
            assertFalse(p.id, p.isDefault(r))
            assertTrue(p.id, p.isDefault(p.reset(r)))
        }
    }
}

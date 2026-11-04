package com.roylet.photos.editor

import com.roylet.photos.editor.recipe.CurveMath
import com.roylet.photos.editor.recipe.CurvePoint
import com.roylet.photos.editor.recipe.Curves
import com.roylet.photos.editor.recipe.Lut3D
import com.roylet.photos.editor.recipe.LutRegistry
import com.roylet.photos.editor.recipe.LutResolver
import com.roylet.photos.editor.recipe.Presets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CurveAndLutTest {
    @Test fun identityCurveBakesToIdentity() {
        val lut = CurveMath.bake(Curves.IDENTITY)
        for (i in lut.indices) assertEquals(i / 255f, lut[i], 1e-6f)
    }

    @Test fun bakedCurveIsMonotoneAndHitsControlPoints() {
        val rnd = Random(42)
        repeat(200) {
            val n = rnd.nextInt(2, 7)
            val xs = (List(n) { rnd.nextFloat() } + listOf(0f, 1f)).distinct().sorted()
            var y = 0f
            val pts = xs.map { x -> y = (y + rnd.nextFloat() * 0.4f).coerceAtMost(1f); CurvePoint(x, y) }
            val lut = CurveMath.bake(pts)
            for (i in 1 until lut.size) assertTrue("non-monotone at $i for $pts", lut[i] >= lut[i - 1] - 1e-6f)
            for (p in CurveMath.normalize(pts)) {
                val i = (p.x * 255f)
                if (kotlin.math.abs(i - Math.round(i)) < 1e-4f) assertEquals(p.y, lut[Math.round(i)], 1e-4f)
            }
        }
    }

    @Test fun flatOutsideEndPoints() {
        val lut = CurveMath.bake(listOf(CurvePoint(0.2f, 0.1f), CurvePoint(0.8f, 0.9f)))
        assertEquals(0.1f, lut[0], 1e-6f)
        assertEquals(0.9f, lut[255], 1e-6f)
    }

    @Test fun compositeAppliesMasterThenChannel() {
        val inverted = listOf(CurvePoint(0f, 1f), CurvePoint(1f, 0f))
        val lut = CurveMath.compositeLut(Curves(rgb = inverted, red = inverted))
        assertEquals(0f, lut[0 * 3 + 0], 1e-5f)  // red: invert twice = identity
        assertEquals(1f, lut[0 * 3 + 1], 1e-5f)  // green: inverted once
    }

    @Test fun identityLutSamplesExactly() {
        val lut = Lut3D.identity(17)
        val out = lut.sample(0.3f, 0.61f, 0.99f)
        assertEquals(0.3f, out[0], 1e-5f); assertEquals(0.61f, out[1], 1e-5f); assertEquals(0.99f, out[2], 1e-5f)
    }

    @Test fun parsesCube() {
        val text = buildString {
            appendLine("# comment"); appendLine("TITLE \"Swap\""); appendLine("LUT_3D_SIZE 2")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("$b $g $r") // swap r and b
        }
        val lut = Lut3D.parseCube(text)
        assertEquals("Swap", lut.title)
        val o = lut.sample(1f, 0f, 0f)
        assertEquals(0f, o[0], 1e-6f); assertEquals(1f, o[2], 1e-6f)
    }

    @Test fun presetsBuildAndResolve() {
        assertEquals(8, Presets.all.size)
        assertEquals(Presets.all.size, Presets.all.map { it.id }.toSet().size)
        for (p in Presets.all) {
            assertEquals(33, p.lut.size)
            assertTrue(p.lut.data.all { it in 0f..1f })
            assertNotNull(LutResolver.Default.resolve(p.id))
        }
        val mono = Presets.Mono.lut.sample(0.9f, 0.2f, 0.1f)
        assertEquals(mono[0], mono[1], 1e-5f); assertEquals(mono[1], mono[2], 1e-5f)
        LutRegistry.register("custom", Lut3D.identity(2))
        assertNotNull(LutResolver.Default.resolve("custom"))
        LutRegistry.unregister("custom")
    }
}

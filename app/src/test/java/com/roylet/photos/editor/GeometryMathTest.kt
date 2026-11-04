package com.roylet.photos.editor

import com.roylet.photos.editor.io.Orientation
import com.roylet.photos.editor.recipe.CropRect
import com.roylet.photos.editor.recipe.Geometry
import com.roylet.photos.editor.recipe.GeometryMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GeometryMathTest {
    private val w = 400
    private val h = 300

    private fun map(g: Geometry, u: Double, v: Double, sw: Int = w, sh: Int = h) =
        GeometryMath.outputToSource(g, sw, sh).map(u, v)

    private fun assertPoint(expected: DoubleArray, actual: DoubleArray, eps: Double = 1e-6) {
        assertArrayEquals(expected, actual, eps)
    }

    @Test fun identity() {
        assertPoint(doubleArrayOf(0.3, 0.7), map(Geometry(), 0.3, 0.7))
        assertEquals(w to h, GeometryMath.outputSize(Geometry(), w, h))
        assertEquals(1.0, GeometryMath.coverScale(Geometry(), w, h), 0.0)
    }

    @Test fun cropMapsCornersAndSize() {
        val g = Geometry(crop = CropRect(0.25f, 0.5f, 0.75f, 1f))
        assertEquals(200 to 150, GeometryMath.outputSize(g, w, h))
        assertPoint(doubleArrayOf(0.25, 0.5), map(g, 0.0, 0.0))
        assertPoint(doubleArrayOf(0.75, 1.0), map(g, 1.0, 1.0))
        assertEquals(400 to 300, GeometryMath.outputSize(g, w, h, ignoreCrop = true))
    }

    @Test fun quarterTurns() {
        val g = Geometry(rotate90 = 1)
        assertEquals(h to w, GeometryMath.outputSize(g, w, h))
        // Clockwise: output top-left shows the source bottom-left.
        assertPoint(doubleArrayOf(0.0, 1.0), map(g, 0.0, 0.0))
        assertPoint(doubleArrayOf(0.0, 0.0), map(g, 1.0, 0.0))
        assertPoint(doubleArrayOf(1.0, 0.0), map(Geometry(rotate90 = 2), 0.0, 1.0))
        assertPoint(doubleArrayOf(1.0, 0.0), map(Geometry(rotate90 = 3), 0.0, 0.0))
    }

    @Test fun flips() {
        assertPoint(doubleArrayOf(0.8, 0.3), map(Geometry(flipH = true), 0.2, 0.3))
        assertPoint(doubleArrayOf(0.2, 0.7), map(Geometry(flipV = true), 0.2, 0.3))
    }

    @Test fun straightenCoversFrame() {
        for (deg in listOf(-45f, -10f, 3f, 20f, 45f)) {
            val g = Geometry(straighten = deg, perspectiveVertical = 30f)
            val s = GeometryMath.coverScale(g, w, h)
            assertTrue(s > 1.0)
            for (u in listOf(0.0, 1.0)) for (v in listOf(0.0, 1.0)) {
                val p = map(g, u, v)
                assertTrue("$deg $u,$v -> ${p.toList()}", p[0] in -1e-6..1.000001 && p[1] in -1e-6..1.000001)
            }
            // Centre stays put.
            assertPoint(doubleArrayOf(0.5, 0.5), map(g, 0.5, 0.5), 1e-9)
        }
    }

    @Test fun straightenRotatesClockwise() {
        // A point right of centre in the output comes from above-right... i.e. source rotated cw:
        // the output's +x axis samples the source along (cos, -sin).
        val g = Geometry(straighten = 10f)
        val p = map(g, 0.6, 0.5, 300, 300)
        assertTrue(p[0] > 0.5 && p[1] < 0.5)
    }

    private fun randomGeometry(rnd: Random): Geometry {
        val l = rnd.nextFloat() * 0.4f
        val t = rnd.nextFloat() * 0.4f
        return Geometry(
            crop = CropRect(l, t, l + 0.2f + rnd.nextFloat() * 0.4f, t + 0.2f + rnd.nextFloat() * 0.4f),
            rotate90 = rnd.nextInt(4),
            straighten = rnd.nextFloat() * 40f - 20f,
            flipH = rnd.nextBoolean(),
            flipV = rnd.nextBoolean(),
            perspectiveVertical = rnd.nextFloat() * 100f - 50f,
            perspectiveHorizontal = rnd.nextFloat() * 100f - 50f,
        )
    }

    @Test fun uiTransformsAreVisualRotationsAndMirrors() {
        val rnd = Random(7)
        repeat(100) {
            val g = randomGeometry(rnd)
            val cw = g.rotatedCw()
            val fh = g.flippedHorizontally()
            val fv = g.flippedVertically()
            repeat(5) {
                val u = rnd.nextDouble(); val v = rnd.nextDouble()
                // Rotating the result cw: output (u, v) shows what was at (v, 1 - u).
                assertPoint(map(g, v, 1 - u), map(cw, u, v), 1e-5)
                assertPoint(map(g, 1 - u, v), map(fh, u, v), 1e-5)
                assertPoint(map(g, u, 1 - v), map(fv, u, v), 1e-5)
            }
            val back = g.rotatedCw().rotatedCw().rotatedCw().rotatedCw()
            assertEquals(g.rotate90, back.rotate90)
            assertEquals(g.crop.left, back.crop.left, 1e-5f); assertEquals(g.crop.bottom, back.crop.bottom, 1e-5f)
            assertEquals(g.straighten, back.straighten, 0f)
            assertEquals(g.perspectiveVertical, back.perspectiveVertical, 0f)
            val twice = g.flippedHorizontally().flippedHorizontally()
            assertEquals(g.flipH, twice.flipH); assertEquals(g.crop.right, twice.crop.right, 1e-5f)
            val (ow, oh) = GeometryMath.outputSize(g, w, h)
            val (cwW, cwH) = GeometryMath.outputSize(cw, w, h)
            assertTrue(kotlin.math.abs(ow - cwH) <= 1 && kotlin.math.abs(oh - cwW) <= 1)
        }
    }

    @Test fun exifOrientationMaps() {
        // Orientation 6 (rotate 90 cw): oriented top-right corner is the raw top-left.
        assertEquals(0.0 to 0.0, Orientation.orientedToRaw(6, 300.0, 0.0, 400, 300))
        assertEquals(0.0 to 0.0, Orientation.orientedToRaw(8, 0.0, 400.0, 400, 300))
        assertEquals(0.0 to 0.0, Orientation.orientedToRaw(7, 300.0, 400.0, 400, 300))
        assertEquals(300 to 400, Orientation.orientedSize(6, 400, 300))
        for (o in 1..8) {
            val (ow, oh) = Orientation.orientedSize(o, 400, 300)
            val r = Orientation.orientedRectToRaw(o, 0, 0, ow, oh, 400, 300)
            assertArrayEquals("orientation $o", intArrayOf(0, 0, 400, 300), r)
        }
        assertArrayEquals(intArrayOf(390, 0, 400, 20), Orientation.orientedRectToRaw(2, 0, 0, 10, 20, 400, 300))
    }
}

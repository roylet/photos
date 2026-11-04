package com.roylet.photos.editor

import com.roylet.photos.editor.cpu.ArgbImage
import com.roylet.photos.editor.cpu.ReferenceRenderer
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
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.recipe.RenderParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ReferenceRendererTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(p: Int) = p shr 16 and 0xFF
    private fun g(p: Int) = p shr 8 and 0xFF
    private fun b(p: Int) = p and 0xFF
    private fun lin(c: Int) = ReferenceRenderer.srgbToLinear(c / 255f)

    private val noise: ArgbImage = Random(1).let { rnd ->
        ArgbImage(48, 32, IntArray(48 * 32) { argb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) })
    }

    /** A smooth gradient with colour, less degenerate than noise for local effects. */
    private val gradient = ArgbImage(64, 48, IntArray(64 * 48) { i ->
        val x = i % 64; val y = i / 64
        argb(40 + x * 3, 30 + y * 4, 200 - x * 2)
    })

    private fun render(src: ArgbImage, recipe: Recipe) = ReferenceRenderer.render(src, recipe)
    private fun solid(c: Int, w: Int = 8, h: Int = 8) = ArgbImage.filled(w, h, c)
    private fun one(c: Int, recipe: Recipe) = render(solid(c), recipe)[4, 4]

    @Test fun identityLeavesPixelsUnchanged() {
        val out = render(noise, Recipe())
        assertEquals(noise.width, out.width)
        var maxDiff = 0
        for (i in noise.pixels.indices) {
            val a = noise.pixels[i]; val o = out.pixels[i]
            maxDiff = maxOf(maxDiff, abs(r(a) - r(o)), abs(g(a) - g(o)), abs(b(a) - b(o)))
        }
        assertTrue("max diff $maxDiff", maxDiff <= 1)
    }

    @Test fun zeroAmountFilterIsIdentity() {
        val out = render(gradient, Recipe(filter = Filter("noir", 0f)))
        assertTrue(out.pixels.indices.all { abs(r(out.pixels[it]) - r(gradient.pixels[it])) <= 1 })
    }

    @Test fun exposurePlusOneDoublesLinear() {
        for (v in listOf(30, 60, 90, 120, 160)) {
            val o = one(argb(v, v, v), Recipe(light = Light(exposure = 1f)))
            assertEquals("grey $v", 2 * lin(v), lin(r(o)), 0.012f)
            assertEquals(r(o), g(o)); assertEquals(g(o), b(o))
        }
        val o = one(argb(100, 60, 40), Recipe(light = Light(exposure = 1f)))
        assertEquals(2 * lin(100), lin(r(o)), 0.015f)
        assertEquals(2 * lin(40), lin(b(o)), 0.01f)
        // Minus one stop halves.
        val d = one(argb(150, 150, 150), Recipe(light = Light(exposure = -1f)))
        assertEquals(lin(150) / 2, lin(r(d)), 0.006f)
    }

    @Test fun overexposureClipsHuePreserving() {
        val o = one(argb(200, 100, 50), Recipe(light = Light(exposure = 4f)))
        assertTrue(r(o) >= g(o) && g(o) >= b(o))
    }

    @Test fun saturationMinus100IsGrey() {
        val out = render(noise, Recipe(color = ColorAdjust(saturation = -100f)))
        for (p in out.pixels) { assertTrue(abs(r(p) - g(p)) <= 1 && abs(g(p) - b(p)) <= 1) }
    }

    @Test fun saturationAndVibranceIncreaseChroma() {
        val c = argb(150, 110, 90)
        for (recipe in listOf(Recipe(color = ColorAdjust(saturation = 50f)), Recipe(color = ColorAdjust(vibrance = 50f)))) {
            val o = one(c, recipe)
            assertTrue((r(o) - b(o)) > (150 - 90))
        }
    }

    @Test fun greyIsUntouchedByColourTools() {
        val grey = argb(128, 128, 128)
        val recipe = Recipe(color = ColorAdjust(vibrance = 80f, saturation = 80f,
            hsl = List(8) { HslAdjust(50f, 50f, 50f) }))
        val o = one(grey, recipe)
        assertEquals(128, r(o)); assertEquals(128, g(o)); assertEquals(128, b(o))
    }

    @Test fun contrastPivotsOnMidGrey() {
        val k = Recipe(light = Light(contrast = 80f))
        val mid = one(argb(188, 188, 188), k) // linear luma 0.5 -> encoded ~0.735; check pivot in encoded space
        val dark = one(argb(60, 60, 60), k)
        val light = one(argb(200, 200, 200), k)
        assertTrue(r(dark) < 60)
        assertTrue(r(light) > 200)
        val pivot = one(argb(128, 128, 128), k)
        assertTrue(abs(r(pivot) - 128) <= 1)
        assertNotEquals(0, r(mid))
    }

    @Test fun shadowsLiftDarksMoreThanLights() {
        val s = Recipe(light = Light(shadows = 80f))
        val darkGain = r(one(argb(50, 50, 50), s)) - 50
        val lightGain = r(one(argb(230, 230, 230), s)) - 230
        assertTrue(darkGain > 10 && darkGain > lightGain)
        val hl = Recipe(light = Light(highlights = -80f))
        assertTrue(r(one(argb(220, 220, 220), hl)) < 210)
        assertTrue(abs(r(one(argb(30, 30, 30), hl)) - 30) <= 6)
    }

    @Test fun blacksAndWhitesMoveEndPoints() {
        assertTrue(r(one(argb(0, 0, 0), Recipe(light = Light(blacks = 100f)))) > 20)
        assertTrue(r(one(argb(220, 220, 220), Recipe(light = Light(whites = 100f)))) > 245)
        assertTrue(r(one(argb(255, 255, 255), Recipe(light = Light(whites = -100f)))) < 240)
    }

    @Test fun toneCurveIsIdentityAtZeroAndMonotoneWhenPushed() {
        val zero = RenderParams()
        for (i in 0..100) assertEquals(i / 100f, ReferenceRenderer.toneCurve(i / 100f, zero), 1e-5f)
        for (extreme in listOf(-1f, 1f)) {
            val p = RenderParams(contrast = extreme, shadows = extreme, highlights = -extreme)
            var prev = -1f
            for (i in 0..200) {
                val y = ReferenceRenderer.toneCurve(i / 200f, p)
                assertTrue(y >= prev - 1e-5f); prev = y
            }
        }
    }

    @Test fun temperatureWarmsAndCools() {
        val grey = argb(128, 128, 128)
        val warm = one(grey, Recipe(color = ColorAdjust(temperature = 60f)))
        assertTrue(r(warm) > b(warm) + 10)
        val cool = one(grey, Recipe(color = ColorAdjust(temperature = -60f)))
        assertTrue(b(cool) > r(cool) + 10)
        val magenta = one(grey, Recipe(color = ColorAdjust(tint = 60f)))
        assertTrue(g(magenta) < r(magenta))
    }

    @Test fun hslBandTargetsItsHue() {
        val red = argb(200, 40, 40)
        val blue = argb(40, 60, 200)
        val recipe = Recipe(color = ColorAdjust().withHsl(HslBand.RED, HslAdjust(saturation = -100f)))
        val r1 = one(red, recipe)
        assertTrue(abs(r(r1) - g(r1)) < 8) // red desaturated
        assertEquals(blue, one(blue, recipe))
        val shifted = one(red, Recipe(color = ColorAdjust().withHsl(HslBand.RED, HslAdjust(hue = 100f))))
        assertTrue(g(shifted) > 40 + 30) // towards orange/yellow
        val darker = one(blue, Recipe(color = ColorAdjust().withHsl(HslBand.BLUE, HslAdjust(luminance = -100f))))
        assertTrue(b(darker) < 200 - 30)
    }

    @Test fun curvesApply() {
        val inv = listOf(CurvePoint(0f, 1f), CurvePoint(1f, 0f))
        val o = one(argb(30, 100, 220), Recipe(curves = Curves(rgb = inv)))
        assertTrue(abs(r(o) - 225) <= 1 && abs(g(o) - 155) <= 1 && abs(b(o) - 35) <= 1)
        val redOnly = one(argb(100, 100, 100), Recipe(curves = Curves(red = listOf(CurvePoint(0f, 0f), CurvePoint(0.4f, 0.6f), CurvePoint(1f, 1f)))))
        assertTrue(r(redOnly) > 120 && g(redOnly) == 100)
    }

    @Test fun lutFilterBlendsByAmount() {
        val c = argb(200, 80, 40)
        val full = one(c, Recipe(filter = Filter("mono")))
        assertTrue(abs(r(full) - g(full)) <= 1 && abs(g(full) - b(full)) <= 1)
        val half = one(c, Recipe(filter = Filter("mono", 50f)))
        assertTrue(abs(r(half) - (200 + r(full)) / 2) <= 2)
        assertEquals(c, one(c, Recipe(filter = Filter("does-not-exist"))))
    }

    @Test fun vignetteDarkensCornersOnly() {
        val out = render(solid(argb(180, 180, 180), 41, 41), Recipe(effects = Effects(vignette = -100f)))
        assertTrue(abs(r(out[20, 20]) - 180) <= 1)
        assertTrue(r(out[0, 0]) < 90)
        val light = render(solid(argb(100, 100, 100), 41, 41), Recipe(effects = Effects(vignette = 100f)))
        assertTrue(r(light[0, 0]) > 150)
    }

    @Test fun grainIsDeterministicAndRoughlyNeutral() {
        val src = solid(argb(128, 128, 128), 200, 120)
        val recipe = Recipe(effects = Effects(grain = 100f))
        val a = render(src, recipe)
        val b = render(src, recipe)
        assertTrue(a.pixels.contentEquals(b.pixels))
        val mean = a.pixels.map { r(it) }.average()
        assertEquals(128.0, mean, 2.0)
        assertTrue(a.pixels.map { r(it) }.distinct().size > 10)
        // Same output position -> same noise, independent of the output resolution (lattice in uv).
        assertEquals(ReferenceRenderer.valueNoise(12.5f, 7.25f), ReferenceRenderer.valueNoise(12.5f, 7.25f))
        assertEquals(0x688990c0, ReferenceRenderer.hashU(1)) // pins the hash the shader uses
    }

    @Test fun clarityAndSharpenIncreaseLocalContrast() {
        // A step edge: local effects push the two sides apart.
        val step = ArgbImage(64, 16, IntArray(64 * 16) { i -> if (i % 64 < 32) argb(100, 100, 100) else argb(150, 150, 150) })
        for (recipe in listOf(Recipe(effects = Effects(clarity = 100f)), Recipe(effects = Effects(sharpen = 100f)))) {
            val out = render(step, recipe)
            assertTrue("$recipe", r(out[31, 8]) < 100 && r(out[32, 8]) > 150)
        }
        val flat = render(solid(argb(120, 120, 120), 32, 32), Recipe(effects = Effects(clarity = 100f, sharpen = 100f)))
        assertTrue(flat.pixels.all { abs(r(it) - 120) <= 1 })
    }

    @Test fun dehazeDeepensHazyImage() {
        val hazy = solid(argb(170, 175, 185), 32, 32)
        val out = render(hazy, Recipe(effects = Effects(dehaze = 100f)))
        assertTrue(r(out[16, 16]) < 150)
        val added = render(solid(argb(40, 40, 40), 32, 32), Recipe(effects = Effects(dehaze = -100f)))
        assertTrue(r(added[16, 16]) > 60)
    }

    @Test fun geometryCropsAndRotates() {
        val src = ArgbImage(4, 2, intArrayOf(
            argb(1, 0, 0), argb(2, 0, 0), argb(3, 0, 0), argb(4, 0, 0),
            argb(5, 0, 0), argb(6, 0, 0), argb(7, 0, 0), argb(8, 0, 0)))
        val cropped = render(src, Recipe(geometry = Geometry(crop = CropRect(0.5f, 0f, 1f, 0.5f))))
        assertEquals(2, cropped.width); assertEquals(1, cropped.height)
        assertEquals(listOf(3, 4), cropped.pixels.map { r(it) })
        val rotated = render(src, Recipe(geometry = Geometry(rotate90 = 1)))
        assertEquals(2, rotated.width); assertEquals(4, rotated.height)
        assertEquals(listOf(5, 1, 6, 2, 7, 3, 8, 4), rotated.pixels.map { r(it) })
        val flipped = render(src, Recipe(geometry = Geometry(flipH = true)))
        assertEquals(listOf(4, 3, 2, 1, 8, 7, 6, 5), flipped.pixels.map { r(it) })
    }

    @Test fun straightenFillsTheFrame() {
        val out = render(gradient, Recipe(geometry = Geometry(straighten = 30f)))
        assertTrue(out.pixels.none { it == 0xFF000000.toInt() })
    }
}

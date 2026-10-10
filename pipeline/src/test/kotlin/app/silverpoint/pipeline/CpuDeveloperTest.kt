package app.silverpoint.pipeline

import app.silverpoint.recipes.RecipeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuDeveloperTest {
    private val neutral = doubleArrayOf(0.49, 1.0, 0.75)

    @Test
    fun flatGreyDevelopsToEqualChannels() {
        val meta = SyntheticScene.meta(neutral)
        val p = DevelopParams.build(meta, RecipeParams(), rotation = 0)
        val img = CpuDeveloper.develop(SyntheticScene.flatGrey(meta, 0.18f), meta.width, p)
        val px = img.argb[img.argb.size / 2]
        val r = px shr 16 and 0xFF
        val g = px shr 8 and 0xFF
        val b = px and 0xFF
        assertTrue("got $r $g $b", Math.abs(r - g) <= 1 && Math.abs(b - g) <= 1)
        assertTrue("mid grey should be mid-range, got $g", g in 60..200)
    }

    @Test
    fun demosaicOfFlatColourIsThatColour() {
        val w = 12
        val h = 10
        for (pattern in Cfa.entries) {
            val cfa = FloatArray(w * h) { i -> floatArrayOf(0.7f, 0.4f, 0.2f)[pattern.channel(i % w, i / w)] }
            val rgb = CpuDeveloper.demosaic(cfa, w, h, pattern)
            for (y in 0 until h) for (x in 0 until w) {
                val o = (y * w + x) * 3
                assertEquals("$pattern r at $x,$y", 0.7f, rgb[o], 1e-5f)
                assertEquals("$pattern g at $x,$y", 0.4f, rgb[o + 1], 1e-5f)
                assertEquals("$pattern b at $x,$y", 0.2f, rgb[o + 2], 1e-5f)
            }
        }
    }

    @Test
    fun refineLeavesFlatColourAlone() {
        val w = 16
        val h = 12
        for (pattern in Cfa.entries) {
            val cfa = FloatArray(w * h) { i -> floatArrayOf(0.7f, 0.4f, 0.2f)[pattern.channel(i % w, i / w)] }
            val rgb = CpuDeveloper.demosaic(cfa, w, h, pattern, DevelopParams.SHARPEN, DevelopParams.CHROMA_SMOOTHING)
            for (i in 0 until w * h) {
                assertEquals("$pattern r", 0.7f, rgb[i * 3], 1e-4f)
                assertEquals("$pattern g", 0.4f, rgb[i * 3 + 1], 1e-4f)
                assertEquals("$pattern b", 0.2f, rgb[i * 3 + 2], 1e-4f)
            }
        }
    }

    @Test
    fun refineRemovesColourSpecksAndSharpensEdges() {
        val w = 24
        val h = 24
        val pattern = Cfa.GBRG
        // Grey, with one red sample turned up: a false-colour speck.
        val speck = FloatArray(w * h) { 0.3f }
        val sx = (0 until w).first { pattern.channel(it, 13) == 0 }
        speck[13 * w + sx] = 0.6f
        fun chroma(rgb: FloatArray, x: Int, y: Int): Float {
            val o = (y * w + x) * 3
            return maxOf(rgb[o], rgb[o + 1], rgb[o + 2]) - minOf(rgb[o], rgb[o + 1], rgb[o + 2])
        }
        val plain = CpuDeveloper.demosaic(speck, w, h, pattern)
        val smooth = CpuDeveloper.demosaic(speck, w, h, pattern, 0f, 1f)
        assertTrue("speck colour ${chroma(plain, sx, 13)} -> ${chroma(smooth, sx, 13)}", chroma(smooth, sx, 13) < 0.4f * chroma(plain, sx, 13))

        // A grey step: sharpening makes the dark side darker and the light side lighter next to it.
        val step = FloatArray(w * h) { i -> if (i % w < 12) 0.1f else 0.5f }
        val soft = CpuDeveloper.demosaic(step, w, h, pattern)
        val sharp = CpuDeveloper.demosaic(step, w, h, pattern, 1f, 0f)
        fun lum(rgb: FloatArray, x: Int) = (rgb[(10 * w + x) * 3] + 2 * rgb[(10 * w + x) * 3 + 1] + rgb[(10 * w + x) * 3 + 2]) / 4
        assertTrue(lum(sharp, 11) < lum(soft, 11))
        assertTrue(lum(sharp, 12) > lum(soft, 12))
        // Far from the edge nothing changes.
        assertEquals(lum(soft, 3), lum(sharp, 3), 1e-5f)

        // Edge masking spares faint texture but still sharpens the strong edge.
        // Faint vertical stripes every third column: texture, not a colour the Bayer grid could alias to.
        val faint = FloatArray(w * h) { i -> if (i % w % 3 == 0) 0.31f else 0.30f }
        val unmasked = CpuDeveloper.demosaic(faint, w, h, pattern, 1f, 0f)
        val masked = CpuDeveloper.demosaic(faint, w, h, pattern, 1f, 0f, 1f)
        val base = CpuDeveloper.demosaic(faint, w, h, pattern)
        fun spread(rgb: FloatArray) = (0 until w).map { lum(rgb, it) }.let { it.max() - it.min() }
        assertTrue(spread(masked) - spread(base) < 0.5f * (spread(unmasked) - spread(base)))
        val sharpMasked = CpuDeveloper.demosaic(step, w, h, pattern, 1f, 0f, 0.2f)
        assertTrue(lum(sharpMasked, 12) > lum(soft, 12) + 0.5f * (lum(sharp, 12) - lum(soft, 12)))
    }

    @Test
    fun blownHighlightsStayNeutral() {
        val meta = SyntheticScene.meta(neutral)
        val raw = ShortArray(meta.width * meta.height) { 1023 }
        val p = DevelopParams.build(meta, RecipeParams(), rotation = 0)
        val px = CpuDeveloper.develop(raw, meta.width, p).argb[50]
        val r = px shr 16 and 0xFF
        val g = px shr 8 and 0xFF
        val b = px and 0xFF
        assertTrue("got $r $g $b", Math.abs(r - g) <= 2 && Math.abs(b - g) <= 2)
    }

    @Test
    fun rotationSwapsSizeAndMovesCorners() {
        val meta = SyntheticScene.meta(neutral, width = 8, height = 6)
        val raw = SyntheticScene.flatGrey(meta, 0.1f)
        // Brighten the top-left 2x2 block so it can be tracked.
        for (y in 0..1) for (x in 0..1) raw[y * 8 + x] = 900
        val r90 = CpuDeveloper.develop(raw, 8, DevelopParams.build(meta, RecipeParams(), rotation = 90))
        assertEquals(6, r90.width)
        assertEquals(8, r90.height)
        // Clockwise 90°: the source top-left ends up top-right.
        val topRight = r90.argb[r90.width - 1] and 0xFF
        val topLeft = r90.argb[0] and 0xFF
        assertTrue("top-right $topRight should be brighter than top-left $topLeft", topRight > topLeft + 50)
        val r270 = CpuDeveloper.develop(raw, 8, DevelopParams.build(meta, RecipeParams(), rotation = 270))
        val bottomLeft = r270.argb[(r270.height - 1) * r270.width] and 0xFF
        assertTrue(bottomLeft > (r270.argb[0] and 0xFF) + 50)
    }

    @Test
    fun exposureBrightensAndSaturationMinusHundredIsGrey() {
        val meta = SyntheticScene.meta(neutral)
        val raw = SyntheticScene.flatGrey(meta, 0.1f)
        val base = CpuDeveloper.develop(raw, meta.width, DevelopParams.build(meta, RecipeParams(), 0)).argb[60] and 0xFF
        val up = CpuDeveloper.develop(raw, meta.width, DevelopParams.build(meta, RecipeParams(exposure = 1f), 0)).argb[60] and 0xFF
        assertTrue(up > base)
        // A coloured patch: red-ish camera response.
        val red = ShortArray(meta.width * meta.height) { i ->
            val ch = meta.cfa.channel(i % meta.width, i / meta.width)
            (64 + floatArrayOf(0.6f, 0.15f, 0.1f)[ch] * 959).toInt().toShort()
        }
        val grey = CpuDeveloper.develop(red, meta.width, DevelopParams.build(meta, RecipeParams(saturation = -100f), 0)).argb[60]
        val r = grey shr 16 and 0xFF
        val g = grey shr 8 and 0xFF
        val b = grey and 0xFF
        assertTrue("got $r $g $b", Math.abs(r - g) <= 2 && Math.abs(b - g) <= 2)
    }

    @Test
    fun lensShadingGridCornersAreExact() {
        val gains = FloatArray(3 * 2 * 4) { it.toFloat() }
        val ls = LensShading(3, 2, gains)
        // Top-left sample of an RGGB image is red: channel 0 of grid point (0,0).
        assertEquals(0f, ls.gain(0, 0, 9, 5, Cfa.RGGB), 1e-6f)
        // Bottom-right sample (8,4) is red too (even x, even y): channel 0 of grid point (2,1) = index 5*4.
        assertEquals(20f, ls.gain(8, 4, 9, 5, Cfa.RGGB), 1e-6f)
    }

    @Test
    fun reflectKeepsCfaPhase() {
        assertEquals(1, CpuDeveloper.reflect(-1, 10))
        assertEquals(2, CpuDeveloper.reflect(-2, 10))
        assertEquals(8, CpuDeveloper.reflect(10, 10))
        assertEquals(7, CpuDeveloper.reflect(11, 10))
    }
}

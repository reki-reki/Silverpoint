package app.silverpoint.pipeline

import app.silverpoint.recipes.Recipe
import app.silverpoint.recipes.RecipeMatch
import app.silverpoint.recipes.RecipeParams
import app.silverpoint.recipes.RecipeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.Random

class LookTest {
    private val recipes: List<Recipe> by lazy {
        val f = listOf("../app/src/main/assets/recipes.json", "app/src/main/assets/recipes.json").map(::File).first { it.exists() }
        RecipeStore.parse(f.readText())
    }

    private fun apply(look: RecipeLook, r: Float, g: Float, b: Float): FloatArray = FloatArray(3).also { look.apply(r, g, b, it) }

    private fun lab(rgb: FloatArray): FloatArray = FloatArray(3).also {
        Oklab.fromLinearSrgb(ToneCurve.srgbDecode(rgb[0]), ToneCurve.srgbDecode(rgb[1]), ToneCurve.srgbDecode(rgb[2]), it)
    }

    @Test
    fun neutralRecipeWithoutBaseContrastIsIdentity() {
        val look = RecipeLook(RecipeParams(), baseContrast = 0f)
        val rnd = Random(1)
        repeat(500) {
            val c = floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
            val out = apply(look, c[0], c[1], c[2])
            for (i in 0..2) assertEquals(c[i], out[i], 1e-5f)
        }
    }

    @Test
    fun matchDoesWhatItSays() {
        val rnd = Random(2)
        val colours = List(300) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()) }
        // The "no change" match is the identity.
        val identity = RecipeLook(RecipeParams(match = RecipeMatch()), baseContrast = 0f)
        for (c in colours) {
            val out = apply(identity, c[0], c[1], c[2])
            for (i in 0..2) assertEquals(c[i], out[i], 2e-4f)
        }
        // A brighter tone curve raises lightness; halving all chroma halves saturation; a tint shifts greys.
        val brighter = RecipeLook(RecipeParams(match = RecipeMatch(tone = listOf(0f, 0.3f, 0.5f, 0.7f, 0.9f, 1f))), baseContrast = 0f)
        val muted = RecipeLook(RecipeParams(match = RecipeMatch(chroma = List(6) { 0.5f })), baseContrast = 0f)
        val warm = RecipeLook(RecipeParams(match = RecipeMatch(highlightTint = listOf(0.02f, 0.02f))), baseContrast = 0f)
        val mid = floatArrayOf(0.45f, 0.5f, 0.3f)
        assertTrue(lab(apply(brighter, mid[0], mid[1], mid[2]))[0] > lab(mid)[0] + 0.05f)
        val c0 = lab(mid).let { Math.hypot(it[1].toDouble(), it[2].toDouble()) }
        val c1 = lab(apply(muted, mid[0], mid[1], mid[2])).let { Math.hypot(it[1].toDouble(), it[2].toDouble()) }
        assertEquals(0.5, c1 / c0, 0.05)
        val grey = lab(apply(warm, 0.8f, 0.8f, 0.8f))
        assertTrue(grey[1] > 0.01f && grey[2] > 0.01f)
    }

    @Test
    fun channelCurvesGradeAndCalibrationDoWhatTheySay() {
        // Blue channel curve 0->3, 255->250 (a common film-look tweak): bluer blacks, warmer whites.
        val blue = RecipeLook(RecipeParams(curveBlue = listOf(listOf(0, 3), listOf(128, 128), listOf(255, 250))), baseContrast = 0f)
        assertEquals(3f / 255f, apply(blue, 0f, 0f, 0f)[2], 1e-4f)
        assertEquals(250f / 255f, apply(blue, 1f, 1f, 1f)[2], 1e-4f)
        assertEquals(1f, apply(blue, 1f, 1f, 1f)[0], 1e-4f)

        // Midtone grading tints mid-grey towards its hue and leaves black and white alone.
        val warmMids = RecipeLook(RecipeParams(gradeMidtoneHue = 30f, gradeMidtoneSat = 50f), baseContrast = 0f)
        val mid = lab(apply(warmMids, 0.5f, 0.5f, 0.5f))
        assertTrue("warm midtones ${mid[1]} ${mid[2]}", mid[1] > 0.005f && mid[2] > 0.005f)
        assertEquals(0f, apply(warmMids, 1f, 1f, 1f).let { it[0] - it[2] }, 1e-3f)

        // Calibration: nothing set -> no matrix; white stays white; a red hue turn moves pure red.
        assertTrue(RecipeLook.calibrationMatrix(RecipeParams()) == null)
        val cal = RecipeLook(RecipeParams(calRedHue = 50f, calBlueSaturation = 20f), baseContrast = 0f)
        val white = apply(cal, 1f, 1f, 1f)
        for (c in white) assertEquals(1f, c, 2e-3f)
        val grey = apply(cal, 0.5f, 0.5f, 0.5f)
        assertEquals(grey[0], grey[2], 2e-3f)
        val plainRed = lab(floatArrayOf(0.8f, 0.1f, 0.1f))
        val turnedRed = lab(apply(cal, 0.8f, 0.1f, 0.1f))
        val hueBefore = Math.toDegrees(Math.atan2(plainRed[2].toDouble(), plainRed[1].toDouble()))
        val hueAfter = Math.toDegrees(Math.atan2(turnedRed[2].toDouble(), turnedRed[1].toDouble()))
        assertTrue("red turned towards orange: $hueBefore -> $hueAfter", hueAfter > hueBefore + 3)
    }

    @Test
    fun vignetteShapeAndSharpeningFollowTheSliders() {
        assertEquals(0.3f to 1f, DevelopParams.vignetteShape(50f, 50f).let { (a, b) -> Math.round(a * 1e4f) / 1e4f to Math.round(b * 1e4f) / 1e4f })
        val inner = DevelopParams.vignetteShape(40f, 70f)
        assertTrue("lower midpoint starts further in", inner.first < 0.3f)
        val meta = SyntheticScene.meta(doubleArrayOf(0.5, 1.0, 0.7))
        assertEquals(DevelopParams.SHARPEN, DevelopParams.build(meta, RecipeParams(), 0).sharpen, 1e-6f)
        val soft = DevelopParams.build(meta, RecipeParams(sharpness = 25f, sharpenEdgeMasking = 40f), 0)
        assertEquals(DevelopParams.SHARPEN * 25f / 40f, soft.sharpen, 1e-6f)
        assertEquals(0.4f, soft.sharpenMask, 1e-6f)
    }

    /** Encoded colours as they reach the look in real photos: both DNG fixture crops developed with no recipe. */
    private val photoColours: List<FloatArray> by lazy {
        val out = ArrayList<FloatArray>()
        for (name in listOf("window_wall", "cushion")) {
            val f = Fixture.load(name)
            val p = DevelopParams.build(f.meta, RecipeParams(), rotation = 0, base = BaseLook.DEFAULT.copy(contrast = 0f))
            val lin = CpuDeveloper.linearSrgb(f.raw, f.meta.width, p)
            val px = FloatArray(3)
            val lab = FloatArray(3)
            for (i in 0 until f.meta.width * f.meta.height step 7) {
                CpuDeveloper.linearLook(lin[i * 3], lin[i * 3 + 1], lin[i * 3 + 2], p, px, lab)
                out += FloatArray(3) { c -> ToneCurve.srgbEncode(ToneCurve.shoulder(px[c], p.knee)) }
            }
        }
        out
    }

    /** Sorted per-sample errors (largest channel, 8-bit levels) of the baked table against the look. */
    private fun lutErrors(look: RecipeLook, lut: LookLut, samples: List<FloatArray>): FloatArray {
        val a = FloatArray(3)
        val b = FloatArray(3)
        val e = FloatArray(samples.size) { k ->
            val c = samples[k]
            look.apply(c[0], c[1], c[2], a)
            lut.apply(c[0], c[1], c[2], b)
            maxOf(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2])) * 255f
        }
        e.sort()
        return e
    }

    /**
     * [lutErrors] for photo colours, split by whether the look puts them at the edge of sRGB (a channel
     * within [EDGE] of 0 or 1). Strongly saturating looks clip channels there (decision R4), and a table
     * cell that straddles the clip's hard corner interpolates across it: smoother than the exact look,
     * and not an error anyone would see, so those colours get their own, looser bound.
     */
    private fun photoErrors(look: RecipeLook, lut: LookLut): Pair<FloatArray, FloatArray> {
        val a = FloatArray(3)
        val smooth = ArrayList<FloatArray>()
        val clipped = ArrayList<FloatArray>()
        for (c in photoColours) {
            look.apply(c[0], c[1], c[2], a)
            if (a.any { it < EDGE || it > 1f - EDGE }) clipped += c else smooth += c
        }
        return lutErrors(look, lut, smooth) to lutErrors(look, lut, clipped)
    }

    @Test
    fun bakedTableMatchesTheLookOnRealPhotos() {
        val rnd = Random(7)
        val cube = List(4000) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()) }
        for (recipe in recipes) {
            val look = RecipeLook(recipe.params, BaseLook.DEFAULT.contrast)
            val lut = look.bake()
            val (photo, clipped) = photoErrors(look, lut)
            val anyColour = lutErrors(look, lut, cube)
            println(String.format(Locale.US, "%-32s photo p99 %.2f max %.2f | clipped %d p99 %.2f max %.2f | whole cube p99 %.2f max %.2f (8-bit levels)",
                recipe.name, photo[(photo.size * 0.99).toInt()], photo.last(),
                clipped.size, clipped.getOrElse((clipped.size * 0.99).toInt()) { 0f }, clipped.lastOrNull() ?: 0f,
                anyColour[(anyColour.size * 0.99).toInt()], anyColour.last()))
            // Vivid Slide's strong yellow-green adjustments are the steepest: p99 about 1.5 levels on the sofa
            // scene's leaves (a 65³ table would halve it at four times the bake time; decision R3). A baking
            // or indexing bug shows as tens of levels everywhere.
            assertTrue("${recipe.name}: 99th percentile error on photo colours", photo[(photo.size * 0.99).toInt()] < 2f)
            // Single near-grey pixels of strongly tuned recipes reach ~9-11 levels: hue is ill-defined near
            // grey, so band adjustments change quickly there. Invisible next to sensor noise.
            assertTrue("${recipe.name}: largest error on photo colours", photo.last() < 12f)
            if (clipped.isNotEmpty()) {
                assertTrue("${recipe.name}: 99th percentile error at the gamut edge", clipped[(clipped.size * 0.99).toInt()] < 10f)
                assertTrue("${recipe.name}: largest error at the gamut edge", clipped.last() < 25f)
            }
        }
    }

    @Test
    fun toneIsMonotonicForEveryRecipe() {
        for (recipe in recipes) {
            val look = RecipeLook(recipe.params, BaseLook.DEFAULT.contrast)
            var prev = -1f
            for (i in 0..1000) {
                val v = look.tone(i / 1000f)
                assertTrue("${recipe.name} tone not monotonic at ${i / 1000f}: $v < $prev", v >= prev - 1e-6f)
                prev = v
            }
        }
    }

    @Test
    fun blackAndWhiteIsNeutral() {
        val look = RecipeLook(RecipeParams(grayscale = true, grayRed = 30f, grayBlue = -40f), 0f)
        val rnd = Random(3)
        repeat(300) {
            val out = apply(look, rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
            assertEquals(out[0], out[1], 2e-3f)
            assertEquals(out[2], out[1], 2e-3f)
        }
        // The mixer darkens blues relative to reds of the same brightness.
        val red = apply(look, 0.8f, 0.3f, 0.3f)[1]
        val plain = apply(RecipeLook(RecipeParams(grayscale = true), 0f), 0.8f, 0.3f, 0.3f)[1]
        assertTrue(red > plain)
    }

    @Test
    fun hueSliderTurnsRedsTowardsOrange() {
        val red = floatArrayOf(0.8f, 0.15f, 0.12f)
        val before = lab(apply(RecipeLook(RecipeParams(), 0f), red[0], red[1], red[2]))
        val after = lab(apply(RecipeLook(RecipeParams(hueRed = 100f), 0f), red[0], red[1], red[2]))
        val dh = RecipeLook.oklabHue(after[1], after[2]) - RecipeLook.oklabHue(before[1], before[2])
        // 30 degrees in OKLab before gamut clipping pulls part of it back.
        assertTrue("hue moved $dh degrees", dh in 8f..35f)
    }

    @Test
    fun blueSaturationLeavesOtherColoursAlone() {
        val look = RecipeLook(RecipeParams(satBlue = -100f), 0f)
        val blue = lab(apply(look, 0.15f, 0.25f, 0.8f))
        assertTrue("blue chroma ${Oklab.chroma(blue[1], blue[2])}", Oklab.chroma(blue[1], blue[2]) < 0.02f)
        val green = floatArrayOf(0.2f, 0.7f, 0.2f)
        val out = apply(look, green[0], green[1], green[2])
        for (i in 0..2) assertEquals(green[i], out[i], 1e-3f)
    }

    @Test
    fun splitToningTintsShadowsAndHighlightsSeparately() {
        val look = RecipeLook(RecipeParams(splitShadowHue = 220f, splitShadowSaturation = 100f, splitHighlightHue = 40f, splitHighlightSaturation = 100f), 0f)
        val shadow = lab(apply(look, 0.2f, 0.2f, 0.2f))
        val highlight = lab(apply(look, 0.85f, 0.85f, 0.85f))
        assertTrue("shadows go blue: b=${shadow[2]}", shadow[2] < -0.02f)
        assertTrue("highlights go orange: b=${highlight[2]}", highlight[2] > 0.02f)
    }

    @Test
    fun hueBandsAreContinuousAndExactAtCentres() {
        val v = floatArrayOf(10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f)
        for (i in 0..7) assertEquals(v[i], HueBands.at(v, HueBands.CENTRES[i]), 1e-4f)
        assertEquals(HueBands.at(v, 359.999f), HueBands.at(v, 0f), 1e-2f)
        assertEquals(35f, HueBands.at(v, 90f), 1e-4f)
    }

    @Test
    fun cubeExportHasTheRightShape() {
        val text = LookLut.identity(3).toCube("test")
        val lines = text.trim().lines()
        assertEquals("LUT_3D_SIZE 3", lines[1])
        assertEquals(2 + 27, lines.size)
        assertEquals("1.000000 0.000000 0.000000", lines[4])
    }

    private companion object {
        /** Output within this of 0 or 1 counts as at the gamut edge: about one table cell (1/32) of headroom. */
        const val EDGE = 10f / 255f
    }
}

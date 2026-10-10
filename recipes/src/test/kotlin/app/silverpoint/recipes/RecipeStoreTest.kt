package app.silverpoint.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecipeStoreTest {
    private val recipes = RecipeStore.parse(File("../app/src/main/assets/recipes.json").readText())

    @Test
    fun parsesAllInFileOrder() {
        assertEquals(21, recipes.size)
        assertEquals("Reportage", recipes.first().name)
        assertEquals(7, recipes.count { it.set == "core" })
        assertEquals(14, recipes.count { it.set == "popular" })
    }

    @Test
    fun classicChromeValuesMatchTheFile() {
        val p = recipes.first { it.name == "Reportage" }.params
        assertEquals(14f, p.contrast)
        assertEquals(-35f, p.highlights)
        assertEquals(-16f, p.saturation)
        assertEquals(0f, p.exposure) // absent in the file: neutral default
        assertEquals(HslAdjustment(-12f, -28f, 6f), p.hsl(HueBand.Yellow))
        assertEquals(0f to 6 / 255f, p.curvePoints.first())
        assertFalse(p.grayscale)
    }

    @Test
    fun blackAndWhiteRecipesAreFlagged() {
        val mono = recipes.filter { it.params.grayscale }.map { it.name }
        assertEquals(listOf("Mono Yellow Filter", "Grit 400"), mono)
    }

    @Test
    fun everyCurveIsAscendingAndInRange() {
        for (r in recipes) {
            val xs = r.params.curve.map { it[0] }
            assertEquals(r.name, xs.sorted(), xs)
            assertTrue(r.name, r.params.curve.flatten().all { it in 0..255 })
        }
    }

    @Test
    fun curveWithRepeatedInputIsRejectedAtLoad() {
        // The look's spline cannot take two points at the same input; catch it here, not when baking.
        val bad = """{"X": {"params": {"curve": [[0, 0], [128, 100], [128, 140], [255, 255]]}}}"""
        assertTrue(runCatching { RecipeStore.parse(bad) }.isFailure)
    }

    @Test
    fun unknownKeysAreIgnoredAndMissingParamsDefault() {
        val r = RecipeStore.parse("""{"X": {"file": "x.xmp", "future_field": 1, "params": {"NewSlider": 3}}}""").single()
        assertEquals("X", r.name)
        assertEquals(RecipeParams(), r.params)
    }

    @Test
    fun everyRecipeHasAValidMatch() {
        for (r in recipes) {
            val m = r.params.match ?: error("${r.name} has no match")
            assertEquals(r.name, 0f, m.tone.first(), 0.01f)
            assertEquals(r.name, 1f, m.tone.last(), 0.01f)
            assertTrue(r.name, m.chroma.all { it in 0.7f..1.3f } && m.hue.all { it in -20f..20f })
        }
    }

    @Test
    fun matchWithDecreasingToneIsRejectedAtLoad() {
        val bad = """{"X": {"params": {"match": {"tone": [0, 0.3, 0.2, 0.6, 0.8, 1]}}}}"""
        assertTrue(runCatching { RecipeStore.parse(bad) }.isFailure)
        val ok = RecipeStore.parse("""{"X": {"params": {"match": {"tone": [0, 0.15, 0.4, 0.6, 0.85, 1]}}}}""").single()
        assertEquals(listOf(1f, 1f, 1f, 1f, 1f, 1f), ok.params.match!!.chroma)
    }
}

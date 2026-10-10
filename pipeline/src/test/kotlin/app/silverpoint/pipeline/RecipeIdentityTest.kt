package app.silverpoint.pipeline

import app.silverpoint.recipes.Recipe
import app.silverpoint.recipes.RecipeParams
import app.silverpoint.recipes.RecipeStore
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * The `match` corrections move every recipe towards Lightroom's render; they must not change what
 * makes a recipe itself. A recipe's character is measured as what it does to a standard colour
 * chart compared with the neutral look (the OKLab difference for each patch), with and without the
 * correction applied to both. The two must point the same way and be of similar size.
 */
class RecipeIdentityTest {
    private val recipes: List<Recipe> by lazy { RecipeStore.parse(File("../app/src/main/assets/recipes.json").readText()) }

    private fun look(p: RecipeParams) = RecipeLook(p, BaseLook.DEFAULT.contrast)

    private fun lab(look: RecipeLook, rgb: IntArray): FloatArray {
        val out = FloatArray(3)
        look.apply(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f, out)
        return FloatArray(3).also { Oklab.fromLinearSrgb(ToneCurve.srgbDecode(out[0]), ToneCurve.srgbDecode(out[1]), ToneCurve.srgbDecode(out[2]), it) }
    }

    /** What [p] does to every chart patch relative to [neutral], as stacked OKLab differences. */
    private fun effect(p: RecipeParams, neutral: RecipeParams): DoubleArray {
        val l = look(p)
        val n = look(neutral)
        return CHART.flatMap { c -> val a = lab(l, c); val b = lab(n, c); listOf(a[0] - b[0], a[1] - b[1], a[2] - b[2]) }.map { it.toDouble() }.toDoubleArray()
    }

    @Test
    fun matchKeepsEveryRecipesCharacter() {
        val lines = ArrayList<String>()
        for (r in recipes) {
            val m = r.params.match ?: continue
            val before = effect(r.params.copy(match = null), RecipeParams())
            val after = effect(r.params, RecipeParams(match = m))
            val dot = before.indices.sumOf { before[it] * after[it] }
            val nb = sqrt(before.sumOf { it * it })
            val na = sqrt(after.sumOf { it * it })
            val cos = dot / (nb * na)
            val ratio = na / nb
            lines += String.format(Locale.US, "%-32s direction %.3f  size %.2f", r.name, cos, ratio)
            assertTrue("${r.name}: character changes direction (cos $cos)", cos >= 0.9)
            assertTrue("${r.name}: character changes size (x$ratio)", ratio in 0.75..1.33)
        }
        println(lines.joinToString("\n"))
    }

    companion object {
        /** The 24 ColorChecker patches (commonly quoted sRGB values) plus a grey ramp. */
        val CHART = listOf(
            intArrayOf(115, 82, 68), intArrayOf(194, 150, 130), intArrayOf(98, 122, 157), intArrayOf(87, 108, 67),
            intArrayOf(133, 128, 177), intArrayOf(103, 189, 170), intArrayOf(214, 126, 44), intArrayOf(80, 91, 166),
            intArrayOf(193, 90, 99), intArrayOf(94, 60, 108), intArrayOf(157, 188, 64), intArrayOf(224, 163, 46),
            intArrayOf(56, 61, 150), intArrayOf(70, 148, 73), intArrayOf(175, 54, 60), intArrayOf(231, 199, 31),
            intArrayOf(187, 86, 149), intArrayOf(8, 133, 161), intArrayOf(243, 243, 242), intArrayOf(200, 200, 200),
            intArrayOf(160, 160, 160), intArrayOf(122, 122, 121), intArrayOf(85, 85, 85), intArrayOf(52, 52, 52),
            intArrayOf(8, 8, 8), intArrayOf(25, 25, 25), intArrayOf(35, 30, 28), intArrayOf(225, 215, 200),
        )
    }
}

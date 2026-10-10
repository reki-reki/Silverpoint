package app.silverpoint

import app.silverpoint.pipeline.RecipeLook
import app.silverpoint.recipes.RecipeParams

/**
 * What a recipe does to three familiar colours, shown for a moment before the look previews are ready:
 * the recipe's preview look applied to skin, foliage and sky. Black and white recipes come out grey.
 */
object RecipeSwatch {
    /** sRGB-encoded reference colours: light skin, foliage, blue sky. */
    private val REFERENCE = arrayOf(
        floatArrayOf(0.86f, 0.64f, 0.52f),
        floatArrayOf(0.38f, 0.54f, 0.27f),
        floatArrayOf(0.42f, 0.62f, 0.88f),
    )

    /** Packed ARGB colours, one per reference colour. */
    fun of(params: RecipeParams): IntArray {
        val look = RecipeLook(params, baseContrast = 0f, includeExposureAndSaturation = true)
        val out = FloatArray(3)
        return IntArray(REFERENCE.size) { i ->
            val c = REFERENCE[i]
            look.apply(c[0], c[1], c[2], out)
            fun b(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            (0xFF shl 24) or (b(out[0]) shl 16) or (b(out[1]) shl 8) or b(out[2])
        }
    }
}

package app.silverpoint.pipeline

import app.silverpoint.recipes.RecipeParams

/**
 * Look thumbnails for the look selector. The live ones are drawn by [PreviewRenderer.attachThumb]
 * through [lut]; the still ones (live previews off, or no camera frame yet) are a bundled sample
 * picture put through [lut] with [apply]. The looks are baked small ([LUT_SIZE]³, about a seventh of
 * the work of the viewfinder's) since thumbnails are tiny.
 */
object LookThumbnails {
    const val LUT_SIZE = 17

    /** The preview look of [params] at thumbnail precision. */
    fun lut(params: RecipeParams): LookLut =
        RecipeLook(params, baseContrast = 0f, includeExposureAndSaturation = true).bake(LUT_SIZE)

    /** [argb] (packed, opaque) through [lut]. */
    fun apply(argb: IntArray, lut: LookLut): IntArray {
        val out = FloatArray(3)
        return IntArray(argb.size) { i ->
            val p = argb[i]
            lut.apply(((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f, out)
            (0xFF shl 24) or (to8(out[0]) shl 16) or (to8(out[1]) shl 8) or to8(out[2])
        }
    }

    private fun to8(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}

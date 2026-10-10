package app.silverpoint.pipeline

import app.silverpoint.recipes.RecipeParams

/**
 * Develops synthetic RAW frames on the GPU and with [CpuDeveloper], and compares them pixel by
 * pixel. Run from the Diagnostics screen: it is how shader bugs show up without a debugger.
 */
object GpuSelfTest {
    /** Largest allowed per-channel difference (8-bit levels): float rounding differs slightly between CPU and GPU. */
    const val TOLERANCE = 2

    /** Largest allowed relative difference of the low-resolution luminance. */
    const val LOW_RES_TOLERANCE = 0.002

    class Case(
        val name: String, val maxDiff: Int, val meanDiff: Double, val gpuMs: Long, val error: String?,
        /** Largest relative difference of the gain-map luminance (GPU vs CPU), or null if not read back. */
        val lowResDiff: Double? = null,
    ) {
        val passed get() = error == null && maxDiff <= TOLERANCE

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "case" to name,
            "passed" to passed,
            "maxDiff" to maxDiff,
            "meanDiff" to Math.round(meanDiff * 1000) / 1000.0,
            "gpuMs" to gpuMs,
            "error" to error,
            "gainMapLuminanceDiff" to (lowResDiff?.let { Math.round(it * 1e5) / 1e5 } ?: "not available"),
        )
    }

    fun run(gpu: GpuDeveloper, look: RecipeParams): List<Case> = listOf(
        case(gpu, "GBRG, lens shading, rotated 90, recipe", Cfa.GBRG, SyntheticScene.radialShading(), 90, look),
        case(gpu, "RGGB, no shading, rotated 270, every recipe control", Cfa.RGGB, null, 270, EVERYTHING),
        case(gpu, "GRBG, lens shading, upright, neutral look", Cfa.GRBG, SyntheticScene.radialShading(), 0, RecipeParams()),
        case(gpu, "BGGR, rotated 180, black and white", Cfa.BGGR, SyntheticScene.radialShading(), 180, BLACK_AND_WHITE),
    )

    /** A made-up recipe that turns every control on, so every shader path runs. */
    val EVERYTHING = RecipeParams(
        exposure = 0.3f, contrast = 25f, highlights = -40f, shadows = 30f, whites = 15f, blacks = -10f,
        texture = 30f, clarity = 40f, dehaze = 20f, vibrance = 20f, saturation = -10f,
        splitShadowHue = 200f, splitShadowSaturation = 30f, splitHighlightHue = 40f, splitHighlightSaturation = 25f, splitBalance = 20f,
        grainAmount = 60f, grainSize = 40f, grainFrequency = 70f, vignette = -30f,
        curve = listOf(listOf(0, 12), listOf(64, 60), listOf(128, 132), listOf(192, 200), listOf(255, 245)),
        hueRed = 10f, hueOrange = -8f, hueYellow = 6f, hueGreen = 20f, hueAqua = -10f, hueBlue = -12f, huePurple = 5f, hueMagenta = 4f,
        satRed = 15f, satOrange = -10f, satYellow = -20f, satGreen = -30f, satAqua = 10f, satBlue = 20f, satPurple = -5f, satMagenta = 8f,
        lumRed = 5f, lumOrange = 10f, lumYellow = -5f, lumGreen = -15f, lumAqua = 5f, lumBlue = -20f, lumPurple = 3f, lumMagenta = -4f,
    )

    /** Black and white with a grey mixer, vignette and grain. */
    val BLACK_AND_WHITE = RecipeParams(
        grayscale = true, contrast = 30f, grainAmount = 40f, vignette = 20f, clarity = -20f,
        grayRed = 20f, grayOrange = 10f, grayYellow = -10f, grayGreen = -20f, grayAqua = -30f, grayBlue = -40f, grayPurple = 10f, grayMagenta = 5f,
    )

    private fun case(gpu: GpuDeveloper, name: String, cfa: Cfa, shading: LensShading?, rotation: Int, look: RecipeParams): Case = try {
        val meta = SyntheticScene.meta(doubleArrayOf(0.49, 1.0, 0.75), width = 320, height = 240, cfa = cfa, shading = shading)
        val raw = SyntheticScene.busyFrame(meta)
        val p = DevelopParams.build(meta, look, rotation, grainSeed = 1234)
        val cpu = CpuDeveloper.develop(raw, meta.width, p)
        val result = gpu.develop(raw, meta.width, p, readLowRes = true)
        val bmp = result.bitmap
        require(bmp.width == cpu.width && bmp.height == cpu.height) {
            "size ${bmp.width}x${bmp.height}, expected ${cpu.width}x${cpu.height}"
        }
        val gpuPixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(gpuPixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        var max = 0
        var sum = 0L
        for (i in gpuPixels.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                val d = Math.abs((gpuPixels[i] shr shift and 0xFF) - (cpu.argb[i] shr shift and 0xFF))
                if (d > max) max = d
                sum += d
            }
        }
        // The Ultra HDR gain map comes from the low-resolution luminance: check it too.
        var lowDiff: Double? = null
        val low = cpu.lowRes
        val gpuLow = result.lowLinear
        var error: String? = null
        if (low != null && gpuLow != null) {
            lowDiff = low.linear.indices.maxOf { i -> Math.abs(gpuLow[i] - low.linear[i]) / maxOf(low.linear[i], 1e-3f) }.toDouble()
            if (lowDiff > LOW_RES_TOLERANCE) error = "gain-map luminance differs by ${"%.4f".format(lowDiff)}"
        }
        Case(name, max, sum.toDouble() / (gpuPixels.size * 3), result.timing.totalMs, error, lowDiff)
    } catch (t: Throwable) {
        Case(name, Int.MAX_VALUE, Double.NaN, 0, "${t.javaClass.simpleName}: ${t.message}")
    }
}

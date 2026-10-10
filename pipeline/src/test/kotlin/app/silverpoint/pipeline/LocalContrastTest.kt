package app.silverpoint.pipeline

import app.silverpoint.recipes.RecipeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalContrastTest {
    @Test
    fun gaussianWeightsSumToOne() {
        for (sigma in floatArrayOf(1f, 2.5f, 20f)) {
            val w = LocalContrast.weights(sigma)
            var total = w[0]
            for (k in 1 until w.size) total += 2 * w[k]
            assertEquals(1f, total, 1e-5f)
        }
    }

    @Test
    fun blurOfFlatImageIsFlatAndSampleHitsBlockCentres() {
        val img = FloatArray(10 * 8) { 0.4f }
        val out = LocalContrast.blurPass(img, 10, 8, LocalContrast.weights(2f), true)
        for (v in out) assertEquals(0.4f, v, 1e-5f)
        val ramp = FloatArray(4 * 3) { it.toFloat() }
        // Block (i, j) is centred on sensor pixel (4i + 1.5, 4j + 1.5): pixel (9, 5) is at u = 1.875, v = 0.875.
        assertEquals(1.875f + 4f * 0.875f, LocalContrast.sample(ramp, 4, 3, 9, 5), 1e-5f)
        assertEquals(0f, LocalContrast.sample(ramp, 4, 3, 0, 0), 0f)
        assertEquals(11f, LocalContrast.sample(ramp, 4, 3, 100, 100), 0f)
    }

    @Test
    fun adjustLeavesFlatAreasAlone() {
        assertEquals(0.5f, LocalContrast.adjust(0.5f, 0.5f, 0.5f, 1f, 1f), 0f)
        assertTrue(LocalContrast.adjust(0.6f, 0.6f, 0.5f, 1f, 0f) > 0.6f)
        assertTrue(LocalContrast.adjust(0.4f, 0.4f, 0.5f, 1f, 0f) < 0.4f)
    }

    @Test
    fun clarityIncreasesContrastAcrossAnEdge() {
        val meta = SyntheticScene.meta(doubleArrayOf(0.49, 1.0, 0.75), width = 128, height = 64)
        val raw = SyntheticScene.flatGrey(meta, 0.05f)
        val bright = SyntheticScene.flatGrey(meta, 0.2f)
        for (y in 0 until 64) for (x in 64 until 128) raw[y * 128 + x] = bright[y * 128 + x]
        fun g(clarity: Float, x: Int): Int {
            val out = CpuDeveloper.develop(raw, 128, DevelopParams.build(meta, RecipeParams(clarity = clarity), 0))
            return out.argb[32 * 128 + x] shr 8 and 0xFF
        }
        assertTrue("dark side darker", g(100f, 60) < g(0f, 60))
        assertTrue("bright side brighter", g(100f, 68) > g(0f, 68))
        // Far from the edge nothing changes much.
        assertTrue(Math.abs(g(100f, 2) - g(0f, 2)) <= 2)
    }
}

package app.silverpoint.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrGainMapTest {
    @Test
    fun midtonesKeepTheirBrightnessAndHighlightsGetHeadroom() {
        assertEquals(1f, HdrGainMap.gain(0.05f, 0.1f), 0f)
        assertTrue(HdrGainMap.gain(0.2f, 0.1f) < 1.1f)
        assertTrue(HdrGainMap.gain(1f, 0.1f) > 1.4f)
        assertEquals(HdrGainMap.HEADROOM, HdrGainMap.gain(20f, 0.1f), 0f)
    }

    @Test
    fun encodedMapIsUprightAndScaled() {
        // 3x2 sensor-orientation image; the bright pixel is at the top-left.
        val linear = floatArrayOf(10f, 0f, 0f, 0f, 0f, 0f)
        val m = HdrGainMap.encode(linear, 3, 2, 0.1f, 90)
        assertEquals(2, m.width)
        assertEquals(3, m.height)
        // Rotated 90° clockwise, the top-left goes to the top-right.
        assertEquals(255, m.bytes[1].toInt() and 0xFF)
        assertEquals(0, m.bytes[0].toInt() and 0xFF)
    }

    @Test
    fun cpuDeveloperProvidesTheLowResolutionLuminance() {
        val meta = SyntheticScene.meta(doubleArrayOf(0.49, 1.0, 0.75), width = 64, height = 48)
        val out = CpuDeveloper.develop(SyntheticScene.busyFrame(meta), 64, DevelopParams.build(meta, app.silverpoint.recipes.RecipeParams(), 0))
        val low = out.lowRes!!
        assertEquals(16, low.width)
        assertEquals(12, low.height)
        // The clipped block in the bottom-right corner is the brightest area.
        assertTrue(low.linear.last() > low.linear.first())
    }
}

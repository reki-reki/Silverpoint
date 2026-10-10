package app.silverpoint.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrainTest {
    @Test
    fun pcgMatchesUnsigned32BitReference() {
        // Computed with Python integers masked to 32 bits, the same arithmetic as GLSL uint.
        assertEquals(129708002, Grain.pcg(0))
        assertEquals(-1463883204, Grain.pcg(1))
        assertEquals(-195121906, Grain.pcg(12345))
        assertEquals(-433436414, Grain.pcg(-1))
        assertEquals(-0.71743345f, Grain.lattice(3, 7, 1234), 1e-6f)
        assertEquals(0.4092317f, Grain.lattice(0, 0, 0), 1e-6f)
    }

    @Test
    fun grainIsDeterministicCentredAndVariesBySeed() {
        var sum = 0.0
        var sq = 0.0
        var differs = 0
        val n = 200 * 200
        for (y in 0 until 200) for (x in 0 until 200) {
            val g = Grain.at(x, y, 2.7f, 0.5f, 99)
            assertEquals(g, Grain.at(x, y, 2.7f, 0.5f, 99), 0f)
            if (g != Grain.at(x, y, 2.7f, 0.5f, 100)) differs++
            sum += g
            sq += g * g
        }
        val mean = sum / n
        val std = Math.sqrt(sq / n - mean * mean)
        assertTrue("mean $mean", Math.abs(mean) < 0.03)
        assertTrue("std $std", std in 0.2..0.6)
        assertTrue(differs > n * 0.9)
    }

    @Test
    fun grainCellScalesWithOutputSize() {
        assertEquals(1.175f, Grain.cellPixels(25f, 3000), 1e-5f)
        assertEquals(2.35f, Grain.cellPixels(25f, 6000), 1e-5f)
        assertTrue(Grain.cellPixels(80f, 4080) > Grain.cellPixels(20f, 4080))
        assertEquals(0.75f, Grain.cellPixels(0f, 500), 0f)
    }

    @Test
    fun amountAndWeightFollowLightroom() {
        assertEquals(0f, Grain.amplitude(0f), 0f)
        assertEquals(1f, Grain.amplitude(100f), 1e-6f)
        assertEquals(0.629f, Grain.amplitude(50f), 1e-3f)
        // Even from black to light grey, fading only near white.
        assertEquals(1f, Grain.weight(0f), 0f)
        assertEquals(1f, Grain.weight(0.8f), 0f)
        assertEquals(0.15f, Grain.weight(1f), 1e-6f)
    }
}

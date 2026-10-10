package app.silverpoint.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks ΔE2000 against the published test pairs (Sharma, Wu, Dalal 2005, table 1). */
class ColorScienceTest {
    private fun de(l1: Double, a1: Double, b1: Double, l2: Double, a2: Double, b2: Double) =
        ColorScience.deltaE2000(doubleArrayOf(l1, a1, b1), doubleArrayOf(l2, a2, b2))

    @Test
    fun matchesPublishedPairs() {
        assertEquals(2.0425, de(50.0, 2.6772, -79.7751, 50.0, 0.0, -82.7485), 1e-4) // pair 1
        assertEquals(1.0000, de(50.0, -1.3802, -84.2814, 50.0, 0.0, -82.7485), 1e-4) // pair 4
        assertEquals(4.3065, de(50.0, 2.5, 0.0, 50.0, 0.0, -2.5), 1e-4) // pair 17
        assertEquals(1.0000, de(50.0, 2.5, 0.0, 50.0, 3.2972, 0.0), 1e-4) // pair 22
        assertEquals(1.2644, de(60.2574, -34.0099, 36.2677, 60.4626, -34.1751, 39.4387), 1e-4) // pair 25
        assertEquals(1.2630, de(63.0109, -31.0961, -5.8663, 62.8187, -29.7946, -4.0864), 1e-4) // pair 26
        assertEquals(2.0373, de(22.7233, 20.0904, -46.6940, 23.0331, 14.9730, -42.5619), 1e-4) // pair 30
    }

    @Test
    fun whiteIsL100() {
        val lab = ColorScience.srgbToLab(0xFFFFFF)
        assertEquals(100.0, lab[0], 1e-3)
        assertEquals(0.0, lab[1], 1e-2)
        assertEquals(0.0, lab[2], 1e-2)
    }
}

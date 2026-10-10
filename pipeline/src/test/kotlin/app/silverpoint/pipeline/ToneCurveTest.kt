package app.silverpoint.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneCurveTest {
    @Test
    fun splinePassesThroughPointsAndIsMonotone() {
        val pts = listOf(0f to 6 / 255f, 64 / 255f to 56 / 255f, 0.5f to 0.5f, 192 / 255f to 202 / 255f, 1f to 252 / 255f)
        val f = ToneCurve.monotoneSpline(pts)
        for ((x, y) in pts) assertEquals(y, f(x), 1e-5f)
        val lut = ToneCurve.bake(f = f)
        assertTrue(lut.toList().zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun identityCurveIsIdentity() {
        val f = ToneCurve.monotoneSpline(listOf(0f to 0f, 1f to 1f))
        for (i in 0..10) assertEquals(i / 10f, f(i / 10f), 1e-6f)
    }

    @Test
    fun flatSegmentStaysFlat() {
        val f = ToneCurve.monotoneSpline(listOf(0f to 0f, 0.3f to 0.4f, 0.6f to 0.4f, 1f to 1f))
        assertEquals(0.4f, f(0.45f), 1e-6f)
    }

    @Test
    fun contrastKeepsEndsAndMidAndIsMonotone() {
        for (amount in listOf(-0.6f, 0f, 0.6f)) {
            assertEquals(0f, ToneCurve.contrast(0f, amount), 1e-6f)
            assertEquals(0.5f, ToneCurve.contrast(0.5f, amount), 1e-6f)
            assertEquals(1f, ToneCurve.contrast(1f, amount), 1e-6f)
            val ys = (0..100).map { ToneCurve.contrast(it / 100f, amount) }
            assertTrue(ys.zipWithNext().all { (a, b) -> b >= a })
        }
        assertTrue(ToneCurve.contrast(0.25f, 0.6f) < 0.25f)
    }

    @Test
    fun shoulderIsContinuousAndBelowOne() {
        assertEquals(0.5f, ToneCurve.shoulder(0.5f), 0f)
        assertEquals(ToneCurve.SHOULDER_KNEE, ToneCurve.shoulder(ToneCurve.SHOULDER_KNEE + 1e-6f), 1e-5f)
        assertTrue(ToneCurve.shoulder(1.0f) < 1f)
        assertTrue(ToneCurve.shoulder(5f) <= 1f) // reaches 1 only through float rounding
        assertTrue(ToneCurve.shoulder(1.2f) > ToneCurve.shoulder(1.0f))
    }

    @Test
    fun srgbRoundTrips() {
        for (i in 0..20) {
            val x = i / 20f
            assertEquals(x, ToneCurve.srgbDecode(ToneCurve.srgbEncode(x)), 1e-5f)
        }
        assertEquals(0.4613f, ToneCurve.srgbEncode(0.18f), 1e-3f)
    }

    @Test
    fun lookupInterpolatesAndClamps() {
        val lut = floatArrayOf(0f, 0.5f, 1f)
        assertEquals(0.25f, ToneCurve.lookup(lut, 0.25f), 1e-6f)
        assertEquals(1f, ToneCurve.lookup(lut, 2f), 0f)
        assertEquals(0f, ToneCurve.lookup(lut, -1f), 0f)
    }

    @Test
    fun oklabRoundTrips() {
        val lab = FloatArray(3)
        val rgb = FloatArray(3)
        for ((r, g, b) in listOf(Triple(0.2f, 0.5f, 0.8f), Triple(1f, 0f, 0f), Triple(0.18f, 0.18f, 0.18f))) {
            Oklab.fromLinearSrgb(r, g, b, lab)
            Oklab.toLinearSrgb(lab[0], lab[1], lab[2], rgb)
            assertEquals(r, rgb[0], 1e-4f)
            assertEquals(g, rgb[1], 1e-4f)
            assertEquals(b, rgb[2], 1e-4f)
        }
        Oklab.fromLinearSrgb(0.18f, 0.18f, 0.18f, lab)
        assertEquals(0f, Oklab.chroma(lab[1], lab[2]), 1e-4f)
    }
}

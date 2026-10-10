package app.silverpoint.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {
    @Test
    fun inverseTimesMatrixIsIdentity() {
        val m = Mat3.of(0.6, 0.2, 0.1, -0.3, 1.2, 0.1, 0.05, -0.2, 0.9)
        val i = m * m.inverse()
        for (r in 0..2) for (c in 0..2) assertEquals(if (r == c) 1.0 else 0.0, i[r, c], 1e-12)
    }

    @Test
    fun mcCamyGivesKnownTemperatures() {
        assertEquals(6504.0, ColorMath.cct(0.31271, 0.32902), 15.0)
        assertEquals(2856.0, ColorMath.cct(0.44757, 0.40745), 15.0)
    }

    @Test
    fun weightFollowsInverseTemperature() {
        assertEquals(1.0, ColorMath.weight1(6504.0, 6504.0, 2856.0), 1e-9)
        assertEquals(0.0, ColorMath.weight1(2856.0, 6504.0, 2856.0), 1e-9)
        assertEquals(1.0, ColorMath.weight1(9000.0, 6504.0, 2856.0), 1e-9) // clamped
        val mid = ColorMath.weight1(4000.0, 6504.0, 2856.0)
        assertTrue(mid in 0.3..0.7)
    }

    @Test
    fun neutralSurfaceComesOutGreyWithForwardMatrix() {
        val meta = SyntheticScene.meta(neutral = doubleArrayOf(0.49, 1.0, 0.75))
        val m = ColorMath.cameraToSrgb(meta)
        val gains = ColorMath.whiteBalanceGains(meta)
        val wb = DoubleArray(3) { meta.asShotNeutral[it] * gains[it] }
        val rgb = m * wb
        // Equal within 1e-4: the published XYZ-to-sRGB matrix is rounded to 7 digits.
        assertEquals(rgb[0], rgb[1], 1e-4)
        assertEquals(rgb[2], rgb[1], 1e-4)
        assertTrue(rgb[1] > 0)
    }

    @Test
    fun neutralSurfaceComesOutGreyWithColorMatrixOnly() {
        val meta = SyntheticScene.meta(neutral = doubleArrayOf(0.55, 1.0, 0.7), withForward = false)
        val gains = ColorMath.whiteBalanceGains(meta)
        val rgb = ColorMath.cameraToSrgb(meta) * DoubleArray(3) { meta.asShotNeutral[it] * gains[it] }
        assertEquals(rgb[0], rgb[1], 1e-4)
        assertEquals(rgb[2], rgb[1], 1e-4)
    }

    @Test
    fun whitePointOfD65NeutralIsNearD65() {
        // Neutral = camera response to D65 white under calibration 1 (illuminant 21 = D65).
        val base = SyntheticScene.meta(neutral = doubleArrayOf(1.0, 1.0, 1.0))
        val d65 = doubleArrayOf(0.95047, 1.0, 1.08883)
        val n = base.calibration1.colorMatrix * d65
        val meta = SyntheticScene.meta(neutral = doubleArrayOf(n[0] / n[1], 1.0, n[2] / n[1]))
        val s = ColorMath.solve(meta)
        assertEquals(0.3127, s.whiteXy.first, 0.002)
        assertEquals(0.3290, s.whiteXy.second, 0.002)
        assertTrue(s.weight1 > 0.95)
    }

    @Test
    fun bradfordMapsSourceWhiteToTargetWhite() {
        val a = ColorMath.bradford(0.44757 to 0.40745, 0.3457 to 0.3585)
        val src = doubleArrayOf(0.44757 / 0.40745, 1.0, (1 - 0.44757 - 0.40745) / 0.40745)
        val out = a * src
        assertEquals(0.3457 / 0.3585, out[0], 1e-4)
        assertEquals(1.0, out[1], 1e-4)
    }

    @Test
    fun planckianLocusHitsKnownPoints() {
        // Standard illuminant A is a black body at 2856 K: x 0.44757, y 0.40745.
        val (x, y) = ColorMath.planckianXy(2856.0)
        assertEquals(0.44757, x, 5e-4)
        assertEquals(0.40745, y, 5e-4)
    }

    @Test
    fun neutralForATemperatureSolvesBackToIt() {
        val meta = SyntheticScene.meta(doubleArrayOf(0.5, 1.0, 0.7))
        for (k in doubleArrayOf(3000.0, 4500.0, 6500.0)) {
            val solved = ColorMath.solve(meta.withNeutral(ColorMath.neutralFor(meta, k)))
            assertEquals("at $k K", k, solved.temperature, k * 0.02)
        }
    }

    @Test
    fun warmerLightNeedsMoreBlueGain() {
        val meta = SyntheticScene.meta(doubleArrayOf(0.5, 1.0, 0.7))
        val tungsten = ColorMath.neutralFor(meta, 3000.0)
        val daylight = ColorMath.neutralFor(meta, 6000.0)
        // Under tungsten the camera sees relatively more red and less blue.
        assertTrue(tungsten[0] > daylight[0])
        assertTrue(tungsten[2] < daylight[2])
    }

    @Test
    fun positiveTintTurnsThePhotoMagentaAsInLightroom() {
        // A grey card under 5000 K light, balanced as 5000 K with a tint. As in Lightroom, the tint
        // names the light being corrected: +tint assumes greenish light, so the photo turns magenta.
        val base = SyntheticScene.meta(doubleArrayOf(0.5, 1.0, 0.7), width = 8, height = 8)
        val scene = base.withNeutral(ColorMath.neutralFor(base, 5000.0))
        val raw = SyntheticScene.flatGrey(scene, 0.4f)
        fun render(tint: Double): FloatArray {
            val meta = scene.withNeutral(ColorMath.neutralFor(scene, 5000.0, tint))
            val p = DevelopParams.build(meta, app.silverpoint.recipes.RecipeParams(), 0, base = BaseLook.NONE)
            val rgb = CpuDeveloper.linearSrgb(raw, meta.width, p)
            val i = (4 * meta.width + 4) * 3
            return floatArrayOf(rgb[i], rgb[i + 1], rgb[i + 2])
        }
        val plus = render(30.0)
        assertTrue("+30 tint should be magenta: ${plus.toList()}", plus[1] < plus[0] && plus[1] < plus[2])
        val minus = render(-30.0)
        assertTrue("-30 tint should be green: ${minus.toList()}", minus[1] > minus[0] && minus[1] > minus[2])
        // The assumed light for +tint lies on the green side of the black-body locus.
        assertTrue(ColorMath.whiteXy(5000.0, 30.0).second > ColorMath.whiteXy(5000.0, -30.0).second)
    }
}

package app.silverpoint.pipeline

import app.silverpoint.recipes.RecipeParams
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Regression check of the base look on two crops of a real S25 Ultra DNG against the same regions
 * of Lightroom's no-edit export (see `scripts/make_fixture.py`). Fails if a change to the pipeline
 * drifts the neutral rendering away from Lightroom's.
 */
class LightroomMatchTest {

    private class Result(val meanDe: Double, val luminanceRatio: Double, val brightest: Int, val medianY: Double, val refMedianY: Double) {
        /** Median lightness difference in CIE L* (ours minus Lightroom): what the eye sees, even in deep shadows. */
        val lightnessDiff get() = lstar(medianY) - lstar(refMedianY)
    }

    /** Develops [name] with no recipe and compares 4x4-pixel blocks (in the half-size reference) with Lightroom. */
    private fun compare(name: String, base: BaseLook = BaseLook.DEFAULT): Result {
        val f = Fixture.load(name)
        val p = DevelopParams.build(f.meta, RecipeParams(), rotation = 90, base = base)
        val ours = CpuDeveloper.develop(f.raw, f.meta.width, p)
        val ref = Fixture.lightroom(name)
        require(ours.width == ref.width * 2 && ours.height == ref.height * 2) { "size mismatch" }
        val block = 4
        var sum = 0.0
        var count = 0
        val ys = ArrayList<Double>()
        val refYs = ArrayList<Double>()
        var brightest = 0
        for (v in ours.argb) brightest = maxOf(brightest, v shr 8 and 0xFF)
        for (by in 0 until ref.height / block) for (bx in 0 until ref.width / block) {
            val o = DoubleArray(3)
            val r = DoubleArray(3)
            for (y in 0 until block) for (x in 0 until block) {
                val rx = bx * block + x
                val ry = by * block + y
                addLinear(r, ref[rx, ry], 1.0)
                for (sy in 0..1) for (sx in 0..1) addLinear(o, ours.argb[(ry * 2 + sy) * ours.width + rx * 2 + sx], 0.25)
            }
            for (i in 0..2) {
                o[i] /= block * block
                r[i] /= block * block
            }
            sum += ColorScience.deltaE2000(ColorScience.linearToLab(o[0], o[1], o[2]), ColorScience.linearToLab(r[0], r[1], r[2]))
            count++
            ys += 0.2126 * o[0] + 0.7152 * o[1] + 0.0722 * o[2]
            refYs += 0.2126 * r[0] + 0.7152 * r[1] + 0.0722 * r[2]
        }
        ys.sort()
        refYs.sort()
        return Result(sum / count, ys[ys.size / 2] / refYs[refYs.size / 2], brightest, ys[ys.size / 2], refYs[refYs.size / 2])
    }

    private fun addLinear(acc: DoubleArray, px: Int, weight: Double) {
        acc[0] += ToneCurve.srgbDecode((px shr 16 and 0xFF) / 255f) * weight
        acc[1] += ToneCurve.srgbDecode((px shr 8 and 0xFF) / 255f) * weight
        acc[2] += ToneCurve.srgbDecode((px and 0xFF) / 255f) * weight
    }

    private fun report(name: String, r: Result) =
        String.format(Locale.US, "%s: mean dE2000 %.2f, median luminance ratio %.3f (L* %+.1f), brightest green %d", name, r.meanDe, r.luminanceRatio, r.lightnessDiff, r.brightest)

    @Test
    fun windowWallMatchesLightroom() {
        val r = compare("window_wall")
        println(report("window_wall", r))
        assertTrue(report("window_wall", r), r.meanDe < WALL_MAX_DE)
        assertTrue(report("window_wall", r), r.luminanceRatio in 0.9..1.1)
        assertTrue("the window-lit wall must not clip to white: ${report("window_wall", r)}", r.brightest < 255)
    }

    @Test
    fun cushionMatchesLightroom() {
        val r = compare("cushion")
        println(report("cushion", r))
        assertTrue(report("cushion", r), r.meanDe < CUSHION_MAX_DE)
        // Mostly deep browns: the neutral rendering's deepest shadows sit a few L* above Lightroom's (the
        // looks' shared match tone corrects that; docs/LIGHTROOM_MATCHING.md section 5). In linear light
        // that is a large ratio of small numbers, so judge it in L*.
        assertTrue(report("cushion", r), r.lightnessDiff in -2.0..5.0)
    }

    @Test
    fun baseLookIsBetterThanNone() {
        for (name in listOf("window_wall", "cushion")) {
            val with = compare(name)
            val without = compare(name, BaseLook.NONE)
            println("$name: base look ${with.meanDe}, none ${without.meanDe}")
            assertTrue("$name: base look should beat no base look (${with.meanDe} vs ${without.meanDe})", with.meanDe < without.meanDe)
        }
    }

    companion object {
        private fun lstar(y: Double) = if (y > 216.0 / 24389.0) 116.0 * Math.cbrt(y) - 16.0 else y * 24389.0 / 27.0

        // Set from the fitted result plus a margin; tighten as the base look improves.
        const val WALL_MAX_DE = 3.6
        const val CUSHION_MAX_DE = 3.4
    }
}

package app.silverpoint.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class MergeTest {
    private val w = 512
    private val h = 384
    private val black = floatArrayOf(64f, 64f, 64f, 64f)
    private val white = 1023f
    private val noise = Merge.Noise(scale = 2e-4, offset = 2e-6)

    /** A smooth but textured scene (normalised 0..1) that alignment can lock onto, with a flat patch. */
    private fun scene(x: Double, y: Double): Double {
        if (x in 360.0..470.0 && y in 40.0..150.0) return 0.25 // flat patch for measuring noise
        return 0.3 + 0.15 * Math.sin(x / 9.0) * Math.cos(y / 13.0) + 0.1 * Math.sin((x + 2 * y) / 23.0) +
            if (((x / 40).toInt() + (y / 40).toInt()) % 2 == 0) 0.1 else 0.0
    }

    /** One RAW frame of the scene shifted by (sx, sy) sensor pixels, with Poisson-like noise. */
    private fun frame(sx: Int, sy: Int, seed: Long, movingSquareAt: Int? = null): ShortArray {
        val rnd = Random(seed)
        return ShortArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var v = scene((x + sx).toDouble(), (y + sy).toDouble())
            if (movingSquareAt != null && x in movingSquareAt until movingSquareAt + 40 && y in 200 until 240) v = 0.9
            val sigma = sqrt(noise.scale * v + noise.offset)
            val n = v + rnd.nextGaussian() * sigma
            (64 + n * (white - 64)).toInt().coerceIn(0, 1023).toShort()
        }
    }

    private fun normalised(raw: ShortArray, i: Int, scale: Int = 1) = ((raw[i].toInt() and 0xFFFF) / scale.toDouble() - 64) / (white - 64)

    @Test
    fun alignmentFindsHandShake() {
        val ref = frame(0, 0, 1)
        val m = Merge.Merger(ref, w, w, h, black, white, noise)
        m.add(frame(6, -4, 2))
        // The frame shows the scene moved by (6, -4) sensor pixels, so it lines up when read at
        // (-6, +4): (-3, 2) in grey pixels (one grey pixel = 2 sensor pixels).
        val offsets = m.lastOffsets.single()
        val tilesX = (w / 2 + Merge.TILE - 1) / Merge.TILE
        val centre = offsets[(h / 4 / Merge.TILE) * tilesX + w / 4 / Merge.TILE]
        assertEquals(-3, centre[0])
        assertEquals(2, centre[1])
        val agree = offsets.count { it[0] == -3 && it[1] == 2 }
        assertTrue("$agree of ${offsets.size} tiles agree", agree > offsets.size * 0.8)
    }

    @Test
    fun mergingEightFramesCutsNoiseAboutThreefold() {
        val shifts = listOf(0 to 0, 2 to 0, -4 to 2, 6 to -2, 0 to 4, -2 to -6, 8 to 2, 4 to 4)
        val ref = frame(0, 0, 10)
        val m = Merge.Merger(ref, w, w, h, black, white, noise)
        shifts.drop(1).forEachIndexed { k, (sx, sy) -> m.add(frame(sx, sy, 11L + k)) }
        val merged = m.result()
        fun std(raw: ShortArray, scale: Int): Double {
            val v = ArrayList<Double>()
            // Inside the flat patch, same CFA position (even x, even y), clear of its edges in every frame.
            for (y in 60 until 130 step 2) for (x in 380 until 450 step 2) v += normalised(raw, y * w + x, scale)
            val mean = v.average()
            return sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
        }
        val single = std(ref, 1)
        val eight = std(merged, Merge.SCALE)
        assertTrue("noise $single -> $eight", eight < single / 2.2)
        assertTrue("frames should be used almost fully: ${m.frameWeights}", m.frameWeights.all { it > 0.85f })
        // Brightness is unchanged.
        val meanMerged = (60 until 130 step 2).flatMap { y -> (380 until 450 step 2).map { x -> normalised(merged, y * w + x, Merge.SCALE) } }.average()
        assertEquals(0.25, meanMerged, 0.005)
    }

    @Test
    fun movingObjectsDoNotGhost() {
        val ref = frame(0, 0, 20, movingSquareAt = 100)
        val m = Merge.Merger(ref, w, w, h, black, white, noise)
        for (k in 1..5) m.add(frame(0, 0, 20L + k, movingSquareAt = 100 + 60 * k))
        val merged = m.result()
        // Inside the square in the reference: stays bright. Where it later moved: stays background.
        val inSquare = normalised(merged, 220 * w + 120, Merge.SCALE)
        assertEquals(0.9, inSquare, 0.05)
        val x = 230
        val background = scene(x.toDouble(), 220.0)
        assertEquals(background, normalised(merged, 220 * w + x, Merge.SCALE), 0.05)
    }

    @Test
    fun noiseProfileFallsBackToIso() {
        val n = Merge.Noise.of(null, 800)
        assertTrue(n.scale > Merge.Noise.of(null, 100).scale)
        val p = Merge.Noise.of(listOf(1e-4 to 1e-6, 3e-4 to 3e-6), 100)
        assertEquals(2e-4, p.scale, 1e-12)
    }
}

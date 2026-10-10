package app.silverpoint.pipeline

import kotlin.math.floor
import kotlin.math.pow

/**
 * Film grain: value noise on lattices of hashed random values, so it is the same on every run and
 * identical on the CPU and the GPU (`grain()` in `develop.comp` uses the same integer hash).
 * Monochrome, sized relative to the output so a photo of any resolution gets grain of the same look.
 *
 * Matched to Lightroom's grain (October 2026 photo pairs): fine and sharp (about a 1.6 pixel cell at
 * Size 25 on a 4000-pixel photo), equally strong from shadows to bright highlights, and growing
 * with Amount^0.67. Two lattices turned against each other keep the cells from looking square.
 */
object Grain {
    /** Output long edge at which [cellPixels] equals the slider-derived size. */
    const val REFERENCE_LONG_EDGE = 3000f

    /** Encoded-value amplitude of the grain at Amount 100. */
    const val STRENGTH = 0.09f

    /** Lattice cell size in output pixels for Lightroom's GrainSize (0..100) and an output long edge. */
    fun cellPixels(grainSize: Float, outLongEdge: Int): Float =
        maxOf(0.75f, (0.75f + 0.017f * grainSize.coerceIn(0f, 100f)) * outLongEdge / REFERENCE_LONG_EDGE)

    /** Grain amplitude 0..1 for Lightroom's GrainAmount (0..100): strength grows as Amount^0.67, as Lightroom's does. */
    fun amplitude(grainAmount: Float): Float = (grainAmount.coerceIn(0f, 100f) / 100f).toDouble().pow(0.67).toFloat()

    /** PCG hash (O'Neill), 32-bit unsigned arithmetic carried in an Int. */
    fun pcg(v: Int): Int {
        val state = v * 747796405 + -1403630843 // 2891336453 as a signed Int
        val word = ((state ushr ((state ushr 28) + 4)) xor state) * 277803737
        return (word ushr 22) xor word
    }

    /** Lattice value in -1..1 for cell ([x], [y]) of noise layer [seed]. */
    fun lattice(x: Int, y: Int, seed: Int): Float {
        val h = pcg(x + pcg(y + pcg(seed)))
        return (h and 0xFFFF) / 65535f * 2f - 1f
    }

    /** Smoothly interpolated value noise at pixel position ([px], [py]) with cells of [cell] pixels. */
    fun valueNoise(px: Float, py: Float, cell: Float, seed: Int): Float {
        val fx = px / cell
        val fy = py / cell
        val ix = floor(fx)
        val iy = floor(fy)
        val tx = fx - ix
        val ty = fy - iy
        val sx = tx * tx * (3f - 2f * tx)
        val sy = ty * ty * (3f - 2f * ty)
        val x0 = ix.toInt()
        val y0 = iy.toInt()
        val a = lattice(x0, y0, seed)
        val b = lattice(x0 + 1, y0, seed)
        val c = lattice(x0, y0 + 1, seed)
        val d = lattice(x0 + 1, y0 + 1, seed)
        val top = a + (b - a) * sx
        val bottom = c + (d - c) * sx
        return top + (bottom - top) * sy
    }

    /**
     * Grain value (about -1..1) at output pixel ([ox], [oy]). [roughness] 0..1 (Lightroom's
     * GrainFrequency / 100) mixes in a finer second layer.
     */
    fun at(ox: Int, oy: Int, cell: Float, roughness: Float, seed: Int): Float {
        val px = ox + 0.5f
        val py = oy + 0.5f
        // A second lattice turned by about 37° (offset so coordinates stay positive).
        val rx = ROT_C * px - ROT_S * py + ROT_OFFSET
        val ry = ROT_S * px + ROT_C * py
        val coarse = (valueNoise(px, py, cell, seed) + valueNoise(rx, ry, cell, seed + 2)) * HALF_SQRT2
        val fine = (valueNoise(px, py, cell * 0.5f, seed + 1) + valueNoise(rx, ry, cell * 0.5f, seed + 3)) * HALF_SQRT2
        val k = 0.5f * roughness
        return coarse * (1f - k) + fine * k
    }

    /** How much grain a pixel of encoded luminance [y] gets: all of it, fading only towards white. */
    fun weight(y: Float): Float = 1f - 0.85f * RecipeLook.smoothstep(0.8f, 1f, y)

    private const val ROT_C = 0.8f
    private const val ROT_S = 0.6f
    private const val ROT_OFFSET = 8192f
    private const val HALF_SQRT2 = 0.70710678f
}

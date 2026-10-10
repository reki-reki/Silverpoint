package app.silverpoint.pipeline

import java.util.stream.IntStream
import kotlin.math.abs

/**
 * Multi-frame RAW merge for lower noise, in the spirit of Google's HDR+ (Hasinoff et al., 2016):
 * align each frame to a reference with a coarse-to-fine tile search, then average it in with a
 * per-pixel weight that drops to zero where it disagrees with the reference by more than noise
 * explains (moving people, cars, leaves). Written from the paper's ideas; no code is copied.
 *
 * Works on the CFA directly. Alignment runs on a half-resolution grey image (one value per 2x2 CFA
 * quad), so every offset is a whole number of quads and the colour pattern stays in phase.
 *
 * Usage: [Merger] takes the reference frame, then [Merger.add] each other frame (so the camera's
 * buffer can be released after each), then [Merger.result].
 */
object Merge {
    /** Tile size in grey pixels (32 CFA pixels). */
    const val TILE = 16

    /** Coarsest level searches ±this many pixels; the finer levels refine by ±[REFINE]. */
    const val COARSE_SEARCH = 8
    const val REFINE = 2
    const val LEVELS = 4

    /** Merged frames are written with this many times the original precision (black and white levels scaled to match). */
    const val SCALE = 16

    /**
     * Sensor noise as in Camera2's `SENSOR_NOISE_PROFILE`: variance = [scale] × value + [offset],
     * for values normalised so black is 0 and white is 1.
     */
    class Noise(val scale: Double, val offset: Double) {
        fun variance(v: Float): Float = (scale * v.coerceAtLeast(0f) + offset).toFloat().coerceAtLeast(1e-9f)

        companion object {
            /** Average of the per-channel pairs Camera2 reports; a guess from the ISO when there are none. */
            fun of(pairs: List<Pair<Double, Double>>?, iso: Int?): Noise =
                if (!pairs.isNullOrEmpty()) Noise(pairs.map { it.first }.average(), pairs.map { it.second }.average())
                else Noise(2.2e-4 * (iso ?: 100) / 100.0, 1e-7 * (iso ?: 100) / 100.0)
        }
    }

    /** A grey image and its downsampled pyramid (level 0 = half resolution of the CFA). */
    class Pyramid(val levels: List<Grey>)

    class Grey(val width: Int, val height: Int, val data: FloatArray) {
        operator fun get(x: Int, y: Int): Float = data[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
    }

    /** One quad per grey pixel: the mean of the 2x2 CFA block, black subtracted and normalised. */
    fun grey(raw: ShortArray, stride: Int, width: Int, height: Int, black: FloatArray, white: Float): Grey {
        val gw = width / 2
        val gh = height / 2
        val out = FloatArray(gw * gh)
        val b = black.average().toFloat()
        val range = white - b
        IntStream.range(0, gh).parallel().forEach { j ->
            val r0 = 2 * j * stride
            val r1 = r0 + stride
            for (i in 0 until gw) {
                val x = 2 * i
                val s = (raw[r0 + x].toInt() and 0xFFFF) + (raw[r0 + x + 1].toInt() and 0xFFFF) +
                    (raw[r1 + x].toInt() and 0xFFFF) + (raw[r1 + x + 1].toInt() and 0xFFFF)
                out[j * gw + i] = (s / 4f - b) / range
            }
        }
        return Grey(gw, gh, out)
    }

    fun pyramid(g: Grey): Pyramid {
        val levels = ArrayList<Grey>()
        levels += g
        repeat(LEVELS - 1) {
            val p = levels.last()
            val w = maxOf(1, p.width / 2)
            val h = maxOf(1, p.height / 2)
            val d = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                d[y * w + x] = (p[2 * x, 2 * y] + p[2 * x + 1, 2 * y] + p[2 * x, 2 * y + 1] + p[2 * x + 1, 2 * y + 1]) / 4f
            }
            levels += Grey(w, h, d)
        }
        return Pyramid(levels)
    }

    /** Sum of absolute differences of a [size] square at (x0, y0) in [a] against [b] shifted by (dx, dy). */
    private fun sad(a: Grey, b: Grey, x0: Int, y0: Int, size: Int, dx: Int, dy: Int): Float {
        var s = 0f
        for (y in y0 until minOf(y0 + size, a.height)) {
            for (x in x0 until minOf(x0 + size, a.width)) s += abs(a.data[y * a.width + x] - b[x + dx, y + dy])
        }
        return s
    }

    /**
     * Per-tile offsets (in grey pixels, tile grid of [TILE]) that align [frame] to [reference]:
     * a global shift found on the coarsest level, then per-tile refinement level by level.
     */
    fun align(reference: Pyramid, frame: Pyramid): Array<IntArray> {
        val top = LEVELS - 1
        val ra = reference.levels[top]
        val fa = frame.levels[top]
        // Global search at the coarsest level, on the central part of the frame.
        var best = Float.MAX_VALUE
        var gx = 0
        var gy = 0
        val mx = ra.width / 6
        val my = ra.height / 6
        for (dy in -COARSE_SEARCH..COARSE_SEARCH) for (dx in -COARSE_SEARCH..COARSE_SEARCH) {
            var s = 0f
            for (y in my until ra.height - my) for (x in mx until ra.width - mx) s += abs(ra.data[y * ra.width + x] - fa[x + dx, y + dy])
            if (s < best) {
                best = s
                gx = dx
                gy = dy
            }
        }
        val g0 = reference.levels[0]
        val tilesX = (g0.width + TILE - 1) / TILE
        val tilesY = (g0.height + TILE - 1) / TILE
        val offsets = Array(tilesX * tilesY) { intArrayOf(gx shl top, gy shl top) }
        // Refine per tile from level top-1 down to 0. Offsets are kept in level-0 pixels.
        for (level in top - 1 downTo 0) {
            val r = reference.levels[level]
            val f = frame.levels[level]
            val scale = 1 shl level
            val window = maxOf(TILE / scale, 8)
            IntStream.range(0, tilesX * tilesY).parallel().forEach { t ->
                val tx = t % tilesX
                val ty = t / tilesX
                val cx = (tx * TILE + TILE / 2) / scale
                val cy = (ty * TILE + TILE / 2) / scale
                val x0 = (cx - window / 2).coerceIn(0, maxOf(r.width - window, 0))
                val y0 = (cy - window / 2).coerceIn(0, maxOf(r.height - window, 0))
                val ox = offsets[t][0] / scale
                val oy = offsets[t][1] / scale
                var bestS = Float.MAX_VALUE
                var bx = ox
                var by = oy
                for (dy in -REFINE..REFINE) for (dx in -REFINE..REFINE) {
                    val s = sad(r, f, x0, y0, window, ox + dx, oy + dy)
                    // Prefer the smaller move on ties, which keeps flat areas from wandering.
                    if (s < bestS - 1e-6f) {
                        bestS = s
                        bx = ox + dx
                        by = oy + dy
                    }
                }
                offsets[t][0] = bx * scale
                offsets[t][1] = by * scale
            }
        }
        return offsets
    }

    /** Accumulates frames into one; see [Merge]. */
    class Merger(
        private val reference: ShortArray,
        private val stride: Int,
        val width: Int,
        val height: Int,
        private val black: FloatArray,
        private val white: Float,
        private val noise: Noise,
    ) {
        private val refGrey = grey(reference, stride, width, height, black, white)
        private val refPyramid = pyramid(refGrey)
        private val acc = FloatArray(width * height)
        private val weight = FloatArray(refGrey.width * refGrey.height) { 1f }
        private val norm = FloatArray(4) { 1f / (white - black[it]) }

        /** How many frames went in, and the mean weight each non-reference frame got (1 = fully used). */
        var frames = 1
            private set
        val frameWeights = ArrayList<Float>()
        val lastOffsets = ArrayList<Array<IntArray>>()

        init {
            for (y in 0 until height) for (x in 0 until width) acc[y * width + x] = value(reference, y * stride + x, x, y)
        }

        private fun value(raw: ShortArray, i: Int, x: Int, y: Int): Float {
            val k = (x and 1) + 2 * (y and 1)
            return ((raw[i].toInt() and 0xFFFF) - black[k]) * norm[k]
        }

        /** Aligns [frame] (same size and stride as the reference) and averages it in. */
        fun add(frame: ShortArray) {
            val g = grey(frame, stride, width, height, black, white)
            val offsets = align(refPyramid, pyramid(g))
            lastOffsets += offsets
            val gw = refGrey.width
            val gh = refGrey.height
            val tilesX = (gw + TILE - 1) / TILE
            val used = DoubleArray(gh)
            IntStream.range(0, gh).parallel().forEach { j ->
                var rowWeight = 0.0
                for (i in 0 until gw) {
                    val o = offsets[(j / TILE) * tilesX + i / TILE]
                    val dx = o[0]
                    val dy = o[1]
                    // Local mean difference over 3x3 grey pixels; its noise variance is 2σ²/9 of a
                    // grey pixel, whose own variance is a quarter of a sample's.
                    var d = 0f
                    for (yy in -1..1) for (xx in -1..1) d += g[i + xx + dx, j + yy + dy] - refGrey[i + xx, j + yy]
                    d /= 9f
                    val sigma2 = 2f * noise.variance(refGrey[i, j]) / 4f / 9f
                    val z = d * d / sigma2
                    val w = if (z <= Z_FULL) 1f else maxOf(0f, 1f - (z - Z_FULL) / Z_FADE)
                    val sx = 2 * (i + dx)
                    val sy = 2 * (j + dy)
                    // Where the shifted quad falls outside the frame, only the reference counts.
                    if (sx < 0 || sy < 0 || sx + 1 >= width || sy + 1 >= height) continue
                    rowWeight += w
                    if (w <= 0f) continue
                    weight[j * gw + i] += w
                    for (qy in 0..1) for (qx in 0..1) {
                        val x = 2 * i + qx
                        val y = 2 * j + qy
                        acc[y * width + x] += w * value(frame, (sy + qy) * stride + sx + qx, sx + qx, sy + qy)
                    }
                }
                used[j] = rowWeight / gw
            }
            frameWeights += used.average().toFloat()
            frames++
        }

        /**
         * The merged frame as 16-bit samples ([width] per row) at [SCALE] times the original precision:
         * use black and white levels multiplied by [SCALE] to develop it.
         */
        fun result(): ShortArray {
            val out = ShortArray(width * height)
            IntStream.range(0, height).parallel().forEach { y ->
                for (x in 0 until width) {
                    val k = (x and 1) + 2 * (y and 1)
                    val w = weight[(y / 2).coerceAtMost(refGrey.height - 1) * refGrey.width + (x / 2).coerceAtMost(refGrey.width - 1)]
                    val v = acc[y * width + x] / w
                    val raw = (black[k] + v * (white - black[k])) * SCALE
                    out[y * width + x] = raw.coerceIn(0f, 65535f).toInt().toShort()
                }
            }
            return out
        }
    }

    /** Below this squared z-score a frame counts fully; above, its weight fades to 0 over [Z_FADE]. */
    const val Z_FULL = 2f
    const val Z_FADE = 6f
}

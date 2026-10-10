package app.silverpoint.pipeline

import app.silverpoint.recipes.RecipeParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Development tool, not a regular test: fits [BaseLook] so that a full frame developed with no
 * recipe matches Lightroom's no-edit export of the same DNG. Runs only when the environment variable
 * `SILVERPOINT_FULL_FIXTURE` points at a directory made by `scripts/make_fixture.py ... --full`
 * (containing full.raw.gz, full.json, alignment.json and the Lightroom JPEG as lightroom.jpg).
 *
 *     SILVERPOINT_FULL_FIXTURE=/path/to/dir ./gradlew :pipeline:testDebugUnitTest --tests '*LightroomFitTest*'
 *
 * Prints the fitted values for `BaseLook.DEFAULT` and writes `fit_side_by_side.png` into the directory.
 */
class LightroomFitTest {
    private val dir = System.getProperty("silverpoint.fullFixture")?.let(::File)

    /** One comparison sample: our block-averaged linear sRGB and Lightroom's Lab at the same place. */
    private class Sample(val r: Float, val g: Float, val b: Float, val refLab: DoubleArray, val refY: Double)

    @Test
    fun fitBaseLookToLightroom() {
        assumeTrue("set SILVERPOINT_FULL_FIXTURE to run the Lightroom fit", dir != null && File(dir, "full.json").exists())
        val d = dir!!
        val fixture = Fixture.load("full", d)
        val meta = fixture.meta
        val align = Json.parseToJsonElement(File(d, "alignment.json").readText()) as JsonObject
        val scale = (align["scale"] as JsonPrimitive).double
        val dx = (align["dx"] as JsonPrimitive).int
        val dy = (align["dy"] as JsonPrimitive).int
        val ref = Fixture.image(File(d, "lightroom.jpg"))

        val p0 = DevelopParams.build(meta, RecipeParams(), rotation = 0, base = BaseLook.NONE)
        val lin = CpuDeveloper.linearSrgb(fixture.raw, meta.width, p0)

        // Upright image (rotated 90° clockwise) is H wide and W tall; compare on 8x8 blocks of it.
        val upW = meta.height
        val upH = meta.width
        val block = 8
        val bw = upW / block
        val bh = upH / block
        val samples = ArrayList<Sample>()
        val ours = Array(bh) { arrayOfNulls<FloatArray>(bw) }
        for (by in 0 until bh) for (bx in 0 until bw) {
            var r = 0.0
            var g = 0.0
            var b = 0.0
            for (yy in 0 until block) for (xx in 0 until block) {
                val ux = bx * block + xx
                val uy = by * block + yy
                // Upright (ux, uy) comes from sensor (uy, H - 1 - ux).
                val i = ((meta.height - 1 - ux) * meta.width + uy) * 3
                r += lin[i]
                g += lin[i + 1]
                b += lin[i + 2]
            }
            val n = (block * block).toDouble()
            ours[by][bx] = floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
            // Skip the outer 12%: Lightroom corrected lens distortion there and we do not.
            if (bx < bw * 0.12 || bx > bw * 0.88 || by < bh * 0.12 || by > bh * 0.88) continue
            val lab = refLabAt(ref, (bx * block + block / 2.0) * scale + dx, (by * block + block / 2.0) * scale + dy, block * scale)
                ?: continue
            samples += Sample(ours[by][bx]!![0], ours[by][bx]!![1], ours[by][bx]!![2], lab.first, lab.second)
        }
        println("samples: ${samples.size}")

        fun evaluate(base: BaseLook): Pair<Double, Double> {
            val p = DevelopParams.build(meta, RecipeParams(), rotation = 0, base = base)
            val out = FloatArray(3)
            val tmp = FloatArray(3)
            var sum = 0.0
            val ys = DoubleArray(samples.size)
            val refYs = DoubleArray(samples.size)
            samples.forEachIndexed { k, s ->
                CpuDeveloper.finish(s.r, s.g, s.b, p, out, tmp)
                val lab = ColorScience.linearToLab(dec(out[0]), dec(out[1]), dec(out[2]))
                sum += ColorScience.deltaE2000(lab, s.refLab)
                ys[k] = 0.2126 * dec(out[0]) + 0.7152 * dec(out[1]) + 0.0722 * dec(out[2])
                refYs[k] = s.refY
            }
            ys.sort()
            refYs.sort()
            return sum / samples.size to ys[ys.size / 2] / refYs[refYs.size / 2]
        }

        val start = evaluate(BaseLook.NONE)
        println(String.format(Locale.US, "before fit (BaseLook.NONE): mean dE2000 %.2f, median luminance ratio %.3f", start.first, start.second))
        val current = evaluate(BaseLook.DEFAULT)
        println(String.format(Locale.US, "current BaseLook.DEFAULT: mean dE2000 %.2f, median luminance ratio %.3f", current.first, current.second))
        residualsByHue(samples, DevelopParams.build(meta, RecipeParams(), rotation = 0, base = BaseLook.DEFAULT))

        // Coordinate descent over the four parameters, shrinking the step each pass.
        var best = BaseLook(baselineEv = 1f, knee = 0.6f, contrast = 0f, chroma = 1f)
        var bestErr = evaluate(best).first
        var step = floatArrayOf(0.4f, 0.2f, 0.2f, 0.2f)
        val lo = floatArrayOf(-1f, 0f, -0.6f, 0.5f)
        val hi = floatArrayOf(3f, 0.95f, 0.9f, 2f)
        repeat(7) {
            for (k in 0..3) {
                var improved = true
                while (improved) {
                    improved = false
                    for (sign in intArrayOf(1, -1)) {
                        val v = floatArrayOf(best.baselineEv, best.knee, best.contrast, best.chroma)
                        v[k] = (v[k] + sign * step[k]).coerceIn(lo[k], hi[k])
                        val cand = BaseLook(v[0], v[1], v[2], v[3])
                        val err = evaluate(cand).first
                        if (err < bestErr - 1e-4) {
                            best = cand
                            bestErr = err
                            improved = true
                        }
                    }
                }
            }
            step = FloatArray(4) { step[it] / 2 }
        }
        val fit = evaluate(best)
        println(String.format(Locale.US, "fitted: baselineEv=%.2f knee=%.2f contrast=%.2f chroma=%.2f -> mean dE2000 %.2f, median luminance ratio %.3f",
            best.baselineEv, best.knee, best.contrast, best.chroma, fit.first, fit.second))
        residualsByHue(samples, DevelopParams.build(meta, RecipeParams(), rotation = 0, base = best))

        writeSideBySide(File(d, "fit_side_by_side.png"), ours, bw, bh, block, scale, dx, dy, ref, best, meta)
    }

    /** Where the remaining error is: mean dE, lightness and chroma differences, by Lightroom's hue (neutral samples apart). */
    private fun residualsByHue(samples: List<Sample>, p: DevelopParams) {
        val names = listOf("red", "orange", "yellow", "green", "cyan", "blue", "purple", "magenta", "neutral")
        val n = IntArray(9)
        val de = DoubleArray(9)
        val dl = DoubleArray(9)
        val dc = DoubleArray(9)
        val out = FloatArray(3)
        val tmp = FloatArray(3)
        for (s in samples) {
            CpuDeveloper.finish(s.r, s.g, s.b, p, out, tmp)
            val lab = ColorScience.linearToLab(dec(out[0]), dec(out[1]), dec(out[2]))
            val refC = Math.hypot(s.refLab[1], s.refLab[2])
            val hue = (Math.toDegrees(Math.atan2(s.refLab[2], s.refLab[1])) + 360) % 360
            // Lab hue angles: red ~20-40, orange ~60, yellow ~90, green ~140, cyan ~200, blue ~260-290, purple ~310, magenta ~340.
            val bin = if (refC < 8) 8 else when {
                hue < 45 -> 0
                hue < 75 -> 1
                hue < 105 -> 2
                hue < 170 -> 3
                hue < 235 -> 4
                hue < 295 -> 5
                hue < 325 -> 6
                else -> 7
            }
            n[bin]++
            de[bin] += ColorScience.deltaE2000(lab, s.refLab)
            dl[bin] += lab[0] - s.refLab[0]
            dc[bin] += Math.hypot(lab[1], lab[2]) - refC
        }
        for (i in 0..8) if (n[i] > 0) {
            println(String.format(Locale.US, "  %-8s n=%6d  dE %.2f  dL %+.2f  dC %+.2f", names[i], n[i], de[i] / n[i], dl[i] / n[i], dc[i] / n[i]))
        }
    }

    /** Lightroom's average colour over a square of side [size] centred on ([cx], [cy]), as Lab and linear luminance. */
    private fun refLabAt(ref: Picture, cx: Double, cy: Double, size: Double): Pair<DoubleArray, Double>? {
        val x0 = (cx - size / 2).toInt()
        val y0 = (cy - size / 2).toInt()
        val x1 = (cx + size / 2).toInt()
        val y1 = (cy + size / 2).toInt()
        if (x0 < 0 || y0 < 0 || x1 >= ref.width || y1 >= ref.height) return null
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0
        for (y in y0..y1) for (x in x0..x1) {
            val px = ref[x, y]
            r += dec((px shr 16 and 0xFF) / 255f)
            g += dec((px shr 8 and 0xFF) / 255f)
            b += dec((px and 0xFF) / 255f)
            n++
        }
        r /= n
        g /= n
        b /= n
        return ColorScience.linearToLab(r, g, b) to (0.2126 * r + 0.7152 * g + 0.0722 * b)
    }

    private fun dec(v: Float): Double = ToneCurve.srgbDecode(v.coerceIn(0f, 1f)).toDouble()

    private fun writeSideBySide(
        file: File, ours: Array<Array<FloatArray?>>, bw: Int, bh: Int, block: Int,
        scale: Double, dx: Int, dy: Int, ref: Picture, base: BaseLook, meta: RawMetadata,
    ) {
        val p = DevelopParams.build(meta, RecipeParams(), rotation = 0, base = base)
        val img = BufferedImage(bw * 2, bh, BufferedImage.TYPE_INT_RGB)
        val out = FloatArray(3)
        val tmp = FloatArray(3)
        for (by in 0 until bh) for (bx in 0 until bw) {
            val o = ours[by][bx]!!
            CpuDeveloper.finish(o[0], o[1], o[2], p, out, tmp)
            img.setRGB(bx, by, (to8(out[0]) shl 16) or (to8(out[1]) shl 8) or to8(out[2]))
            val rx = ((bx * block + block / 2.0) * scale + dx).toInt().coerceIn(0, ref.width - 1)
            val ry = ((by * block + block / 2.0) * scale + dy).toInt().coerceIn(0, ref.height - 1)
            img.setRGB(bw + bx, by, ref[rx, ry] and 0xFFFFFF)
        }
        ImageIO.write(img, "png", file)
        println("side by side: $file")
    }

    private fun to8(v: Float) = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
}

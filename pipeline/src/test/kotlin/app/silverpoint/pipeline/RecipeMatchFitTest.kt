package app.silverpoint.pipeline

import app.silverpoint.recipes.Recipe
import app.silverpoint.recipes.RecipeMatch
import app.silverpoint.recipes.RecipeStore
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Fits each recipe's [RecipeMatch] (its correction towards Lightroom) to pairs of photos:
 * Silverpoint's JPEG and Lightroom's export of the same DNG with the same preset. Dev-only, like
 * [LightroomFitTest]: skipped unless `SILVERPOINT_MATCH_SAMPLES` names a folder of
 * `<Recipe_Name>.tsv` sample files (written by `scripts/match_samples.py`).
 *
 * Each line of a sample file is one flat block of the photo: the recipe whose Silverpoint JPEG the
 * colour came from, that colour (sRGB-encoded 0..1), Lightroom's colour for the target recipe at
 * the same place, the block's distance from the centre (0 middle, 1 corner) and optionally
 * Silverpoint's own colour for the target recipe there. The Silverpoint colour is turned back into
 * the scene colour by inverting the exact look that made it (without any match it may already
 * have); the target recipe is then rendered from that scene colour with candidate corrections and
 * compared with Lightroom in CIE ΔE2000. Prints the fitted corrections as `recipes.json` entries
 * and writes them to `match.json` in the same folder.
 */
class RecipeMatchFitTest {
    private class Sample(val scene: FloatArray, val target: DoubleArray, val r: Float)

    /** A sample ready for the look table: the encoded colour going into it, and the vignette after it. */
    private class Prepared(val encoded: FloatArray, val vignette: Float, val target: DoubleArray)

    private val dir = System.getProperty("silverpoint.matchSamples")?.let(::File)

    @Test
    fun fitMatchesToLightroom() {
        assumeTrue("set SILVERPOINT_MATCH_SAMPLES to run", dir != null && dir.isDirectory)
        val recipes = RecipeStore.parse(File("../app/src/main/assets/recipes.json").readText()).associateBy { it.name }
        // The photos were made without a match: invert with the plain recipe.
        fun plain(name: String) = recipes.getValue(name).params.copy(match = null)
        val meta = SyntheticScene.meta(doubleArrayOf(0.5, 1.0, 0.7))
        val shotParams = HashMap<String, DevelopParams>()
        fun shot(name: String) = shotParams.getOrPut(name) { DevelopParams.build(meta, plain(name), 0) }

        val sets = LinkedHashMap<String, MutableList<Sample>>()
        var dropped = 0
        var checkSum = 0.0
        var checkN = 0
        for (f in dir!!.listFiles { x -> x.name.endsWith(".tsv") }!!.sorted()) {
            val base = fileKey(f.name.removeSuffix(".tsv"))
            val target = recipes.keys.firstOrNull { fileKey(it) == base } ?: error("no recipe for ${f.name}")
            val list = sets.getOrPut(target) { ArrayList() }
            for (line in f.readLines()) {
                val c = line.split('\t')
                val source = c[0]
                val app = FloatArray(3) { c[1 + it].toFloat() }
                val lr = FloatArray(3) { c[4 + it].toFloat() }
                val r = c[7].toFloat()
                val p = shot(source)
                val v = CpuDeveloper.vignetteAt(r, p.vignette, p.vignetteStart, p.vignetteEnd)
                val scene = invert(FloatArray(3) { app[it] / v }, p)
                if (scene == null) {
                    dropped++
                    continue
                }
                list += Sample(scene, lab(lr), r)
                // Borrowed scene colours (another frame of the same scene): check them against
                // Silverpoint's own photo for the target recipe.
                if (c.size > 8 && source != target) {
                    val chk = FloatArray(3) { c[8 + it].toFloat() }
                    checkSum += ColorScience.deltaE2000(lab(render(scene, r, shot(target))), lab(chk))
                    checkN++
                }
            }
        }
        println("samples: " + sets.map { "${it.key} ${it.value.size}" } + ", not invertible: $dropped")
        if (checkN > 0) println(String.format(Locale.US, "borrowed scene colours vs Silverpoint's own photo: mean dE2000 %.2f", checkN.let { checkSum / it }))

        val json = StringBuilder()
        for ((name, list) in sets) {
            val recipe = recipes.getValue(name)
            val p = shot(name)
            val prepared = list.map { s ->
                val lin = FloatArray(3)
                CpuDeveloper.linearLook(s.scene[0], s.scene[1], s.scene[2], p, lin, FloatArray(3))
                val e = FloatArray(3) { ToneCurve.srgbEncode(ToneCurve.shoulder(lin[it], p.knee)) }
                Prepared(e, CpuDeveloper.vignetteAt(s.r, p.vignette, p.vignetteStart, p.vignetteEnd), s.target)
            }
            val before = report("$name, no match", prepared, plain(name))
            val fitted = fit(recipe, prepared)
            val after = report("$name, fitted match", prepared, plain(name).copy(match = fitted))
            // The photo is made through the 33³ look table: check that it keeps the gain.
            val lut = DevelopParams.bakeLook(plain(name).copy(match = fitted))
            var lutSum = 0.0
            val out = FloatArray(3)
            for (s in prepared) {
                lut.apply(s.encoded[0], s.encoded[1], s.encoded[2], out)
                lutSum += ColorScience.deltaE2000(lab(FloatArray(3) { out[it] * s.vignette }), s.target)
            }
            println(String.format(Locale.US, "  through the look table: mean dE2000 %.2f (was %.2f, exact %.2f)", lutSum / prepared.size, before, after))
            json.append("\"").append(name).append("\": ").append(toJson(fitted)).append('\n')
        }
        println("match entries for recipes.json (inside each recipe's params):\n$json")
        File(dir, "match.json").writeText(json.toString())
    }

    /** Coordinate descent over the 22 match values, pulled gently towards "no change". */
    private fun fit(recipe: Recipe, samples: List<Prepared>): RecipeMatch {
        val bw = recipe.params.grayscale
        val v = DoubleArray(22)
        for (i in 0..5) v[i] = i * 0.2 // tone
        for (i in 6..11) v[i] = 1.0 // chroma
        // 12..17 hue, 18..21 tints: zero
        fun match(x: DoubleArray) = RecipeMatch(
            tone = (0..5).map { x[it].toFloat() }.runningReduce { a, b -> maxOf(a, b) },
            chroma = (6..11).map { x[it].toFloat() },
            hue = (12..17).map { x[it].toFloat() },
            shadowTint = listOf(x[18].toFloat(), x[19].toFloat()),
            highlightTint = listOf(x[20].toFloat(), x[21].toFloat()),
        )
        // How far each value may move per unit of penalty (ΔE): tone 0.04 L, chroma 25 %, hue 12°, tint 0.012.
        fun penalty(x: DoubleArray): Double {
            var p = 0.0
            for (i in 0..5) p += sq((x[i] - i * 0.2) / 0.04)
            for (i in 6..11) p += sq(Math.log(x[i]) / 0.25)
            for (i in 12..17) p += sq(x[i] / 12.0)
            for (i in 18..21) p += sq(x[i] / 0.012)
            return 0.02 * p
        }
        // Samples are dense in some colours: weight them so that each lightness and hue region counts.
        val weights = balance(samples)
        val out = FloatArray(3)
        fun error(x: DoubleArray): Double {
            val look = RecipeLook(recipe.params.copy(match = match(x)), BaseLook.DEFAULT.contrast)
            var sum = 0.0
            var wsum = 0.0
            for ((k, s) in samples.withIndex()) {
                if (k % 2 == 1) continue
                look.apply(s.encoded[0], s.encoded[1], s.encoded[2], out)
                sum += weights[k] * ColorScience.deltaE2000(lab(FloatArray(3) { out[it] * s.vignette }), s.target)
                wsum += weights[k]
            }
            return sum / wsum + penalty(x)
        }
        val free = (0..21).filter { !bw || it < 6 } // black and white: only the tone curve
        val steps = DoubleArray(22) { i -> when (i) { in 0..5 -> 0.04; in 6..11 -> 0.12; in 12..17 -> 6.0; else -> 0.01 } }
        var bestErr = error(v)
        repeat(6) {
            for (k in free) {
                var improved = true
                while (improved) {
                    improved = false
                    for (sign in intArrayOf(1, -1)) {
                        val x = v.copyOf()
                        x[k] = if (k in 6..11) x[k] * Math.exp(sign * steps[k]) else x[k] + sign * steps[k]
                        if (k in 0..5) x[k] = x[k].coerceIn(0.0, 1.0)
                        val err = error(x)
                        if (err < bestErr - 1e-5) {
                            System.arraycopy(x, 0, v, 0, 22)
                            bestErr = err
                            improved = true
                        }
                    }
                }
            }
            for (i in steps.indices) steps[i] /= 2
        }
        return match(v)
    }

    /** Weights that make every lightness band and (for colours) every hue sector count about equally. */
    private fun balance(samples: List<Prepared>): DoubleArray {
        val key = IntArray(samples.size) { k ->
            val t = samples[k].target
            val c = Math.hypot(t[1], t[2])
            val lBin = (t[0] / 12.5).toInt().coerceIn(0, 7)
            val hBin = if (c < 8) 6 else ((Math.toDegrees(Math.atan2(t[2], t[1])) + 360) % 360 / 60).toInt().coerceIn(0, 5)
            lBin * 7 + hBin
        }
        val counts = HashMap<Int, Int>()
        for (k in key) counts[k] = (counts[k] ?: 0) + 1
        // Square root: rare regions count more, without letting a handful of blocks dominate.
        return DoubleArray(samples.size) { 1.0 / Math.sqrt(counts.getValue(key[it]).toDouble()) }
    }

    private fun report(label: String, samples: List<Prepared>, params: app.silverpoint.recipes.RecipeParams): Double {
        val look = RecipeLook(params, BaseLook.DEFAULT.contrast)
        val out = FloatArray(3)
        var sum = 0.0
        val bins = DoubleArray(10)
        val binN = IntArray(10)
        val chroma = DoubleArray(2)
        for (s in samples) {
            look.apply(s.encoded[0], s.encoded[1], s.encoded[2], out)
            val l = lab(FloatArray(3) { out[it] * s.vignette })
            sum += ColorScience.deltaE2000(l, s.target)
            val b = (s.target[0] / 10).toInt().coerceIn(0, 9)
            bins[b] += l[0] - s.target[0]
            binN[b]++
            chroma[0] += Math.hypot(l[1], l[2])
            chroma[1] += Math.hypot(s.target[1], s.target[2])
        }
        val mean = sum / samples.size
        println(String.format(Locale.US, "  %-34s mean dE2000 %.2f, chroma ours/LR %.2f, L* error by LR L*: %s", label, mean, chroma[0] / chroma[1],
            (0..9).filter { binN[it] > 30 }.joinToString(" ") { "${it * 10}:%+.1f".format(Locale.US, bins[it] / binN[it]) }))
        return mean
    }

    /** Silverpoint's render of scene-linear [scene] for a block at radius [r]: what its JPEG shows there. */
    private fun render(scene: FloatArray, r: Float, p: DevelopParams): FloatArray {
        val out = FloatArray(3)
        CpuDeveloper.finish(scene[0], scene[1], scene[2], p, out, FloatArray(3))
        val v = CpuDeveloper.vignetteAt(r, p.vignette, p.vignetteStart, p.vignetteEnd)
        return FloatArray(3) { (out[it] * v).coerceIn(0f, 1f) }
    }

    /** The scene-linear colour that [p] renders as [encoded] (no vignette), by Newton's method; null if none. */
    private fun invert(encoded: FloatArray, p: DevelopParams): FloatArray? {
        val x = DoubleArray(3) { ToneCurve.srgbDecode(encoded[it].coerceIn(0f, 1f)).toDouble() / p.exposureGain.coerceAtLeast(1e-3f) }
        val out = FloatArray(3)
        val tmp = FloatArray(3)
        fun f(v: DoubleArray): DoubleArray {
            CpuDeveloper.finish(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), p, out, tmp)
            return DoubleArray(3) { out[it] - encoded[it].toDouble() }
        }
        repeat(60) {
            val fx = f(x)
            if (fx.maxOf { Math.abs(it) } < 3e-4) return FloatArray(3) { x[it].toFloat() }
            val j = DoubleArray(9)
            for (c in 0..2) {
                val h = maxOf(1e-5, x[c] * 0.01)
                val xp = x.copyOf()
                xp[c] += h
                val fp = f(xp)
                for (r in 0..2) j[r * 3 + c] = (fp[r] - fx[r]) / h
            }
            val inv = runCatching { Mat3(j).inverse() }.getOrNull() ?: return null
            val dx = inv * fx
            for (c in 0..2) x[c] = maxOf(0.0, x[c] - 0.8 * dx[c])
        }
        val fx = f(x)
        return if (fx.maxOf { Math.abs(it) } < 2e-3) FloatArray(3) { x[it].toFloat() } else null
    }

    private fun toJson(m: RecipeMatch): String {
        fun list(v: List<Float>, digits: Int) = v.joinToString(", ", "[", "]") { String.format(Locale.US, "%.${digits}f", it) }
        return "{\"tone\": ${list(m.tone, 3)}, \"chroma\": ${list(m.chroma, 3)}, \"hue\": ${list(m.hue, 1)}, " +
            "\"shadowTint\": ${list(m.shadowTint, 4)}, \"highlightTint\": ${list(m.highlightTint, 4)}}"
    }

    /** A recipe name as it appears in file names: runs of anything but letters and digits become `_`. */
    private fun fileKey(name: String) = name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')

    private fun sq(x: Double) = x * x

    private fun dec(v: Float) = ToneCurve.srgbDecode(v.coerceIn(0f, 1f)).toDouble()

    private fun lab(rgb: FloatArray) = ColorScience.linearToLab(dec(rgb[0]), dec(rgb[1]), dec(rgb[2]))
}

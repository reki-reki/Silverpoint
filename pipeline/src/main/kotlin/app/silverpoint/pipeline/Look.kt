package app.silverpoint.pipeline

import app.silverpoint.recipes.HueBand
import app.silverpoint.recipes.RecipeParams
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A recipe's colour and tone, as one function from sRGB-encoded RGB (0..1) to sRGB-encoded RGB.
 * Everything per pixel that does not depend on where the pixel is lives here, so it can be baked
 * into a [LookLut] and shared by the final render and the live preview.
 *
 * Order, following Lightroom's panels: calibration primaries → dehaze (global part) → blacks,
 * shadows, highlights, whites → base contrast → recipe contrast → point curve → channel curves →
 * HSL bands or B&W mixer → split toning / colour grading → match.
 * The slider-to-effect scales are my own approximations of Lightroom's behaviour, tuned by eye;
 * see `docs/RECIPE_ENGINE.md`.
 */
class RecipeLook(
    private val p: RecipeParams,
    /** S-curve of the neutral rendering ([BaseLook.contrast]); 0 for the preview, which starts from Samsung's already-toned image. */
    private val baseContrast: Float,
    /**
     * Preview only: the camera preview has no RAW stage, so the recipe's exposure, saturation and
     * vibrance are applied here too (the final render applies them in linear light before the shoulder).
     */
    private val includeExposureAndSaturation: Boolean = false,
    /** How strongly each slider acts ([LookTuning.DEFAULT] unless fitting). */
    private val tuning: LookTuning = LookTuning.DEFAULT,
) {
    private val curve = ToneCurve.monotoneSpline(p.curvePoints)
    private val channelCurves = if (p.hasChannelCurves) p.channelCurvePoints.map { ToneCurve.monotoneSpline(it) } else null
    private val calibration = calibrationMatrix(p, tuning)
    private val contrastAmount = ToneCurve.contrastAmount(p.contrast, tuning.contrast)
    private val dehaze = (p.dehaze / 100f).coerceIn(-1f, 1f)
    private val blacks = (p.blacks / 100f).coerceIn(-1f, 1f)
    private val shadows = (p.shadows / 100f).coerceIn(-1f, 1f)
    private val highlights = (p.highlights / 100f).coerceIn(-1f, 1f)
    private val whites = (p.whites / 100f).coerceIn(-1f, 1f)
    private val hue = FloatArray(8) { p.hsl(HueBand.entries[it]).hue }
    private val sat = FloatArray(8) { p.hsl(HueBand.entries[it]).saturation }
    private val lum = FloatArray(8) { p.hsl(HueBand.entries[it]).luminance }
    private val gray = FloatArray(8) { p.grayMix(HueBand.entries[it]) }
    private val hasHsl = (hue + sat + lum).any { it != 0f }
    private val shadowTint = tintDirection(p.splitShadowHue)
    private val highlightTint = tintDirection(p.splitHighlightHue)
    private val shadowTintAmount = tuning.split * (p.splitShadowSaturation / 100f).coerceIn(0f, 1f)
    private val highlightTintAmount = tuning.split * (p.splitHighlightSaturation / 100f).coerceIn(0f, 1f)
    private val splitPivot = 0.5f - 0.2f * (p.splitBalance / 100f).coerceIn(-1f, 1f)
    private val splitWidth = tuning.splitWidth * (p.gradeBlending / 50f).coerceIn(0.2f, 2f)
    private val midTint = tintDirection(p.gradeMidtoneHue)
    private val midTintAmount = tuning.split * (p.gradeMidtoneSat / 100f).coerceIn(0f, 1f)
    private val globalTint = tintDirection(p.gradeGlobalHue)
    private val globalTintAmount = tuning.split * (p.gradeGlobalSat / 100f).coerceIn(0f, 1f)
    private val exposureGain = 2.0.pow(p.exposure.toDouble()).toFloat()
    private val match = p.match
    private val matchTone = match?.let { m -> ToneCurve.monotoneSpline(m.tone.mapIndexed { i, v -> i * 0.2f to v }) }
    private val saturation = (1f + tuning.saturation * p.saturation / 100f).coerceAtLeast(0f)
    private val vibrance = (tuning.vibrance * p.vibrance / 100f).coerceIn(-1f, 1f)

    /** Applies the look to encoded ([r], [g], [b]) and writes encoded RGB into [out]. */
    fun apply(r: Float, g: Float, b: Float, out: FloatArray) {
        val lab = FloatArray(3)
        var er = r
        var eg = g
        var eb = b
        if (calibration != null) {
            val m = calibration
            val lr = ToneCurve.srgbDecode(er.coerceIn(0f, 1f))
            val lg = ToneCurve.srgbDecode(eg.coerceIn(0f, 1f))
            val lb = ToneCurve.srgbDecode(eb.coerceIn(0f, 1f))
            er = ToneCurve.srgbEncode((m[0] * lr + m[1] * lg + m[2] * lb).coerceIn(0f, 1f))
            eg = ToneCurve.srgbEncode((m[3] * lr + m[4] * lg + m[5] * lb).coerceIn(0f, 1f))
            eb = ToneCurve.srgbEncode((m[6] * lr + m[7] * lg + m[8] * lb).coerceIn(0f, 1f))
        }
        if (includeExposureAndSaturation) {
            var lr = ToneCurve.srgbDecode(er.coerceIn(0f, 1f)) * exposureGain
            var lg = ToneCurve.srgbDecode(eg.coerceIn(0f, 1f)) * exposureGain
            var lb = ToneCurve.srgbDecode(eb.coerceIn(0f, 1f)) * exposureGain
            if (saturation != 1f || vibrance != 0f) {
                Oklab.fromLinearSrgb(lr, lg, lb, lab)
                val c = Oklab.chroma(lab[1], lab[2])
                val muted = (1f - c / DevelopParams.VIBRANCE_CHROMA).coerceIn(0f, 1f)
                val k = saturation * (1f + vibrance * muted)
                Oklab.toLinearSrgb(lab[0], lab[1] * k, lab[2] * k, out)
                lr = out[0]
                lg = out[1]
                lb = out[2]
            }
            er = ToneCurve.srgbEncode(lr.coerceIn(0f, 1f))
            eg = ToneCurve.srgbEncode(lg.coerceIn(0f, 1f))
            eb = ToneCurve.srgbEncode(lb.coerceIn(0f, 1f))
        }
        er = tone(er)
        eg = tone(eg)
        eb = tone(eb)
        if (channelCurves != null) {
            er = channelCurves[0](er).coerceIn(0f, 1f)
            eg = channelCurves[1](eg).coerceIn(0f, 1f)
            eb = channelCurves[2](eb).coerceIn(0f, 1f)
        }
        colour(er, eg, eb, lab, out)
    }

    /** Per-channel tone: dehaze black point, the four tone sliders, contrasts and the point curve. */
    fun tone(x0: Float): Float {
        var x = x0.coerceIn(0f, 1f)
        if (dehaze != 0f) x = (x - tuning.dehazeBlack * dehaze) / (1f - tuning.dehazeBlack * dehaze)
        x = x.coerceIn(0f, 1f)
        if (blacks != 0f) x += tuning.blacks * blacks * (1f - smoothstep(0f, 0.4f, x))
        if (whites != 0f) x += tuning.whites * whites * smoothstep(0.6f, 1f, x)
        if (shadows != 0f) x += tuning.shadows * shadows * bump(x, 0.25f)
        if (highlights != 0f) x += tuning.highlights * highlights * bump(x, 0.72f)
        x = x.coerceIn(0f, 1f)
        x = ToneCurve.contrast(ToneCurve.contrast(x, baseContrast), contrastAmount)
        return curve(x).coerceIn(0f, 1f)
    }

    private fun colour(r: Float, g: Float, b: Float, lab: FloatArray, out: FloatArray) {
        val bw = p.grayscale
        val needColour = bw || hasHsl || shadowTintAmount > 0f || highlightTintAmount > 0f || midTintAmount > 0f ||
            globalTintAmount > 0f || dehaze != 0f || match != null
        if (!needColour) {
            out[0] = r
            out[1] = g
            out[2] = b
            return
        }
        val bandHue = hsvHue(r, g, b)
        Oklab.fromLinearSrgb(ToneCurve.srgbDecode(r), ToneCurve.srgbDecode(g), ToneCurve.srgbDecode(b), lab)
        var l = lab[0]
        var a = lab[1]
        var bb = lab[2]
        val c = Oklab.chroma(a, bb)
        val colourWeight = smoothstep(0.02f, 0.10f, c)
        val lumWeight = smoothstep(0.02f, 0.15f, c)
        if (bw) {
            val mix = HueBands.at(gray, bandHue)
            l *= 1f + tuning.grayMix * mix / 100f * lumWeight
            a = 0f
            bb = 0f
        } else if (hasHsl) {
            val dh = HueBands.at(hue, bandHue)
            val ds = HueBands.at(sat, bandHue)
            val dl = HueBands.at(lum, bandHue)
            if (dh != 0f) {
                val angle = (dh / 100f * tuning.hueDegrees * colourWeight) * (PI.toFloat() / 180f)
                val ca = cos(angle)
                val sa = sin(angle)
                val na = a * ca - bb * sa
                bb = a * sa + bb * ca
                a = na
            }
            val k = (1f + tuning.hslSaturation * ds / 100f * colourWeight).coerceAtLeast(0f)
            a *= k
            bb *= k
            l *= 1f + tuning.luminance * dl / 100f * lumWeight
        }
        if (dehaze != 0f) {
            val k = (1f + tuning.dehazeChroma * dehaze).coerceAtLeast(0f)
            a *= k
            bb *= k
        }
        if (shadowTintAmount > 0f || highlightTintAmount > 0f || midTintAmount > 0f || globalTintAmount > 0f) {
            val hi = smoothstep(splitPivot - splitWidth, splitPivot + splitWidth, l)
            val mid = 4f * hi * (1f - hi)
            val edge = (6f * l * (1f - l)).coerceIn(0f, 1f)
            a += (shadowTint[0] * shadowTintAmount * (1f - hi) + highlightTint[0] * highlightTintAmount * hi +
                midTint[0] * midTintAmount * mid + globalTint[0] * globalTintAmount) * edge
            bb += (shadowTint[1] * shadowTintAmount * (1f - hi) + highlightTint[1] * highlightTintAmount * hi +
                midTint[1] * midTintAmount * mid + globalTint[1] * globalTintAmount) * edge
        }
        if (match != null) {
            l = matchTone!!(l.coerceIn(0f, 1f))
            val mc = Oklab.chroma(a, bb)
            if (mc > 1e-6f) {
                val h = oklabHue(a, bb)
                val k = sectors(match.chroma, h)
                val turn = sectors(match.hue, h) * smoothstep(0.01f, 0.06f, mc) * (PI.toFloat() / 180f)
                val ca = cos(turn)
                val sa = sin(turn)
                val na = (a * ca - bb * sa) * k
                bb = (a * sa + bb * ca) * k
                a = na
            }
            val sh = 1f - smoothstep(0.15f, 0.65f, l)
            val hl = smoothstep(0.35f, 0.85f, l)
            val edge = (6f * l * (1f - l)).coerceIn(0f, 1f)
            a += (match.shadowTint[0] * sh + match.highlightTint[0] * hl) * edge
            bb += (match.shadowTint[1] * sh + match.highlightTint[1] * hl) * edge
        }
        // Per-channel clipping. Chroma reduction at constant lightness was tried and rejected: near the
        // blue corner of sRGB it jumps between very different colours for nearly equal inputs.
        Oklab.toLinearSrgb(l.coerceIn(0f, 1f), a, bb, out)
        out[0] = ToneCurve.srgbEncode(out[0].coerceIn(0f, 1f))
        out[1] = ToneCurve.srgbEncode(out[1].coerceIn(0f, 1f))
        out[2] = ToneCurve.srgbEncode(out[2].coerceIn(0f, 1f))
    }

    /** Bakes the look into an [n]³ lookup table. */
    fun bake(n: Int = LookLut.DEFAULT_SIZE): LookLut {
        val data = FloatArray(n * n * n * 3)
        val out = FloatArray(3)
        val step = 1f / (n - 1)
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            apply(ri * step, gi * step, bi * step, out)
            val o = ((bi * n + gi) * n + ri) * 3
            data[o] = out[0]
            data[o + 1] = out[1]
            data[o + 2] = out[2]
        }
        return LookLut(n, data)
    }

    companion object {
        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** A bump centred on [centre], zero at 0 and 1, used to aim shadows and highlights. */
        fun bump(x: Float, centre: Float): Float {
            val d = (x - centre) / 0.18f
            return 4f * x * (1f - x) * kotlin.math.exp(-d * d)
        }

        /** HSV hue in degrees (0..360) of an RGB triple; 0 for greys. */
        fun hsvHue(r: Float, g: Float, b: Float): Float {
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val d = max - min
            if (d <= 1e-6f) return 0f
            val h = when (max) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
            return if (h < 0f) h + 360f else h
        }

        /**
         * Lightroom's Calibration panel as a 3x3 matrix on linear sRGB, or null when every slider is 0.
         * Each primary (pure red, green, blue) is turned in OKLab hue by up to [LookTuning.calibrationHueDegrees]
         * and its chroma scaled by up to ±[LookTuning.calibrationSaturation]; the columns are then
         * rescaled so white stays white. An approximation: Lightroom edits the camera profile instead.
         */
        fun calibrationMatrix(p: RecipeParams, tuning: LookTuning = LookTuning.DEFAULT): FloatArray? {
            val hues = floatArrayOf(p.calRedHue, p.calGreenHue, p.calBlueHue)
            val sats = floatArrayOf(p.calRedSaturation, p.calGreenSaturation, p.calBlueSaturation)
            if (hues.all { it == 0f } && sats.all { it == 0f }) return null
            val cols = Array(3) { i ->
                val lab = FloatArray(3)
                Oklab.fromLinearSrgb(if (i == 0) 1f else 0f, if (i == 1) 1f else 0f, if (i == 2) 1f else 0f, lab)
                val angle = (hues[i] / 100f).coerceIn(-1f, 1f) * tuning.calibrationHueDegrees * (PI.toFloat() / 180f)
                val k = (1f + tuning.calibrationSaturation * (sats[i] / 100f).coerceIn(-1f, 1f)).coerceAtLeast(0f)
                val a = (lab[1] * cos(angle) - lab[2] * sin(angle)) * k
                val b = (lab[1] * sin(angle) + lab[2] * cos(angle)) * k
                FloatArray(3).also { Oklab.toLinearSrgb(lab[0], a, b, it) }
            }
            // Column scales s with C s = (1, 1, 1): white maps to white.
            val c = DoubleArray(9) { cols[it % 3][it / 3].toDouble() } // row-major: c[row * 3 + col]
            val s = solve3(c, doubleArrayOf(1.0, 1.0, 1.0)) ?: return null
            return FloatArray(9) { (c[it] * s[it % 3]).toFloat() }
        }

        private fun solve3(m: DoubleArray, v: DoubleArray): DoubleArray? {
            fun det(a: DoubleArray) = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
            val d = det(m)
            if (kotlin.math.abs(d) < 1e-9) return null
            return DoubleArray(3) { col -> det(DoubleArray(9) { if (it % 3 == col) v[it / 3] else m[it] }) / d }
        }

        /** Unit OKLab (a, b) direction of a fully saturated colour with HSV [hueDegrees]. */
        fun tintDirection(hueDegrees: Float): FloatArray {
            val h = ((hueDegrees % 360f) + 360f) % 360f / 60f
            val x = 1f - kotlin.math.abs(h % 2f - 1f)
            val rgb = when (h.toInt()) {
                0 -> floatArrayOf(1f, x, 0f)
                1 -> floatArrayOf(x, 1f, 0f)
                2 -> floatArrayOf(0f, 1f, x)
                3 -> floatArrayOf(0f, x, 1f)
                4 -> floatArrayOf(x, 0f, 1f)
                else -> floatArrayOf(1f, 0f, x)
            }
            val lab = FloatArray(3)
            Oklab.fromLinearSrgb(ToneCurve.srgbDecode(rgb[0]), ToneCurve.srgbDecode(rgb[1]), ToneCurve.srgbDecode(rgb[2]), lab)
            val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
            return if (c < 1e-6f) floatArrayOf(0f, 0f) else floatArrayOf(lab[1] / c, lab[2] / c)
        }

        /** A value given at OKLab hues 0°, 60°, …, 300°, at [hueDegrees], blended with a raised cosine. */
        fun sectors(values: List<Float>, hueDegrees: Float): Float {
            val h = ((hueDegrees % 360f) + 360f) % 360f / 60f
            val i = h.toInt() % 6
            val t = h - h.toInt()
            val s = (1f - cos(PI.toFloat() * t)) / 2f
            return values[i] * (1f - s) + values[(i + 1) % 6] * s
        }

        /** OKLab hue angle in degrees (0..360). */
        fun oklabHue(a: Float, b: Float): Float = ((Math.toDegrees(atan2(b, a).toDouble()).toFloat() + 360f) % 360f)
    }
}

/**
 * How strongly each Lightroom slider acts. Lightroom's engine is not public, so these are the
 * engine's own scales: first set by eye, then fitted to Lightroom exports of the same photos
 * (`RecipeMatchFitTest`, `docs/RECIPE_ENGINE.md`).
 */
data class LookTuning(
    /** Dehaze ±100 moves the black point by up to this much. */
    val dehazeBlack: Float = 0.05f,
    /** Dehaze ±100 scales chroma by up to this fraction. */
    val dehazeChroma: Float = 0.25f,
    /** Blacks, Whites, Shadows, Highlights ±100: largest change of an encoded value. */
    val blacks: Float = 0.06f,
    val whites: Float = 0.06f,
    val shadows: Float = 0.15f,
    val highlights: Float = 0.15f,
    /** Contrast ±100 as an S-curve amount (slope 1 ± this at mid-grey). */
    val contrast: Float = 0.6f,
    /** HSL hue ±100 turns a colour by up to this many degrees. */
    val hueDegrees: Float = 30f,
    /** HSL saturation ±100 scales chroma by up to this fraction. */
    val hslSaturation: Float = 1f,
    /** HSL luminance ±100 changes lightness of a saturated colour by up to this fraction. */
    val luminance: Float = 0.3f,
    /** Grey mixer ±100 changes lightness by up to this fraction. */
    val grayMix: Float = 0.4f,
    /** OKLab chroma of a split-toning tint at saturation 100. */
    val split: Float = 0.08f,
    /** Half-width (OKLab lightness) of the blend between the shadow and highlight tints (at Blending 50). */
    val splitWidth: Float = 0.25f,
    /** Calibration hue ±100 turns a primary by up to this many OKLab degrees; saturation ±100 scales its chroma by up to this fraction. */
    val calibrationHueDegrees: Float = 30f,
    val calibrationSaturation: Float = 1f,
    /** Multiplies the Saturation and Vibrance sliders. */
    val saturation: Float = 1f,
    val vibrance: Float = 1f,
) {
    companion object {
        val DEFAULT = LookTuning()
    }
}

/**
 * Lightroom's eight colour bands as a smooth function of hue: each band's value applies fully at its
 * centre and blends (raised cosine) into the next band's.
 */
object HueBands {
    /** Band centres in HSV degrees: red, orange, yellow, green, aqua, blue, purple, magenta. */
    val CENTRES = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 270f, 300f)

    fun at(values: FloatArray, hueDegrees: Float): Float {
        val h = ((hueDegrees % 360f) + 360f) % 360f
        var i = 7
        for (k in 0 until 7) if (h < CENTRES[k + 1]) { i = k; break }
        val start = CENTRES[i]
        val end = if (i == 7) 360f else CENTRES[i + 1]
        val t = (h - start) / (end - start)
        val s = (1f - cos(PI.toFloat() * t)) / 2f
        return values[i] * (1f - s) + values[(i + 1) % 8] * s
    }
}

/** An n³ RGB lookup table over encoded RGB, red varying fastest. Read with trilinear interpolation. */
class LookLut(val size: Int, val data: FloatArray) {
    init {
        require(size >= 2 && data.size == size * size * size * 3) { "LUT data does not match size $size" }
    }

    /** Trilinear lookup; same arithmetic as `lookLut()` in `develop.comp`. */
    fun apply(r: Float, g: Float, b: Float, out: FloatArray) {
        val n = size
        val fr = r.coerceIn(0f, 1f) * (n - 1)
        val fg = g.coerceIn(0f, 1f) * (n - 1)
        val fb = b.coerceIn(0f, 1f) * (n - 1)
        val r0 = minOf(fr.toInt(), n - 2)
        val g0 = minOf(fg.toInt(), n - 2)
        val b0 = minOf(fb.toInt(), n - 2)
        val tr = fr - r0
        val tg = fg - g0
        val tb = fb - b0
        for (c in 0..2) {
            val c00 = mix(at(r0, g0, b0, c), at(r0 + 1, g0, b0, c), tr)
            val c10 = mix(at(r0, g0 + 1, b0, c), at(r0 + 1, g0 + 1, b0, c), tr)
            val c01 = mix(at(r0, g0, b0 + 1, c), at(r0 + 1, g0, b0 + 1, c), tr)
            val c11 = mix(at(r0, g0 + 1, b0 + 1, c), at(r0 + 1, g0 + 1, b0 + 1, c), tr)
            out[c] = mix(mix(c00, c10, tg), mix(c01, c11, tg), tb)
        }
    }

    private fun at(ri: Int, gi: Int, bi: Int, c: Int) = data[((bi * size + gi) * size + ri) * 3 + c]

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Adobe/Resolve `.cube` text, for checking a look in other tools. */
    fun toCube(title: String): String = buildString {
        append("TITLE \"").append(title.replace("\"", "'")).append("\"\n")
        append("LUT_3D_SIZE ").append(size).append('\n')
        for (i in 0 until size * size * size) {
            append(String.format(java.util.Locale.US, "%.6f %.6f %.6f\n", data[i * 3], data[i * 3 + 1], data[i * 3 + 2]))
        }
    }

    companion object {
        const val DEFAULT_SIZE = 33

        /** The identity table, for tests and as a fallback. */
        fun identity(n: Int = DEFAULT_SIZE): LookLut {
            val data = FloatArray(n * n * n * 3)
            for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
                val o = ((bi * n + gi) * n + ri) * 3
                data[o] = ri / (n - 1f)
                data[o + 1] = gi / (n - 1f)
                data[o + 2] = bi / (n - 1f)
            }
            return LookLut(n, data)
        }
    }
}

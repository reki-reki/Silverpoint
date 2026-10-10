package app.silverpoint.pipeline

/**
 * Reference RAW developer on the CPU. Slow (seconds for 12 MP) but simple and exactly mirrored by
 * the GPU shaders, so it is the yardstick for both unit tests and the on-device self-test.
 */
object CpuDeveloper {

    /** Finished image, upright, as packed ARGB pixels, plus the low-resolution luminance (not rotated). */
    class Rendered(val width: Int, val height: Int, val argb: IntArray, val lowRes: LocalContrast.LowRes? = null)

    /**
     * Stage 1, per CFA sample: subtract black, normalise by white, lens-shading gain, white-balance
     * gain, clip at 1 (so blown highlights stay neutral).
     * @param raw 16-bit samples (read as unsigned), [stride] samples per row.
     */
    fun prepareCfa(raw: ShortArray, stride: Int, p: DevelopParams): FloatArray {
        val w = p.width
        val h = p.height
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = (x and 1) + 2 * (y and 1)
                val black = p.blackLevel[idx]
                var v = ((raw[y * stride + x].toInt() and 0xFFFF) - black) / (p.whiteLevel - black)
                if (v < 0f) v = 0f
                val shading = p.lensShading?.gain(x, y, w, h, p.cfa) ?: 1f
                v *= shading * p.wbGains[p.cfa.channel(x, y)]
                out[y * w + x] = if (v > 1f) 1f else v
            }
        }
        return out
    }

    /**
     * Malvar-He-Cutler demosaic of a prepared CFA plane to interleaved RGB (camera space,
     * white-balanced), then [refine] with [sharpen] and [chromaSmoothing] (both off by default).
     */
    fun demosaic(cfa: FloatArray, w: Int, h: Int, pattern: Cfa, sharpen: Float = 0f, chromaSmoothing: Float = 0f, sharpenMask: Float = 0f): FloatArray {
        val out = FloatArray(w * h * 3)
        fun at(x: Int, y: Int): Float = cfa[reflect(y, h) * w + reflect(x, w)]
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pattern.channel(x, y)
                val v = at(x, y)
                var r: Float
                var g: Float
                var b: Float
                if (c == 1) {
                    // Green site: the colour in this row is on the horizontal neighbours.
                    val horizontal = (8f * v + (at(x - 1, y) + at(x + 1, y)) * 4f) -
                        (at(x - 2, y) + at(x + 2, y) + at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)) +
                        (at(x, y - 2) + at(x, y + 2)) * 0.5f - 3f * v
                    val vertical = (8f * v + (at(x, y - 1) + at(x, y + 1)) * 4f) -
                        (at(x, y - 2) + at(x, y + 2) + at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)) +
                        (at(x - 2, y) + at(x + 2, y)) * 0.5f - 3f * v
                    g = v
                    if (pattern.channel(x + 1, y) == 0) {
                        r = horizontal / 8f
                        b = vertical / 8f
                    } else {
                        b = horizontal / 8f
                        r = vertical / 8f
                    }
                } else {
                    val green = (4f * v + 2f * (at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1)) -
                        (at(x - 2, y) + at(x + 2, y) + at(x, y - 2) + at(x, y + 2))) / 8f
                    val opposite = (6f * v + 2f * (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)) -
                        1.5f * (at(x - 2, y) + at(x + 2, y) + at(x, y - 2) + at(x, y + 2))) / 8f
                    g = green
                    if (c == 0) {
                        r = v
                        b = opposite
                    } else {
                        b = v
                        r = opposite
                    }
                }
                val o = (y * w + x) * 3
                out[o] = r.coerceIn(0f, 1f)
                out[o + 1] = g.coerceIn(0f, 1f)
                out[o + 2] = b.coerceIn(0f, 1f)
                if (sharpen != 0f || chromaSmoothing != 0f) refine(cfa, w, h, pattern, x, y, sharpen, chromaSmoothing, sharpenMask, out, o)
            }
        }
        return out
    }

    /**
     * Lightroom's default RAW detail work on one demosaiced pixel (`out[o..o+2]`), straight from the
     * CFA samples around it. Mirrored by `refine` in `develop.comp`.
     * - Colour smoothing: the pixel keeps its own brightness but takes its colour from the 5x5 grid
     *   of 2x2 sensor blocks around it (about 4 pixels each way, Gaussian-weighted), counting
     *   blocks much brighter or darker than the surroundings less so colour does not bleed across
     *   edges. This removes colour noise and the false-colour specks of detail finer than the
     *   colour filter grid.
     * - Sharpening: an unsharp mask on brightness against a 5x5 binomial blur (radius about 1
     *   pixel), in square-root (roughly perceptual) units; with edge masking ([sharpenMask] > 0),
     *   detail weaker than [sharpenMask] × [MASK_DETAIL] is sharpened less.
     */
    fun refine(
        cfa: FloatArray, w: Int, h: Int, pattern: Cfa, x: Int, y: Int,
        sharpen: Float, chromaSmoothing: Float, sharpenMask: Float, out: FloatArray, o: Int,
    ) {
        fun at(xx: Int, yy: Int) = cfa[reflect(yy, h) * w + reflect(xx, w)]
        var r = out[o]
        var g = out[o + 1]
        var b = out[o + 2]
        val lc = (r + 2f * g + b) * 0.25f
        // In a Bayer 5x5 binomial window green carries half of the 256 weight and red and blue a
        // quarter each, so this is the blur of (R + 2G + B) / 4 without looking at channels.
        var sum = 0f
        for (dy in -2..2) for (dx in -2..2) sum += BINOMIAL[dx + 2] * BINOMIAL[dy + 2] * at(x + dx, y + dy)
        val lb = sum / 256f
        if (chromaSmoothing != 0f && lb > 1e-5f) {
            var sr = 0f
            var sg = 0f
            var sb = 0f
            var sl = 0f
            val qx0 = (x and 1.inv()) - 2 * CHROMA_QUADS
            val qy0 = (y and 1.inv()) - 2 * CHROMA_QUADS
            for (j in 0..2 * CHROMA_QUADS) {
                for (i in 0..2 * CHROMA_QUADS) {
                    val qx = qx0 + 2 * i
                    val qy = qy0 + 2 * j
                    var qr = 0f
                    var qg = 0f
                    var qb = 0f
                    for (k in 0 until 4) {
                        val sx = qx + (k and 1)
                        val sy = qy + (k shr 1)
                        val v = at(sx, sy)
                        when (pattern.channel(sx, sy)) {
                            0 -> qr = v
                            1 -> qg += v
                            else -> qb = v
                        }
                    }
                    qg *= 0.5f
                    val lq = (qr + 2f * qg + qb) * 0.25f
                    val dx = qx + 0.5f - x
                    val dy = qy + 0.5f - y
                    val d = (lq - lb) / (CHROMA_RANGE * lb + 0.002f)
                    val wq = kotlin.math.exp(-(dx * dx + dy * dy) * CHROMA_SPATIAL) / (1f + d * d)
                    sr += wq * qr
                    sg += wq * qg
                    sb += wq * qb
                    sl += wq * lq
                }
            }
            if (sl > 1e-6f) {
                val t = lc / sl
                r += (sr * t - r) * chromaSmoothing
                g += (sg * t - g) * chromaSmoothing
                b += (sb * t - b) * chromaSmoothing
            }
        }
        if (sharpen != 0f && lc > 1e-5f) {
            val sc = kotlin.math.sqrt(lc)
            val detail = sc - kotlin.math.sqrt(lb)
            // Edge masking: detail smaller than the threshold (flat areas, noise) is sharpened less.
            val mask = if (sharpenMask > 0f) RecipeLook.smoothstep(0f, sharpenMask * MASK_DETAIL, kotlin.math.abs(detail)) else 1f
            val s = maxOf(0f, sc + sharpen * mask * detail)
            val m = minOf(s * s / lc, 4f)
            r *= m
            g *= m
            b *= m
        }
        out[o] = r.coerceIn(0f, 1f)
        out[o + 1] = g.coerceIn(0f, 1f)
        out[o + 2] = b.coerceIn(0f, 1f)
    }

    /**
     * Colour smoothing reach: 2x2 blocks up to [CHROMA_QUADS] each way (about 4 pixels), Gaussian of
     * sigma 2.5 pixels (as 1 / (2 sigma^2)), and how far a block's brightness may differ from the
     * surroundings (relative) before it counts less. Matched to Lightroom's Color 25 (October 2026).
     */
    private const val CHROMA_QUADS = 2

    /** Square-root brightness difference that counts as an edge at Masking 100 (my approximation). */
    const val MASK_DETAIL = 0.05f
    private const val CHROMA_SPATIAL = 0.08f
    private const val CHROMA_RANGE = 1f


    private val BINOMIAL = floatArrayOf(1f, 4f, 6f, 4f, 1f)

    /** Linear sRGB (no exposure, tone or recipe), interleaved, not rotated. For colour checks. */
    fun linearSrgb(raw: ShortArray, stride: Int, p: DevelopParams): FloatArray {
        val rgb = demosaic(prepareCfa(raw, stride, p), p.width, p.height, p.cfa)
        toLinearSrgb(rgb, p)
        return rgb
    }

    /** Full development: RAW to an upright, 8-bit image with the look applied. */
    fun develop(raw: ShortArray, stride: Int, p: DevelopParams): Rendered {
        val cfa = prepareCfa(raw, stride, p)
        val lin = demosaic(cfa, p.width, p.height, p.cfa, p.sharpen, p.chromaSmoothing, p.sharpenMask)
        toLinearSrgb(lin, p)
        val low = LocalContrast.lowRes(cfa, p)
        val detail = if (p.hasDetail) LocalContrast.blur(low) else null
        val ow = p.outWidth
        val oh = p.outHeight
        val out = IntArray(ow * oh)
        val px = FloatArray(3)
        val lab = FloatArray(3)
        for (oy in 0 until oh) {
            for (ox in 0 until ow) {
                val (sx, sy) = sourceOf(ox, oy, p)
                val i = (sy * p.width + sx) * 3
                var small = 0f
                var large = 0f
                if (detail != null) {
                    small = LocalContrast.sample(detail.small, detail.width, detail.height, sx, sy)
                    large = LocalContrast.sample(detail.large, detail.width, detail.height, sx, sy)
                }
                finishAt(lin[i], lin[i + 1], lin[i + 2], p, px, lab, ox, oy, small, large)
                out[oy * ow + ox] = (0xFF shl 24) or (to8(px[0]) shl 16) or (to8(px[1]) shl 8) or to8(px[2])
            }
        }
        return Rendered(ow, oh, out, low)
    }

    /** Camera RGB to linear sRGB in place. */
    private fun toLinearSrgb(rgb: FloatArray, p: DevelopParams) {
        val m = p.cameraToSrgb
        for (i in 0 until p.width * p.height) {
            val o = i * 3
            val r = rgb[o]
            val g = rgb[o + 1]
            val b = rgb[o + 2]
            rgb[o] = m[0] * r + m[1] * g + m[2] * b
            rgb[o + 1] = m[3] * r + m[4] * g + m[5] * b
            rgb[o + 2] = m[6] * r + m[7] * g + m[8] * b
        }
    }

    /** Exposure, then saturation and vibrance in OKLab. Linear in, linear out (written to [out]). */
    fun linearLook(r0: Float, g0: Float, b0: Float, p: DevelopParams, out: FloatArray, lab: FloatArray) {
        var r = maxOf(r0, 0f) * p.exposureGain
        var g = maxOf(g0, 0f) * p.exposureGain
        var b = maxOf(b0, 0f) * p.exposureGain
        if (p.saturation != 1f || p.vibrance != 0f) {
            Oklab.fromLinearSrgb(r, g, b, lab)
            val c = Oklab.chroma(lab[1], lab[2])
            val muted = (1f - c / DevelopParams.VIBRANCE_CHROMA).coerceIn(0f, 1f)
            val k = p.saturation * (1f + p.vibrance * muted)
            Oklab.toLinearSrgb(lab[0], lab[1] * k, lab[2] * k, out)
            r = maxOf(out[0], 0f)
            g = maxOf(out[1], 0f)
            b = maxOf(out[2], 0f)
        }
        out[0] = r
        out[1] = g
        out[2] = b
    }

    /**
     * Per-pixel finishing from linear sRGB without the position-dependent effects: exposure,
     * saturation/vibrance, highlight shoulder, sRGB encoding, then the look table. Writes encoded
     * 0..1 values into [out]. Used for block averages in the colour-fitting tools.
     */
    fun finish(r0: Float, g0: Float, b0: Float, p: DevelopParams, out: FloatArray, lab: FloatArray) {
        linearLook(r0, g0, b0, p, out, lab)
        val er = ToneCurve.srgbEncode(ToneCurve.shoulder(out[0], p.knee))
        val eg = ToneCurve.srgbEncode(ToneCurve.shoulder(out[1], p.knee))
        val eb = ToneCurve.srgbEncode(ToneCurve.shoulder(out[2], p.knee))
        p.look.apply(er, eg, eb, out)
    }

    /**
     * The full per-pixel finishing for output pixel ([ox], [oy]); mirrors `finishPixel` in
     * `develop.comp`. [small] and [large] are the blurred surroundings (ignored without Clarity/Texture).
     */
    fun finishAt(
        r0: Float, g0: Float, b0: Float, p: DevelopParams, out: FloatArray, lab: FloatArray,
        ox: Int, oy: Int, small: Float, large: Float,
    ) {
        linearLook(r0, g0, b0, p, out, lab)
        var er = ToneCurve.srgbEncode(ToneCurve.shoulder(out[0], p.knee))
        var eg = ToneCurve.srgbEncode(ToneCurve.shoulder(out[1], p.knee))
        var eb = ToneCurve.srgbEncode(ToneCurve.shoulder(out[2], p.knee))
        if (p.hasDetail) {
            val y = LocalContrast.LR * er + LocalContrast.LG * eg + LocalContrast.LB * eb
            if (y > 1e-4f) {
                val k = LocalContrast.adjust(y, small, large, p.clarity, p.texture) / y
                er = (er * k).coerceIn(0f, 1f)
                eg = (eg * k).coerceIn(0f, 1f)
                eb = (eb * k).coerceIn(0f, 1f)
            }
        }
        p.look.apply(er, eg, eb, out)
        if (p.vignette != 0f) {
            val v = vignetteGain(ox, oy, p)
            for (c in 0..2) out[c] = (out[c] * v).coerceIn(0f, 1f)
        }
        if (p.grainAmount > 0f) {
            val y = LocalContrast.LR * out[0] + LocalContrast.LG * out[1] + LocalContrast.LB * out[2]
            val n = Grain.at(ox, oy, p.grainCell, p.grainRoughness, p.grainSeed) *
                Grain.STRENGTH * p.grainAmount * Grain.weight(y)
            for (c in 0..2) out[c] = (out[c] + n).coerceIn(0f, 1f)
        }
    }

    /** Vignette multiplier at output pixel ([ox], [oy]): 1 in the middle, 1 + 0.8 × amount in the corners. */
    fun vignetteGain(ox: Int, oy: Int, p: DevelopParams): Float {
        val hw = p.outWidth / 2f
        val hh = p.outHeight / 2f
        val dx = (ox + 0.5f - hw) / hw
        val dy = (oy + 0.5f - hh) / hh
        val r = kotlin.math.sqrt((dx * dx + dy * dy) / 2f)
        return vignetteAt(r, p.vignette, p.vignetteStart, p.vignetteEnd)
    }

    /**
     * Vignette multiplier at normalised radius [r] (0 in the middle, 1 in the corners) for [amount]
     * -1..1, falling off between [start] and [end].
     */
    fun vignetteAt(r: Float, amount: Float, start: Float = 0.3f, end: Float = 1f): Float =
        maxOf(0f, 1f + VIGNETTE_GAIN * amount * RecipeLook.smoothstep(start, end, r))

    const val VIGNETTE_GAIN = 0.8f

    /** Source pixel for output pixel ([ox], [oy]) after a clockwise rotation of [DevelopParams.rotation]. */
    fun sourceOf(ox: Int, oy: Int, p: DevelopParams): Pair<Int, Int> = when (p.rotation) {
        90 -> oy to (p.height - 1 - ox)
        180 -> (p.width - 1 - ox) to (p.height - 1 - oy)
        270 -> (p.width - 1 - oy) to ox
        else -> ox to oy
    }

    /** Mirror without repeating the edge sample, which keeps the CFA phase: -1 → 1, n → n - 2. */
    fun reflect(i: Int, n: Int): Int = when {
        i < 0 -> -i
        i >= n -> 2 * n - 2 - i
        else -> i
    }

    private fun to8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}

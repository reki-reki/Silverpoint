package app.silverpoint.pipeline

import kotlin.math.ceil
import kotlin.math.exp

/**
 * Clarity and Texture: local contrast from a quarter-resolution luminance image, blurred at a small
 * and a large radius. Also produces the low-resolution linear luminance used for the Ultra HDR gain map.
 *
 * Steps (mirrored by `lowres.comp`, `blur.comp` and `develop.comp`):
 * 1. [lowRes]: every 4x4 block of the prepared CFA plane is averaged per colour channel, then sent
 *    through the same colour, exposure, saturation, shoulder and encoding as a full-resolution pixel.
 *    Output per block: encoded luminance and linear (pre-shoulder) luminance.
 * 2. [blur]: separable Gaussian of the encoded luminance at [smallSigma] and [largeSigma].
 * 3. [sample]: bilinear read of a blurred image at a full-resolution sensor position.
 */
object LocalContrast {
    /** Sensor pixels per low-resolution pixel, each way. Even, so a block holds whole CFA quads. */
    const val BLOCK = 4

    /** Encoded-luminance weights (Rec. 709). */
    const val LR = 0.2126f
    const val LG = 0.7152f
    const val LB = 0.0722f

    /** Texture works on detail of about one low-resolution pixel (4 sensor pixels). */
    const val SMALL_SIGMA = 1f

    /** Clarity works on detail up to about 2% of the frame. */
    fun largeSigma(lowWidth: Int, lowHeight: Int): Float = maxOf(2f, 0.02f * maxOf(lowWidth, lowHeight))

    const val CLARITY_GAIN = 0.6f
    const val TEXTURE_GAIN = 0.8f

    class LowRes(val width: Int, val height: Int, /** Encoded luminance, row-major. */ val encoded: FloatArray, /** Linear luminance before the shoulder. */ val linear: FloatArray)

    /** Blurred encoded luminance at both radii. */
    class Detail(val width: Int, val height: Int, val small: FloatArray, val large: FloatArray)

    fun lowSize(n: Int) = (n + BLOCK - 1) / BLOCK

    /** Step 1. [cfa] is the output of [CpuDeveloper.prepareCfa]. */
    fun lowRes(cfa: FloatArray, p: DevelopParams): LowRes {
        val lw = lowSize(p.width)
        val lh = lowSize(p.height)
        val enc = FloatArray(lw * lh)
        val lin = FloatArray(lw * lh)
        val px = FloatArray(3)
        val lab = FloatArray(3)
        for (j in 0 until lh) for (i in 0 until lw) {
            val sum = FloatArray(3)
            val count = FloatArray(3)
            for (dy in 0 until BLOCK) for (dx in 0 until BLOCK) {
                val x = minOf(i * BLOCK + dx, p.width - 1)
                val y = minOf(j * BLOCK + dy, p.height - 1)
                val c = p.cfa.channel(x, y)
                sum[c] += cfa[y * p.width + x]
                count[c] += 1f
            }
            val r = sum[0] / count[0]
            val g = sum[1] / count[1]
            val b = sum[2] / count[2]
            val m = p.cameraToSrgb
            CpuDeveloper.linearLook(
                m[0] * r + m[1] * g + m[2] * b,
                m[3] * r + m[4] * g + m[5] * b,
                m[6] * r + m[7] * g + m[8] * b,
                p, px, lab,
            )
            lin[j * lw + i] = LR * px[0] + LG * px[1] + LB * px[2]
            val er = ToneCurve.srgbEncode(ToneCurve.shoulder(px[0], p.knee))
            val eg = ToneCurve.srgbEncode(ToneCurve.shoulder(px[1], p.knee))
            val eb = ToneCurve.srgbEncode(ToneCurve.shoulder(px[2], p.knee))
            enc[j * lw + i] = LR * er + LG * eg + LB * eb
        }
        return LowRes(lw, lh, enc, lin)
    }

    /** Normalised Gaussian weights for offsets 0..radius (radius = ceil(3 sigma)). */
    fun weights(sigma: Float): FloatArray {
        val radius = ceil(3f * sigma).toInt()
        val w = FloatArray(radius + 1) { exp(-(it * it) / (2f * sigma * sigma)) }
        var total = w[0]
        for (k in 1..radius) total += 2f * w[k]
        for (k in w.indices) w[k] /= total
        return w
    }

    /** One separable pass, clamping at the edges. Sum order (centre, then ±1, ±2, …) matches `blur.comp`. */
    fun blurPass(src: FloatArray, w: Int, h: Int, weights: FloatArray, horizontal: Boolean): FloatArray {
        val out = FloatArray(w * h)
        val radius = weights.size - 1
        for (y in 0 until h) for (x in 0 until w) {
            var acc = src[y * w + x] * weights[0]
            for (k in 1..radius) {
                val a: Float
                val b: Float
                if (horizontal) {
                    a = src[y * w + maxOf(x - k, 0)]
                    b = src[y * w + minOf(x + k, w - 1)]
                } else {
                    a = src[maxOf(y - k, 0) * w + x]
                    b = src[minOf(y + k, h - 1) * w + x]
                }
                acc += (a + b) * weights[k]
            }
            out[y * w + x] = acc
        }
        return out
    }

    /** Step 2. */
    fun blur(low: LowRes): Detail {
        val ws = weights(SMALL_SIGMA)
        val wl = weights(largeSigma(low.width, low.height))
        val small = blurPass(blurPass(low.encoded, low.width, low.height, ws, true), low.width, low.height, ws, false)
        val large = blurPass(blurPass(low.encoded, low.width, low.height, wl, true), low.width, low.height, wl, false)
        return Detail(low.width, low.height, small, large)
    }

    /** Step 3: bilinear sample of [img] (low resolution) at full-resolution sensor pixel ([x], [y]). */
    fun sample(img: FloatArray, lw: Int, lh: Int, x: Int, y: Int): Float {
        val u = ((x - 1.5f) / BLOCK).coerceIn(0f, (lw - 1).toFloat())
        val v = ((y - 1.5f) / BLOCK).coerceIn(0f, (lh - 1).toFloat())
        val i0 = minOf(u.toInt(), maxOf(lw - 2, 0))
        val j0 = minOf(v.toInt(), maxOf(lh - 2, 0))
        val i1 = minOf(i0 + 1, lw - 1)
        val j1 = minOf(j0 + 1, lh - 1)
        val fu = u - i0
        val fv = v - j0
        val top = img[j0 * lw + i0] + (img[j0 * lw + i1] - img[j0 * lw + i0]) * fu
        val bottom = img[j1 * lw + i0] + (img[j1 * lw + i1] - img[j1 * lw + i0]) * fu
        return top + (bottom - top) * fv
    }

    /**
     * New encoded luminance for a pixel of luminance [y] given the blurred surroundings:
     * Clarity boosts midtone detail at the large radius, Texture fine detail at the small one.
     */
    fun adjust(y: Float, small: Float, large: Float, clarity: Float, texture: Float): Float {
        val mid = (4f * y * (1f - y)).coerceIn(0f, 1f)
        return y + CLARITY_GAIN * clarity * mid * (y - large) + TEXTURE_GAIN * texture * (y - small)
    }
}

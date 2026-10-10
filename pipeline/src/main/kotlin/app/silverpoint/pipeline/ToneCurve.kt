package app.silverpoint.pipeline

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** 1D tone functions. Everything maps 0..1 to 0..1 unless noted. */
object ToneCurve {
    const val LUT_SIZE = 4096

    /** Default knee of the highlight shoulder: linear values above it roll off smoothly towards 1. */
    const val SHOULDER_KNEE = 0.8f

    /** Identity below [knee], then an exponential roll-off with matching slope; never reaches 1. */
    fun shoulder(x: Float, knee: Float = SHOULDER_KNEE): Float {
        if (x <= knee) return x
        val room = 1f - knee
        return knee + room * (1f - exp(-(x - knee) / room))
    }

    fun srgbEncode(x: Float): Float =
        if (x <= 0.0031308f) 12.92f * x else 1.055f * x.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f

    fun srgbDecode(x: Float): Float =
        if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    /**
     * Symmetric S-curve around mid-grey in encoded space. [amount] in -1..1: positive steepens the
     * middle (slope 1 + amount at 0.5) and flattens the ends. Monotonic and keeps 0 and 1 fixed.
     */
    fun contrast(x: Float, amount: Float): Float = (x - amount * sin(2.0 * PI * x) / (2.0 * PI)).toFloat()

    /** Lightroom's Contrast slider (-100..100) as an [contrast] amount, [scale] at ±100. */
    fun contrastAmount(slider: Float, scale: Float = LookTuning.DEFAULT.contrast): Float = scale * slider.coerceIn(-100f, 100f) / 100f

    /**
     * Monotone cubic interpolation (Fritsch-Carlson) through [points] (x ascending, both 0..1).
     * Outside the first and last point the curve is held flat.
     */
    fun monotoneSpline(points: List<Pair<Float, Float>>): (Float) -> Float {
        require(points.size >= 2) { "a curve needs at least two points" }
        val xs = points.map { it.first.toDouble() }
        val ys = points.map { it.second.toDouble() }
        require(xs.zipWithNext().all { (a, b) -> b > a }) { "curve x values must increase" }
        val n = xs.size
        val d = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        val m = DoubleArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0) 0.0 else (d[i - 1] + d[i]) / 2
        for (i in 0 until n - 1) {
            if (d[i] == 0.0) {
                m[i] = 0.0
                m[i + 1] = 0.0
                continue
            }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9) {
                val t = 3 / Math.sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        return { xf ->
            val x = xf.toDouble()
            when {
                x <= xs[0] -> ys[0].toFloat()
                x >= xs[n - 1] -> ys[n - 1].toFloat()
                else -> {
                    var i = 0
                    while (x > xs[i + 1]) i++
                    val h = xs[i + 1] - xs[i]
                    val t = (x - xs[i]) / h
                    val t2 = t * t
                    val t3 = t2 * t
                    (
                        (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] +
                            (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
                        ).toFloat()
                }
            }
        }
    }

    /** Samples [f] at [size] evenly spaced points from 0 to 1. */
    fun bake(size: Int = LUT_SIZE, f: (Float) -> Float): FloatArray =
        FloatArray(size) { f(it.toFloat() / (size - 1)).coerceIn(0f, 1f) }

    /** Linear interpolation into a LUT made by [bake]; [x] is clamped to 0..1. */
    fun lookup(lut: FloatArray, x: Float): Float {
        val pos = x.coerceIn(0f, 1f) * (lut.size - 1)
        val i = pos.toInt().coerceAtMost(lut.size - 2)
        val f = pos - i
        return lut[i] + (lut[i + 1] - lut[i]) * f
    }
}

package app.silverpoint.pipeline

import kotlin.math.abs
import kotlin.math.pow

/**
 * DNG-style colour processing (DNG specification 1.6, chapter 6 "Mapping Camera Color Space to
 * CIE XYZ Space"), reduced to what Camera2 provides: no analog balance, two calibration illuminants.
 */
object ColorMath {
    /** XYZ (D50 white) to linear sRGB, Bradford-adapted to D65 (Lindbloom). */
    val XYZ_D50_TO_SRGB = Mat3.of(
        3.1338561, -1.6168667, -0.4906146,
        -0.9787684, 1.9161415, 0.0334540,
        0.0719453, -0.2289914, 1.4052427,
    )

    /** Correlated colour temperature (K) of the EXIF light sources Camera2 reports. */
    fun illuminantTemperature(code: Int): Double = when (code) {
        17 -> 2856.0 // Standard light A
        18 -> 4874.0 // Standard light B
        19 -> 6774.0 // Standard light C
        20 -> 5503.0 // D55
        21 -> 6504.0 // D65
        22 -> 7504.0 // D75
        23 -> 5003.0 // D50
        24 -> 3200.0 // ISO studio tungsten
        2, 14 -> 4230.0 // fluorescent / cool white fluorescent
        3 -> 2856.0 // tungsten
        else -> 5003.0
    }

    /** CCT from CIE xy with McCamy's cubic: within a few kelvin of exact in the 2,800-6,500 K range we need. */
    fun cct(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return 449.0 * n.pow(3) + 3525.0 * n * n + 6823.3 * n + 5520.33
    }

    /** Weight of calibration 1 for a scene at [temperature], interpolating linearly in inverse temperature. */
    fun weight1(temperature: Double, t1: Double, t2: Double): Double {
        if (abs(t1 - t2) < 1.0) return 1.0
        val w = (1.0 / temperature - 1.0 / t2) / (1.0 / t1 - 1.0 / t2)
        return w.coerceIn(0.0, 1.0)
    }

    private fun mix(a: Mat3?, b: Mat3?, w1: Double): Mat3? = when {
        a == null -> b
        b == null -> a
        else -> a * w1 + b * (1.0 - w1)
    }

    /** The interpolated matrices for one shot plus the white point they were chosen for. */
    class Solution(
        val colorMatrix: Mat3,
        val forwardMatrix: Mat3?,
        val calibration: Mat3,
        val whiteXy: Pair<Double, Double>,
        val temperature: Double,
        val weight1: Double,
    )

    /** Finds the scene white point from the as-shot neutral by fixed-point iteration (DNG 6.2.5). */
    fun solve(meta: RawMetadata): Solution {
        val c1 = meta.calibration1
        val c2 = meta.calibration2
        val t1 = illuminantTemperature(c1.illuminant)
        val t2 = c2?.let { illuminantTemperature(it.illuminant) } ?: t1
        var xy = 0.3457 to 0.3585 // D50 start
        var w = 1.0
        var temp = 5003.0
        for (i in 0 until 30) {
            temp = cct(xy.first, xy.second).coerceIn(1500.0, 50000.0)
            w = weight1(temp, t1, t2)
            val cm = mix(c1.colorMatrix, c2?.colorMatrix, w)!!
            val cc = mix(c1.calibration ?: Mat3.IDENTITY, c2?.calibration ?: Mat3.IDENTITY, w)!!
            val xyz = (cc * cm).inverse() * meta.asShotNeutral
            val sum = xyz[0] + xyz[1] + xyz[2]
            val next = xyz[0] / sum to xyz[1] / sum
            val done = abs(next.first - xy.first) + abs(next.second - xy.second) < 1e-7
            xy = next
            if (done) break
        }
        temp = cct(xy.first, xy.second).coerceIn(1500.0, 50000.0)
        w = weight1(temp, t1, t2)
        return Solution(
            colorMatrix = mix(c1.colorMatrix, c2?.colorMatrix, w)!!,
            forwardMatrix = mix(c1.forwardMatrix, c2?.forwardMatrix, w),
            calibration = mix(c1.calibration ?: Mat3.IDENTITY, c2?.calibration ?: Mat3.IDENTITY, w)!!,
            whiteXy = xy,
            temperature = temp,
            weight1 = w,
        )
    }

    /**
     * CIE xy of the black-body (Planckian) locus at [kelvin], from Kim et al.'s cubic fit
     * (valid 1,667 to 25,000 K).
     */
    fun planckianXy(kelvin: Double): Pair<Double, Double> {
        val t = kelvin.coerceIn(1667.0, 25000.0)
        val x = if (t <= 4000) {
            -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return x to y
    }

    /** CIE 1960 uv shift per unit of tint, Adobe's scale (DNG SDK): tint ±50 moves the white ±0.0167 off the locus. */
    const val TINT_DUV_PER_UNIT = 1.0 / 3000.0

    /**
     * CIE xy of the light to balance for at [kelvin] with [tint], as Lightroom reads its sliders:
     * positive tint means greenish light (above the locus), so balancing for it turns the photo
     * magenta; negative tint means magenta light and a greener photo.
     */
    fun whiteXy(kelvin: Double, tint: Double): Pair<Double, Double> {
        fun uv(xy: Pair<Double, Double>): Pair<Double, Double> {
            val d = -2 * xy.first + 12 * xy.second + 3
            return 4 * xy.first / d to 6 * xy.second / d
        }
        val (u, v) = uv(planckianXy(kelvin))
        val (u2, v2) = uv(planckianXy(kelvin + 10))
        // Unit normal to the locus pointing towards green (larger v).
        var nu = -(v2 - v)
        var nv = u2 - u
        if (nv < 0) {
            nu = -nu
            nv = -nv
        }
        val len = kotlin.math.sqrt(nu * nu + nv * nv)
        val shift = tint * TINT_DUV_PER_UNIT
        val tu = u + nu / len * shift
        val tv = v + nv / len * shift
        val d = 2 * tu - 8 * tv + 4
        return 3 * tu / d to 2 * tv / d
    }

    /**
     * The camera's neutral (its RGB response to a white at [kelvin] and [tint], green = 1), from the
     * colour matrices interpolated for that temperature. Feeding it back as the as-shot neutral white
     * balances the photo to that light.
     */
    fun neutralFor(meta: RawMetadata, kelvin: Double, tint: Double = 0.0): DoubleArray {
        val c1 = meta.calibration1
        val c2 = meta.calibration2
        val t1 = illuminantTemperature(c1.illuminant)
        val t2 = c2?.let { illuminantTemperature(it.illuminant) } ?: t1
        val w = weight1(kelvin, t1, t2)
        val cm = mix(c1.colorMatrix, c2?.colorMatrix, w)!!
        val cc = mix(c1.calibration ?: Mat3.IDENTITY, c2?.calibration ?: Mat3.IDENTITY, w)!!
        val (x, y) = whiteXy(kelvin, tint)
        val n = (cc * cm) * doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        return doubleArrayOf(n[0] / n[1], 1.0, n[2] / n[1])
    }

    /** RAW white-balance multipliers (R, G, B): 1 / neutral, green = 1. */
    fun whiteBalanceGains(meta: RawMetadata): DoubleArray {
        val n = meta.asShotNeutral
        return doubleArrayOf(n[1] / n[0], 1.0, n[1] / n[2])
    }

    /**
     * Matrix taking camera RGB that has **already been white balanced** with [whiteBalanceGains]
     * to linear sRGB (D65). Uses the forward matrix when present (DNG 6.3.2), else the colour matrix
     * with Bradford adaptation from the scene white to D50 (DNG 6.3.1).
     */
    fun cameraToSrgb(meta: RawMetadata): Mat3 {
        val s = solve(meta)
        val gains = whiteBalanceGains(meta)
        val undoGains = Mat3.diag(1.0 / gains[0], 1.0 / gains[1], 1.0 / gains[2])
        val ccInv = s.calibration.inverse()
        val cameraToXyzD50 = if (s.forwardMatrix != null) {
            val ref = ccInv * meta.asShotNeutral
            s.forwardMatrix * Mat3.diag(1.0 / ref[0], 1.0 / ref[1], 1.0 / ref[2]) * ccInv
        } else {
            val toSceneXyz = (s.calibration * s.colorMatrix).inverse()
            bradford(s.whiteXy, D50_XY) * toSceneXyz
        }
        return XYZ_D50_TO_SRGB * cameraToXyzD50 * undoGains
    }

    private val D50_XY = 0.34567 to 0.35850

    private val BRADFORD = Mat3.of(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )

    /** Chromatic adaptation from white [from] to white [to] (CIE xy), normalising Y. */
    fun bradford(from: Pair<Double, Double>, to: Pair<Double, Double>): Mat3 {
        fun xyz(xy: Pair<Double, Double>) = doubleArrayOf(xy.first / xy.second, 1.0, (1 - xy.first - xy.second) / xy.second)
        val s = BRADFORD * xyz(from)
        val d = BRADFORD * xyz(to)
        return BRADFORD.inverse() * Mat3.diag(d[0] / s[0], d[1] / s[1], d[2] / s[2]) * BRADFORD
    }
}

package app.silverpoint.pipeline

import kotlin.math.cbrt
import kotlin.math.sqrt

/** Björn Ottosson's OKLab, from and to linear sRGB. Used for saturation so hues stay put. */
object Oklab {
    fun fromLinearSrgb(r: Float, g: Float, b: Float, out: FloatArray) {
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    fun toLinearSrgb(lab0: Float, a: Float, b: Float, out: FloatArray) {
        val l1 = lab0 + 0.3963377774f * a + 0.2158037573f * b
        val m1 = lab0 - 0.1055613458f * a - 0.0638541728f * b
        val s1 = lab0 - 0.0894841775f * a - 1.2914855480f * b
        val l = l1 * l1 * l1
        val m = m1 * m1 * m1
        val s = s1 * s1 * s1
        out[0] = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        out[1] = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        out[2] = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
    }

    fun chroma(a: Float, b: Float): Float = sqrt(a * a + b * b)
}

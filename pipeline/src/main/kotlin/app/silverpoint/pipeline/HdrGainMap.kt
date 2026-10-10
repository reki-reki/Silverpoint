package app.silverpoint.pipeline

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The Ultra HDR gain map: how much brighter each area may be on an HDR screen than in the normal
 * (SDR) photo. The SDR photo squeezes highlights into the highlight shoulder; the gain map
 * undoes that squeeze, up to [HEADROOM] times brighter, so on an HDR screen clouds, lamps and
 * reflections glow while midtones (and the recipe's look) stay as they are.
 *
 * Built from the quarter-resolution linear luminance that `lowres.comp` computes anyway.
 */
object HdrGainMap {
    /** Largest boost, 2 stops. */
    const val HEADROOM = 4f

    class Map(val width: Int, val height: Int, /** log2(gain) / log2(HEADROOM) as 0..255, row-major, upright. */ val bytes: ByteArray) {
        /** The part of the map under a crop of the photo, given as fractions of its width and height. */
        fun crop(fx: Float, fy: Float, fw: Float, fh: Float): Map {
            val x0 = (fx * width).toInt().coerceIn(0, width - 1)
            val y0 = (fy * height).toInt().coerceIn(0, height - 1)
            val w = Math.round(fw * width).coerceIn(1, width - x0)
            val h = Math.round(fh * height).coerceIn(1, height - y0)
            val out = ByteArray(w * h)
            for (y in 0 until h) System.arraycopy(bytes, (y0 + y) * width + x0, out, y * w, w)
            return Map(w, h, out)
        }
    }

    /** Gain for a linear luminance [y] (exposure applied, before the shoulder). */
    fun gain(y: Float, knee: Float): Float {
        if (y <= knee) return 1f
        val sdr = ToneCurve.shoulder(y, knee)
        return (y / sdr).coerceIn(1f, HEADROOM)
    }

    /**
     * Encodes [linear] ([lw] x [lh], sensor orientation) into an upright gain map for a photo
     * rotated [rotation] degrees clockwise.
     */
    fun encode(linear: FloatArray, lw: Int, lh: Int, knee: Float, rotation: Int): Map {
        val ow = if (rotation % 180 == 0) lw else lh
        val oh = if (rotation % 180 == 0) lh else lw
        val out = ByteArray(ow * oh)
        val logMax = ln(HEADROOM)
        for (oy in 0 until oh) for (ox in 0 until ow) {
            // Two plain Ints, not a Pair: this runs for every gain-map pixel of every photo.
            val sx = when (rotation) {
                90 -> oy
                180 -> lw - 1 - ox
                270 -> lw - 1 - oy
                else -> ox
            }
            val sy = when (rotation) {
                90 -> lh - 1 - ox
                180 -> lh - 1 - oy
                270 -> ox
                else -> oy
            }
            val g = gain(linear[sy * lw + sx], knee)
            out[oy * ow + ox] = ((ln(g) / logMax) * 255f).roundToInt().coerceIn(0, 255).toByte()
        }
        return Map(ow, oh, out)
    }
}

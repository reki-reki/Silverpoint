package app.silverpoint.camera

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Pure helpers for Pro mode: exposure priority, standard stops, tap regions. Unit tested. */
object ExposureMath {
    /** One-third-stop ISO values photographers expect. */
    val ISO_STOPS = intArrayOf(12, 16, 20, 25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)

    /** One-third-stop shutter speeds as (numerator, denominator) seconds, fastest first. */
    val SHUTTER_STOPS: List<Pair<Int, Int>> = listOf(
        1 to 8000, 1 to 6400, 1 to 5000, 1 to 4000, 1 to 3200, 1 to 2500, 1 to 2000, 1 to 1600, 1 to 1250, 1 to 1000,
        1 to 800, 1 to 640, 1 to 500, 1 to 400, 1 to 320, 1 to 250, 1 to 200, 1 to 160, 1 to 125, 1 to 100,
        1 to 80, 1 to 60, 1 to 50, 1 to 40, 1 to 30, 1 to 25, 1 to 20, 1 to 15, 1 to 13, 1 to 10,
        1 to 8, 1 to 6, 1 to 5, 1 to 4, 3 to 10, 2 to 5, 1 to 2,
    )

    fun shutterNs(stop: Pair<Int, Int>): Long = (1e9 * stop.first / stop.second).roundToLong()

    /** "1/250", "1/4" or "0.3 s" (1/4 s itself is a fraction, so it does not read as 0.3 s). */
    fun shutterLabel(ns: Long): String =
        if (ns <= 250_000_000L) "1/${(1e9 / ns).roundToInt()}" else String.format(java.util.Locale.US, "%.1f s", ns / 1e9)

    fun isoStops(min: Int, max: Int): List<Int> = ISO_STOPS.filter { it in min..max }

    fun shutterStops(minNs: Long, maxNs: Long): List<Long> = SHUTTER_STOPS.map(::shutterNs).filter { it in minNs..maxNs }

    /**
     * The ISO and exposure time to shoot with in Pro mode, or null to let auto-exposure decide.
     * With only one of [iso] or [exposureNs] set, the other is chosen so the total exposure matches
     * what auto-exposure metered for the preview ([meteredIso] at [meteredNs], compensation included):
     * shutter or ISO priority, emulated because this phone has no hybrid auto-exposure.
     */
    fun forCapture(
        iso: Int?, exposureNs: Long?,
        meteredIso: Int?, meteredNs: Long?,
        isoRange: IntRange, exposureRange: LongRange,
    ): Pair<Int, Long>? {
        if (iso == null && exposureNs == null) return null
        if (iso != null && exposureNs != null) return iso.coerceIn(isoRange) to exposureNs.coerceIn(exposureRange)
        if (meteredIso == null || meteredNs == null) return null
        val total = meteredIso.toDouble() * meteredNs
        return if (exposureNs != null) {
            val ns = exposureNs.coerceIn(exposureRange)
            (total / ns).roundToInt().coerceIn(isoRange) to ns
        } else {
            val i = iso!!.coerceIn(isoRange)
            i to (total / i).roundToLong().coerceIn(exposureRange)
        }
    }

    /**
     * A point in the upright viewfinder ([u], [v] in 0..1, origin top-left) as a point on the sensor
     * (0..1, sensor orientation), for a sensor mounted at [sensorOrientation] degrees.
     */
    fun viewToSensor(u: Float, v: Float, sensorOrientation: Int): Pair<Float, Float> = when (((sensorOrientation % 360) + 360) % 360) {
        90 -> v to 1f - u
        180 -> 1f - u to 1f - v
        270 -> 1f - v to u
        else -> u to v
    }

    /** Lens focus distance in diopters as a label: "∞", "2.5 m", "35 cm". */
    fun focusLabel(diopters: Float): String = when {
        diopters <= 0.01f -> "∞"
        1f / diopters >= 1f -> String.format(java.util.Locale.US, "%.1f m", 1f / diopters)
        else -> "${(100f / diopters).roundToInt()} cm"
    }
}

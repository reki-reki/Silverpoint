package app.silverpoint

import android.hardware.camera2.CameraMetadata
import java.util.Locale
import kotlin.math.roundToInt

/** Small pure helpers for the camera screen, kept separate so they can be unit tested. */
object CameraMath {
    /** The EV dial moves in thirds of a stop from -2 to +2: index -6..6. */
    const val EV_THIRDS_MAX = 6

    /**
     * Camera AE-compensation steps for a dial position, rounded to the camera's step size
     * ([stepNumerator]/[stepDenominator] EV, 1/10 on the S25 Ultra) and clamped to its range.
     */
    fun aeSteps(thirds: Int, stepNumerator: Int, stepDenominator: Int, minSteps: Int, maxSteps: Int): Int {
        val ev = thirds / 3.0
        val step = stepNumerator.toDouble() / stepDenominator
        return (ev / step).roundToInt().coerceIn(minSteps, maxSteps)
    }

    /** Label for the EV actually applied, for example "+0.7 EV" or "±0.0 EV". */
    fun evLabel(steps: Int, stepNumerator: Int, stepDenominator: Int): String {
        val ev = steps.toDouble() * stepNumerator / stepDenominator
        return if (Math.abs(ev) < 0.05) "±0.0 EV" else String.format(Locale.US, "%+.1f EV", ev)
    }

    /**
     * Clockwise rotation (0/90/180/270) that makes a back-camera photo upright, from the sensor
     * orientation and the phone's orientation as reported by `OrientationEventListener`
     * (degrees clockwise from natural portrait, or -1 when flat/unknown).
     */
    fun photoRotation(sensorOrientation: Int, deviceDegrees: Int): Int {
        val device = if (deviceDegrees < 0) 0 else ((deviceDegrees + 45) / 90 * 90) % 360
        return (sensorOrientation + device) % 360
    }

    /**
     * How far the phone is from level, -45..45 degrees, from an `OrientationEventListener` angle
     * (the nearest of portrait, landscape and their upside-down versions counts as level).
     */
    fun tiltFromLevel(orientation: Int): Float {
        val d = ((orientation % 90) + 90) % 90
        return if (d > 45) (d - 90).toFloat() else d.toFloat()
    }

    /**
     * The camera's white-balance preset closest to [kelvin], for the preview only (the photo is
     * balanced exactly in the developer). Null means auto.
     */
    fun awbModeFor(kelvin: Int?): Int = when {
        kelvin == null -> CameraMetadata.CONTROL_AWB_MODE_AUTO
        kelvin < 3200 -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        kelvin < 3700 -> CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
        kelvin < 4600 -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        kelvin < 6000 -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        kelvin < 7000 -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        else -> CameraMetadata.CONTROL_AWB_MODE_SHADE
    }

    /** Low light for the Auto merge: ISO 640 or more, or a shutter of 1/30 s or longer. */
    fun isLowLight(iso: Int, exposureNs: Long): Boolean = iso >= 640 || exposureNs >= 33_000_000L

    /** Where the viewfinder sits, in the stage under the top bar (any unit; dp on screen). */
    data class Framing(
        /** The camera picture (always 3:4 portrait, the sensor's shape): its size and top-left corner. */
        val frameX: Float, val frameY: Float, val frameW: Float, val frameH: Float,
        /** The part of it the photo will keep, shown unmasked. */
        val visX: Float, val visY: Float, val visW: Float, val visH: Float,
    ) {
        /** A point of the visible area (0..1 from its top-left) as a point of the whole camera picture. */
        fun toFrame(u: Float, v: Float): Pair<Float, Float> =
            ((visX + u * visW - frameX) / frameW) to ((visY + v * visH - frameY) / frameH)
    }

    /**
     * Lays out the viewfinder for an aspect [ratio] (long side over short, portrait) in a stage
     * [stageW] wide and [stageH] tall. The visible area is as wide as the stage. Shapes squarer than
     * the sensor (4:5, 1:1) are the middle band of the full-width 3:4 picture; narrower ones (9:16)
     * enlarge the picture until it fills their height and crop its sides, as Samsung's camera does.
     * The picture never starts above the stage, so it cannot show through the top bar.
     */
    fun framing(stageW: Float, stageH: Float, ratio: Float): Framing {
        var visW = stageW
        var visH = stageW * ratio
        if (visH > stageH) {
            visH = stageH
            visW = stageH / ratio
        }
        return if (ratio <= SENSOR_RATIO + 1e-4f) {
            val frameW = visW
            val frameH = frameW * SENSOR_RATIO
            val frameX = (stageW - frameW) / 2f
            Framing(frameX, 0f, frameW, frameH, frameX, (frameH - visH) / 2f, visW, visH)
        } else {
            val frameH = visH
            val frameW = frameH / SENSOR_RATIO
            val visX = (stageW - visW) / 2f
            Framing((stageW - frameW) / 2f, 0f, frameW, frameH, visX, 0f, visW, visH)
        }
    }

    /** The sensor's shape, long side over short (4:3). */
    const val SENSOR_RATIO = 4f / 3f

    /**
     * The part of a developed photo [width] x [height] to keep for an aspect [ratio] (long side over
     * short): centred, the long side along whichever way the photo is held. (x, y, width, height).
     */
    fun cropFor(width: Int, height: Int, ratio: Float): IntArray {
        val portrait = height >= width
        val long = maxOf(width, height)
        val short = minOf(width, height)
        val current = long.toFloat() / short
        var keepLong = long
        var keepShort = short
        if (ratio > current + 1e-3f) keepShort = (long / ratio).roundToInt() else if (ratio < current - 1e-3f) keepLong = (short * ratio).roundToInt()
        val w = if (portrait) keepShort else keepLong
        val h = if (portrait) keepLong else keepShort
        return intArrayOf((width - w) / 2, (height - h) / 2, w, h)
    }
}


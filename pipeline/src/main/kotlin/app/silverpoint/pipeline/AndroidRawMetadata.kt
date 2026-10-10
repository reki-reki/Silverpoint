package app.silverpoint.pipeline

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.LensShadingMap
import android.hardware.camera2.params.RggbChannelVector

/** Builds [RawMetadata] from Camera2 objects. Use the physical lens's characteristics and result. */
object AndroidRawMetadata {

    fun from(chars: CameraCharacteristics, result: CaptureResult, width: Int, height: Int): RawMetadata {
        val cfa = when (chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> Cfa.RGGB
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> Cfa.GRBG
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> Cfa.GBRG
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> Cfa.BGGR
            else -> throw IllegalArgumentException("not a Bayer sensor")
        }
        // Dynamic black level is listed in the same 2x2 order as the static pattern.
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.copyOf()
            ?: chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
                floatArrayOf(
                    p.getOffsetForIndex(0, 0).toFloat(), p.getOffsetForIndex(1, 0).toFloat(),
                    p.getOffsetForIndex(0, 1).toFloat(), p.getOffsetForIndex(1, 1).toFloat(),
                )
            } ?: floatArrayOf(0f, 0f, 0f, 0f)
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: 1023
        val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map { it.toDouble() }
            ?: result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { g -> listOf(1.0 / g.red, 2.0 / (g.greenEven + g.greenOdd), 1.0 / g.blue) }
            ?: listOf(1.0, 1.0, 1.0)
        val n = DoubleArray(3) { neutral[it] / neutral[1] }

        val cal1 = Calibration(
            illuminant = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 21,
            colorMatrix = mat(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1))
                ?: throw IllegalArgumentException("sensor has no colour matrix"),
            forwardMatrix = mat(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)),
            calibration = mat(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)),
        )
        val cm2 = mat(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2))
        val cal2 = cm2?.let {
            Calibration(
                illuminant = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: cal1.illuminant,
                colorMatrix = it,
                forwardMatrix = mat(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)),
                calibration = mat(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)),
            )
        }
        return RawMetadata(
            width = width,
            height = height,
            cfa = cfa,
            blackLevel = black,
            whiteLevel = white.toFloat(),
            asShotNeutral = n,
            calibration1 = cal1,
            calibration2 = cal2,
            lensShading = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { shading(it) },
        )
    }

    private fun mat(t: ColorSpaceTransform?): Mat3? = t?.let { m ->
        Mat3(DoubleArray(9) { i -> m.getElement(i % 3, i / 3).toDouble() })
    }

    private fun shading(map: LensShadingMap): LensShading {
        val cols = map.columnCount
        val rows = map.rowCount
        val g = FloatArray(cols * rows * 4)
        for (r in 0 until rows) for (c in 0 until cols) {
            val o = (r * cols + c) * 4
            g[o] = map.getGainFactor(RggbChannelVector.RED, c, r)
            g[o + 1] = map.getGainFactor(RggbChannelVector.GREEN_EVEN, c, r)
            g[o + 2] = map.getGainFactor(RggbChannelVector.GREEN_ODD, c, r)
            g[o + 3] = map.getGainFactor(RggbChannelVector.BLUE, c, r)
        }
        return LensShading(cols, rows, g)
    }
}

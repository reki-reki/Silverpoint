package app.silverpoint.pipeline

/** Colour filter layout, named by the colours at (0,0), (1,0), (0,1), (1,1). */
enum class Cfa(val pattern: IntArray) {
    RGGB(intArrayOf(0, 1, 1, 2)),
    GRBG(intArrayOf(1, 0, 2, 1)),
    GBRG(intArrayOf(1, 2, 0, 1)),
    BGGR(intArrayOf(2, 1, 1, 0));

    /** Colour channel (0 = R, 1 = G, 2 = B) of the sample at ([x], [y]). */
    fun channel(x: Int, y: Int): Int = pattern[(x and 1) + 2 * (y and 1)]
}

/**
 * Lens shading gains on a coarse grid that spans the whole image. [gains] holds, for each grid point
 * in row-major order, the four values R, G-even, G-odd, B (Camera2's order: G-even is the green on
 * even rows of the RAW image, G-odd on odd rows).
 */
class LensShading(val columns: Int, val rows: Int, val gains: FloatArray) {
    init {
        require(gains.size == columns * rows * 4) { "expected ${columns * rows * 4} gains, got ${gains.size}" }
    }

    /** Gain for the sample at ([x], [y]) of a [width] x [height] image, bilinearly interpolated. */
    fun gain(x: Int, y: Int, width: Int, height: Int, cfa: Cfa): Float {
        val ch = when (cfa.channel(x, y)) {
            0 -> 0
            2 -> 3
            else -> if (y and 1 == 0) 1 else 2
        }
        val gx = x.toFloat() * (columns - 1) / (width - 1).coerceAtLeast(1)
        val gy = y.toFloat() * (rows - 1) / (height - 1).coerceAtLeast(1)
        val x0 = gx.toInt().coerceAtMost(columns - 2)
        val y0 = gy.toInt().coerceAtMost(rows - 2)
        val fx = gx - x0
        val fy = gy - y0
        fun g(c: Int, r: Int) = gains[(r * columns + c) * 4 + ch]
        val top = g(x0, y0) + (g(x0 + 1, y0) - g(x0, y0)) * fx
        val bottom = g(x0, y0 + 1) + (g(x0 + 1, y0 + 1) - g(x0, y0 + 1)) * fx
        return top + (bottom - top) * fy
    }
}

/** One calibration illuminant of the sensor (DNG terms) with its matrices. */
class Calibration(
    /** EXIF light source code: 17 = Standard A, 21 = D65, and so on. */
    val illuminant: Int,
    /** XYZ to reference camera RGB (Camera2 `SENSOR_COLOR_TRANSFORM`, DNG ColorMatrix). */
    val colorMatrix: Mat3,
    /** White-balanced camera RGB to XYZ D50 (Camera2 `SENSOR_FORWARD_MATRIX`, DNG ForwardMatrix). */
    val forwardMatrix: Mat3?,
    /** Reference to this unit's camera RGB (Camera2 `SENSOR_CALIBRATION_TRANSFORM`, DNG CameraCalibration). */
    val calibration: Mat3?,
)

/**
 * Everything the developer needs to know about one RAW frame, independent of Android types so the
 * same maths runs in JVM tests. Built on device by `AndroidRawMetadata.from`.
 */
class RawMetadata(
    val width: Int,
    val height: Int,
    val cfa: Cfa,
    /** Black level per 2x2 position: index (x and 1) + 2 * (y and 1). */
    val blackLevel: FloatArray,
    val whiteLevel: Float,
    /** As-shot white balance: camera RGB response to a neutral surface, green normalised to 1. */
    val asShotNeutral: DoubleArray,
    val calibration1: Calibration,
    val calibration2: Calibration?,
    val lensShading: LensShading?,
) {
    init {
        require(blackLevel.size == 4) { "black level needs 4 values" }
        require(asShotNeutral.size == 3) { "neutral needs 3 values" }
    }

    /** The same frame white balanced to a different neutral (Pro mode white balance). */
    fun withNeutral(neutral: DoubleArray) =
        RawMetadata(width, height, cfa, blackLevel, whiteLevel, neutral, calibration1, calibration2, lensShading)

    /** The same frame with black and white levels scaled by [factor] (merged frames keep extra precision). */
    fun withLevelsScaled(factor: Float) =
        RawMetadata(width, height, cfa, FloatArray(4) { blackLevel[it] * factor }, whiteLevel * factor, asShotNeutral, calibration1, calibration2, lensShading)
}

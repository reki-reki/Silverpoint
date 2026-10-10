package app.silverpoint.pipeline

/**
 * Made-up sensor and scene for tests and the on-device GPU self-test: plausible matrices and a RAW
 * frame with greys, colours, gradients, noise and clipped highlights. Not any real camera's data.
 */
object SyntheticScene {
    /** XYZ to camera RGB, roughly the shape of a phone sensor's colour matrix (D65). */
    private val CM_D65 = Mat3.of(
        0.95, -0.30, -0.08,
        -0.45, 1.30, 0.15,
        -0.10, 0.25, 0.55,
    )

    /** Same for standard illuminant A. */
    private val CM_A = Mat3.of(
        1.10, -0.45, -0.05,
        -0.40, 1.25, 0.18,
        -0.05, 0.15, 0.80,
    )

    /** Forward matrix whose rows send camera white (1,1,1) to D50 white, as the DNG spec requires. */
    private fun forwardFor(cm: Mat3): Mat3 {
        val inv = cm.inverse()
        val d50 = doubleArrayOf(0.96422, 1.0, 0.82521)
        return Mat3(DoubleArray(9) { i ->
            val r = i / 3
            inv[r, i % 3] * d50[r] / (inv[r, 0] + inv[r, 1] + inv[r, 2])
        })
    }

    fun meta(
        neutral: DoubleArray,
        withForward: Boolean = true,
        width: Int = 16,
        height: Int = 12,
        cfa: Cfa = Cfa.GBRG,
        shading: LensShading? = null,
    ) = RawMetadata(
        width = width,
        height = height,
        cfa = cfa,
        blackLevel = floatArrayOf(64f, 64f, 64f, 64f),
        whiteLevel = 1023f,
        asShotNeutral = neutral,
        calibration1 = Calibration(21, CM_D65, if (withForward) forwardFor(CM_D65) else null, Mat3.IDENTITY),
        calibration2 = Calibration(17, CM_A, if (withForward) forwardFor(CM_A) else null, Mat3.IDENTITY),
        lensShading = shading,
    )

    /** A RAW frame where every sample is the camera's response to a grey of linear level [level]. */
    fun flatGrey(meta: RawMetadata, level: Float): ShortArray {
        val raw = ShortArray(meta.width * meta.height)
        for (y in 0 until meta.height) for (x in 0 until meta.width) {
            val ch = meta.cfa.channel(x, y)
            val v = 64f + level * meta.asShotNeutral[ch].toFloat() * (1023f - 64f)
            raw[y * meta.width + x] = v.toInt().toShort()
        }
        return raw
    }

    /** Lens shading that rises from 1 in the centre to 2 in the corners, slightly different per channel. */
    fun radialShading(columns: Int = 17, rows: Int = 13): LensShading {
        val g = FloatArray(columns * rows * 4)
        for (r in 0 until rows) for (c in 0 until columns) {
            val dx = (c - (columns - 1) / 2f) / ((columns - 1) / 2f)
            val dy = (r - (rows - 1) / 2f) / ((rows - 1) / 2f)
            val d2 = (dx * dx + dy * dy) / 2f
            for (ch in 0..3) g[(r * columns + c) * 4 + ch] = 1f + d2 * (0.9f + 0.05f * ch)
        }
        return LensShading(columns, rows, g)
    }

    /**
     * A busy test frame: grey ramp across the top, six colour patches, a sharp diagonal edge,
     * a clipped highlight block, plus deterministic noise.
     */
    fun busyFrame(meta: RawMetadata): ShortArray {
        val w = meta.width
        val h = meta.height
        val raw = ShortArray(w * h)
        val colours = arrayOf(
            floatArrayOf(0.30f, 0.08f, 0.05f), floatArrayOf(0.10f, 0.25f, 0.06f), floatArrayOf(0.05f, 0.10f, 0.30f),
            floatArrayOf(0.30f, 0.28f, 0.06f), floatArrayOf(0.06f, 0.22f, 0.25f), floatArrayOf(0.25f, 0.08f, 0.22f),
        )
        var seed = 12345
        for (y in 0 until h) for (x in 0 until w) {
            val ch = meta.cfa.channel(x, y)
            val n = meta.asShotNeutral[ch].toFloat()
            val level: Float = when {
                y < h / 4 -> n * (x.toFloat() / w) * 0.9f
                x > w * 7 / 8 && y > h * 7 / 8 -> 1.2f
                y > h * 3 / 4 -> n * if (x > y) 0.6f else 0.05f
                else -> colours[(x * 3 / w) + 3 * ((y - h / 4) * 2 / (h / 2)).coerceAtMost(1)][ch] * n * 2f
            }
            seed = seed * 1103515245 + 12345
            val noise = ((seed ushr 16) and 0x3F) - 32
            val v = (64f + level.coerceAtMost(1.2f) * 959f + noise).coerceIn(0f, 1023f)
            raw[y * w + x] = v.toInt().toShort()
        }
        return raw
    }
}

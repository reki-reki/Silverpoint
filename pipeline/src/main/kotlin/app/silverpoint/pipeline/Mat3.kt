package app.silverpoint.pipeline

/** Row-major 3x3 matrix in double precision. Small and immutable: colour maths only needs a handful per shot. */
class Mat3(private val m: DoubleArray) {
    init {
        require(m.size == 9) { "Mat3 needs 9 values" }
    }

    operator fun get(row: Int, col: Int): Double = m[row * 3 + col]

    operator fun times(o: Mat3): Mat3 = Mat3(DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        this[r, 0] * o[0, c] + this[r, 1] * o[1, c] + this[r, 2] * o[2, c]
    })

    operator fun times(v: DoubleArray): DoubleArray = DoubleArray(3) { r ->
        this[r, 0] * v[0] + this[r, 1] * v[1] + this[r, 2] * v[2]
    }

    operator fun times(s: Double): Mat3 = Mat3(DoubleArray(9) { m[it] * s })

    operator fun plus(o: Mat3): Mat3 = Mat3(DoubleArray(9) { m[it] + o.m[it] })

    fun inverse(): Mat3 {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        require(Math.abs(det) > 1e-12) { "matrix is not invertible" }
        val inv = 1.0 / det
        return Mat3(doubleArrayOf(
            c00 * inv, (a[2] * a[7] - a[1] * a[8]) * inv, (a[1] * a[5] - a[2] * a[4]) * inv,
            c01 * inv, (a[0] * a[8] - a[2] * a[6]) * inv, (a[2] * a[3] - a[0] * a[5]) * inv,
            c02 * inv, (a[1] * a[6] - a[0] * a[7]) * inv, (a[0] * a[4] - a[1] * a[3]) * inv,
        ))
    }

    fun toFloatArray(): FloatArray = FloatArray(9) { m[it].toFloat() }

    fun toList(): List<Double> = m.toList()

    override fun toString(): String = m.joinToString(prefix = "Mat3(", postfix = ")")

    companion object {
        val IDENTITY = diag(1.0, 1.0, 1.0)

        fun of(vararg v: Double) = Mat3(v.copyOf())

        fun of(v: FloatArray) = Mat3(DoubleArray(9) { v[it].toDouble() })

        fun diag(a: Double, b: Double, c: Double) = Mat3(doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c))
    }
}

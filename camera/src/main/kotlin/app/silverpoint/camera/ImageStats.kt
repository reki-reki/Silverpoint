package app.silverpoint.camera

import android.media.Image

internal object ImageStats {
    /** Size, layout and a sparse sample of pixel values, enough to tell a real frame from an empty one. */
    fun describe(image: Image): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(
            "imageSize" to "${image.width}x${image.height}",
            "imageFormat" to image.format,
            "timestampNs" to image.timestamp,
        )
        val plane = image.planes.firstOrNull() ?: return out + ("note" to "no planes")
        val buf = plane.buffer
        out["rowStrideBytes"] = plane.rowStride
        out["pixelStrideBytes"] = plane.pixelStride
        out["bufferBytes"] = buf.capacity()
        // Sample ~20k 16-bit samples spread across the buffer (little-endian).
        val shorts = buf.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = shorts.limit()
        if (n > 0) {
            val step = maxOf(1, n / 20_000)
            var min = Int.MAX_VALUE
            var max = 0
            var sum = 0L
            var count = 0
            var i = 0
            while (i < n) {
                val v = shorts.get(i).toInt() and 0xFFFF
                if (v < min) min = v
                if (v > max) max = v
                sum += v
                count++
                i += step
            }
            out["sampleMin"] = min
            out["sampleMax"] = max
            out["sampleMean"] = Math.round(sum.toDouble() / count * 10) / 10.0
        }
        return out
    }
}

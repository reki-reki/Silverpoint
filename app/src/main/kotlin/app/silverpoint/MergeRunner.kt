package app.silverpoint

import app.silverpoint.camera.RawCapture
import app.silverpoint.pipeline.Merge
import app.silverpoint.pipeline.RawMetadata
import java.nio.ByteOrder

/** Feeds a burst into [Merge.Merger] one frame at a time, releasing each camera buffer as soon as it is copied. */
object MergeRunner {
    /** Returns the merged frame ([meta] width per row) at [Merge.SCALE] times the original precision. */
    fun merge(captures: List<RawCapture>, meta: RawMetadata, noise: Merge.Noise): ShortArray {
        val reference = captures.first()
        val stride = reference.image.planes[0].rowStride / 2
        val scratch = ShortArray(stride * meta.height)
        copy(reference, scratch)
        reference.close()
        // The merger reads the reference only while it is constructed, so the buffer can be reused.
        val merger = Merge.Merger(scratch, stride, meta.width, meta.height, meta.blackLevel, meta.whiteLevel, noise)
        for (c in captures.drop(1)) {
            copy(c, scratch)
            c.close()
            merger.add(scratch)
        }
        return merger.result()
    }

    private fun copy(c: RawCapture, into: ShortArray) {
        val buf = c.image.planes[0].buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        buf.get(into, 0, minOf(into.size, buf.remaining()))
    }
}

package app.silverpoint.storage

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes EXIF into a JPEG without re-encoding or rewriting the rest of the file, so an Ultra HDR
 * gain map (a second JPEG after the first, indexed by an MPF segment) survives: the EXIF segment is
 * inserted right after the start-of-image marker, and the MPF entry for the primary image, whose
 * size grows by the inserted bytes, is patched to match.
 */
object JpegExif {
    /** One EXIF field. [ifd] says which directory it belongs to. */
    sealed class Field(val tag: Int, val ifd: Ifd) {
        class Ascii(tag: Int, ifd: Ifd, val value: String) : Field(tag, ifd)
        class Short(tag: Int, ifd: Ifd, val value: Int) : Field(tag, ifd)
        class Rational(tag: Int, ifd: Ifd, val numerator: Long, val denominator: Long) : Field(tag, ifd)
        class Undefined(tag: Int, ifd: Ifd, val value: ByteArray) : Field(tag, ifd)
    }

    enum class Ifd { Main, Exif }

    // Tags (EXIF 2.32).
    const val IMAGE_DESCRIPTION = 0x010E
    const val MAKE = 0x010F
    const val MODEL = 0x0110
    const val ORIENTATION = 0x0112
    const val SOFTWARE = 0x0131
    const val DATE_TIME = 0x0132
    const val EXIF_POINTER = 0x8769
    const val EXPOSURE_TIME = 0x829A
    const val F_NUMBER = 0x829D
    const val ISO = 0x8827
    const val EXIF_VERSION = 0x9000
    const val DATE_TIME_ORIGINAL = 0x9003
    const val OFFSET_TIME_ORIGINAL = 0x9011
    const val FOCAL_LENGTH = 0x920A
    const val USER_COMMENT = 0x9286
    const val COLOR_SPACE = 0xA001

    /** Returns [jpeg] with an EXIF APP1 segment holding [fields] after SOI (any existing EXIF segment is dropped). */
    fun insert(jpeg: ByteArray, fields: List<Field>): ByteArray {
        require(jpeg.size > 4 && (jpeg[0].toInt() and 0xFF) == 0xFF && (jpeg[1].toInt() and 0xFF) == 0xD8) { "not a JPEG" }
        val segment = app1(fields)
        val out = ByteArrayOutputStream(jpeg.size + segment.size)
        out.write(jpeg, 0, 2)
        out.write(segment)
        // Copy the rest, leaving out existing EXIF APP1 segments before the image data.
        var p = 2
        var removed = 0
        while (p + 4 <= jpeg.size && (jpeg[p].toInt() and 0xFF) == 0xFF) {
            val marker = jpeg[p + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val len = ((jpeg[p + 2].toInt() and 0xFF) shl 8) or (jpeg[p + 3].toInt() and 0xFF)
            val isExif = marker == 0xE1 && p + 10 <= jpeg.size && String(jpeg, p + 4, 4, Charsets.ISO_8859_1) == "Exif"
            if (isExif) removed += len + 2 else out.write(jpeg, p, len + 2)
            p += len + 2
        }
        out.write(jpeg, p, jpeg.size - p)
        val result = out.toByteArray()
        patchMpf(result, segment.size - removed)
        return result
    }

    private fun app1(fields: List<Field>): ByteArray {
        val main = fields.filter { it.ifd == Ifd.Main }.sortedBy { it.tag }
        val exif = fields.filter { it.ifd == Ifd.Exif }.sortedBy { it.tag }
        // TIFF, big-endian. Layout: header, IFD0 (+ pointer to Exif IFD), Exif IFD, then values.
        val mainCount = main.size + 1
        val ifd0Size = 2 + 12 * mainCount + 4
        val exifSize = 2 + 12 * exif.size + 4
        val exifOffset = 8 + ifd0Size
        var dataOffset = exifOffset + exifSize
        val data = ByteArrayOutputStream()
        val tiff = ByteBuffer.allocate(dataOffset).order(ByteOrder.BIG_ENDIAN)
        tiff.put(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42))
        tiff.putInt(8)

        fun entry(f: Field) {
            tiff.putShort(f.tag.toShort())
            val bytes: ByteArray
            when (f) {
                is Field.Ascii -> {
                    bytes = (f.value + "\u0000").toByteArray(Charsets.US_ASCII)
                    tiff.putShort(2)
                    tiff.putInt(bytes.size)
                }
                is Field.Short -> {
                    tiff.putShort(3)
                    tiff.putInt(1)
                    tiff.putShort(f.value.toShort())
                    tiff.putShort(0)
                    return
                }
                is Field.Rational -> {
                    bytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(f.numerator.toInt()).putInt(f.denominator.toInt()).array()
                    tiff.putShort(5)
                    tiff.putInt(1)
                }
                is Field.Undefined -> {
                    bytes = f.value
                    tiff.putShort(7)
                    tiff.putInt(bytes.size)
                }
            }
            if (bytes.size <= 4) {
                tiff.put(bytes.copyOf(4))
            } else {
                tiff.putInt(dataOffset + data.size())
                data.write(bytes)
                if (data.size() % 2 == 1) data.write(0) // keep values word-aligned
            }
        }

        tiff.putShort(mainCount.toShort())
        val withPointer = (main + Field.Short(EXIF_POINTER, Ifd.Main, 0)).sortedBy { it.tag }
        for (f in withPointer) {
            if (f.tag == EXIF_POINTER) {
                tiff.putShort(EXIF_POINTER.toShort())
                tiff.putShort(4)
                tiff.putInt(1)
                tiff.putInt(exifOffset)
            } else {
                entry(f)
            }
        }
        tiff.putInt(0)
        tiff.putShort(exif.size.toShort())
        exif.forEach(::entry)
        tiff.putInt(0)

        val body = ByteArrayOutputStream()
        body.write("Exif".toByteArray(Charsets.US_ASCII))
        body.write(byteArrayOf(0, 0))
        body.write(tiff.array())
        body.write(data.toByteArray())
        val payload = body.toByteArray()
        require(payload.size + 2 <= 0xFFFF) { "EXIF too large" }
        val seg = ByteArrayOutputStream()
        seg.write(0xFF)
        seg.write(0xE1)
        seg.write((payload.size + 2) shr 8)
        seg.write((payload.size + 2) and 0xFF)
        seg.write(payload)
        return seg.toByteArray()
    }

    /**
     * Adds [delta] to the size of the first (primary) image in an MPF index, if the JPEG has one.
     * Offsets of the other images are relative to the MPF header, which moved by the same amount
     * as they did, so they stay valid.
     */
    fun patchMpf(jpeg: ByteArray, delta: Int) {
        if (delta == 0) return
        var p = 2
        while (p + 4 <= jpeg.size && (jpeg[p].toInt() and 0xFF) == 0xFF) {
            val marker = jpeg[p + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return
            val len = ((jpeg[p + 2].toInt() and 0xFF) shl 8) or (jpeg[p + 3].toInt() and 0xFF)
            if (marker == 0xE2 && String(jpeg, p + 4, 4, Charsets.ISO_8859_1) == "MPF\u0000") {
                val tiffStart = p + 8
                val order = if (jpeg[tiffStart].toInt() == 'I'.code) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                val buf = ByteBuffer.wrap(jpeg).order(order)
                val ifd = tiffStart + buf.getInt(tiffStart + 4)
                val count = buf.getShort(ifd).toInt() and 0xFFFF
                for (k in 0 until count) {
                    val e = ifd + 2 + 12 * k
                    if ((buf.getShort(e).toInt() and 0xFFFF) == 0xB002) {
                        val entries = tiffStart + buf.getInt(e + 8)
                        // MP entry: attribute (4), size (4), offset (4), dependent images (2 + 2).
                        buf.putInt(entries + 4, buf.getInt(entries + 4) + delta)
                        return
                    }
                }
                return
            }
            p += len + 2
        }
    }

    /** Parses the EXIF APP1 of [jpeg] back into tag → raw value strings. For tests and diagnostics. */
    fun read(jpeg: ByteArray): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        var p = 2
        while (p + 4 <= jpeg.size && (jpeg[p].toInt() and 0xFF) == 0xFF) {
            val marker = jpeg[p + 1].toInt() and 0xFF
            if (marker == 0xDA) break
            val len = ((jpeg[p + 2].toInt() and 0xFF) shl 8) or (jpeg[p + 3].toInt() and 0xFF)
            if (marker == 0xE1 && String(jpeg, p + 4, 4, Charsets.ISO_8859_1) == "Exif") {
                val t = p + 10
                val buf = ByteBuffer.wrap(jpeg).order(if (jpeg[t].toInt() == 'I'.code) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                fun readIfd(at: Int) {
                    val count = buf.getShort(at).toInt() and 0xFFFF
                    for (k in 0 until count) {
                        val e = at + 2 + 12 * k
                        val tag = buf.getShort(e).toInt() and 0xFFFF
                        val type = buf.getShort(e + 2).toInt()
                        val n = buf.getInt(e + 4)
                        val valueAt = if ((type == 2 || type == 7) && n > 4 || type == 5) t + buf.getInt(e + 8) else e + 8
                        out[tag] = when (type) {
                            2 -> String(jpeg, valueAt, n - 1, Charsets.US_ASCII)
                            3 -> (buf.getShort(valueAt).toInt() and 0xFFFF).toString()
                            4 -> buf.getInt(valueAt).toString()
                            5 -> "${buf.getInt(valueAt)}/${buf.getInt(valueAt + 4)}"
                            else -> String(jpeg, valueAt, n, Charsets.ISO_8859_1)
                        }
                        if (tag == EXIF_POINTER) readIfd(t + buf.getInt(e + 8))
                    }
                }
                readIfd(t + buf.getInt(t + 4))
                return out
            }
            p += len + 2
        }
        return out
    }
}

package app.silverpoint.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

class JpegExifTest {
    companion object {
        /** A real 16x8 JPEG from the JDK's encoder. */
        fun tinyJpeg(colour: Int = 0x336699): ByteArray {
            val img = BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 8) for (x in 0 until 16) img.setRGB(x, y, colour + x * 3)
            val out = ByteArrayOutputStream()
            ImageIO.write(img, "jpg", out)
            return out.toByteArray()
        }

        /**
         * Primary + secondary JPEG with an MPF APP2 segment after SOI, the way Ultra HDR files are laid
         * out: entry 0 = the primary (offset 0, its size), entry 1 = the secondary (offset from the MPF header).
         */
        fun ultraHdrLike(): ByteArray {
            val primary = tinyJpeg()
            val secondary = tinyJpeg(0x808080)
            val mpfBody = ByteBuffer.allocate(4 + 8 + 2 + 3 * 12 + 4 + 32).order(ByteOrder.BIG_ENDIAN)
            mpfBody.put("MPF".toByteArray()).put(0)
            val tiffStart = mpfBody.position()
            mpfBody.put(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42)).putInt(8)
            mpfBody.putShort(3)
            mpfBody.putShort(0xB000.toShort()).putShort(7).putInt(4).put("0100".toByteArray())
            mpfBody.putShort(0xB001.toShort()).putShort(4).putInt(1).putInt(2)
            val entriesAt = 8 + 2 + 3 * 12 + 4
            mpfBody.putShort(0xB002.toShort()).putShort(7).putInt(32).putInt(entriesAt)
            mpfBody.putInt(0)
            val segLen = mpfBody.capacity() + 2
            val totalPrimary = primary.size + segLen + 2
            // Entry 0: primary, offset 0. Entry 1: secondary, offset from the MPF TIFF header.
            mpfBody.putInt(0x030000).putInt(totalPrimary).putInt(0).putShort(0).putShort(0)
            val mpfHeaderInFile = 2 + 4 + tiffStart
            mpfBody.putInt(0).putInt(secondary.size).putInt(totalPrimary - mpfHeaderInFile).putShort(0).putShort(0)
            val out = ByteArrayOutputStream()
            out.write(primary, 0, 2)
            out.write(0xFF)
            out.write(0xE2)
            out.write(segLen shr 8)
            out.write(segLen and 0xFF)
            out.write(mpfBody.array())
            out.write(primary, 2, primary.size - 2)
            out.write(secondary)
            return out.toByteArray()
        }

        /** (size, offset) of MP entries, offsets made absolute. */
        fun mpEntries(jpeg: ByteArray): List<Pair<Int, Int>> {
            var p = 2
            while (true) {
                val len = ((jpeg[p + 2].toInt() and 0xFF) shl 8) or (jpeg[p + 3].toInt() and 0xFF)
                if ((jpeg[p + 1].toInt() and 0xFF) == 0xE2 && String(jpeg, p + 4, 3) == "MPF") {
                    val t = p + 8
                    val b = ByteBuffer.wrap(jpeg).order(ByteOrder.BIG_ENDIAN)
                    val entries = t + 8 + 2 + 3 * 12 + 4
                    return (0..1).map { k ->
                        val size = b.getInt(entries + 16 * k + 4)
                        val off = b.getInt(entries + 16 * k + 8)
                        size to if (k == 0) 0 else t + off
                    }
                }
                p += len + 2
            }
        }
    }

    @Test
    fun exifSurvivesAndTheJpegStillDecodes() {
        val out = JpegExif.insert(tinyJpeg(), listOf(
            JpegExif.Field.Ascii(JpegExif.MAKE, JpegExif.Ifd.Main, "samsung"),
            JpegExif.Field.Short(JpegExif.ISO, JpegExif.Ifd.Exif, 400),
        ))
        assertEquals("samsung", JpegExif.read(out)[JpegExif.MAKE])
        assertEquals("400", JpegExif.read(out)[JpegExif.ISO])
        val img = ImageIO.read(out.inputStream())
        assertEquals(16, img.width)
        File(System.getProperty("java.io.tmpdir"), "silverpoint_exif_test.jpg").writeBytes(out)
    }

    @Test
    fun existingExifIsReplacedNotDuplicated() {
        val once = JpegExif.insert(tinyJpeg(), listOf(JpegExif.Field.Ascii(JpegExif.MAKE, JpegExif.Ifd.Main, "a")))
        val twice = JpegExif.insert(once, listOf(JpegExif.Field.Ascii(JpegExif.MAKE, JpegExif.Ifd.Main, "b")))
        assertEquals("b", JpegExif.read(twice)[JpegExif.MAKE])
        assertEquals(once.size, twice.size)
    }

    @Test
    fun ultraHdrIndexStillPointsAtTheGainMap() {
        val before = ultraHdrLike()
        val (_, secondaryAt) = mpEntries(before)[1]
        val gainMap = before.copyOfRange(secondaryAt, before.size)
        val after = JpegExif.insert(before, ExifFields.build(PhotoInfo("Vivid Slide", 0L, 100, 1_000_000, 1.7f, 6.3f, "m", "m", "s")))
        val entries = mpEntries(after)
        // The primary image grew by the EXIF segment; the secondary is where the index says.
        assertEquals(entries[1].second, entries[0].first)
        assertArrayEquals(gainMap, after.copyOfRange(entries[1].second, entries[1].second + entries[1].first))
        assertEquals(0xD8, after[entries[1].second + 1].toInt() and 0xFF)
        File(System.getProperty("java.io.tmpdir"), "silverpoint_uhdr_test.jpg").writeBytes(after)
    }
}

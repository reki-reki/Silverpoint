package app.silverpoint.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.TimeZone

class GallerySaverTest {
    private val utc = TimeZone.getTimeZone("UTC")
    // A half-hour offset, to check that minutes are written too.
    private val zone = TimeZone.getTimeZone("GMT+05:30")
    private val t = 1791517793000L // 2026-10-09T03:49:53Z

    @Test
    fun fileNameHasDateTimeAndSafeRecipe() {
        assertEquals("IMG_20261009_034953_Reportage.jpg", GallerySaver.fileName(t, "Reportage", utc))
        assertEquals("IMG_20261009_034953_Mono_Yellow_Filter.jpg", GallerySaver.fileName(t, "Mono Yellow Filter", utc))
        assertEquals("IMG_20261009_034953_Silverpoint.jpg", GallerySaver.fileName(t, "***", utc))
        assertEquals("IMG_20261009_034953_Reportage.dng", DngSaver.fileName(t, "Reportage", utc))
    }

    @Test
    fun fileNameUsesLocalTime() {
        assertEquals("IMG_20261009_091953_Reportage.jpg", GallerySaver.fileName(t, "Reportage", zone))
    }

    private fun fields(info: PhotoInfo): Map<Int, String> =
        JpegExif.read(JpegExif.insert(JpegExifTest.tinyJpeg(), ExifFields.build(info)))

    @Test
    fun exifFieldsCarryCaptureSettingsAndRecipe() {
        val v = fields(PhotoInfo("Reportage", t, 204, 9_991_324L, 1.7f, 6.3f, "samsung", "SM-S938B", "Silverpoint 0.1.0", zone, framesMerged = 6))
        assertEquals("2026:10:09 09:19:53", v[JpegExif.DATE_TIME_ORIGINAL])
        assertEquals("+05:30", v[JpegExif.OFFSET_TIME_ORIGINAL])
        assertEquals("204", v[JpegExif.ISO])
        assertEquals("1/100", v[JpegExif.EXPOSURE_TIME])
        assertEquals("17/10", v[JpegExif.F_NUMBER])
        assertEquals("630/100", v[JpegExif.FOCAL_LENGTH])
        assertEquals("1", v[JpegExif.ORIENTATION])
        assertEquals("samsung", v[JpegExif.MAKE])
        assertEquals("Silverpoint recipe: Reportage, 6 frames merged", v[JpegExif.IMAGE_DESCRIPTION])
        assertEquals("ASCII\u0000\u0000\u0000Recipe: Reportage, 6 frames merged", v[JpegExif.USER_COMMENT])
    }

    @Test
    fun exposureTimeIsWrittenExactly() {
        fun exposure(ns: Long) = fields(PhotoInfo("X", t, 100, ns, null, null, "m", "m", "s", utc))[JpegExif.EXPOSURE_TIME]
        assertEquals("1/7692", exposure(130_000L))
        assertEquals("1/3", exposure(333_333_333L))
        // 0.4 s is not one over a whole number: written in milliseconds, not rounded to 1/3 s.
        assertEquals("400/1000", exposure(400_000_000L))
        assertEquals("2000/1000", exposure(2_000_000_000L))
    }

    @Test
    fun missingCaptureValuesAreLeftOut() {
        val v = fields(PhotoInfo("X", t, null, null, null, null, "m", "m", "s", utc))
        assertFalse(v.containsKey(JpegExif.ISO))
        assertFalse(v.containsKey(JpegExif.FOCAL_LENGTH))
        assertEquals("+00:00", v[JpegExif.OFFSET_TIME_ORIGINAL])
    }
}

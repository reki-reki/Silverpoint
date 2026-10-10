package app.silverpoint.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What gets written into a saved photo's EXIF. Null fields are left out. */
data class PhotoInfo(
    val recipeName: String,
    val takenAtMillis: Long,
    val iso: Int?,
    val exposureTimeNs: Long?,
    val fNumber: Float?,
    val focalLengthMm: Float?,
    val make: String,
    val model: String,
    val software: String,
    val timeZone: TimeZone = TimeZone.getDefault(),
    /** RAW frames merged into the photo (1 = single shot). */
    val framesMerged: Int = 1,
)

/** Saves finished photos to `DCIM/Silverpoint` through MediaStore. No storage permission is needed. */
object GallerySaver {
    const val RELATIVE_PATH = "DCIM/Silverpoint"

    /** `IMG_20261009_084953_Reportage.jpg`: date, time, recipe with unsafe characters replaced. */
    fun fileName(takenAtMillis: Long, recipeName: String, timeZone: TimeZone = TimeZone.getDefault()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { this.timeZone = timeZone }.format(Date(takenAtMillis))
        val recipe = recipeName.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "Silverpoint" }
        return "IMG_${stamp}_$recipe.jpg"
    }

    /**
     * Writes [bitmap] as a JPEG with EXIF from [info]. If [bitmap] carries a gain map (Android 14+),
     * the file is an Ultra HDR JPEG. The file stays hidden (`IS_PENDING`) until it is complete; on
     * any failure the half-written entry is deleted and the exception rethrown.
     */
    fun saveJpeg(context: Context, bitmap: Bitmap, info: PhotoInfo, quality: Int = 95): Uri {
        val encoded = ByteArrayOutputStream(8 shl 20)
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, encoded)) { "JPEG encoding failed" }
        val bytes = JpegExif.insert(encoded.toByteArray(), ExifFields.build(info))
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(info.takenAtMillis, info.recipeName, info.timeZone))
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.DATE_TAKEN, info.takenAtMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused the new photo")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("could not open the photo for writing")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }
}

/** Writes RAW frames as DNG files next to the photos (developer option). */
object DngSaver {
    /** Same base name as the JPEG, with `.dng`. */
    fun fileName(takenAtMillis: Long, recipeName: String, timeZone: TimeZone = TimeZone.getDefault()): String =
        GallerySaver.fileName(takenAtMillis, recipeName, timeZone).removeSuffix(".jpg") + ".dng"

    /**
     * Saves [image] (RAW_SENSOR) with its metadata to `DCIM/Silverpoint`. [exifOrientation] is an
     * `ExifInterface.ORIENTATION_*` value so RAW editors show it upright.
     */
    fun save(
        context: Context,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        image: Image,
        info: PhotoInfo,
        exifOrientation: Int,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(info.takenAtMillis, info.recipeName, info.timeZone))
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, GallerySaver.RELATIVE_PATH)
            put(MediaStore.Images.Media.DATE_TAKEN, info.takenAtMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused the DNG")
        try {
            DngCreator(characteristics, result).use { dng ->
                dng.setOrientation(exifOrientation)
                dng.setDescription("Silverpoint ${info.software.removePrefix("Silverpoint ")}, recipe ${info.recipeName} (applied to the JPEG only)")
                resolver.openOutputStream(uri)?.use { dng.writeImage(it, image) } ?: throw IllegalStateException("could not open the DNG for writing")
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /** EXIF orientation for a clockwise rotation that makes the image upright. */
    fun exifOrientation(rotationDegrees: Int): Int = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
}

/** The EXIF fields Silverpoint writes for a [PhotoInfo]. */
object ExifFields {
    fun build(info: PhotoInfo): List<JpegExif.Field> {
        val date = Date(info.takenAtMillis)
        val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = info.timeZone }.format(date)
        // EXIF wants "+00:00" where Java writes "Z" for UTC.
        val offset = SimpleDateFormat("XXX", Locale.US).apply { timeZone = info.timeZone }.format(date).let { if (it == "Z") "+00:00" else it }
        val main = JpegExif.Ifd.Main
        val exif = JpegExif.Ifd.Exif
        val merged = if (info.framesMerged > 1) ", ${info.framesMerged} frames merged" else ""
        val out = mutableListOf<JpegExif.Field>(
            JpegExif.Field.Ascii(JpegExif.MAKE, main, info.make),
            JpegExif.Field.Ascii(JpegExif.MODEL, main, info.model),
            JpegExif.Field.Ascii(JpegExif.SOFTWARE, main, info.software),
            JpegExif.Field.Ascii(JpegExif.DATE_TIME, main, stamp),
            // Pixels are already rotated upright, so no viewer should rotate them again.
            JpegExif.Field.Short(JpegExif.ORIENTATION, main, 1),
            JpegExif.Field.Ascii(JpegExif.IMAGE_DESCRIPTION, main, "Silverpoint recipe: ${info.recipeName}$merged"),
            JpegExif.Field.Undefined(JpegExif.EXIF_VERSION, exif, "0232".toByteArray(Charsets.US_ASCII)),
            JpegExif.Field.Ascii(JpegExif.DATE_TIME_ORIGINAL, exif, stamp),
            JpegExif.Field.Ascii(JpegExif.OFFSET_TIME_ORIGINAL, exif, offset),
            JpegExif.Field.Short(JpegExif.COLOR_SPACE, exif, 1),
            JpegExif.Field.Undefined(
                JpegExif.USER_COMMENT, exif,
                byteArrayOf(0x41, 0x53, 0x43, 0x49, 0x49, 0, 0, 0) + "Recipe: ${info.recipeName}$merged".toByteArray(Charsets.US_ASCII),
            ),
        )
        info.iso?.let { out += JpegExif.Field.Short(JpegExif.ISO, exif, it.coerceAtMost(65535)) }
        info.exposureTimeNs?.takeIf { it > 0 }?.let { ns ->
            // As 1/x when the time is (within 1%) one over a whole number, which is how viewers like to
            // show it; otherwise in milliseconds, so 0.4 s is not written as 1/3 s.
            val x = Math.round(1e9 / ns)
            out += if (x >= 1 && Math.abs(1e9 / ns - x) < 0.01 * x) JpegExif.Field.Rational(JpegExif.EXPOSURE_TIME, exif, 1, x)
            else JpegExif.Field.Rational(JpegExif.EXPOSURE_TIME, exif, Math.round(ns / 1e6), 1000)
        }
        info.fNumber?.let { out += JpegExif.Field.Rational(JpegExif.F_NUMBER, exif, Math.round(it * 10.0), 10) }
        info.focalLengthMm?.let { out += JpegExif.Field.Rational(JpegExif.FOCAL_LENGTH, exif, Math.round(it * 100.0), 100) }
        return out
    }
}

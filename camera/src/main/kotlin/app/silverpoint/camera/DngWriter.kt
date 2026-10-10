package app.silverpoint.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.ExifInterface
import android.media.Image
import android.provider.MediaStore

/** Writes a RAW_SENSOR [Image] as a DNG into a Downloads folder. No storage permission is needed. */
internal object DngWriter {

    /** EXIF orientation for a clockwise rotation that makes the image upright. */
    fun exifOrientation(rotationDegrees: Int): Int = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /** Returns the file name. Throws [ProbeException] on failure, leaving no half-written file behind. */
    fun write(
        context: Context,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        image: Image,
        name: String,
        rotationDegrees: Int,
        folder: String,
        description: String,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw ProbeException("dng", "MediaStore insert failed")
        try {
            DngCreator(characteristics, result).use { dng ->
                dng.setOrientation(exifOrientation(rotationDegrees))
                // Shows up as the image description in Lightroom and EXIF tools, so the lens is known even if renamed.
                dng.setDescription(description)
                resolver.openOutputStream(uri)?.use { dng.writeImage(it, image) }
                    ?: throw ProbeException("dng", "no output stream")
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return name
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t as? ProbeException ?: ProbeException("dng", "${t.javaClass.simpleName}: ${t.message}")
        }
    }
}

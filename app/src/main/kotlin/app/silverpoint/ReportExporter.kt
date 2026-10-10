package app.silverpoint

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import app.silverpoint.camera.DiagnosticsRun

/**
 * Saves the Diagnostics report next to that run's DNGs (same folder, same run ID) and shares it.
 * No storage permission is needed.
 */
object ReportExporter {

    /** Returns the saved path (folder/name) on success, or null if the write failed. */
    fun saveJson(context: Context, json: String, run: DiagnosticsRun): String? {
        val name = run.reportName
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, run.folder)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: error("no output stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "${run.folder}/$name"
        } catch (_: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Silverpoint probe report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share probe report"))
    }
}

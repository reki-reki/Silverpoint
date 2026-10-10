package app.silverpoint.pipeline

import android.content.res.AssetManager

/**
 * Loads a shader from the app's assets and inlines its `#include "file"` lines (files in the same
 * folder), dropping the `GL_GOOGLE_include_directive` extension line that only glslang needs.
 * `scripts/check_shaders.py` does the same before validating in CI.
 */
object ShaderSource {
    private val include = Regex("""^\s*#include\s+"([^"]+)"\s*$""")
    private val extension = Regex("""^\s*#extension\s+GL_GOOGLE_include_directive\b.*$""")

    fun load(assets: AssetManager, path: String): String = expand(path) { p -> assets.open(p).use { it.readBytes().decodeToString() } }

    /** [read] returns a file's text by asset path. */
    fun expand(path: String, read: (String) -> String): String {
        val dir = path.substringBeforeLast('/', "")
        return read(path).lines().joinToString("\n") { line ->
            when {
                extension.matches(line) -> ""
                else -> include.matchEntire(line)?.let { m ->
                    expand(if (dir.isEmpty()) m.groupValues[1] else "$dir/${m.groupValues[1]}", read)
                } ?: line
            }
        }
    }
}

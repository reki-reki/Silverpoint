package app.silverpoint.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class ShaderSourceTest {
    @Test
    fun includesAreInlinedAndTheGlslangExtensionDropped() {
        val files = mapOf(
            "shaders/a.comp" to "#version 310 es\n#extension GL_GOOGLE_include_directive : require\n#include \"b.glsl\"\nvoid main() {}",
            "shaders/b.glsl" to "float b() { return 1.0; }",
        )
        val out = ShaderSource.expand("shaders/a.comp") { files.getValue(it) }
        assertEquals("#version 310 es\n\nfloat b() { return 1.0; }\nvoid main() {}", out)
    }
}

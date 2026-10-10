package app.silverpoint.pipeline

import app.silverpoint.recipes.RecipeParams
import org.junit.Assert.assertTrue
import org.junit.Test

class LookThumbnailsTest {
    private val frame = IntArray(64) { i -> (0xFF shl 24) or ((i * 4) shl 16) or (128 shl 8) or (255 - i * 4) }

    @Test
    fun neutralLookLeavesTheFrameAlone() {
        val out = LookThumbnails.apply(frame, LookThumbnails.lut(RecipeParams()))
        for (i in frame.indices) for (shift in intArrayOf(16, 8, 0)) {
            assertTrue(kotlin.math.abs((frame[i] shr shift and 0xFF) - (out[i] shr shift and 0xFF)) <= 1)
        }
    }

    @Test
    fun thumbnailsFollowThePreviewLook() {
        // Small table vs the viewfinder's full one: within a couple of levels on these colours.
        val p = RecipeParams(saturation = -60f, contrast = 30f, splitHighlightHue = 40f, splitHighlightSaturation = 40f)
        val small = LookThumbnails.apply(frame, LookThumbnails.lut(p))
        val full = LookThumbnails.apply(frame, PreviewRenderer.previewLook(p))
        var worst = 0
        for (i in frame.indices) for (shift in intArrayOf(16, 8, 0)) worst = maxOf(worst, kotlin.math.abs((small[i] shr shift and 0xFF) - (full[i] shr shift and 0xFF)))
        assertTrue("worst $worst", worst <= 3)
        assertTrue(small.all { it ushr 24 == 0xFF })
    }
}

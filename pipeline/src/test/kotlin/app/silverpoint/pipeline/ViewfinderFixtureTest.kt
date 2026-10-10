package app.silverpoint.pipeline

import app.silverpoint.recipes.RecipeParams
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Dev-only: makes the screenshot tests' viewfinder picture (app/src/test/resources/screens/sofa.png)
 * by developing a whole frame with no recipe and shrinking it to 384 x 512. Runs when
 * SILVERPOINT_FULL_FIXTURE points at a folder made by `scripts/make_fixture.py <dng> - <dir> --full`;
 * writes `viewfinder.png` there.
 */
class ViewfinderFixtureTest {
    private val dir = System.getProperty("silverpoint.fullFixture")?.let(::File)

    @Test
    fun writeViewfinderPicture() {
        assumeTrue(dir != null && File(dir, "full.json").exists())
        val d = dir!!
        val f = Fixture.load("full", d)
        val p = DevelopParams.build(f.meta, RecipeParams(), rotation = 90)
        val full = CpuDeveloper.develop(f.raw, f.meta.width, p)
        val w = 384
        val h = 512
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        // Box-filter down (average every source pixel that falls in the target pixel).
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = x * full.width / w
            val x1 = (x + 1) * full.width / w
            val y0 = y * full.height / h
            val y1 = (y + 1) * full.height / h
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0
            for (sy in y0 until y1) for (sx in x0 until x1) {
                val v = full.argb[sy * full.width + sx]
                r += v shr 16 and 0xFF
                g += v shr 8 and 0xFF
                b += v and 0xFF
                n++
            }
            out.setRGB(x, y, ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt())
        }
        ImageIO.write(out, "png", File(d, "viewfinder.png"))
    }
}

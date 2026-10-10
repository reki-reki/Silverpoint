package app.silverpoint.pipeline

import app.silverpoint.recipes.RecipeParams
import app.silverpoint.recipes.RecipeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Development tool: renders the full-frame fixture with every recipe as a contact sheet next to
 * Lightroom's no-edit export, for judging by eye. Runs only with SILVERPOINT_FULL_FIXTURE (see
 * [LightroomFitTest]). Writes `recipes_contact_sheet.png` into that directory.
 */
class PreviewRenderTest {
    private val dir = System.getProperty("silverpoint.fullFixture")?.let(::File)

    @Test
    fun renderPreview() {
        assumeTrue(dir != null && File(dir, "full.json").exists())
        val d = dir!!
        val f = Fixture.load("full", d)
        val meta = f.meta
        val align = Json.parseToJsonElement(File(d, "alignment.json").readText()) as JsonObject
        val scale = (align["scale"] as JsonPrimitive).double
        val dx = (align["dx"] as JsonPrimitive).int
        val dy = (align["dy"] as JsonPrimitive).int
        val ref = Fixture.image(File(d, "lightroom.jpg"))
        val recipes = RecipeStore.parse(File("../app/src/main/assets/recipes.json").readText())
        val lin = CpuDeveloper.linearSrgb(f.raw, meta.width, DevelopParams.build(meta, RecipeParams(), 0))

        // Contact sheet: Lightroom's export, Silverpoint with no recipe, then every recipe (colour and tone
        // only: no grain, vignette or local contrast, which need the full-resolution path).
        val block = 8
        val bw = meta.height / block
        val bh = meta.width / block
        val looks = listOf("Silverpoint neutral" to RecipeParams()) + recipes.map { it.name to it.params }
        val cols = 6
        val tiles = 1 + looks.size
        val rows = (tiles + cols - 1) / cols
        val label = 18
        val img = BufferedImage(bw * cols, (bh + label) * rows, BufferedImage.TYPE_INT_RGB)
        val out = FloatArray(3)
        val tmp = FloatArray(3)
        val params = looks.map { DevelopParams.build(meta, it.second, 0) }
        fun put(tile: Int, x: Int, y: Int, rgb: Int) = img.setRGB((tile % cols) * bw + x, (tile / cols) * (bh + label) + label + y, rgb)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var r = 0f
            var g = 0f
            var b = 0f
            for (yy in 0 until block) for (xx in 0 until block) {
                val i = ((meta.height - 1 - (bx * block + xx)) * meta.width + by * block + yy) * 3
                r += lin[i]
                g += lin[i + 1]
                b += lin[i + 2]
            }
            val n = (block * block).toFloat()
            val rx = ((bx * block + block / 2.0) * scale + dx).toInt().coerceIn(0, ref.width - 1)
            val ry = ((by * block + block / 2.0) * scale + dy).toInt().coerceIn(0, ref.height - 1)
            put(0, bx, by, ref[rx, ry] and 0xFFFFFF)
            params.forEachIndexed { k, p ->
                CpuDeveloper.finish(r / n, g / n, b / n, p, out, tmp)
                put(k + 1, bx, by, (to8(out[0]) shl 16) or (to8(out[1]) shl 8) or to8(out[2]))
            }
        }
        val gfx = img.createGraphics()
        gfx.color = java.awt.Color.WHITE
        gfx.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13)
        (listOf("Lightroom (no edits)") + looks.map { it.first }).forEachIndexed { t, name ->
            gfx.drawString(name, (t % cols) * bw + 4, (t / cols) * (bh + label) + 14)
        }
        gfx.dispose()
        val file = File(d, "recipes_contact_sheet.png")
        ImageIO.write(img, "png", file)
        println("contact sheet: $file")
    }

    private fun to8(v: Float) = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
}

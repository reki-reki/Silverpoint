package app.silverpoint.pipeline

import app.silverpoint.recipes.RecipeParams
import app.silverpoint.recipes.RecipeStore
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dev-only: writes the Diagnostics self-test cases (inputs and the CPU result) for
 * `scripts/gpu_parity.py`, which runs the real shaders on a desktop OpenGL (Mesa's llvmpipe works)
 * and compares, so shader changes can be checked without a phone. Runs only when
 * `SILVERPOINT_GPU_PARITY` names an output folder.
 */
class GpuParityDumpTest {
    private val dir = System.getProperty("silverpoint.gpuParity")?.let(::File)

    @Test
    fun dumpCases() {
        assumeTrue("set SILVERPOINT_GPU_PARITY to run", dir != null)
        dir!!.mkdirs()
        val recipe = RecipeStore.parse(File("../app/src/main/assets/recipes.json").readText()).first().params
        val cases = listOf(
            Triple("gbrg_recipe", Cfa.GBRG to SyntheticScene.radialShading(), 90 to recipe),
            Triple("rggb_everything", Cfa.RGGB to null, 270 to GpuSelfTest.EVERYTHING),
            Triple("grbg_neutral", Cfa.GRBG to SyntheticScene.radialShading(), 0 to RecipeParams()),
            Triple("bggr_bw", Cfa.BGGR to SyntheticScene.radialShading(), 180 to GpuSelfTest.BLACK_AND_WHITE),
        )
        for ((name, cfaShading, rotLook) in cases) {
            val meta = SyntheticScene.meta(doubleArrayOf(0.49, 1.0, 0.75), width = 320, height = 240, cfa = cfaShading.first, shading = cfaShading.second)
            write(File(dir, name), SyntheticScene.busyFrame(meta), DevelopParams.build(meta, rotLook.second, rotLook.first, grainSeed = 1234))
        }
        // A real photo (the committed cushion crop) with every recipe control on.
        val f = Fixture.load("cushion")
        write(File(dir, "photo_cushion"), f.raw, DevelopParams.build(f.meta, GpuSelfTest.EVERYTHING, 90, grainSeed = 99))
        // The same photo with the newer Lightroom settings: edge-masked sharpening, vignette midpoint and
        // feather, channel curves, colour grading and calibration (made-up values, chosen to be visible).
        val newer = RecipeParams(
            sharpness = 55f, sharpenEdgeMasking = 35f,
            vignette = -25f, vignetteMidpoint = 30f, vignetteFeather = 70f,
            curveRed = listOf(listOf(0, 0), listOf(128, 134), listOf(255, 255)),
            curveBlue = listOf(listOf(0, 6), listOf(128, 124), listOf(255, 248)),
            gradeMidtoneHue = 35f, gradeMidtoneSat = 20f, gradeGlobalHue = 210f, gradeGlobalSat = 8f, gradeBlending = 70f,
            calRedHue = 12f, calRedSaturation = 10f, calGreenHue = -15f, calBlueSaturation = -12f,
        )
        write(File(dir, "photo_newer_settings"), f.raw, DevelopParams.build(f.meta, newer, 270, grainSeed = 5))
    }

    private fun write(d: File, raw: ShortArray, p: DevelopParams) {
        d.mkdirs()
        val cpu = CpuDeveloper.develop(raw, p.width, p)
        val lw = LocalContrast.lowSize(p.width)
        val lh = LocalContrast.lowSize(p.height)
        val shading = p.lensShading
        val json = buildString {
            append("{\n")
            fun f(a: FloatArray) = a.joinToString(", ", "[", "]") { it.toString() }
            append("\"width\": ${p.width}, \"height\": ${p.height}, \"pattern\": ${p.cfa.pattern.joinToString(", ", "[", "]")},\n")
            append("\"black\": ${f(p.blackLevel)}, \"white\": ${p.whiteLevel}, \"wb\": ${f(p.wbGains)}, \"matrix\": ${f(p.cameraToSrgb)},\n")
            append("\"exposure\": ${p.exposureGain}, \"saturation\": ${p.saturation}, \"vibrance\": ${p.vibrance}, \"knee\": ${p.knee},\n")
            append("\"shadingSize\": [${shading?.columns ?: 2}, ${shading?.rows ?: 2}], \"hasShading\": ${if (shading != null) 1 else 0},\n")
            append("\"lookSize\": ${p.look.size}, \"rotation\": ${p.rotation}, \"lowSize\": [$lw, $lh],\n")
            append("\"clarity\": ${p.clarity}, \"texture\": ${p.texture}, \"vignette\": ${p.vignette},\n")
            append("\"grainAmount\": ${p.grainAmount}, \"grainCell\": ${p.grainCell}, \"grainRoughness\": ${p.grainRoughness}, \"grainSeed\": ${p.grainSeed},\n")
            append("\"sharpen\": ${p.sharpen}, \"chromaSmoothing\": ${p.chromaSmoothing}, \"sharpenMask\": ${p.sharpenMask},\n")
            append("\"vignetteStart\": ${p.vignetteStart}, \"vignetteEnd\": ${p.vignetteEnd},\n")
            append("\"weightsS\": ${f(LocalContrast.weights(LocalContrast.SMALL_SIGMA))},\n")
            append("\"weightsL\": ${f(LocalContrast.weights(LocalContrast.largeSigma(lw, lh)))},\n")
            append("\"outSize\": [${cpu.width}, ${cpu.height}]\n}\n")
        }
        File(d, "params.json").writeText(json)
        fun bytes(n: Int, fill: ByteBuffer.() -> Unit): ByteArray = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()
        File(d, "raw.u16").writeBytes(bytes(raw.size * 2) { raw.forEach { putShort(it) } })
        File(d, "shading.f32").writeBytes((shading?.gains ?: FloatArray(16) { 1f }).let { g -> bytes(g.size * 4) { g.forEach { putFloat(it) } } })
        File(d, "look.f32").writeBytes(bytes(p.look.data.size * 4) { p.look.data.forEach { putFloat(it) } })
        File(d, "cpu.argb").writeBytes(bytes(cpu.argb.size * 4) { cpu.argb.forEach { putInt(it) } })
        cpu.lowRes?.let { low -> File(d, "cpu_low.f32").writeBytes(bytes(low.linear.size * 4) { low.linear.forEach { putFloat(it) } }) }
    }
}

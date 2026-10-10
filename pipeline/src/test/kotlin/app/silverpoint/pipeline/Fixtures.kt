package app.silverpoint.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO

/** A RAW frame from `scripts/make_fixture.py`: samples plus the metadata `DngCreator` wrote. */
class Fixture(val meta: RawMetadata, val raw: ShortArray) {
    companion object {
        private val RESOURCES = File("src/test/resources/fixture")

        fun load(name: String, dir: File = RESOURCES): Fixture {
            val json = Json.parseToJsonElement(File(dir, "$name.json").readText()) as JsonObject
            val w = json.int("width")
            val h = json.int("height")
            val bytes = GZIPInputStream(File(dir, "$name.raw.gz").inputStream()).use { it.readBytes() }
            require(bytes.size == w * h * 2) { "$name: expected ${w * h * 2} bytes, got ${bytes.size}" }
            val raw = ShortArray(w * h)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(raw)
            fun mat(key: String) = json[key]?.takeIf { it !is JsonNull }?.let { e ->
                Mat3((e as JsonArray).map { (it as JsonPrimitive).double }.toDoubleArray())
            }
            val shading = json["shadingGains"]?.let { g ->
                LensShading(json.int("shadingColumns"), json.int("shadingRows"),
                    (g as JsonArray).map { (it as JsonPrimitive).double.toFloat() }.toFloatArray())
            }
            val meta = RawMetadata(
                width = w,
                height = h,
                cfa = Cfa.valueOf((json["cfa"] as JsonPrimitive).content),
                blackLevel = json.doubles("blackLevel").map { it.toFloat() }.toFloatArray(),
                whiteLevel = (json["whiteLevel"] as JsonPrimitive).double.toFloat(),
                asShotNeutral = json.doubles("asShotNeutral").let { n -> DoubleArray(3) { n[it] / n[1] } },
                calibration1 = Calibration(json.int("illuminant1"), mat("colorMatrix1")!!, mat("forwardMatrix1"), mat("calibration1")),
                calibration2 = mat("colorMatrix2")?.let {
                    Calibration(json.int("illuminant2"), it, mat("forwardMatrix2"), mat("calibration2"))
                },
                lensShading = shading,
            )
            return Fixture(meta, raw)
        }

        /** sRGB image as packed RGB ints, row-major. */
        fun image(file: File): Picture {
            val img = ImageIO.read(file)
            val px = IntArray(img.width * img.height)
            img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
            return Picture(img.width, img.height, px)
        }

        fun lightroom(name: String, dir: File = RESOURCES) = image(File(dir, "$name.lightroom.png"))

        private fun JsonObject.int(key: String) = (this[key] as JsonPrimitive).int
        private fun JsonObject.doubles(key: String) = (this[key] as JsonArray).map { (it as JsonPrimitive).double }
    }
}

/** An 8-bit sRGB picture as packed RGB ints, row-major. */
class Picture(val width: Int, val height: Int, val px: IntArray) {
    operator fun get(x: Int, y: Int) = px[y * width + x]
}

/** CIE Lab and ΔE2000 for comparing renders. sRGB in, D65 white. */
object ColorScience {
    fun srgbToLab(rgb: Int): DoubleArray {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        val r = lin(rgb shr 16 and 0xFF)
        val g = lin(rgb shr 8 and 0xFF)
        val b = lin(rgb and 0xFF)
        return linearToLab(r, g, b)
    }

    fun linearToLab(r: Double, g: Double, b: Double): DoubleArray {
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        fun f(t: Double) = if (t > 216.0 / 24389) Math.cbrt(t) else (24389.0 / 27 * t + 16) / 116
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** CIEDE2000 colour difference (Sharma, Wu, Dalal 2005). */
    fun deltaE2000(a: DoubleArray, b: DoubleArray): Double {
        val (l1, a1, b1) = Triple(a[0], a[1], b1Of(a))
        val (l2, a2, b2) = Triple(b[0], b[1], b1Of(b))
        val c1 = Math.hypot(a1, b1)
        val c2 = Math.hypot(a2, b2)
        val cBar = (c1 + c2) / 2
        val g = 0.5 * (1 - Math.sqrt(Math.pow(cBar, 7.0) / (Math.pow(cBar, 7.0) + Math.pow(25.0, 7.0))))
        val a1p = (1 + g) * a1
        val a2p = (1 + g) * a2
        val c1p = Math.hypot(a1p, b1)
        val c2p = Math.hypot(a2p, b2)
        fun hue(ap: Double, bp: Double): Double {
            if (ap == 0.0 && bp == 0.0) return 0.0
            val h = Math.toDegrees(Math.atan2(bp, ap))
            return if (h < 0) h + 360 else h
        }
        val h1 = hue(a1p, b1)
        val h2 = hue(a2p, b2)
        val dL = l2 - l1
        val dC = c2p - c1p
        val dhRaw = when {
            c1p * c2p == 0.0 -> 0.0
            Math.abs(h2 - h1) <= 180 -> h2 - h1
            h2 - h1 > 180 -> h2 - h1 - 360
            else -> h2 - h1 + 360
        }
        val dH = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dhRaw / 2))
        val lBar = (l1 + l2) / 2
        val cBarP = (c1p + c2p) / 2
        val hBar = when {
            c1p * c2p == 0.0 -> h1 + h2
            Math.abs(h1 - h2) <= 180 -> (h1 + h2) / 2
            h1 + h2 < 360 -> (h1 + h2 + 360) / 2
            else -> (h1 + h2 - 360) / 2
        }
        val t = 1 - 0.17 * Math.cos(Math.toRadians(hBar - 30)) + 0.24 * Math.cos(Math.toRadians(2 * hBar)) +
            0.32 * Math.cos(Math.toRadians(3 * hBar + 6)) - 0.20 * Math.cos(Math.toRadians(4 * hBar - 63))
        val dTheta = 30 * Math.exp(-Math.pow((hBar - 275) / 25, 2.0))
        val rc = 2 * Math.sqrt(Math.pow(cBarP, 7.0) / (Math.pow(cBarP, 7.0) + Math.pow(25.0, 7.0)))
        val sl = 1 + 0.015 * Math.pow(lBar - 50, 2.0) / Math.sqrt(20 + Math.pow(lBar - 50, 2.0))
        val sc = 1 + 0.045 * cBarP
        val sh = 1 + 0.015 * cBarP * t
        val rt = -Math.sin(Math.toRadians(2 * dTheta)) * rc
        return Math.sqrt(
            Math.pow(dL / sl, 2.0) + Math.pow(dC / sc, 2.0) + Math.pow(dH / sh, 2.0) + rt * (dC / sc) * (dH / sh),
        )
    }

    private fun b1Of(lab: DoubleArray) = lab[2]

    /** Relative luminance (linear) of a packed sRGB pixel. */
    fun luminance(rgb: Int): Double {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(rgb shr 16 and 0xFF) + 0.7152 * lin(rgb shr 8 and 0xFF) + 0.0722 * lin(rgb and 0xFF)
    }
}

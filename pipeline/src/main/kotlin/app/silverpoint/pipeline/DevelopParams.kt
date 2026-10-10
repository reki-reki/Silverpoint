package app.silverpoint.pipeline

import app.silverpoint.recipes.RecipeParams
import kotlin.math.pow

/**
 * Per-shot numbers the developer needs, computed once on the CPU and shared by the CPU reference
 * and the GPU shaders so both do exactly the same maths.
 */
class DevelopParams(
    val width: Int,
    val height: Int,
    val cfa: Cfa,
    val blackLevel: FloatArray,
    val whiteLevel: Float,
    /** RAW white-balance multipliers R, G, B. */
    val wbGains: FloatArray,
    /** White-balanced camera RGB to linear sRGB, row-major. */
    val cameraToSrgb: FloatArray,
    /** Linear gain from exposure (2^EV). */
    val exposureGain: Float,
    /** OKLab chroma multiplier from Saturation (1 = unchanged). */
    val saturation: Float,
    /** Vibrance amount -1..1: extra chroma for muted colours. */
    val vibrance: Float,
    /** The recipe's tone and colour on sRGB-encoded values (see [RecipeLook]), base contrast included. */
    val look: LookLut,
    /** Linear level where the highlight shoulder starts (see [ToneCurve.shoulder]). */
    val knee: Float,
    val lensShading: LensShading?,
    /** Clockwise rotation (0, 90, 180, 270) that makes the output upright. */
    val rotation: Int,
    /** Clarity and Texture, -1..1 (see [LocalContrast]). */
    val clarity: Float = 0f,
    val texture: Float = 0f,
    /** Post-crop vignette, -1..1: negative darkens the corners. */
    val vignette: Float = 0f,
    /** Normalised radius where the vignette starts and where it reaches full strength (see [vignetteShape]). */
    val vignetteStart: Float = 0.3f,
    val vignetteEnd: Float = 1f,
    /** Grain amplitude 0..1 ([Grain.amplitude]), lattice cell size in output pixels, roughness 0..1 and noise seed (see [Grain]). */
    val grainAmount: Float = 0f,
    val grainCell: Float = 1f,
    val grainRoughness: Float = 0f,
    val grainSeed: Int = 0,
    /** Capture sharpening and colour smoothing at demosaic, 0 = off (see [CpuDeveloper.refine]). */
    val sharpen: Float = 0f,
    val chromaSmoothing: Float = 0f,
    /** Edge masking 0..1: how much flat areas are spared from sharpening (Lightroom's Masking / 100). */
    val sharpenMask: Float = 0f,
) {
    /** True when the local-contrast passes change the picture. */
    val hasDetail get() = clarity != 0f || texture != 0f

    val outWidth get() = if (rotation % 180 == 0) width else height
    val outHeight get() = if (rotation % 180 == 0) height else width

    companion object {
        /** Chroma above this (OKLab) counts as fully saturated for Vibrance. */
        const val VIBRANCE_CHROMA = 0.25f

        /**
         * Lightroom sharpens and removes colour noise on every RAW by default (Sharpening 40 at
         * radius 1.0, Color Noise Reduction 25). [SHARPEN] is Sharpening 40, measured against
         * Lightroom exports (October 2026 photo pairs); a recipe's own Sharpness scales it.
         */
        const val SHARPEN = 0.8f

        /**
         * Vignette falloff (start, end radius) for Lightroom's Midpoint and Feather (0..100, default
         * 50 each). At the defaults it is Silverpoint's original 0.3..1. A lower midpoint moves the
         * falloff inwards; more feather widens it. My approximation of Lightroom's shape.
         */
        fun vignetteShape(midpoint: Float, feather: Float): Pair<Float, Float> {
            val centre = 0.65f + 0.7f * (midpoint.coerceIn(0f, 100f) - 50f) / 100f
            val width = 0.7f * (0.5f + feather.coerceIn(0f, 100f) / 100f)
            val start = (centre - width / 2f).coerceIn(0f, 0.95f)
            return start to maxOf(centre + width / 2f, start + 0.05f)
        }
        const val CHROMA_SMOOTHING = 1f

        /**
         * @param look the recipe's parameters.
         * @param extraEv post-capture exposure offset in EV.
         * @param base the neutral rendering under the recipe.
         * @param lut the baked look for this recipe and [base], when the caller has it cached; baked here otherwise.
         * @param grainSeed varies the grain pattern between photos.
         */
        fun build(
            meta: RawMetadata,
            look: RecipeParams,
            rotation: Int,
            extraEv: Float = 0f,
            base: BaseLook = BaseLook.DEFAULT,
            lut: LookLut? = null,
            grainSeed: Int = 0,
            tuning: LookTuning = LookTuning.DEFAULT,
        ): DevelopParams {
            require(rotation in setOf(0, 90, 180, 270)) { "rotation must be a multiple of 90" }
            val gains = ColorMath.whiteBalanceGains(meta)
            val outLong = maxOf(meta.width, meta.height)
            return DevelopParams(
                width = meta.width,
                height = meta.height,
                cfa = meta.cfa,
                blackLevel = meta.blackLevel.copyOf(),
                whiteLevel = meta.whiteLevel,
                wbGains = FloatArray(3) { gains[it].toFloat() },
                cameraToSrgb = ColorMath.cameraToSrgb(meta).toFloatArray(),
                exposureGain = 2.0.pow((look.exposure + extraEv + base.baselineEv).toDouble()).toFloat(),
                saturation = (base.chroma * (1f + tuning.saturation * look.saturation / 100f)).coerceAtLeast(0f),
                vibrance = (tuning.vibrance * look.vibrance / 100f).coerceIn(-1f, 1f),
                look = lut ?: bakeLook(look, base, tuning),
                knee = base.knee,
                lensShading = meta.lensShading,
                rotation = rotation,
                clarity = (look.clarity / 100f).coerceIn(-1f, 1f),
                texture = (look.texture / 100f).coerceIn(-1f, 1f),
                vignette = (look.vignette / 100f).coerceIn(-1f, 1f),
                vignetteStart = vignetteShape(look.vignetteMidpoint, look.vignetteFeather).first,
                vignetteEnd = vignetteShape(look.vignetteMidpoint, look.vignetteFeather).second,
                grainAmount = Grain.amplitude(look.grainAmount),
                grainCell = Grain.cellPixels(look.grainSize, outLong),
                grainRoughness = (look.grainFrequency / 100f).coerceIn(0f, 1f),
                grainSeed = grainSeed,
                sharpen = SHARPEN * (look.sharpness / 40f).coerceIn(0f, 150f / 40f),
                chromaSmoothing = CHROMA_SMOOTHING,
                sharpenMask = (look.sharpenEdgeMasking / 100f).coerceIn(0f, 1f),
            )
        }

        /** The final-render look table for a recipe on top of [base]. */
        fun bakeLook(look: RecipeParams, base: BaseLook = BaseLook.DEFAULT, tuning: LookTuning = LookTuning.DEFAULT): LookLut =
            RecipeLook(look, base.contrast, tuning = tuning).bake()
    }
}

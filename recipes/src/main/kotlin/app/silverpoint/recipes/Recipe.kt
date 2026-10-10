package app.silverpoint.recipes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One film-look recipe from `recipes.json`. Parameter names follow Lightroom's Camera Raw settings. */
data class Recipe(
    val name: String,
    val file: String,
    val set: String,
    val group: String,
    val baseSim: String,
    val reference: String,
    val look: String,
    val bestFor: String,
    val params: RecipeParams,
)

/** The colour bands Lightroom uses for its HSL and grey-mixer panels, in hue order. */
enum class HueBand { Red, Orange, Yellow, Green, Aqua, Blue, Purple, Magenta }

/** A band's hue shift, saturation and luminance adjustment, each -100..100. */
data class HslAdjustment(val hue: Float, val saturation: Float, val luminance: Float)

/**
 * Recipe parameters. Every key is optional in the file and defaults to Lightroom's neutral value.
 * Ranges: exposure in EV; most sliders -100..100; hues 0..360; grain 0..100.
 */
@Serializable
data class RecipeParams(
    @SerialName("Exposure2012") val exposure: Float = 0f,
    @SerialName("Contrast2012") val contrast: Float = 0f,
    @SerialName("Highlights2012") val highlights: Float = 0f,
    @SerialName("Shadows2012") val shadows: Float = 0f,
    @SerialName("Whites2012") val whites: Float = 0f,
    @SerialName("Blacks2012") val blacks: Float = 0f,
    @SerialName("Texture") val texture: Float = 0f,
    @SerialName("Clarity2012") val clarity: Float = 0f,
    @SerialName("Dehaze") val dehaze: Float = 0f,
    @SerialName("Vibrance") val vibrance: Float = 0f,
    @SerialName("Saturation") val saturation: Float = 0f,
    @SerialName("SplitToningShadowHue") val splitShadowHue: Float = 0f,
    @SerialName("SplitToningShadowSaturation") val splitShadowSaturation: Float = 0f,
    @SerialName("SplitToningHighlightHue") val splitHighlightHue: Float = 0f,
    @SerialName("SplitToningHighlightSaturation") val splitHighlightSaturation: Float = 0f,
    @SerialName("SplitToningBalance") val splitBalance: Float = 0f,
    @SerialName("GrainAmount") val grainAmount: Float = 0f,
    @SerialName("GrainSize") val grainSize: Float = 25f,
    @SerialName("GrainFrequency") val grainFrequency: Float = 50f,
    @SerialName("PostCropVignetteAmount") val vignette: Float = 0f,
    /** Where the vignette's falloff sits (0 middle .. 100 corners) and how gradual it is, 0..100. */
    @SerialName("PostCropVignetteMidpoint") val vignetteMidpoint: Float = 50f,
    @SerialName("PostCropVignetteFeather") val vignetteFeather: Float = 50f,
    /** Parsed for completeness, not applied: Silverpoint's vignette is always round, highlight priority. */
    @SerialName("PostCropVignetteRoundness") val vignetteRoundness: Float = 0f,
    @SerialName("PostCropVignetteStyle") val vignetteStyle: Int = 1,
    @SerialName("PostCropVignetteHighlightContrast") val vignetteHighlightContrast: Float = 0f,
    @SerialName("ConvertToGrayscale") val grayscale: Boolean = false,
    /** Tone curve points (input, output), both 0..255, ascending input. Identity if empty. */
    @SerialName("curve") val curve: List<List<Int>> = IDENTITY_CURVE,
    /** Per-channel point curves (ToneCurvePV2012Red/Green/Blue), applied after [curve]. */
    @SerialName("curve_red") val curveRed: List<List<Int>> = IDENTITY_CURVE,
    @SerialName("curve_green") val curveGreen: List<List<Int>> = IDENTITY_CURVE,
    @SerialName("curve_blue") val curveBlue: List<List<Int>> = IDENTITY_CURVE,
    @SerialName("HueAdjustmentRed") val hueRed: Float = 0f,
    @SerialName("HueAdjustmentOrange") val hueOrange: Float = 0f,
    @SerialName("HueAdjustmentYellow") val hueYellow: Float = 0f,
    @SerialName("HueAdjustmentGreen") val hueGreen: Float = 0f,
    @SerialName("HueAdjustmentAqua") val hueAqua: Float = 0f,
    @SerialName("HueAdjustmentBlue") val hueBlue: Float = 0f,
    @SerialName("HueAdjustmentPurple") val huePurple: Float = 0f,
    @SerialName("HueAdjustmentMagenta") val hueMagenta: Float = 0f,
    @SerialName("SaturationAdjustmentRed") val satRed: Float = 0f,
    @SerialName("SaturationAdjustmentOrange") val satOrange: Float = 0f,
    @SerialName("SaturationAdjustmentYellow") val satYellow: Float = 0f,
    @SerialName("SaturationAdjustmentGreen") val satGreen: Float = 0f,
    @SerialName("SaturationAdjustmentAqua") val satAqua: Float = 0f,
    @SerialName("SaturationAdjustmentBlue") val satBlue: Float = 0f,
    @SerialName("SaturationAdjustmentPurple") val satPurple: Float = 0f,
    @SerialName("SaturationAdjustmentMagenta") val satMagenta: Float = 0f,
    @SerialName("LuminanceAdjustmentRed") val lumRed: Float = 0f,
    @SerialName("LuminanceAdjustmentOrange") val lumOrange: Float = 0f,
    @SerialName("LuminanceAdjustmentYellow") val lumYellow: Float = 0f,
    @SerialName("LuminanceAdjustmentGreen") val lumGreen: Float = 0f,
    @SerialName("LuminanceAdjustmentAqua") val lumAqua: Float = 0f,
    @SerialName("LuminanceAdjustmentBlue") val lumBlue: Float = 0f,
    @SerialName("LuminanceAdjustmentPurple") val lumPurple: Float = 0f,
    @SerialName("LuminanceAdjustmentMagenta") val lumMagenta: Float = 0f,
    @SerialName("GrayMixerRed") val grayRed: Float = 0f,
    @SerialName("GrayMixerOrange") val grayOrange: Float = 0f,
    @SerialName("GrayMixerYellow") val grayYellow: Float = 0f,
    @SerialName("GrayMixerGreen") val grayGreen: Float = 0f,
    @SerialName("GrayMixerAqua") val grayAqua: Float = 0f,
    @SerialName("GrayMixerBlue") val grayBlue: Float = 0f,
    @SerialName("GrayMixerPurple") val grayPurple: Float = 0f,
    @SerialName("GrayMixerMagenta") val grayMagenta: Float = 0f,
    /** Color Grading (Lightroom 10+). Shadow and highlight wheels are the split-toning keys above. */
    @SerialName("ColorGradeMidtoneHue") val gradeMidtoneHue: Float = 0f,
    @SerialName("ColorGradeMidtoneSat") val gradeMidtoneSat: Float = 0f,
    @SerialName("ColorGradeGlobalHue") val gradeGlobalHue: Float = 0f,
    @SerialName("ColorGradeGlobalSat") val gradeGlobalSat: Float = 0f,
    /** How far the shadow, midtone and highlight wheels overlap, 0..100 (Lightroom's default 50). */
    @SerialName("ColorGradeBlending") val gradeBlending: Float = 50f,
    /** Parsed for completeness, not applied yet (no preset sets them away from 0). */
    @SerialName("ColorGradeMidtoneLum") val gradeMidtoneLum: Float = 0f,
    @SerialName("ColorGradeShadowLum") val gradeShadowLum: Float = 0f,
    @SerialName("ColorGradeHighlightLum") val gradeHighlightLum: Float = 0f,
    @SerialName("ColorGradeGlobalLum") val gradeGlobalLum: Float = 0f,
    /** Calibration panel: hue and saturation of the red, green and blue primaries, -100..100. */
    @SerialName("RedHue") val calRedHue: Float = 0f,
    @SerialName("RedSaturation") val calRedSaturation: Float = 0f,
    @SerialName("GreenHue") val calGreenHue: Float = 0f,
    @SerialName("GreenSaturation") val calGreenSaturation: Float = 0f,
    @SerialName("BlueHue") val calBlueHue: Float = 0f,
    @SerialName("BlueSaturation") val calBlueSaturation: Float = 0f,
    /** Calibration shadow tint; parsed, not applied yet (no preset sets it away from 0). */
    @SerialName("ShadowTint") val calShadowTint: Float = 0f,
    /** Detail panel sharpening. Without these keys Lightroom's RAW defaults apply (40, 1.0, 25, 0). */
    @SerialName("Sharpness") val sharpness: Float = 40f,
    @SerialName("SharpenRadius") val sharpenRadius: Float = 1f,
    @SerialName("SharpenDetail") val sharpenDetail: Float = 25f,
    @SerialName("SharpenEdgeMasking") val sharpenEdgeMasking: Float = 0f,
    /** Silverpoint's own correction towards Lightroom's render of this preset; not a Lightroom setting. */
    @SerialName("match") val match: RecipeMatch? = null,
) {
    /** Tone curve as (input, output) pairs in 0..1. */
    val curvePoints: List<Pair<Float, Float>>
        get() = points(curve)

    /** Red, green and blue channel curves as (input, output) pairs in 0..1. */
    val channelCurvePoints: List<List<Pair<Float, Float>>>
        get() = listOf(points(curveRed), points(curveGreen), points(curveBlue))

    val hasChannelCurves: Boolean
        get() = listOf(curveRed, curveGreen, curveBlue).any { it != IDENTITY_CURVE }

    private fun points(c: List<List<Int>>) = c.map { it[0] / 255f to it[1] / 255f }

    fun hsl(band: HueBand): HslAdjustment = when (band) {
        HueBand.Red -> HslAdjustment(hueRed, satRed, lumRed)
        HueBand.Orange -> HslAdjustment(hueOrange, satOrange, lumOrange)
        HueBand.Yellow -> HslAdjustment(hueYellow, satYellow, lumYellow)
        HueBand.Green -> HslAdjustment(hueGreen, satGreen, lumGreen)
        HueBand.Aqua -> HslAdjustment(hueAqua, satAqua, lumAqua)
        HueBand.Blue -> HslAdjustment(hueBlue, satBlue, lumBlue)
        HueBand.Purple -> HslAdjustment(huePurple, satPurple, lumPurple)
        HueBand.Magenta -> HslAdjustment(hueMagenta, satMagenta, lumMagenta)
    }

    fun grayMix(band: HueBand): Float = when (band) {
        HueBand.Red -> grayRed
        HueBand.Orange -> grayOrange
        HueBand.Yellow -> grayYellow
        HueBand.Green -> grayGreen
        HueBand.Aqua -> grayAqua
        HueBand.Blue -> grayBlue
        HueBand.Purple -> grayPurple
        HueBand.Magenta -> grayMagenta
    }
}

/**
 * A recipe's correction towards Lightroom, fitted from photo pairs (Silverpoint's JPEG and Lightroom's
 * export of the same DNG with the preset) by `RecipeMatchFitTest`. Applied in OKLab after the
 * recipe's own colour work, so it is part of the look table (photo and live preview alike).
 * Every field defaults to "no change".
 */
@Serializable
data class RecipeMatch(
    /** OKLab lightness out for lightness in 0, 0.2, 0.4, 0.6, 0.8, 1 (a monotone curve through these). */
    val tone: List<Float> = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f),
    /** Chroma multiplier for colours around OKLab hue 0°, 60°, 120°, 180°, 240°, 300°, blended between. */
    val chroma: List<Float> = List(6) { 1f },
    /** Hue turn in degrees for the same six hues. */
    val hue: List<Float> = List(6) { 0f },
    /** OKLab (a, b) added in the shadows and in the highlights: a colour cast. */
    val shadowTint: List<Float> = listOf(0f, 0f),
    val highlightTint: List<Float> = listOf(0f, 0f),
) {
    init {
        require(tone.size == 6 && chroma.size == 6 && hue.size == 6 && shadowTint.size == 2 && highlightTint.size == 2) { "match: wrong number of values" }
        require(tone.zipWithNext().all { (a, b) -> b >= a }) { "match: tone must not decrease" }
    }
}

/** The straight point curve: Lightroom's default and every curve key's default here. */
val IDENTITY_CURVE: List<List<Int>> = listOf(listOf(0, 0), listOf(255, 255))

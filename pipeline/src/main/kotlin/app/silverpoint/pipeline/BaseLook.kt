package app.silverpoint.pipeline

/**
 * The neutral rendering every photo gets before its recipe: how scene-linear light becomes a
 * pleasing picture. Fitted so that a photo with no recipe looks like Lightroom's default rendering
 * of the same DNG (no edits; Lightroom picks Adobe Standard for these DNGs), because the recipes were designed as Lightroom
 * presets on top of that rendering. See `docs/ARCHITECTURE.md` ("Base look").
 */
data class BaseLook(
    /** Brightness added to every shot, in EV. Phone auto-exposure leaves room for a tone curve to lift midtones. */
    val baselineEv: Float,
    /** Linear level where the highlight shoulder starts rolling off towards white. */
    val knee: Float,
    /** S-curve amount in encoded space, same scale as [ToneCurve.contrast]. */
    val contrast: Float,
    /** Chroma multiplier (OKLab) applied with the recipe's Saturation. */
    val chroma: Float,
) {
    companion object {
        /** No base look at all: linear exposure, shoulder at 0.8, no extra contrast or chroma. */
        val NONE = BaseLook(baselineEv = 0f, knee = 0.8f, contrast = 0f, chroma = 1f)

        /**
         * Fitted with `LightroomFitTest` against one S25 Ultra DNG (main lens, bright daylight, ISO 81)
         * and Lightroom's no-edit export of it, over about 110,000 8x8 blocks: mean CIE ΔE2000 went
         * from 11.7 (no base look) to 3.2, median luminance from 0.55x to 1.02x Lightroom's.
         * The rest of the error is hue-specific (greens and yellows about 2-3 chroma units too
         * saturated), which a per-hue base adjustment can take on in Phase 2. Since checked without
         * refitting on four more pairs (daylight outdoor, daylight indoor, warm low light): mean
         * ΔE2000 2.2 to 3.4 and median luminance 0.93x to 1.0x; see `docs/ARCHITECTURE.md`.
         */
        val DEFAULT = BaseLook(baselineEv = 1.0f, knee = 0.1f, contrast = 0.5f, chroma = 0.96f)
    }
}

# Recipe engine

How Silverpoint turns a recipe from `recipes.json` into pixels. Every recipe key is used. The maths lives in `:pipeline` (`Look.kt`, `LocalContrast.kt`, `Grain.kt`, `CpuDeveloper.kt`) and is mirrored in the compute shaders (`pipeline/src/main/assets/shaders/`). The on-device GPU self-test compares the two.

The recipes were written as Lightroom presets. Lightroom's engine is not public, so each slider below is **my approximation** of what it does, tuned by eye. Where a number says "about", it is a choice, not a measured Lightroom value. On top of that, each recipe carries a small measured correction towards Lightroom (`match`, see [Matching Lightroom](#matching-lightroom)), and grain, sharpening and colour noise reduction were measured against Lightroom exports (October 2026: one indoor scene, four presets).

## Per-pixel order

```
RAW → black/white level, lens shading, white balance → demosaic → colour smoothing, sharpening
    → camera RGB to linear sRGB
    → Exposure2012 (+ base exposure) → Saturation, Vibrance (OKLab chroma)
    → highlight shoulder → sRGB encoding
    → Clarity, Texture (local contrast)
    → look table (33³, see below)
    → vignette → grain → rotate upright
```

## The look table

Everything that depends only on a pixel's colour is baked into one 33×33×33 RGB table per recipe (`RecipeLook.bake`), read with trilinear interpolation. The same function, baked with slightly different options, drives the live preview. Order inside the table, following Lightroom's panels:

| Recipe keys | What Silverpoint does |
|---|---|
| `Dehaze` (global part) | Moves the black point by up to 5% and scales chroma by up to ±25%. Lightroom's dehaze is also local; Silverpoint's is not. |
| `Blacks2012`, `Whites2012` | Up to ±0.06 on encoded values, faded in below 0.4 (blacks) or above 0.6 (whites). |
| `Shadows2012`, `Highlights2012` | Up to ±0.15, shaped by a bump centred on 0.25 (shadows) or 0.72 (highlights) that is zero at black and white. Lightroom's versions are local; these are global curves. |
| `BaseLook.contrast` | The S-curve of the neutral rendering (fitted to Lightroom, see ARCHITECTURE.md). |
| `Contrast2012` | Symmetric S-curve around mid-grey, slope 1 ± 0.6 at ±100. |
| `curve` | Monotone cubic spline through the five points. |
| `HueAdjustment*`, `SaturationAdjustment*`, `LuminanceAdjustment*` | The eight bands are placed by HSV hue (red 0°, orange 30°, yellow 60°, green 120°, aqua 180°, blue 240°, purple 270°, magenta 300°) and blended with a raised cosine between neighbours. Applied in OKLCh: hue ±100 turns by up to 30°, saturation scales chroma by 0..2, luminance changes lightness by up to ±30%. All fade out for near-grey pixels. |
| `ConvertToGrayscale`, `GrayMixer*` | Chroma removed; lightness scaled by up to ±40% per band of the original colour. |
| `SplitToning*` | Adds an OKLab tint of up to 0.08 chroma in the slider's hue, to shadows or highlights split at a pivot moved by `SplitToningBalance`; faded out at pure black and white. |
| `curve_red`, `curve_green`, `curve_blue` (ToneCurvePV2012Red/Green/Blue) | Monotone splines per channel, after the point curve. |
| `ColorGradeMidtoneHue/Sat`, `ColorGradeGlobalHue/Sat`, `ColorGradeBlending` | Midtone and global tints like split toning's (same 0.08 chroma at 100); the midtone weight peaks at the split pivot. Blending scales the shadow/highlight overlap (50 = default). Luminance wheels are read but not applied yet. |
| `RedHue` … `BlueSaturation` (Calibration) | Applied first, as a 3×3 matrix on linear values: each primary turned up to ±30° in OKLab hue and its chroma scaled 0..2×, then rescaled so white stays white. `ShadowTint` is read but not applied yet. |
| `match` (Silverpoint's own, not a Lightroom key) | Last, in OKLab: a monotone lightness curve through six points, chroma scale and hue turn for six hues (0°, 60°, … 300°, raised-cosine blend, faded out near grey), and an (a, b) tint in shadows and in highlights. Fitted per recipe; see below. |

Colours pushed outside sRGB are clipped per channel. Reducing chroma at constant lightness (gamut mapping) was tried and rejected: near the blue corner it jumps between very different colours for nearly equal inputs.

**Table accuracy.** On real photo colours, the 99th-percentile difference between the table and the exact function is under 0.6 of an 8-bit level for every recipe. Single near-grey pixels of strongly tuned recipes reach about 9 levels, because hue is ill-defined near grey. `LookTest` checks both.

## Position-dependent effects

| Recipe keys | What Silverpoint does |
|---|---|
| `Clarity2012`, `Texture` | A quarter-resolution luminance image is blurred at about 4 sensor pixels (Texture) and 2% of the frame (Clarity). Each pixel's luminance moves away from its blurred surroundings: Clarity by up to 0.6× (midtones only), Texture by up to 0.8×. |
| `PostCropVignetteAmount`, `…Midpoint`, `…Feather` | Multiplies by 1 + 0.8 × amount × smoothstep(start, end, r), where r is 1 in the corners. At Midpoint 50 and Feather 50, start..end is 0.3..1; a lower midpoint moves the falloff inwards, more feather widens it (`DevelopParams.vignetteShape`). Roundness, style and highlight contrast are read but not applied. |
| `GrainAmount`, `GrainSize`, `GrainFrequency` | Monochrome value noise from a PCG hash, the same on CPU and GPU, on two lattices turned 37° against each other so the cells never look square. Measured against Lightroom: cell 0.75 + 0.017 × size pixels on a 3000 px image (1.2 px at size 25), scaled with resolution; strength 0.09 × (amount / 100)^0.67; even from black to light grey, fading only near white. Frequency mixes in a finer layer. Each photo gets its own grain pattern. |

## Detail: Lightroom's RAW defaults

Lightroom sharpens every RAW file and removes its colour noise unless told otherwise (Sharpening 40 at radius 1.0, Color Noise Reduction 25), and the presets keep those defaults. Silverpoint does the same right after demosaicing (`CpuDeveloper.refine`, `refine()` in `develop.comp`), for every recipe:

- **Colour smoothing** (`DevelopParams.CHROMA_SMOOTHING` = 1): each pixel keeps its own brightness but takes its colour from the 5×5 grid of 2×2 sensor blocks around it (Gaussian of 2.5 pixels), counting blocks much brighter or darker than the surroundings less, so colour does not bleed across edges. This removes colour noise and the blue/orange specks on detail finer than the colour filter grid (wood grain, fabric). Those specks are in the RAW data itself; every demosaic method tried (bilinear, VNG, edge-aware, Malvar) shows them.
- **Sharpening** (`DevelopParams.SHARPEN` = 0.8 at Lightroom's default 40): an unsharp mask on brightness against a 5×5 binomial blur (radius about 1 pixel), in square-root units. A recipe's own `Sharpness` scales it (25 → 0.5); `SharpenEdgeMasking` spares faint detail (flat areas, noise) and keeps edges sharp. Radius and Detail are read but not applied.

Both strengths were set by measuring fine-detail and colour-noise energy against Lightroom's export of the same DNG. Not matched: Lightroom's demosaic resolves sub-pixel texture a little more smoothly in brightness.

## Matching Lightroom

Each recipe's `match` block is fitted from photo pairs: Silverpoint's JPEG and Lightroom's export of the same DNG with the same preset.

1. `python3 -I scripts/match_samples.py PHOTO_DIR OUT_DIR --colour-from Reportage` aligns each export to its app photo (Lightroom corrects distortion and crops) and writes flat, unclipped 32-pixel blocks as samples. Black-and-white recipes borrow their scene colours from a colour pair of the same scene.
2. `SILVERPOINT_MATCH_SAMPLES=OUT_DIR ./gradlew :pipeline:testDebugUnitTest --tests '*RecipeMatchFitTest*'` turns each app colour back into scene colour by inverting the recipe exactly, then fits the 22 match values (tone only for black and white) by coordinate descent with a penalty that keeps them small. It prints and writes `match.json`.

First fit (October 2026, one indoor scene), mean CIE ΔE2000 against Lightroom through the look table:

| Recipe | Before | After |
|---|---|---|
| California Summer | 3.65 | 1.11 |
| Reportage | 2.86 | 1.18 |
| Grit 400 | 1.66 | 1.10 |
| Vivid Slide | 3.02 | 0.96 |

The same tone error showed in all four (Silverpoint's shadows too light and upper midtones too dark). Every other recipe gets the common correction from [LIGHTROOM_MATCHING.md](LIGHTROOM_MATCHING.md) §8 (average tone plus the colour changes the three measured colour recipes agreed on; tone only for black and white) until it is measured. `RecipeIdentityTest` checks that no correction changes a recipe's character. One scene can over-fit hues that were not in it; more pairs per recipe make the fit safer.

## Live preview

The preview starts from the phone's processed video stream, which is already tone-mapped, so its table leaves out the base contrast and adds the recipe's exposure, saturation and vibrance. The match layer is in the table too. Grain (same hash and lattices) and vignette are drawn on top; sharpening and colour smoothing are not (the phone's stream has its own). It is an approximation of the saved photo.

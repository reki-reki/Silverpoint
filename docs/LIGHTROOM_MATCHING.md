# Matching Lightroom: what we learned

Everything found while matching Silverpoint's photos to Lightroom exports of the same RAW files (October 2026). It covers the phone's DNG files, how Lightroom treats them, every difference measured, and the patterns that held across all recipes. Use it as a guide when tuning the other recipes.

**TL;DR**
- **Tone was the main difference, and it was the same in every recipe.** Before the fix, Silverpoint's shadows were too light (about +4 L\*) and its upper midtones too dark (about −4 L\*). One shared correction fixes most of it: `"tone": [0, 0.161, 0.390, 0.616, 0.831, 1]`. Every recipe carries it (since 0.9.5).
- **Colour differences depend on the recipe**, but a few were consistent:
  - blues need about 6% more saturation and a few degrees turn towards cyan;
  - pinks and reds need about 7% less saturation;
  - oranges need a turn of about 8° towards red.
- **Lightroom also sharpens, cleans colour noise and adds finer grain on every RAW**, whatever the preset. Silverpoint now does the same for all recipes, so per-recipe tuning only needs to deal with colour and tone.

Terms used below:
- **L\*** is CIE lightness (0 black, 100 white).
- **ΔE2000** is the standard score of how different two colours look (about 1 is barely visible, 3 is noticeable side by side).
- **OKLab** is the colour space Silverpoint does its colour work in: L for lightness (0..1), a and b for colour; hue is the angle of (a, b) and chroma (colourfulness) is its length.

---

## 1. The test material

| Item | Detail |
|---|---|
| Scene | One indoor desk scene in daylight from a window. ISO 92, 1/100 s, f/1.7, main lens. |
| Pairs | Four `PHONE_…` JPEGs from Silverpoint 0.9.3 (Ultra HDR off) and four `IMG_…` Lightroom exports of their DNGs, one each for Reportage, Vivid Slide, Grit 400, California Summer. |
| DNG | One (the Reportage shot). The four shots are the same view, seconds apart. |
| Limits | One scene, one light. Colours absent from it (deep greens, skin, saturated sky) were not measured. |

---

## 2. The phone's DNG files

The DNG files are not written by Samsung's camera app. Silverpoint writes them through Android's `DngCreator` from the camera's RAW stream (the file's `ImageDescription` says "Silverpoint 0.9.3"). Samsung's camera service supplies the metadata.

| Tag | Value | What it means |
|---|---|---|
| Make / Model | samsung SM-S938B (Galaxy S25 Ultra), Android 16 | |
| Size | 4080 × 3060, `Orientation` 6 (rotate 90° clockwise) | Portrait photos are stored sideways. |
| Bits | 10-bit values in 16-bit samples; black level 64, white level 1023 | About 960 usable levels per channel. |
| CFA (colour filter pattern) | GBRG on the main lens (GRBG on the others) | Each pixel sees only one colour; demosaicing rebuilds the other two. |
| `DefaultCropOrigin` / `DefaultCropSize` | 8, 8 / 4064 × 3044 | **Lightroom crops 8 pixels off every edge.** Its exports are 3044 × 4064, Silverpoint's 3060 × 4080. |
| `AsShotNeutral` | 0.522, 1, 0.655 | The phone's white balance. Both Silverpoint and Lightroom (no preset sets white balance) use it. |
| Calibration illuminants | D65 (daylight) and Standard A (tungsten) | Two colour matrices; software blends them by the shot's colour temperature. |
| `ColorMatrix1/2`, `ForwardMatrix1/2` | present | Camera-to-standard colour conversion. |
| `CameraCalibration1/2` | diag(0.994, 1, 0.979) | Small per-unit correction of red and blue. |
| `BaselineExposure` | 0 | Samsung does not ask for brighter rendering; Silverpoint adds its own +1 EV (section 4). |
| `NoiseProfile` | 1.93e-4 (shot noise), 0 (read noise) at ISO 92 | Very clean file. |
| `OpcodeList2` | 4 × GainMap | Lens shading (darkening towards the corners) correction, one map per CFA channel. 17 × 13 grid; corner gain about 3.7–4.1× (about 2 stops). Both Silverpoint and Lightroom apply it. |
| `OpcodeList3` | WarpRectilinear | **Lens distortion correction. Lightroom applies it; Silverpoint does not.** Lightroom's export is geometrically different: aligning it needed a homography plus up to about 120–270 px of local shift near the edges. |
| Embedded camera profile | none (no `ProfileName`) | Which profile Lightroom used is not stored in the export. See section 8. |

What the two pipelines do with the same DNG:

| | Lightroom | Silverpoint (0.9.4) |
|---|---|---|
| White balance | As shot | As shot |
| Lens shading (GainMap) | Applied | Applied |
| Distortion (WarpRectilinear) | Applied | **Not applied** (known gap) |
| Crop | 8 px each edge | None |
| Sharpening | Default for RAW (see section 6) | Matched in 0.9.4 |
| Colour noise reduction | Default for RAW | Matched in 0.9.4 |
| JPEG | 4:4:4, very high quality (quantisation table sum 109) | 4:2:0, quality 95 (sum 369) |

**JPEG:** 4:4:4 keeps full-resolution colour; 4:2:0 stores colour at half resolution. My estimate: the difference is rarely visible at normal viewing size; it shows on fine coloured detail when zoomed in.

---

## 3. What the presets set and what they leave alone

The original 21 `.xmp` presets (in `docs/reference/presets/`) are all process version 11, have `SupportsAmount="True"`, and no camera restriction.

- **Set by all 21:** Contrast, Highlights, Shadows, Whites, Blacks, Clarity, Texture, Vibrance, Saturation, tone curve (`Custom`), Grain (Amount, Size, Frequency).
- **Set by most:**
  - HSL hue, saturation and luminance (19);
  - split toning (18);
  - exposure (9);
  - vignette (8);
  - dehaze (7);
  - black and white with a grey mixer (2: Mono Yellow Filter, Grit 400).
- **Set by none:** white balance, camera profile, sharpening, noise reduction, lens corrections.

**Consequence:** for every preset, Lightroom used its own defaults for the profile, sharpening, noise reduction and lens corrections. So the differences in those areas are the same for all recipes, and they were fixed once, globally (section 6).

---

## 4. Silverpoint's neutral rendering (shared by every recipe)

Before any recipe, Silverpoint renders the RAW to match Lightroom's no-edit look. This "base look" was fitted earlier (0.1.1) on five scenes against Lightroom's no-edit exports, and scores ΔE2000 2.2–3.4.

- **Exposure:** +1.0 EV.
- **Highlight shoulder:** starts at 0.1 linear. It is the soft roll-off into white.
- **S-curve:** 0.5.
- **Chroma:** 0.96.

The recipe's sliders are applied on top of this base look, then the match layer (section 5).

---

## 5. Colour and tone differences

### How they were measured

1. **Alignment.** Each Lightroom export was aligned to its app photo: feature matching, then a smooth per-pixel shift. This undoes Lightroom's crop and distortion correction.
2. **Sampling.** Both photos were cut into 32-pixel blocks. Only flat, unclipped blocks were kept: about 4,800–6,800 per recipe.
3. **Fitting.** Each app colour was turned back into scene colour by inverting Silverpoint's exact recipe maths. Then a small correction was fitted to make Silverpoint's render of that scene colour match Lightroom's.

Tools: `scripts/match_samples.py` (steps 1 and 2) and `RecipeMatchFitTest` (step 3).

### Before the fix

L\* error by Lightroom's lightness, as Silverpoint minus Lightroom (positive means Silverpoint is too light):

| Lightroom L\* | 0 | 10 | 20 | 30 | 40 | 50 | 60 | 70 | 80 | 90 |
|---|---|---|---|---|---|---|---|---|---|---|
| California Summer | +5.1 | +3.1 | +0.9 | −0.3 | −1.2 | −3.1 | −5.1 | −5.6 | −1.6 | −0.9 |
| Reportage | +4.5 | +4.1 | +3.2 | +3.2 | +1.2 | −1.3 | −3.3 | −2.7 | −0.4 | −1.1 |
| Grit 400 | +2.7 | +2.2 | +1.1 | +3.8 | +2.6 | −1.0 | −1.0 | −2.8 | −2.7 | −1.6 |
| Vivid Slide | +4.2 | +3.7 | +2.7 | +1.3 | +0.2 | −2.4 | −4.4 | −5.4 | −1.6 | −0.4 |

**The same shape in all four recipes, including black and white:**
- shadows too light (up to +5 L\*);
- crossover around L\* 45;
- upper midtones too dark (−3 to −6 L\* around L\* 60–70);
- whites close.

In Lightroom terms, Silverpoint was missing some contrast, mostly from too little depth in the shadows and too little lift in the upper mids. Because it is shared, it comes from the common part of the pipeline (base look or slider mapping), not from the individual presets.

Overall chroma, Silverpoint ÷ Lightroom (1.00 means equally colourful):

| Recipe | Preset Saturation / Vibrance | Chroma ratio |
|---|---|---|
| California Summer | −4 / +4 | 0.78 (too dull) |
| Reportage | −16 / −8 | 1.39 (too colourful) |
| Vivid Slide | +22 / +16 | 1.21 (too colourful) |

This one is **not** a single pattern: one recipe was too dull and two too colourful. My inference: it comes from each recipe's HSL and split-toning settings interacting with Silverpoint's slider mapping, not from the base look. A single global fit of the slider scales gave implausible values (several hit their limits), so I used per-recipe corrections instead.

Mean ΔE2000 against Lightroom:

| Recipe | Before | After (0.9.4) |
|---|---|---|
| California Summer | 3.65 | 1.11 |
| Reportage | 2.86 | 1.18 |
| Grit 400 | 1.66 | 1.10 |
| Vivid Slide | 3.02 | 0.96 |

A cross-check: Grit 400 scene colours borrowed from the Reportage frame predicted Silverpoint's own Grit 400 photo within ΔE 1.12. That confirms the inversion method works.

### The fitted corrections

The fitted values live in each recipe's `match` block in `app/src/main/assets/recipes.json`:

```json
"California Summer": {"tone": [0.002, 0.164, 0.398, 0.635, 0.832, 0.998], "chroma": [0.938, 1.094, 0.921, 0.989, 1.054, 1.023], "hue": [5.4, -7.3, -3.8, -0.6, -1.7, 0.9], "shadowTint": [0.0041, 0.0025], "highlightTint": [0.0153, 0.0075]}
"Reportage":    {"tone": [0.000, 0.153, 0.374, 0.614, 0.822, 1.000], "chroma": [0.970, 0.894, 0.880, 1.038, 1.066, 0.928], "hue": [2.6, -12.2, 0.8, 1.9, -4.3, -7.1], "shadowTint": [-0.0047, 0.0022], "highlightTint": [0.0012, -0.0028]}
"Grit 400":   {"tone": [0.000, 0.174, 0.405, 0.589, 0.829, 1.000]}
"Vivid Slide":      {"tone": [0.000, 0.153, 0.384, 0.624, 0.840, 0.995], "chroma": [0.874, 0.857, 1.054, 1.023, 1.066, 0.996], "hue": [2.6, -4.5, -13.3, 1.5, -4.5, -4.7], "shadowTint": [-0.0019, 0.0006], "highlightTint": [0.0056, -0.0003]}
```

### What is common, sector by sector

The six colour sectors are OKLab hues 0°, 60° … 300°. The colour column is what each one looks like (sRGB at mid lightness), and the Lightroom HSL band column names the nearest band.

| OKLab hue | Looks like | Nearest Lightroom band | Chroma ×  (CS / CC / VV) | Hue turn ° (CS / CC / VV) | Common? |
|---|---|---|---|---|---|
| 0° | rose pink | red/magenta (red ≈ 29°, magenta ≈ 328°) | 0.94 / 0.97 / 0.87 | +5.4 / +2.6 / +2.6 | **Yes:** less saturated (avg 0.93), turned slightly towards orange (+3.5°). |
| 60° | orange, tan, wood, skin | orange (≈ 53°) | 1.09 / 0.89 / 0.86 | −7.3 / −12.2 / −4.5 | **Hue yes:** turned towards red (avg −8°). Saturation depends on the recipe. |
| 120° | yellow-olive | yellow (≈ 110°) | 0.92 / 0.88 / 1.05 | −3.8 / +0.8 / −13.3 | Weak: mostly less saturated, mostly towards orange; unstable (see below). |
| 180° | teal, aqua | aqua (≈ 195°) | 0.99 / 1.04 / 1.02 | −0.6 / +1.9 / +1.5 | **Yes:** almost no change needed. |
| 240° | sky blue | blue (≈ 264°) | 1.05 / 1.07 / 1.07 | −1.7 / −4.3 / −4.5 | **Yes:** more saturated (avg 1.06), turned towards cyan (avg −3.5°). |
| 300° | lavender, purple | purple (≈ 294°) | 1.02 / 0.93 / 1.00 | +0.9 / −7.1 / −4.7 | Weak: mostly towards blue. |

CS = California Summer, CC = Reportage, VV = Vivid Slide. Positive hue turns go counter-clockwise in OKLab (pink → orange → yellow → green → cyan → blue → purple); negative ones go the other way.

**How sure these are.** A second fit, on re-extracted samples of the same photos, moved some hue values by up to 10°. The largest move was Reportage at 120°: +0.8° became −9.0°. Values in sectors with few colours in the scene (yellow-olive, purple) are the least reliable. Chroma values moved by up to about 0.08 (California Summer at 300°: 1.02 became 0.95). Treat individual numbers as ±5° and ±0.08, and only trust patterns that agree across recipes.

**Tints** (shadowTint, highlightTint: OKLab a, b added to shadows or highlights):
- These were not common.
- California Summer needed warmer highlights (+0.015 a, +0.0075 b: towards pink-orange).
- Reportage needed slightly green-yellow shadows (−0.005 a, +0.002 b).
- My estimate: values under about 0.003 are hard to see.

### Applied to the other recipes

Since 0.9.5, every recipe not yet measured (the 16 colour recipes without their own photo pairs) carries the starting block from section 8: the shared tone plus the colour changes that agreed across all three measured colour recipes. Mono Yellow Filter, black and white, has the tone only. Each will be replaced by its own fit once photo pairs exist.

---

## 6. Differences in detail (the same for every recipe)

These come from Lightroom defaults that no preset overrides. They are fixed globally in `DevelopParams` / `CpuDeveloper.refine` and the matching GPU code, so no recipe needs to touch them.

### Sharpening and colour noise reduction

**Inference:** Lightroom's RAW defaults are Sharpening 40, radius 1.0, detail 25, and Color Noise Reduction 25. The presets don't change them, and the measurements are consistent with them.

Measured on Reportage, 100% detail energy in textured and flat areas. The Silverpoint images were JPEG-compressed first, like real app output.

| | Fine-detail energy (textured areas) | Mid-detail energy | Fine energy (flat areas, mostly grain) | Colour noise (textured areas) | Colour noise (flat areas) |
|---|---|---|---|---|---|
| Silverpoint 0.9.3 | 5.33 | 8.08 | 1.17 | 2.05 | 0.40 |
| Silverpoint 0.9.4 | 7.34 | 9.41 | 1.56 | 1.65 | 0.29 |
| Lightroom | 6.98 | 9.81 | 1.82 | 1.73 | 0.21 |

What 0.9.4 does:
- **Sharpening:** strength 0.8, as an unsharp mask on brightness at a radius of about 1 px.
- **Colour smoothing:** colour taken from about 4 px around each pixel, with less weight for areas of different brightness so colours don't bleed across edges.

**Not matched:** detail finer than the sensor's colour grid (the wood grain in the test scene) shows a dotted brightness pattern in Silverpoint that Lightroom renders smoothly. It is in the RAW data itself: four demosaic methods (bilinear, VNG, edge-aware and Silverpoint's Malvar) all show it. Lightroom's demosaic method is not public.

### Grain

Measured from Lightroom's exports (Reportage: Size 25, Amount 22; Grit 400: Size 42, Amount 58):

| Property | Lightroom (measured) | Silverpoint 0.9.3 | Silverpoint 0.9.4 |
|---|---|---|---|
| Grain size | About 1.6 px at Size 25, 2.0 px at Size 42 on a 4064 px export | 2 px at Size 25 on 3000 px (2.7 px on 4080 px): too coarse | (0.75 + 0.017 × Size) px per 3000 px of long edge: 1.6 px and 2.0 px on 4080 px |
| Strength vs Amount | Grows as Amount^0.67 | Linear | Amount^0.67 |
| Strength vs brightness | Even from black to light grey | Strongest in the midtones, weak in shadows and highlights | Even; fades only above 80% brightness |
| Shape | Fine, no visible pattern | Square-ish cells | Two noise grids turned 37° against each other |

---

## 7. Geometry (not fixed)

- Lightroom applies the DNG's lens-distortion correction (WarpRectilinear) and the 8-pixel crop. Silverpoint applies neither.
- On the main lens the difference is small to the eye, but it means **photos can't be compared pixel by pixel without alignment**. `scripts/match_samples.py` does the alignment.
- On the ultrawide the difference will be much larger (not measured).

---

## 8. Checklist for matching another recipe

**What each recipe needs:**
- `PHONE_<stamp>_<Recipe_Name>.jpg` from Silverpoint 0.9.4 or later, with Ultra HDR off;
- `IMG_<stamp>_<Recipe_Name>.jpg`, the Lightroom export of that shot's DNG with the preset applied;
- one DNG per scene.

**Keep these the same in Lightroom across all exports:**
- **Profile:** the Basic panel's Profile field (for example "Adobe Color" or "Embedded"). The presets don't set it, so it is whatever Lightroom picks for this phone. All the numbers here assume it stayed the same; a different profile changes the base colours.
- **Preset Amount:** 100. The presets support the Amount slider.
- **White balance:** As Shot.
- **Detail and lens corrections:** defaults. If you change Lightroom's defaults for sharpening, noise reduction or lens corrections, the global matching in section 6 no longer applies.
- **Export:** sRGB JPEG, no output sharpening, no resizing. Quality 90 or more (my estimate: above that it barely matters).

**Shoot for coverage:**
- Include an outdoor daylight scene with foliage, sky and skin. The indoor scene had no saturated greens or skin, so those sectors are the least verified.
- A grey card or white paper in frame makes tint problems obvious.

**Then:**
1. `python3 -I scripts/match_samples.py PHOTO_DIR OUT_DIR --colour-from <a colour recipe in the set>` (the `--colour-from` part is only needed for black-and-white recipes).
2. `SILVERPOINT_MATCH_SAMPLES=OUT_DIR ./gradlew :pipeline:testDebugUnitTest --rerun --tests '*RecipeMatchFitTest*'`
3. Paste the printed `match` blocks into `recipes.json`, run `scripts/validate_recipes.py`, and rebuild.

**Rules for editing `match` by hand:**
- `tone`: six output lightnesses for input 0, 0.2 … 1 (OKLab L). They must not decrease.
  - Lower the second and third values for deeper shadows.
  - Raise the fourth and fifth for brighter upper midtones.
  - 0.01 is about 1 L\*.
- `chroma`: a multiplier per sector (1 = no change).
  - Keep it within 0.85–1.15.
  - Beyond that, change the recipe's own Saturation, Vibrance or HSL instead.
- `hue`: degrees per sector.
  - Positive turns pink → orange → yellow → green → cyan → blue → purple.
  - Keep it within ±15°.
  - Turns fade out for near-grey colours automatically.
- `shadowTint`, `highlightTint`: OKLab (a, b) added at the dark or bright end.
  - +a is towards magenta, −a towards green; +b towards yellow, −b towards blue.
  - My estimate: 0.005 is subtle; 0.015 is clearly visible.
- Missing fields mean "no change". Black-and-white recipes only use `tone`.

**The starting point for an unmeasured colour recipe** (my inference, applied to every unmeasured colour recipe since 0.9.5). It combines the shared tone with only the sectors that agreed across all three colour recipes; the uncertain ones stay neutral:

```json
"match": {"tone": [0, 0.161, 0.390, 0.616, 0.831, 1],
          "chroma": [0.93, 1.0, 1.0, 1.0, 1.06, 1.0],
          "hue": [3.5, -8.0, 0, 0, -3.5, 0]}
```

---

## 9. Lessons for anyone continuing this

- **Measure, don't eyeball.** The tone error was invisible in any single photo but identical across four recipes.
- **Black-and-white needs colour from elsewhere.** A B&W photo can't be turned back into scene colour; borrowing colours from a colour shot of the same scene worked, and was validated at ΔE 1.12.
- **Keep corrections small and penalised.** The fit pulls every value towards "no change" unless the data clearly needs it. An unconstrained global fit over-fitted one scene.
- **Global effects belong in the engine, not the recipes.** Sharpening, noise reduction, grain response and the shared tone shape are the same for every preset; fixing them once improved every recipe.
- **Check GPU code before it reaches the phone.** `scripts/gpu_parity.py` runs the real shaders on a computer and compares them with the CPU reference. It caught a shader that would not compile on strict drivers (a non-ASCII character in a comment).

---

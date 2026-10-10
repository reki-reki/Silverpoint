# Proposal: importing your own Lightroom presets

**Status:** feasibility study, not built. Planned for a future update.

**TL;DR:** Feasible, and most of the hard part already exists. Silverpoint's look engine is built around Lightroom's own setting names (`recipes.json` keys *are* Camera Raw's `crs:` keys). An imported preset is therefore mostly a parsing job: read the XMP, map it onto `RecipeParams`, bake its look table. That table is the same kind every built-in look uses.

- **Will work well:** a first version that imports `.xmp`, DNG-embedded and zipped presets would cover the global panels most presets use.
- **Will be dropped, with a note:** local masks, lens corrections and noise reduction.
- **Missing today, worth adding first:** white balance and the parametric tone curve.
- **Harder, later:** Adobe creative profiles (the ones with an embedded colour table).

---

## 1. What a user would do

1. In the look selector, tap **Import** and pick files with Android's file picker. Supported:
   - `.xmp` (Lightroom Classic and desktop exports);
   - `.dng` "mobile presets" (a photo whose embedded XMP carries the settings, the usual way presets are sold for Lightroom mobile);
   - `.zip` packs containing either.

   Sharing a file to Silverpoint from another app would do the same.
2. The app shows what it imported and what it had to ignore (for example "Lens corrections, 2 masks: not supported"), so nothing is silently different.
3. Imported looks appear in a **My looks** tab with live previews, and can be starred, renamed or deleted like any look.

No storage permission is needed: the file picker grants access to just the chosen files. Imported looks are copied into the app's private storage and never leave the phone.

## 2. Coverage

**My inference for third-party presets:** the 21 presets bundled with Silverpoint use only settings the engine handles, but presets in general often also set white balance, a profile and the parametric curve. This is from how Lightroom presets are usually built, not from a measured sample.

| Lightroom setting | Today | For import |
|---|---|---|
| Basic: exposure, contrast, highlights, shadows, whites, blacks, texture, clarity, dehaze, vibrance, saturation | Yes | Yes |
| Point curve, red/green/blue curves | Yes | Yes |
| HSL / Color mixer, B&W mixer | Yes | Yes |
| Split toning, Color Grading (shadows, midtones, highlights, global, blending) | Yes (luminance sliders parsed, not applied) | Yes; the luminance sliders are small work |
| Calibration (red, green, blue primaries) | Yes (shadow tint parsed, not applied) | Yes |
| Grain, vignette amount, midpoint, feather | Yes (roundness and style parsed, not applied) | Yes |
| Sharpening amount and masking | Yes (radius and detail parsed, not applied) | Yes |
| **White balance** (Temperature / Tint, or As Shot) | **No** | **Add.** The developer already applies exact Kelvin and tint for Pro mode, so a preset's white balance can use the same path. Many presets set it, and without it their warmth is lost. |
| **Parametric tone curve** (Highlights / Lights / Darks / Shadows regions) | **No** | **Add.** It is a smooth curve with four regions and three split points, which can be turned into point-curve form and combined with the point curve. |
| Profile: Adobe Standard / Adobe Color / Camera Matching | Adobe Standard only (the base look is fitted to it) | Treat the others as Adobe Standard and say so. Adobe Color could later get a fitted offset like the base look. |
| **Creative profiles** (embedded colour table, `crs:Look` with an RGB table) | No | **Later.** My understanding is that the table is stored compressed in the XMP, in a format the DNG SDK documents. Decoding it and applying it in the right colour space is a separate project; this needs confirming against the DNG SDK. |
| Noise reduction, lens corrections, chromatic aberration | Fixed in the developer (Lightroom-like defaults); no lens profiles | Ignore and list |
| Local adjustments: masks, gradients, brush, healing | No | Ignore and list; they depend on each photo, so a camera cannot apply them live |
| Crop, transform | No | Ignore and list |
| Legacy process versions (before 2012) | No | Reject with a clear message (presets from before 2012 are rare now) |

## 3. Accuracy to expect

Built-in looks carry a `match` correction fitted against Lightroom exports where photo pairs exist; the rest use the shared starting correction ([LIGHTROOM_MATCHING.md](../LIGHTROOM_MATCHING.md)). An imported preset would get the shared starting correction.

*My estimate:* imported looks would match Lightroom about as closely as the built-in looks without their own photo pairs. That means close in tone and overall colour, and occasionally a few ΔE off in specific hues. Presets with extreme HSL or curve settings will differ more, because each slider's effect is an approximation of Lightroom's engine.

The import summary should set that expectation in one line.

## 4. How it would be built

| Part | Work |
|---|---|
| **Parser** (`recipes/`) | Read an XMP packet with Android's XmlPullParser. Settings are attributes (`crs:Contrast2012="+12"`) or sequences (`crs:ToneCurvePV2012` → `x, y` items). Map them onto `RecipeParams`, which already uses the same names, ignore unknown keys, and collect the unsupported ones for the summary. The parser is shared by all three file types. |
| **DNG presets** | Read only the DNG's XMP packet (TIFF tag 700 in IFD0); no image decoding needed. |
| **ZIP packs** | Read entries in memory with a size cap; never write archive paths to disk. |
| **White balance and parametric curve** | Two engine additions (section 2), mirrored on CPU and GPU as every stage is ([DEVELOPMENT.md](../DEVELOPMENT.md)). |
| **Storage** | One JSON per imported look in app-private storage, in the same format as `recipes.json` entries plus the source file name and the list of ignored settings. Look tables are baked on demand and cached, as now. |
| **UI** | Import button and "My looks" tab in the look selector; import summary sheet; rename and delete. |
| **Tests** | The 21 bundled `.xmp` files make a ready test set: parsing each must reproduce its `recipes.json` parameters exactly. Add hand-made XMPs for white balance, parametric curves, masks, legacy process versions, malformed XML and oversized files. |

**Safety:**
- **XML:** Android's XmlPullParser does not expand external entities or DTDs by default, which rules out the usual XML attacks; keep it that way.
- **Limits:** cap file sizes (presets are a few kB, DNG presets a few MB) and the number of files in a ZIP.
- **Failures:** a broken file shows an error and imports nothing.

**Rough effort** *(my estimate)*:
- **First version** (XMP, DNG and ZIP import; white balance; parametric curve; "My looks"; summary; tests): a few focused working sessions.
- **Creative profiles:** a separate step of similar size, after confirming the format.

## 5. Legal and privacy notes

- **Users' own presets:** importing presets a user owns, for their own use, on their own phone, is normal; Lightroom mobile itself does it.
- **No redistribution:** Silverpoint never uploads or shares imported presets. Many paid preset licences forbid redistribution, so there must be no "share look" feature that sends the preset's settings out.
- **Names stay with the user:** an imported preset keeps its own name, chosen by the user, and nothing ships in the app.

## 6. Recommendation

Build it in two steps:

1. **Import with the global panels** (everything in section 2 marked Yes or Add) and a clear list of what was ignored.
2. **Creative profiles,** if users ask for them.

Start step 1 with the parser and its test against the 21 bundled presets, since that proves the mapping before any UI exists.

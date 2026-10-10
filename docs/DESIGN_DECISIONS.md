# Design decisions

The defaults and trade-offs behind Silverpoint, each with the reason for it and where to change it. Items marked **untested** have not yet been confirmed on a phone.

## Recipes and look

| # | Decision | Why | To change |
|---|---|---|---|
| R1 | Each Lightroom slider maps to an effect designed by eye ([RECIPE_ENGINE.md](RECIPE_ENGINE.md)); a per-recipe `match` correction, fitted to Lightroom exports, sits on top. Four recipes are fitted (California Summer, Reportage, Grit 400, Vivid Slide; mean ΔE2000 3.65 / 2.86 / 1.66 / 3.02 → 1.11 / 1.18 / 1.10 / 0.96), from one indoor scene. | Lightroom's engine is not public; fitting the slider scales globally gave implausible values, a small per-recipe correction did not. | Send more PHONE_/IMG_ pairs (other scenes, other recipes); `scripts/match_samples.py` + `RecipeMatchFitTest` refit them. |
| R6 | Every recipe without its own photo pairs (17 old, 5 new) gets the common correction from [LIGHTROOM_MATCHING.md](LIGHTROOM_MATCHING.md) §8: the average tone (`[0, 0.161, 0.390, 0.616, 0.831, 1]`) plus the colour changes all three measured colour recipes agreed on (pinks chroma 0.93 and +3.5°, oranges −8°, blues chroma 1.06 and −3.5°). Black and white gets tone only. | The findings apply to all profiles. `RecipeIdentityTest` shows each recipe keeps its character. The tone part restores Lightroom's matte black level (California Summer: black floor 31 → 17, Lightroom 17). | Edit or delete a recipe's `match` block in `recipes.json`; send PHONE_/IMG_ pairs to replace it with a measured one. |
| R7 | Every photo is sharpened and colour-smoothed like Lightroom's RAW defaults (Sharpening 40, Color NR 25), whatever the recipe. | The presets keep Lightroom's defaults, so their exports have them. | `DevelopParams.SHARPEN`, `DevelopParams.CHROMA_SMOOTHING` (0 turns either off). |
| R9 | Newer Lightroom settings (for presets that use them), built as approximations: Calibration hue ±100 turns a primary up to 30° in OKLab and saturation ±100 scales it 0..2×, white kept neutral; Color Grading midtone/global tints at the split-toning strength, Blending scales the shadow/highlight overlap; Sharpening scales the measured Lightroom-40 strength, Masking spares detail weaker than masking × 0.05 (square-root units); vignette Midpoint/Feather move and widen the falloff. Not applied: grading luminance wheels, calibration shadow tint, vignette roundness/style/highlight contrast, sharpen radius/detail (all at their defaults or near-defaults in these presets). | Lightroom's formulas are not public; these presets set them only lightly (calibration ±5–8, midtone saturation 5). | `LookTuning.calibrationHueDegrees`, `calibrationSaturation`; `DevelopParams.vignetteShape`; `CpuDeveloper.MASK_DETAIL`. A photo pair per preset would measure them. |
| R8 | Grain re-measured against Lightroom: finer (1.2 px cell at size 25 on a 3000 px image), amount^0.67, and as strong in shadows and highlights as in midtones. | The old grain was coarse, blocky and faded in highlights, unlike Lightroom's. | `Grain.kt` and the matching lines in `develop.comp` / `preview.frag`. |
| R2 | Highlights, Shadows and Dehaze are global curves, not local like Lightroom's. | Local versions need another full-resolution blur pass; recipes use small values. | Say if a recipe's skies or shadows look wrong. |
| R3 | Look table size 33³. | Errors are invisible on real photos (see RECIPE_ENGINE.md); 65³ would take about 1 s per recipe to bake on the phone. | `LookLut.DEFAULT_SIZE`. |
| R4 | Out-of-gamut colours are clipped per channel. | Chroma-reduction gamut mapping jumps near the blue corner. | — |
| R5 | Grain differs per photo (seeded by the capture time). | Real film grain never repeats. | — |

## Viewfinder and controls

| # | Decision | Why | To change |
|---|---|---|---|
| V1 | The live preview applies the recipe to the phone's processed video (OpenGL, `PreviewRenderer`), not to RAW. | RAW preview at 30 fps is not available to apps. | — |
| V2 | **Untested risk:** the preview relies on the camera rotating its frames for the screen (via the SurfaceTexture transform). If the viewfinder shows up sideways or stretched, turn off *Settings → Live recipe preview* and report it. | It was built without camera access. | Settings switch; the app also falls back by itself if the GL preview fails to start. |
| V3 | Default favourites: Reportage, Natural Portrait 400, Sunny 200, Vivid Slide, Mono Yellow Filter, Tungsten 800. Star or un-star looks in the look selector (or long-press one in the quick row). | A mix of everyday, vivid, B&W and night looks. | `Settings.DEFAULT_FAVOURITES`. |
| V4 | Viewfinder aids (grid, level, histogram, zebras, focus peaking) share one row behind the top bar's aids button; all off by default, the histogram included since 0.9.6. | The screen felt claustrophobic with them always shown; the aids are situational, and the histogram sat over the sky. | The aids row or Settings; `Settings.histogram` default. |
| V6 | Camera layout (since 0.9.6): top bar Settings · Flash · Timer · Shape · Aids; viewfinder full width in the photo's shape; Photo mode shows ± and lenses on the picture's edge and a quick row of starred looks; Pro mode shows five value buttons and one dial at a time; Gallery · Shutter · Looks. | Every element is either used between shots or shows state you need while shooting; everything else moved to Settings or behind a tap. | `ui/CameraScreen.kt`, `ui/CameraControls.kt`. |
| V7 | Flash: Off by default; Auto and On run the precapture (pre-flash) first; a flash photo is never merged; in Pro priority modes flash photos use auto-exposure, in full manual the flash fires at your ISO and shutter. **Untested on the phone.** | A flash lights one frame of a burst; priority emulation meters without the flash and would overexpose. | `CameraController.prepareFlash`, `CameraViewModel.takePhoto`. |
| V8 | Shapes 3:4, 4:5, 1:1, 9:16 crop the JPEG (and its gain map) to the middle; the DNG keeps the whole sensor. 9:16 enlarges the viewfinder and crops its sides, like Samsung's camera. | The crop is a framing choice you might want to undo in an editor. | `CameraMath.cropFor`, `CameraMath.framing`. |
| V9 | Look thumbnails (since 0.9.7): live, drawn by the preview renderer into each visible card's own surface through a 17³ table, at most every 30 ms (about 30 fps), at the card's pixel size. With Settings → Live look previews off (or the plain preview), a bundled drawn sample picture through each look instead. On by default. | A live feed is far more useful than the 4-second stills of 0.9.6; the GPU draws are cheap and nothing is copied back to the CPU. | Settings switch; `PreviewRenderer.THUMB_FRAME_MS`, `LookThumbnails.LUT_SIZE`, `assets/look_sample.jpg` (`scripts/make_look_sample.py`). |
| V11 | Haptics play through the vibration motor tagged as hardware feedback, so they follow the phone's vibration strength but not its touch-vibration switch; Settings → Haptics turns them off. On by default. | The view-based path of 0.9.6 was dropped silently on the phone. | `ui/Haptics.kt`, `Settings.haptics`. |
| V12 | Layout sized for the S25 Ultra's default screen (estimated 384 × 832 dp with about 40 dp status bar and 24 dp gesture bar): quick looks 64 dp, shutter row 96 dp, tabs 36 dp, Pro buttons 48 dp, dial 50 dp; the look's name shows for 1.6 s over the picture when it changes. | Keeps Photo mode's picture clear except for ± and the lens pill along its edge. | `ui/CameraControls.kt`, `ui/Dial.kt`, `ui/CameraScreen.kt`. |
| V15 | First launch opens with a 3 s start-up film (Remotion), then the mark glides into the tutorial; tap skips it, and it is skipped with system animations off. The system splash is plain black on every launch. | A memorable first impression without slowing later launches; a camera app opens onto black anyway. | `IntroScreen` (`playStartup`), `res/raw/intro_motion.mp4`, `motion/`, `themes.xml`. |
| V10 | Self-timer choices: off, 2, 5, 10 s. | 2 s avoids shake from the press, 10 s lets you join the picture, 5 s in between (Samsung's set). | `TIMER_CHOICES`. |
| V5 | Zebras at 97% of white; peaking threshold 0.18 luminance difference between neighbours. | Common defaults. | `preview.frag`. |
| V13 | First launch shows a four-page introduction (skippable; Settings → Show the introduction again). | First-run onboarding was part of the original brief. | `IntroScreen.kt`. |
| V14 | The interface follows One UI's dark look: true black, grey rounded cards, large titles that shrink as you scroll, pill buttons, a 3:4 viewfinder with rounded corners, PHOTO/PRO tabs, and a darkroom-safelight orange (#FF7A45) as the accent. It stays dark whatever the phone's theme is, and does not use Android's wallpaper colours. Recipe swatches show each look applied to skin, foliage and sky. | Camera apps are dark so the photo stands out; one fixed palette keeps contrast readable (the old theme followed the phone and drew black text on black). | `ui/Theme.kt` (`Sl`). |

## Lenses and Pro mode

| # | Decision | Why | To change |
|---|---|---|---|
| P1 | Lens buttons read 0.6, 1, 3, 5. The "3" lens is really 2.7× the main lens by focal length (file names in Diagnostics say `tele-2.7x`). | Matches Samsung's labels. | `Lens.buttonText`. |
| P2 | Switching lens keeps the camera open and rebuilds only the session (about 0.5 s on the S25 Ultra per the probe). The last lens is remembered. | Fastest switch the phone allows. | — |
| P3 | Shutter or ISO priority is emulated: the preview keeps auto-exposure, and at the shutter press the other value is computed to match the metered exposure (clamped to the lens's range). | The phone has no hybrid auto-exposure (probe). | `ExposureMath.forCapture`. |
| P4 | With both ISO and shutter set (full manual), the preview shows that exposure, but never longer than 1/15 s so it stays fluid; a higher preview ISO makes up the difference, so the preview stays as bright as the photo unless the ISO runs out. The EV dial is disabled. | A 1/4 s preview would stutter. | `CameraController.PREVIEW_MAX_EXPOSURE_NS`. |
| P5 | Manual white balance (2500 to 10000 K, tint ±50) is applied exactly in the developer from the colour matrices. Kelvin and tint read like Lightroom's: they name the light being corrected, so 3000 K turns a daylight photo blue and +tint turns it magenta (Adobe's tint scale). The **preview** only switches to the nearest camera preset (tungsten, fluorescent, daylight, cloudy, shade), so it is approximate. | Camera2 has no Kelvin mode on this phone (probe). | `CameraMath.awbModeFor`. |
| P6 | Tap = focus and meter on that spot and hold focus; double tap = back to continuous autofocus and whole-frame metering. The focus square turns green when locked. | Same as most camera apps. | — |
| P7 | Self-timer: off, 2, 5 or 10 s from the timer icon in the top bar (the seconds show inside it); pressing the shutter during the countdown cancels it. | Same set as Samsung's camera. | `TIMER_CHOICES`. |
| P8 | "Also save the DNG" writes the RAW next to the JPEG in DCIM/Silverpoint (off by default, as decided). | — | Settings. |
| P9 | Shutter speeds offered: 1/8000 s up to the lens's longest (about 1/6 s on the main lens). Longer exposures come from the merge in Phase 4. | Hardware limit (probe). | — |

## Multi-frame merge and background developing

| # | Decision | Why | To change |
|---|---|---|---|
| M1 | Merge mode **Auto** by default: 6 frames when the light is low (ISO 640+ or 1/30 s or slower), otherwise a single frame. *Always* merges every photo; *Off* never does. All frames of a burst share one exposure (the metered one, or Pro mode's), so auto-exposure cannot drift mid-burst. | About 2.4× less noise (1.3 stops) when it matters, without slowing daylight shots. | Settings → Multi-frame merge; `CameraViewModel.MERGE_FRAMES`, `CameraMath.isLowLight`. |
| M2 | Merging runs on the CPU, estimated at 0.3 to 0.6 s per frame on the S25 Ultra (0.4 s measured on a 4-core desktop). **Not yet measured on the phone**; the merge time shows in Settings → Last photo. | Easier to test than a GPU version; it can move to the GPU later if too slow. | — |
| M3 | Moving things are not merged: where a frame differs from the first one by more than noise explains, the first frame is used there. | Avoids ghosts of people and cars. | `Merge.Z_FULL`, `Merge.Z_FADE`. |
| M4 | No merge while the phone is warm (thermal status moderate or worse). | Spec section 11. | — |
| M5 | Up to 3 photos can wait to be developed; the camera is free as soon as a frame is copied. A foreground service ("Developing photos" notification) keeps them going if you leave the app. | Spec section 11. Notifications may be hidden unless you allow them for Silverpoint. | `Developer.MAX_PENDING`. |
| M6 | Long exposures beyond the lens's maximum (about 1/6 s) are not offered yet. Merging reduces noise but does not add motion blur the way a long exposure would. | A true long-exposure mode needs its own design (summing instead of averaging). | Open an issue if you want it. |

## Output

| # | Decision | Why | To change |
|---|---|---|---|
| U1 | Ultra HDR on by default, with up to 2 stops (4×) of extra highlight brightness, only where the highlight shoulder compressed the SDR photo. | Midtones and the recipe's look stay the same on HDR screens; only clouds, lamps and reflections glow. | Settings → Ultra HDR; `HdrGainMap.HEADROOM`. |
| U2 | Ultra HDR is written by Android's own encoder (`Gainmap` + `Bitmap.compress`), not libultrahdr. **Untested on the phone**: if Samsung Gallery does not show HDR, Settings → Last photo says whether a gain map was made. | No native code needed. | — |
| U3 | EXIF is written by Silverpoint's own small writer instead of ExifInterface. Fields: make, model, software, date and time with time zone, orientation (always upright), ISO, shutter, aperture, focal length, recipe (description and user comment), number of merged frames. No location. | Rewriting an Ultra HDR file with ExifInterface risked losing the gain map. | `ExifFields.build`. |
| U4 | JPEG quality 95, sRGB. | Spec. | `GallerySaver.saveJpeg`. |
| U5 | Version 0.9.x is a feature-complete beta. 1.0.0 follows the device test, its fixes, the rename and the new icon ([RELEASING.md](RELEASING.md)). | A first public release should be tested on a phone. | `app/build.gradle.kts` (`versionName`, `versionCode`). |

## Known risks found in review (not yet seen on the phone)

An independent code review of the untested Android code found ten issues. Nine are fixed in 0.9.1 (camera buffers kept alive while a merge reads them, camera errors fully released, foreground service started at the shutter press and stopped by itself, burst frames paired by order if timestamps differ, lens settings for noise reduction and lens shading sent to the physical lens, timeouts that grow with the exposure, safe close when leaving, lens switching that waits for the new viewfinder, less merge memory). One is left:

| # | Risk | What you would see | Workaround |
|---|---|---|---|
| K1 | With the plain (non-recipe) preview, leaving the camera screen during a long merge can briefly leave the camera drawing into a closed viewfinder. | A "Camera error" message when coming back; reopening the screen fixes it. | Keep the live recipe preview on (the default), which does not have this problem. |

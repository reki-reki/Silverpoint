# Development guide

How the code is laid out, how to build and test it, and the developer tools in `scripts/` and the test folders.

## Code map

```
app/        MainActivity (Camera / Settings / Diagnostics / Intro), CameraViewModel, Developer (queue),
            DevelopService, MergeRunner, Settings, SilverpointApp, CameraMath (framing, crop), ui/ (CameraScreen,
            CameraControls, Dial, Haptics, LookSelector, LookPicture, Overlays, SettingsScreen, IntroScreen,
            DiagnosticsScreen)
camera/     Lens (Lens.all), CameraController (Controls, Focus, Flash, prepareFlash, bursts, previewState), PhysicalKeys,
            ExposureMath, probe code (ProbeRunner, ProbeV2, DiagnosticsRun, LensLabel, DngWriter)
pipeline/   RawMetadata, AndroidRawMetadata, ColorMath (DNG colour, Kelvin WB), ToneCurve, BaseLook,
            Look (RecipeLook, LookLut, HueBands), LookThumbnails, LocalContrast, Grain, Merge, HdrGainMap,
            CpuDeveloper (reference), GpuDeveloper, GpuSelfTest, PreviewRenderer, ShaderSource
            assets/shaders: common.glsl, prepare/lowres/blur/develop.comp, preview.vert/.frag, thumb.frag
recipes/    Recipe, RecipeParams, RecipeStore (recipes.json in app/src/main/assets)
storage/    GallerySaver, JpegExif (EXIF writer), ExifFields, DngSaver
docs/       ARCHITECTURE, DESIGN_DECISIONS, RECIPE_ENGINE, LIGHTROOM_MATCHING, RECIPES, PROBE_RESULTS,
            DEVICE_TESTING, RELEASING, screenshots/, proposals/, brand/, reference/ (probe JSONs, presets)
scripts/    validate_recipes.py, check_shaders.py, make_fixture.py, match_samples.py, gpu_parity.py,
            make_look_sample.py, make_logo.py (dev only)
motion/     the start-up film, made with Remotion (React); rendered into app/src/main/res/raw/intro_motion.mp4
```

## The one rule: CPU and GPU stay identical

Every GPU stage is mirrored by `CpuDeveloper` and the Kotlin helpers: the same order of operations, explicit `a + (b - a) * t` interpolation, and the same hash for grain. Change both together.

The on-device self-test (Settings → Diagnostics) catches any drift. Before a phone sees a shader change, run the same comparison on a desktop OpenGL (Mesa's llvmpipe):

```bash
SILVERPOINT_GPU_PARITY=<dir> ./gradlew :pipeline:testDebugUnitTest --rerun --tests '*GpuParityDumpTest*'
python3 -I scripts/gpu_parity.py <dir>   # needs: pip install moderngl; apt-get install libegl-mesa0 libgl1-mesa-dri
```

Shader comments must be plain ASCII: strict GLSL compilers reject anything else.

## Build and test

- **Toolchain:** JDK 17+, Android SDK platform `android-37.0` and build-tools `36.0.0`; AGP 9.4.1 (built-in Kotlin), Gradle 9.6.1, Kotlin 2.4.21, Compose BOM 2026.09.00; `compileSdk 37`, `targetSdk 36`, `minSdk 34`.
- **Everything CI runs:** `./gradlew lint testDebugUnitTest assembleDebug`.
- **Shaders:** `python3 -I scripts/check_shaders.py` (needs `glslang-tools`).
- **Recipes:** `python3 -I scripts/validate_recipes.py`.
- **Release APK:** `./gradlew assembleRelease` (R8-shrunk; signed only when the keystore variables are set, see [RELEASING.md](RELEASING.md)).
- **CI** (`.github/workflows/build.yml`) has three jobs: validate recipes, validate shaders, and lint + tests + debug APK.
- **Lint:** the remaining warnings are accepted:
  - app: `DataExtractionRules`, `DiscouragedApi`, `LockedOrientationActivity`, `OldTargetApi`, `AndroidGradlePluginVersion`;
  - camera: `ExifInterface`.

  Anything new should be fixed.

## Dev-only tools

None of these run in CI. Each is switched on by an environment variable.

| Tool | What it does | How to run it |
|---|---|---|
| `ScreenshotTest` | Renders every screen on the JVM (current set in `docs/screenshots/`) (Robolectric + Roborazzi) at the S25 Ultra's size, with its system bars drawn in. | `SILVERPOINT_SCREENSHOTS=<dir> ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'` |
| `MotionTest` | Stops the animation clock, taps a control and saves a frame every 40 ms. | Same, with `'*MotionTest*'`; frames go to `<dir>/motion/` |
| `LightroomFitTest`, `PreviewRenderTest` | Fit or check the base look against a Lightroom export, and draw every recipe next to Lightroom. | `SILVERPOINT_FULL_FIXTURE=<dir>`, a folder made by `scripts/make_fixture.py <dng> <lightroom.jpg> <dir> --full` |
| `RecipeMatchFitTest` with `scripts/match_samples.py` | Fit a recipe's `match` correction from photo pairs. | See [LIGHTROOM_MATCHING.md](LIGHTROOM_MATCHING.md) and [RECIPE_ENGINE.md](RECIPE_ENGINE.md#matching-lightroom) |
| `ViewfinderFixtureTest` | Develops a whole test frame with no look and shrinks it to the screenshot tests' viewfinder picture (`app/src/test/resources/screens/sofa.png`). | `SILVERPOINT_FULL_FIXTURE=<dir>`, a folder made by `scripts/make_fixture.py <dng> - <dir> --full` (`-`: no Lightroom export) |
| `scripts/make_look_sample.py` | Regenerates the drawn sample picture behind the still look previews. | `python3 scripts/make_look_sample.py app/src/main/assets/look_sample.jpg` |

The screenshot and motion tests need Robolectric's Android jar. If Maven Central is unreachable, set `ROBOLECTRIC_REPO_URL` to a mirror, for example `https://maven-central.storage-download.googleapis.com/maven2`.

## Start-up film (Remotion)

The first-launch film is a [Remotion](https://www.remotion.dev/) project in `motion/`. Grains swirl into Silverpoint's mark, and its centre ignites with a flash, a light streak and a shockwave. The film is a 1080 × 1080, 60 fps, 3 s H.264 video with no audio (about 750 KB). `IntroScreen` plays it centred on black.

Its last frame is the mark with its outermost grains at `MARK_FRACTION` (0.2) of the film's width, held still from frame 160. `ui/SilverpointMark.kt` draws the same mark natively at the same place, then glides it into the first tutorial page, so the handover has no visible seam. If you change the mark, change all three copies of its geometry together:
- `motion/src/Mark.ts`;
- `ui/SilverpointMark.kt`;
- `scripts/make_logo.py`.

```bash
cd motion && npm install
npm run studio                                   # edit with a live preview
REMOTION_BROWSER=<path to Chromium> npm run render   # writes app/src/main/res/raw/intro_motion.mp4
```

`REMOTION_BROWSER` is optional; without it, Remotion downloads its own headless Chrome. `MotionTest.introReveal` saves the native half of the handover frame by frame.

**Licence:** Remotion has its own licence. It is free for individuals and for companies of up to three people; larger companies need a company licence (see [remotion.dev/license](https://www.remotion.dev/license)). Only the rendered video ships in the app; Remotion itself does not.

## Hardware facts behind the design (Galaxy S25 Ultra, from the probes)

- **Lenses:** logical camera `0` with physical lenses:
  - `2`: ultrawide 0.6×, no OIS;
  - `5`: main, 6.3 mm f/1.7, 22.3 mm equivalent;
  - `6`: tele, 2.7× by focal length, 4000 × 3000;
  - `7`: tele 5×.
- **RAW:** 10-bit data in 16-bit samples, white level 1023, black level 64. The colour filter order is GBRG on the main lens and GRBG on the others.
- **Calibration:** two illuminants (D65 = 21, A = 17) with colour, forward and calibration matrices, plus a noise profile, a 17 × 13 lens-shading map and a dynamic black level per frame.
- **Exposure:** AE compensation runs −20 to +20 in 1/10 EV steps. On the main lens, ISO is 12–3200 and exposure about 83 µs–176 ms through the logical camera.
- **OIS** works only as a physical-camera key (lenses 5, 6, 7).
- **Preview + RAW** works on every lens with a 1440 × 1080 preview.
- **Lens switch:** about 0.5 s to the new session, 0.6 s to the first frame, and about 1 s until auto-exposure and white balance settle.
- **Bursts:** 12 RAW frames held at once without drops (30–60 fps).
- **GPU self-test** on the Adreno 830: maximum difference 1/255, 10–20 ms for 320 × 240.

Full probe results: [PROBE_RESULTS.md](PROBE_RESULTS.md).

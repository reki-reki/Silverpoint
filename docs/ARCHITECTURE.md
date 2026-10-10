# Architecture

How Silverpoint is built: the main technical choices, the data flow from sensor to Gallery, and the GPU pipeline. Smaller defaults are in [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md); the code map and tooling are in [DEVELOPMENT.md](DEVELOPMENT.md).

## Main technical choices

| Topic | Decision | Why |
|---|---|---|
| GPU API | OpenGL ES 3.1 compute, not Vulkan | Much simpler to write and debug without direct device access. Shaders stay portable to Vulkan later. |
| GPU host code | Kotlin (`GLES31`/`EGL14`), no NDK | Shaders do the work either way; Kotlin host code avoids a JNI layer and keeps CPU-side maths testable on the JVM. |
| Ultra HDR | Android's own `Gainmap` API (Android 14+) and `Bitmap.compress`, no libultrahdr | The platform encodes the gain map into the JPEG; no native code needed. EXIF is spliced in by Silverpoint's own writer so the gain map index stays valid. |
| Settings | SharedPreferences exposed as Compose state, not DataStore | A handful of switches; no extra dependency. |
| RAW decoding | No LibRaw in v1 | Camera2 delivers raw pixels and metadata directly. We write the demosaic. Avoids a library licence question. |
| Resolution | 12.5 MP RAW (4080x3060) | Confirmed on the S25 Ultra: no max-resolution mode is exposed. 50/200 MP are not offered. |
| Lenses | Physical IDs inside logical camera 0 | All four rear lenses returned RAW through a physical-bound output. Main first; ultrawide, 3x, 5x in Phase 3. The zoom ratio does not switch lenses for RAW, so a lens change recreates the session with another physical ID (camera stays open). |
| Per-lens settings | OIS, AE mode and manual sensor values are sent as physical-camera keys | The logical request alone is ignored by the physical lens for these (probe v2). |
| Multi-frame merge buffer | 6 frames per merged photo (reader holds up to 10) | Probe v2 held 12 frames at once without drops; 6 keeps merge time near 2 to 3 s. |
| Exposure compensation | Dial limited to ±2 EV | The phone's AE compensation range is -20..+20 in 1/10 EV. |
| Pro mode | No hybrid AE, no Kelvin WB mode on this phone | Shutter/ISO priority emulated from the AE-metered exposure; white balance applied in the pipeline. |
| CFA order | Read per lens | Main is GBRG, the other lenses GRBG. |
| DNG | Hidden debug option, off by default | Needed for comparing against Lightroom during tuning, not a user feature. |
| Licence | Apache-2.0 | Compatible with the planned dependencies. |
| Build toolchain | AGP 9.4.1, Gradle 9.6.1, Kotlin 2.4.21, JDK 17+, `compileSdk 37`, `targetSdk 36`, `minSdk 34` | Latest stable on 2026-10-09. `compileSdk` is 37 only because current AndroidX requires it; `targetSdk` stays at 36 so Android 17 behaviour changes are adopted deliberately. |
| v1 scope | Includes Pro mode, multi-frame merge, Ultra HDR | Built in the phase order below so each step can be tested. |

## Data flow

```
Compose UI ──> CameraViewModel ──> CameraController ──> Camera2 session (lens's physical ID)
                    │                                     ├─ preview stream ─> SurfaceTexture ─> PreviewRenderer
                    │                                     │                     (recipe preview table, grain, vignette,
                    │                                     │                      zebras, peaking, histogram) ─> viewfinder
                    │                                     └─ RAW_SENSOR ImageReader (up to 10 frames)
                    │
                    ├─ single shot: copy RAW, release buffer ──────────────┐
                    └─ low light: burst of 6 ─> Merge (CPU, align + merge) ─┤
                                                                            v
Developer queue (Application scope, foreground service while busy, up to 3 waiting)
   GpuDeveloper (GL ES 3.1 compute; CpuDeveloper if the GPU fails):
   prepare.comp   black level, lens shading, white balance
   lowres.comp    quarter-resolution luminance (Clarity/Texture, Ultra HDR gain map)
   blur.comp      small and large Gaussian of that luminance
   develop.comp   demosaic, colour matrix, exposure, saturation, shoulder, sRGB,
                  Clarity/Texture, recipe look table, vignette, grain, rotation
   ─> Bitmap (+ Gainmap) ─> JPEG ─> EXIF spliced in ─> MediaStore (DCIM/Silverpoint, IS_PENDING)
```

## Modules

| Module | Responsibility |
|---|---|
| `app` | Compose UI (camera, recipe picker, Pro controls, settings, introduction, Diagnostics), `CameraViewModel`, `Developer` queue and `DevelopService`, `Settings` |
| `camera` | `Lens` list, `CameraController` (preview + RAW bound to the lens's physical ID, Pro controls, bursts), `PhysicalKeys`, `ExposureMath`, capability probe |
| `pipeline` | RAW metadata, colour maths (DNG matrices, Kelvin white balance), tone curves, recipe look tables, local contrast, grain, merge, gain map, CPU reference developer, GPU developer, live preview renderer |
| `recipes` | `recipes.json` parser and parameter model |
| `storage` | MediaStore writes to `DCIM/Silverpoint` with `IS_PENDING`, EXIF writer that keeps Ultra HDR intact, optional DNG |

## Design rules

- **Probe before building.** Phase 0 output decides which options the UI offers. Unsupported options are hidden or disabled, never left to fail at capture time.
- **Read hardware limits at runtime** from `CameraCharacteristics`. Nothing is hard-coded to the S25 Ultra's published specs.
- **One LUT, two uses.** Everything except grain, clarity and vignette is baked into a 33x33x33 LUT that drives both preview and final render.
- **Whole-frame textures at 12.5 MP.** Tiles of 1024 px were planned for 200 MP; at 12.5 MP the GPU holds the frame (about 250 MB of textures) and one 50 MB `Bitmap` is read back. Tiling returns if larger RAW sizes ever become available.
- **No UI-thread work** on camera or processing. Camera callbacks use a dedicated `HandlerThread`.
- **Never leave half-written files.** Writes use `IS_PENDING`; failures delete the pending row.
- **Test shaders against CPU references** so image-math bugs show up in unit tests rather than only on the phone.

## Base look

Every photo is first given a neutral rendering, then its recipe. The recipes were designed as Lightroom presets, so the neutral rendering is fitted to **Lightroom's default** (no edits) rather than to Samsung's own processing. For these DNGs Lightroom's default profile is Adobe Standard (recorded in the XMP of the October 2026 exports; the earliest exports carry no XMP). The recipe presets do not set a profile, so they apply on top of that same default.

- `BaseLook` holds four numbers: baseline exposure (+1.0 EV), highlight shoulder knee (0.1 linear), base S-curve (0.5) and chroma (0.96). They feed the same per-pixel steps as the recipe: exposure gain, shoulder, encoded-space tone LUT and OKLab chroma, so the GPU shader needed only one new uniform (`uKnee`).
- Fitted by `LightroomFitTest` on one S25 Ultra DNG (bright daylight) and Lightroom's export of it, over about 110,000 blocks: mean ΔE2000 11.7 → 3.2, median brightness 0.55x → 1.02x Lightroom. No Adobe data is copied; only the rendered result is matched.
- Checked on a second DNG + Lightroom pair (same view, later capture): the committed values score ΔE2000 3.31 at 1.005x Lightroom's median brightness, and an independent fit on that pair lands on almost the same numbers (+0.99 EV, knee 0.14, contrast 0.50, chroma 0.96).
- Checked in three more scenes (Diagnostics runs on 0.1.2, main lens, Lightroom for Android exports at full size), with the committed values and no refit:

  | Scene | Lightroom as-shot WB | ISO, shutter | Mean ΔE2000 | Median brightness vs Lightroom | Own best fit (EV, knee, contrast, chroma) |
  |---|---|---|---|---|---|
  | Outdoor, daylight city | 5300 K, tint +12 | 81, 1/7692 s | 3.30 | 0.98x | 1.01, 0.13, 0.49, 0.98 → 3.22 |
  | Indoor, daylight room | 5900 K, tint +10 | 162, 1/100 s | 2.23 | 0.96x | 0.94, 0.23, 0.50, 0.90 → 2.03 |
  | Indoor, low light, warm | 4500 K, tint +9 | 394, 1/33 s | 3.42 | 0.93x | 0.99, 0.49, 0.47, 0.79 → 2.84 |

  One set of values works across these scenes; per-scene refits gain at most 0.6 ΔE. The low-light scene is about 0.1 EV darker than Lightroom and its oranges about 3 chroma units too saturated, with neutrals slightly warm. Tungsten, shade and dusk are still unverified.
- Remaining differences are per-hue: greens, yellows and (in warm light) oranges a little more saturated than Lightroom, darker than Lightroom by 2-4 L* in reds, oranges and yellows outdoors, sky slightly more violet. A per-hue base adjustment can absorb these once the Phase 2 hue-band engine exists.
- `LightroomMatchTest` keeps this in place in CI using two committed crops of the DNG and the export.
- Not yet done: lens distortion correction (the DNG carries it as a WarpRectilinear opcode and Lightroom applies it). It matters mostly for the ultrawide (Phase 3).

## Multi-frame merge

Based on the ideas in the HDR+ paper (Hasinoff et al., 2016), implemented from the paper (`Merge.kt`); no code is copied from other projects.

1. **Reference:** the first frame of the burst (closest to the shutter press).
2. **Grey image:** each 2×2 CFA quad becomes one grey pixel, so every alignment offset is a whole quad and the colour pattern stays in phase.
3. **Alignment:** a 4-level pyramid. A global shift (±8 px) on the coarsest level, then per tile (16 grey pixels = 32 sensor pixels) refined by ±2 px on each finer level, using the sum of absolute differences.
4. **Merge:** per grey pixel, the mean difference over 3×3 pixels is compared with the noise expected from `SENSOR_NOISE_PROFILE`. Within noise, the frame counts fully; beyond about 2.8 standard deviations, not at all (no ghosts from movement).
5. **Output:** the average, written with 16× the original precision (black and white levels scaled to match), then developed like a single frame.

Tested on synthetic bursts: alignment recovers known shifts, noise drops by more than 2.2× over 8 frames, and a moving object leaves no ghost.

## Ultra HDR

The SDR photo squeezes highlights into the shoulder. The gain map (`HdrGainMap`) stores, per quarter-resolution block, how much that squeeze took away (up to 4×, 2 stops), so HDR screens can restore it while midtones and the recipe's look stay as they are. Android's `Gainmap` API writes it into the JPEG. Silverpoint then inserts its EXIF right after the start marker and corrects the primary image's size in the MPF index, so the gain map stays findable.

## Performance targets (to be measured, not guaranteed)

- 12.5 MP single frame developed in under 1 s (the GPU self-test runs a 320×240 frame in 10 to 20 ms on the S25 Ultra).
- Merged burst of 6 in about 2 to 3 s (estimate from desktop timing; Settings → Last photo shows the real time).
- Viewfinder stays responsive while photos develop; at most 3 waiting; no merging under `THERMAL_STATUS_MODERATE` or worse.

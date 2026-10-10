# Third-party notices

Silverpoint is licensed under Apache-2.0 (see [LICENSE](LICENSE)). These are the third-party components it ships with or uses to build and test. The build should not ship a dependency that is missing here.

## Shipped in the app

| Component | Purpose | License |
|---|---|---|
| AndroidX (core, activity, lifecycle, ExifInterface) and Jetpack Compose (UI, Material 3) | UI, EXIF handling for saved photos | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Language runtime, background work, reading recipes.json | Apache-2.0 |

JPEG and Ultra HDR encoding use Android's own `Bitmap.compress` and `Gainmap` APIs; the app has no native libraries.

## Tests and builds only (not shipped)

| Component | Purpose | License |
|---|---|---|
| JUnit 4 | Unit tests | EPL-1.0 |
| Robolectric | Android on the JVM for screenshot and motion tests | MIT |
| Roborazzi, Compose UI test | Screenshot capture, UI test rules | Apache-2.0 |
| Android Gradle Plugin, Gradle wrapper | Build | Apache-2.0 |
| Remotion, React (`motion/`) | Making the start-up film; only the rendered video ships | Remotion License (free for individuals and companies of up to 3 people) / MIT |

## Development-only scripts (not shipped)

- numpy, Pillow and OpenCV, used by the scripts in `scripts/` to make test fixtures, fit recipes and draw the look-preview sample; moderngl for the GPU parity check.

## Not used, on purpose

- **LibRaw** (LGPL-2.1 or CDDL-1.0): not needed while Silverpoint takes raw pixels and metadata straight from Camera2. If DNG import is added later, it would be used under the CDDL option and listed here.
- **GPL-licensed camera apps (for example Open Camera)**: studied for behaviour only. No code is copied from them, so the project stays Apache-2.0.

## Reference material (no code included)

- Recipe looks are inspired by community film-simulation recipes from [Fuji X Weekly](https://fujixweekly.com/). See [docs/RECIPES.md](docs/RECIPES.md).
- The multi-frame merge follows the ideas in the HDR+ paper (Hasinoff et al., SIGGRAPH Asia 2016). The algorithm is re-implemented from the paper.

# Probe results

Results of the Silverpoint capability probe on real phones. The raw reports live in [`docs/reference/probe/`](reference/probe/). To add your phone, run the probe (see the [README](../README.md#diagnostics)) and open a *Device report* issue.

## Galaxy S25 Ultra (SM-S938B)

- Report: [`silverpoint_probe_SM_S938B_20261009.json`](reference/probe/silverpoint_probe_SM_S938B_20261009.json) (probe 0.0.1)
- Run on 2026-10-09. Snapdragon 8 Elite (QTI SM8750), Android 16 (API 36), build `BP4A.251205.006.S938BXXSCCZH1`, security patch 2026-08-05. Samsung's `SEM_PLATFORM_INT` reads 170500, which I take to mean One UI 8.5 (my inference).
- Verdict: **GO (baseline). RAW works up to 12.5 MP. 50 and 200 MP are not available to this app.** 8 of 8 RAW captures succeeded (330 to 520 ms each).

| Question | Answer |
|---|---|
| Capabilities include `RAW` and `MANUAL_SENSOR`? | Yes (logical camera 0 is hardware LEVEL_3; also `BURST_CAPTURE`, `READ_SENSOR_SETTINGS`, `LOGICAL_MULTI_CAMERA`) |
| `ULTRA_HIGH_RESOLUTION_SENSOR` capability / `SENSOR_PIXEL_MODE` key? | No / No |
| Largest RAW in the default stream map | 4080x3060 (12.5 MP) on the logical camera and the main, ultrawide and 5x lenses; 4000x3000 (12.0 MP) on the 3x |
| RAW sizes in the maximum-resolution map | None (the ultrawide and 5x list an empty RAW max-res map) |
| Physical cameras behind logical camera 0 | `2` ultrawide, `5` main, `6` 3x, `7` 5x |
| RAW capture works on each physical ID? | Yes, all four, by binding a RAW output to the physical ID inside logical camera 0 |
| Cameras you can open directly | `0` (logical), `1` (front), `2` (ultrawide), `3` (front 2). Main, 3x and 5x are only reachable through `0` |
| Hybrid auto-exposure (Android 16)? | No (`aePriorityModes` is OFF only) |
| Kelvin white-balance mode (Android 16)? | No (`colorTemperatureRange` is absent) |
| Optical stabilisation | Logical camera: OFF only. Physical main, 3x, 5x: OFF or ON. Ultrawide: none. Whether OIS works through the logical route is untested |
| Exposure compensation | -20 to +20 in 1/10 EV steps (±2 EV) |

### Per lens

| Lens (physical ID) | RAW size | Focal length | Aperture | Sensor (reported) | CFA | ISO range | Exposure range | Min focus |
|---|---|---|---|---|---|---|---|---|
| Ultrawide (2) | 4080x3060 | 2.2 mm | f/1.9 | 5.712 x 4.284 mm | GRBG | 19 - 3200 | 83 us - 176 ms | 20 dpt |
| Main (5) | 4080x3060 | 6.3 mm | f/1.7 | 9.792 x 7.344 mm | GBRG | 12 - 3200 | 43 us - 255 ms | 10 dpt |
| 3x (6) | 4000x3000 | 7.9 mm | f/2.4 | 4.48 x 3.36 mm | GRBG | 50 - 3200 | 54 us - 350 ms | 2.5 dpt |
| 5x (7) | 4080x3060 | 18.6 mm | f/3.4 | 5.712 x 4.284 mm | GRBG | 28 - 3200 | 48 us - 274 ms | 1.25 dpt |

All lenses: 10-bit samples in 16-bit words, white level 1023, black level 64, no row padding. Noise profile, lens shading map, dynamic black level, neutral colour point, `SENSOR_COLOR_TRANSFORM1` and `SENSOR_FORWARD_MATRIX1` are present. The main sensor's 4080x3060 is the 200 MP sensor (16320x12240) binned 16-to-1, and the 50 MP lenses are binned 4-to-1 (my arithmetic, consistent with the reported sizes).

### Probe v2 (same phone, same build)

- Report: [`silverpoint_probe_v2_SM_S938B_20261009.json`](reference/probe/silverpoint_probe_v2_SM_S938B_20261009.json) (probe 0.0.2, `probeVersion` 2), run 2026-10-09.

| Question | Answer |
|---|---|
| OIS through the logical camera | Works when requested per lens (`setPhysicalCameraKey`): main, 3x and 5x report ON in their physical results. The ultrawide has no OIS. The logical result always says ON, so only the physical result is meaningful |
| Live preview plus RAW on one lens | Works on every route (camera 0 alone and physical 2, 5, 6, 7) with a 1440x1080 preview. Auto-exposure and white balance settle in 0.5 to 0.7 s |
| Does the zoom ratio switch lenses? | No. At 0.6x, 1x, 2x, 3x, 5x and 10x the RAW still came from the main lens (physical 5) at 4080x3060. Lenses must be switched by reconfiguring the session with another physical ID |
| RAW bursts | 4, 8 and 12 frames all arrived, 33 ms apart (30 fps), with all 12 (about 300 MB) held open at once |
| Manual ISO and shutter | Not honoured: requests for ISO 100 / 20 ms and ISO 1600 / 4 ms came back as the auto-exposure values (ISO 204 / 10 ms). Probable cause (my inference): auto-exposure was switched off only on the logical request, not per lens. Re-tested in the Phase 1 Diagnostics screen |
| DNG files | One per route written with `DngCreator`. Checked by eye: colours correct, images rotated 90° because no orientation was set |
| Colour calibration | Every lens has two illuminants, 21 (D65) and 17 (Standard A), with `COLOR_TRANSFORM1/2`, `FORWARD_MATRIX1/2` and `CALIBRATION_TRANSFORM1` |
| Per-shot metadata (main lens) | As-shot neutral about [0.49, 1, 0.75]; lens shading map 17x13 per channel; dynamic black level 64, white level 1023; noise profile S = 2.2e-4, O = 0 |
| Request pipeline | Max pipeline depth 8, 2 partial results per frame, sensor timestamps on the REALTIME clock |

Still open: whether the manual-exposure fix works, how long a lens switch takes, and bursts longer than 12 frames.

### Diagnostics with 0.1.0 (probe version 3)

- Report: [`silverpoint_probe_v3_SM_S938B_20261009.json`](reference/probe/silverpoint_probe_v3_SM_S938B_20261009.json), run 2026-10-09.

| Question | Answer |
|---|---|
| Manual ISO and shutter (fix: AE mode sent per lens) | Honoured: ISO 100 / 20 ms came back exactly; ISO 1600 / 4 ms came back as ISO 1592 / 4 ms |
| Lens switch, main (5) to 5x (7), camera kept open | New session in 496 ms, first preview frame at 625 ms, auto-exposure and white balance settled at 958 ms |
| RAW bursts with manual exposure | 4, 8 and 12 frames all arrived, 16 ms apart (about 60 fps at a 1/8000 s exposure) |
| GPU self-test (GPU developer vs CPU reference) | PASS on Adreno 830, OpenGL ES 3.2: largest difference 1/255 in all three cases, 11-19 ms per 320x240 frame |
| DNG files | Now saved upright (orientation tag set) |
| Re-run with 0.1.1 (fitted base look) | Same results; GPU self-test still PASS (max difference 1/255) with the new shoulder knee uniform. Photo of the same view: median brightness 0.84x Lightroom's with Reportage applied (was 0.47x with 0.1.0) |

Frames in the first run were taken without auto-exposure warm-up, so pixel statistics (mean about 130 to 230 on a 64 to 1023 scale) only show the sensors are alive; they are not exposure data.

Decision rule from the spec: if main-lens RAW capture works at any size, the project goes ahead. It does.

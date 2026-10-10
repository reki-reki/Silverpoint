# Contributing to Silverpoint

Thanks for helping out. Silverpoint is a beta, so the most valuable contributions right now are **device reports** and **bug reports**. [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) explains the code layout, the tests and the developer tools.

## Device reports (most wanted)

Camera2 behaviour varies by phone and by firmware. If you have a Galaxy S25 Ultra or another Samsung Ultra, run Settings → Diagnostics and open a *Device report* issue with the exported report. Please include your One UI version and build number. Also attach one JPEG taken with Silverpoint and the DNG of the same shot (Settings → Also save the DNG). New phones are enabled one at a time, after their reports show RAW, colour and the GPU self-test working. [docs/DEVICE_TESTING.md](docs/DEVICE_TESTING.md) is a 30-minute walk through the whole app.

## Reporting bugs

Use the *Bug report* issue template. Include the phone model, One UI version, the app version (or commit), what you did, and what happened. Attach the Diagnostics report, a screenshot or a screen recording if relevant.

## Proposing changes

1. Open an issue first for anything bigger than a small fix, so we can agree on the approach.
2. Fork the repo and create a branch from `main`: `feature/short-name` or `fix/short-name`.
3. Keep pull requests focused: one change per PR.
4. Run `./gradlew lint testDebugUnitTest assembleDebug`, plus `python3 -I scripts/validate_recipes.py` if you touched recipes and `python3 -I scripts/check_shaders.py` if you touched shaders. CI runs the same checks. A shader change must be mirrored in `CpuDeveloper` (see [DEVELOPMENT.md](docs/DEVELOPMENT.md#the-one-rule-cpu-and-gpu-stay-identical)).
5. Fill in the pull request template.

## Code style

- Kotlin: follow the official Kotlin style guide; `.editorconfig` sets indentation and line endings.
- Shaders: one stage per file under `pipeline/src/main/assets/shaders/`, with a comment header stating inputs, outputs and units (linear or gamma encoded). Comments in plain ASCII.
- Commit messages: imperative mood, a short summary line, details in the body when needed.

## Licensing of contributions

By submitting a contribution you agree it is licensed under the [Apache License 2.0](LICENSE), as described in section 5 of that license. **Do not submit code copied from GPL or other incompatible projects** (for example Open Camera). Describe the idea and write it yourself.

## Recipes

Recipe parameters live in `app/src/main/assets/recipes.json`. A new recipe needs a name, `look` and `best_for` text, a `set`/`group`, and the full parameter block. Please say what it is inspired by. Do not paste in proprietary colour data.

## Conduct

All interaction is covered by the [Code of Conduct](CODE_OF_CONDUCT.md).

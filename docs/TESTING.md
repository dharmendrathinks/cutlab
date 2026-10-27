# Testing and validation

## Fast checks

```sh
./scripts/check.sh
```

This checks repository hygiene and local Markdown links, runs JVM tests, builds the debug APK, compiles Android tests, and runs Android lint. CI runs the same command on Ubuntu with JDK 21. Reports are under `core/build/reports/tests/` and `app/build/reports/`.

## Android instrumentation

Use a disposable emulator or a device with backed-up projects. Enable USB debugging for a physical device. Connect exactly one device, or set `ANDROID_SERIAL` when using the helper:

```sh
adb devices
./scripts/device-check.sh
```

The script installs the app and test APK, executes the suite, checks the instrumentation result, and saves logs/diagnostic outputs in ignored `artifacts/`. Alternatively, `./scripts/gradle.sh :app:connectedDebugAndroidTest` creates standard Gradle HTML/XML reports. Tests create synthetic projects and clean up their fixtures; interrupted runs may leave test projects.

The manual **Android device tests** GitHub workflow uses API 35, x86_64, a Pixel 7 profile, and software rendering. It is an additional environment, distinct from the historical arm64 run. Its result must be inspected independently. UI tests assume an English, portrait phone layout and need an unlocked, responsive display. The workflow also runs independent FFmpeg A/V pulse verification.

| Layer | Coverage |
| --- | --- |
| JVM | Timing, speed mapping, edits, history, serialization, checkpoint recovery |
| Android storage | Media ownership, archive round-trips, traversal/size rejection |
| Android engine | Actual codec/GL output, A/V timing, masks/effects, rotation, seeking, cancellation |
| Android UI | Real gestures, trim/reorder, text, undo, cover, persistence, export cancel/retry |
| Independent decode | FFmpeg audio pulse timing and optional reference-image comparisons |

```sh
python3 scripts/check-av-sync.py artifacts/engine-av-sync.mp4 --output artifacts/ffmpeg-av-sync.json
```

The optional reference comparison scripts require separately captured reference exports/screenshots; these are not distributed and are not required to build or test CutLab.

## Fixture provenance

`app/src/androidTest/assets/reference.mp4` is a synthetic three-second test clip with generated color bars and audio, not a CapCut asset or downloaded user video. SHA-256: `d3a2f9b4a074472428dc05fb1313ae93855bf757197f074e046f818894be2c84`. It is included under the project license. The tests create other images and timed audio/video fixtures programmatically. FFmpeg is only an external analysis tool and is not bundled in the app.

## Recorded results and open gates

The [0.4 validation record](../evidence/completion-validation.json) reports 18 JVM passes, 29 Android passes, one unsupported-encoder skip, and a later three-test UI pass on Android 15/API 35 arm64 with SwiftShader. The final UI layout changed after the full run; the three focused checks cover that change. Raw logs/videos remain local; sanitized JSON is historical evidence, not a replacement for rerunning CI.

The 900-frame synthetic preview benchmark measured 12.1 ms median and 43.1 ms p95 frame time. It does not establish sustained 30 fps for arbitrary projects. The old APK hashes identify local debug builds; fresh CI APK hashes and signing identities will differ.

Before claiming device readiness, test physical hardware playback/export, audio sync, mic quality, thermal behavior, long projects, background/restore, low storage, and supported 1080p60 encoders. Minimum API 29 behavior also needs device coverage. Accessibility, tablets, landscape layouts, and broad codec compatibility need further work. No whole-app CapCut parity is claimed.

When reporting results, record commit, device/API/ABI, codec names, input/output format, passed/skipped/failed counts, and actual reproduction steps. Report unsupported encoder tests as skips, never passes.

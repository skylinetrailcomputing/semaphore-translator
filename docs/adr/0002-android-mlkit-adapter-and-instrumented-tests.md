# ADR 0002 — Android adapter (CameraX + ML Kit) & the first instrumented tests

- **Status:** Accepted (2026-06-20)
- **Issue:** [#22](https://github.com/skylinetrailcomputing/semaphore-translator/issues/22) ([3.4], Epic 3)
- **Context refs:** spec §3.1/§3.2, `shared/ADAPTER-CONTRACT.md` §3/§5,
  `shared/native_fixtures/NATIVE-FIXTURES.md`, ADR
  [0001](0001-parity-harness-and-native-project-shape.md) (the iOS/Android
  project shape this builds on), and the iOS counterpart shipped in
  [#21](https://github.com/skylinetrailcomputing/semaphore-translator/issues/21).

## Context

[3.4] is the Android half of the lockstep adapter epic — the mirror of the iOS
work (#21). It adds the only genuinely new logic on the Android side: the
per-platform adapter that turns an ML Kit skeleton into the frozen post-adapter
`Keypoints` frame, the live CameraX→ML Kit capture path that feeds it, and the
two-layer native-fixture verification that pins the adapter's flips. Doing so
pulls in CameraX, ML Kit, and — for the first time in this repo — an
**instrumented** (on-device) test. The decisions below are about keeping that new
surface from leaking into the fast, emulator-free per-commit loop.

## Decision 1 — Bundled ML Kit + CameraX; bump `compileSdk` to 36

- **ML Kit pose, bundled model** (`com.google.mlkit:pose-detection`,
  `STREAM_MODE`). The bundled artifact ships the TFLite model **in the AAR**, so
  it needs no Google Play Services and runs identically on a bare AOSP emulator
  and on a real device. STREAM_MODE matters beyond the live path: it is the only
  mode that populates per-landmark `inFrameLikelihood` (single-image mode leaves
  it `0.0`), which the decoder needs to clear `MIN_KEYPOINT_CONFIDENCE`.
- **CameraX 1.6.1** (`camera-core` / `camera-camera2` / `camera-lifecycle`) for
  the front-camera `ImageAnalysis` pipeline, plus **kotlinx-coroutines** for the
  `Flow<Keypoints>` surface (the Android analogue of iOS's `AsyncStream`).
- **`compileSdk` 35 → 36.** CameraX 1.6.x compiles against API 36. We bump
  `compileSdk` (compile-only) rather than pin an older CameraX — consistent with
  the repo's deliberately-current toolchain posture (AGP 9.2.1 / Gradle 9.5.1,
  ADR 0001). `targetSdk` stays **35**: that governs runtime-behavior opt-in, which
  is orthogonal and unchanged. `minSdk` stays 26.

## Decision 2 — Keep the adapter pure; isolate every ML Kit type in the capture layer

The adapter (`core/MlKitPoseAdapter`) has **zero** ML Kit / Android imports: it
operates on a plain `MlKitSkeleton` value type (six joints in ML Kit's native
image-pixel frame) and does normalize → y-flip → mirror. The `Pose` →
`MlKitSkeleton` extraction — the only code that touches ML Kit pose types — lives
in `capture/MlKitPoseSource.kt`. That split is what lets the Layer-1 regression
run as a **local JVM unit test** (it replays a recorded skeleton through the pure
adapter; no ML Kit, no device), exactly as iOS's pure `VisionPoseAdapter.adapt`
is replayable. The y-flip here is a **real negation** (`y_out = 1 − y/h`) because
ML Kit uses a top-left, y-down origin — the **opposite** of Apple Vision, whose
y-flip is a no-op. That asymmetry is the entire reason the flip is quarantined
per platform and validated against a native fixture rather than assumed
(`ADAPTER-CONTRACT.md` §3a).

The live camera mirror/orientation is **not** unit-tested (no camera in CI), the
same posture as iOS: the `ImageAnalysis` stream is left non-mirrored so the live
buffer matches the observer-perspective fixtures, and the single adapter mirror
is correct for both. Its on-device behavior is a smoke item for [3.5].

## Decision 3 — Two fixture layers; the repo's first instrumented test

Per the #3 grooming (Q2), each platform builds two fixture layers
(`NATIVE-FIXTURES.md` §5):

- **Layer 1 — regression (per-commit, local JVM):** `MlKitAdapterTest` replays a
  recorded ML Kit skeleton (`app/src/test/resources/mlkit_skeletons.json`)
  through the adapter and asserts `invariants.json` + decode. Emulator-free, so
  the Epic-2 fast loop is preserved.
- **Layer 2 — calibration (one-time, instrumented):** `MlKitCalibrationTest` runs
  **live ML Kit** on the canonical `pose_*.png` images and asserts the same
  invariants. ML Kit needs the Android runtime, so this is the **first
  `androidTest` in the repo** — run via `connectedDebugAndroidTest` on a
  device/emulator, **not** a per-commit gate. It also logs the skeleton it
  captured (logcat tag `MlKitCalibration`), which is frozen into the Layer-1
  fixture — the device analogue of iOS's host tool `extract_vision_skeleton.swift`.
  (iOS captures Vision output on the macOS host because body pose does not run on
  the iOS Simulator; ML Kit has no host path, so Android calibrates on a device.)

The recorded skeleton shipped here was captured on a **Pixel 9a (Android 16)**;
ML Kit reported all six upper-body landmarks at ≥0.99 likelihood and the adapted
output satisfied every invariant — empirically confirming the y-flip + mirror +
label-trust on real hardware.

## Implementation notes / consequences

- **`sharedTest` source set.** The native-fixture **invariant DSL** and its Gson
  DTOs are the contract both layers judge against, so they live in
  `app/src/sharedTest/kotlin` and are wired into **both** `test` and `androidTest`
  (`sourceSets { … kotlin.directories.add(…) }`). One evaluator, no drift. The
  JVM-only pieces (the `shared/`-walking `SharedFiles` loader, the decoder
  construction, the parity DTOs) stay in `test/`.
- **Fixtures stay single-source.** The instrumented test reads `invariants.json`
  and the PNGs straight from `shared/native_fixtures`, packaged as `androidTest`
  assets via an extra assets dir (`assets.directories.add(rootProject.file(
  "../shared/native_fixtures"))`) — no copy, no symlink, same rule as ADR 0001
  Decision 2. (Layer-1 still reaches `shared/` on the host via `SharedFiles`.)
- **APK size.** The bundled ML Kit model adds a few MB and some `.so` native libs
  (`libimage_processing_util_jni.so` et al.) — acceptable for an on-device,
  offline pose app; revisit only if app size becomes a constraint.
- **Toolchain to run Layer-2.** A device or an arm64 system image + AVD is now
  needed for the calibration pass. cmdline-tools / a `google_apis;arm64-v8a`
  image is sufficient (bundled ML Kit needs no Play Store). The per-commit loop is
  unchanged: `./gradlew :app:testDebugUnitTest`, still emulator-free.
- **Parity unaffected.** No `shared/*.json` changed, so the cross-platform parity
  harness (#14) and iOS are untouched; Android `ParityTest` stays green.

## How to run

```bash
cd android

# Per-commit (local JVM, no device): parity + Layer-1 adapter regression.
./gradlew :app:testDebugUnitTest

# Layer-2 calibration (one-time, needs a connected device/emulator):
./gradlew :app:connectedDebugAndroidTest
# Re-freeze the Layer-1 fixture from the run:
adb logcat -d -s MlKitCalibration:I   # paste CAPTURED_SKELETON blocks into
                                       # app/src/test/resources/mlkit_skeletons.json
```

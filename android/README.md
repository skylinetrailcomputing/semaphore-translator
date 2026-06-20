# Android — Semaphore Translator

Native Android app (Kotlin / Jetpack Compose; LiteRT classifier lands in a later
epic). This holds the **cross-platform parity harness**
(Issue #14, ADR [0001](../docs/adr/0001-parity-harness-and-native-project-shape.md)),
the **ML Kit pose adapter + CameraX capture path** with its two-layer native
fixtures (Issue #22, ADR [0002](../docs/adr/0002-android-mlkit-adapter-and-instrumented-tests.md)),
and the **live debug screen** — Compose preview + skeleton overlay + per-frame
decode (Issue #23, ADR [0003](../docs/adr/0003-live-debug-preview-and-app-contract-loader.md)).

## Prerequisites

- **Android SDK** with platform `android-36` (CameraX 1.6 compiles against API
  36; `targetSdk`/`minSdk` are unchanged). Point the build at it via either
  `local.properties` (git-ignored) or `$ANDROID_HOME`:
  ```bash
  echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties
  ```
- **JDK 17+** (developed against JDK 25). The Gradle wrapper pins Gradle 9.5.1,
  so you do not need a system Gradle install.
- **A device or emulator** — only for the one-time Layer-2 calibration
  (`connectedDebugAndroidTest`). The per-commit tests need none. Any arm64
  `google_apis` system image works; the bundled ML Kit model needs no Play Store.

## Running the tests

```bash
cd android

# Per-commit — local JVM unit tests, no emulator. Parity harness (#14) + the
# Layer-1 adapter regression (#22, replays a recorded ML Kit skeleton).
./gradlew :app:testDebugUnitTest

# One-time Layer-2 calibration — needs a connected device/emulator. Runs live
# ML Kit on the fixture images and asserts the adapter's flips (#22).
./gradlew :app:connectedDebugAndroidTest
```

The unit-test HTML report lands at
`app/build/reports/tests/testDebugUnitTest/index.html`. To re-freeze the Layer-1
fixture after a calibration run, paste the `CAPTURED_SKELETON` blocks from
`adb logcat -d -s MlKitCalibration:I` into
`app/src/test/resources/mlkit_skeletons.json`.

## Running the live app

```bash
cd android
./gradlew :app:installDebug   # device/emulator with a front camera
```

The screen requests the camera permission, then shows the preview with the
skeleton overlay and the per-frame decoded character. The live mirror/orientation
is an **on-device smoke** (no camera in the JVM tests) — confirm the overlay
tracks and the letter is right; if it looks mirrored/rotated, tune the
preview/overlay display config, **not** the adapter (ADR 0003).

## Toolchain notes

AGP 9 provides **built-in Kotlin compilation**, so there is no separate Kotlin
Gradle plugin. JSON is parsed with **Gson** (reflection, no compiler plugin) in
the **tests**; the shipping app loads its bundled contract with the framework's
`org.json`. The pose path uses **CameraX 1.6** (incl. `camera-view` PreviewView)
+ **ML Kit pose (bundled model)** + **kotlinx-coroutines**; the bundled ML Kit
model needs no Google Play Services. The UI is **Jetpack Compose** — the Compose
compiler plugin is applied on top of AGP's built-in Kotlin and **pinned to that
Kotlin version** (2.2.10; a mismatch fails the build). Versions are pinned in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml). Rationale in ADRs
[0001](../docs/adr/0001-parity-harness-and-native-project-shape.md),
[0002](../docs/adr/0002-android-mlkit-adapter-and-instrumented-tests.md), and
[0003](../docs/adr/0003-live-debug-preview-and-app-contract-loader.md).

## Layout

| Path | What |
|------|------|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml` | Gradle build + version catalog. |
| `app/build.gradle.kts` | The `:app` Android application module. Stages the contract assets (`copySharedContract`) and enables Compose. |
| `app/src/main/kotlin/.../MainActivity.kt` | Compose entry point; hosts `SemaphoreScreen`. |
| `app/src/main/kotlin/.../ui/SemaphoreScreen.kt` | The live debug screen (#23): camera preview + skeleton overlay + per-frame decode + "no signer detected". |
| `app/src/main/kotlin/.../core/SemaphoreDecoder.kt` | The decode logic ported from `shared/tools/gen_test_vectors.py`, decoupled from the wire format (plain-value constructor; no Gson/loader ships in the app). |
| `app/src/main/kotlin/.../core/ContractLoader.kt` | App-side loader (#23): parses the bundled `shared/*.json` assets with `org.json` and builds the `SemaphoreDecoder` — mirrors the test harness's `referenceDecoder()`. |
| `app/src/main/kotlin/.../core/MlKitPoseAdapter.kt` | The **pure** adapter (#22): ML Kit skeleton → frozen `Keypoints` (normalize + y-flip + mirror). No ML Kit / Android imports, so Layer-1 replays it on the JVM. |
| `app/src/main/kotlin/.../capture/MlKitPoseSource.kt` | The ML Kit binding: `Pose` → `MlKitSkeleton`. The only production code that touches ML Kit pose types. |
| `app/src/main/kotlin/.../capture/PoseCaptureSession.kt` | CameraX `ImageAnalysis` (front, KEEP_ONLY_LATEST) → ML Kit → adapter → `Flow<Keypoints>`. Compiled + reviewed, not unit-tested. |
| `app/src/test/kotlin/.../` | `ParityTest.kt` (the harness), `MlKitAdapterTest.kt` (Layer-1 regression), plus `core/Contract.kt` (Gson models), `core/SharedFiles.kt` (the `shared/` loader), and `FixtureSupport.kt` — test-only. |
| `app/src/sharedTest/kotlin/.../` | The native-fixture invariant DSL + DTOs, shared by both the JVM `test` set and the instrumented `androidTest` set. |
| `app/src/androidTest/kotlin/.../MlKitCalibrationTest.kt` | Layer-2: live ML Kit on the fixture PNGs, asserts `invariants.json`. Instrumented (`connectedDebugAndroidTest`); one-time calibration. |
| `app/src/test/resources/mlkit_skeletons.json` | The recorded ML Kit skeleton Layer-1 replays (captured by the Layer-2 run). |

## How the tests reach `shared/`

`SharedFiles` (in `app/src/test/kotlin/.../core/SharedFiles.kt`) walks up from
the JVM working directory until it finds the repo's `shared/test_vectors.json`,
then loads the JSON directly — no copy, no symlink. Both platforms parse the
exact bytes `shared/tools/gen_test_vectors.py` wrote, so they can only disagree
in the decode logic, which is what the harness checks.

The instrumented Layer-2 test runs on a device, so it can't walk the host
filesystem; instead `app/build.gradle.kts` adds `shared/native_fixtures` as an
extra `androidTest` **assets** dir, packaging the canonical PNGs + `invariants.json`
into the test APK — still single-source, no copy.

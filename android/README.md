# Android — Semaphore Translator

Native Android app (Kotlin; Compose / CameraX / ML Kit / LiteRT land in later
epics). Right now this holds the app shell and the **cross-platform parity
harness** (Issue #14) — see [`../docs/adr/0001-parity-harness-and-native-project-shape.md`](../docs/adr/0001-parity-harness-and-native-project-shape.md).

## Prerequisites

- **Android SDK** with platform `android-35`. Point the build at it via either
  `local.properties` (git-ignored) or `$ANDROID_HOME`:
  ```bash
  echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties
  ```
- **JDK 17+** (developed against JDK 25). The Gradle wrapper pins Gradle 9.5.1,
  so you do not need a system Gradle install.

## Running the parity tests

```bash
cd android
./gradlew :app:testDebugUnitTest
```

These are **local JVM unit tests** — no emulator required. The HTML report lands
at `app/build/reports/tests/testDebugUnitTest/index.html`.

## Toolchain notes

AGP 9 provides **built-in Kotlin compilation**, so there is no separate Kotlin
Gradle plugin. JSON is parsed with **Gson** (reflection, no compiler plugin).
Versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
Rationale in the ADR.

## Layout

| Path | What |
|------|------|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml` | Gradle build + version catalog. |
| `app/build.gradle.kts` | The `:app` Android application module. |
| `app/src/main/kotlin/.../MainActivity.kt` | Minimal app shell. |
| `app/src/main/kotlin/.../core/` | `Contract.kt` (Gson models + the `shared/` loader) and `SemaphoreDecoder.kt` (the decode logic ported from `shared/tools/gen_test_vectors.py`). |
| `app/src/test/kotlin/.../ParityTest.kt` | Runs `shared/test_vectors.json` and asserts emitted string + position ids. |

## How the tests reach `shared/`

`SharedFiles` (in `app/src/main/kotlin/.../core/SharedFiles.kt`) walks up from
the JVM working directory until it finds the repo's `shared/test_vectors.json`,
then loads the JSON directly — no copy, no symlink. Both platforms parse the
exact bytes `shared/tools/gen_test_vectors.py` wrote, so they can only disagree
in the decode logic, which is what the harness checks.

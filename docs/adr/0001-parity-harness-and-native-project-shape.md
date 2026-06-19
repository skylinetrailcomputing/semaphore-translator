# ADR 0001 — Parity-harness shape & how native tests reach `shared/`

- **Status:** Accepted (2026-06-19)
- **Issue:** [#14](https://github.com/skylinetrailcomputing/semaphore-translator/issues/14) (Epic 2)
- **Context refs:** spec §6 (parity guarantee), `shared/ADAPTER-CONTRACT.md`,
  `shared/tools/gen_test_vectors.py` (the reference decoder).

## Context

Issue #14 stands up the **first native code in the repo**: a cross-platform
parity harness that runs every vector in `shared/test_vectors.json` through each
platform's feature-extraction → classification → decode path and asserts
byte-for-byte identical results. The decode logic itself is small (a faithful
port of the ~80-line Python reference decoder); the real decisions are
structural and set precedent for the whole project.

Two decisions had to be made and documented.

## Decision 1 — Embed the harness in real app projects (not throwaway libs)

The decode logic is pure (no camera, no platform framework deps), so the harness
*could* have lived in a minimal SwiftPM package + a pure-Kotlin/JVM Gradle
module, runnable with `swift test` / `./gradlew test` and no simulator, emulator,
or Android SDK. We chose instead to stand up the **actual app projects now** and
host the harness inside them:

- **iOS:** an Xcode project (`SemaphoreTranslator.xcodeproj`) with an app target
  and an XCTest unit-test target. Tests run on the iOS Simulator via
  `xcodebuild test`.
- **Android:** a real Android Gradle application module (`:app`, AGP) with the
  parity tests as **local JVM unit tests** (`testDebugUnitTest`) — host JVM, no
  emulator.

**Why:** the next epics build the camera app in exactly these projects anyway.
Front-loading the real Xcode/Gradle structure avoids a later migration and means
the decode logic lands where the app will consume it (the app target / `:app`
module, `@testable import` / same module on the test side). Cost: the harness now
depends on the platform SDKs (a simulator runtime; the Android SDK) instead of
running framework-free.

## Decision 2 — Tests read `shared/*.json` via a relative path (single source)

The harness reaches the three `shared/*.json` files **directly**, by walking up
from a known anchor until it finds the directory containing
`shared/test_vectors.json`:

- **iOS:** anchored on `#filePath` (the test source's compile-time absolute
  path). This is the same mechanism `swift-snapshot-testing` uses to reach host
  files from a simulator-hosted XCTest — verified working here.
- **Android:** anchored on the JVM `user.dir` (the Gradle test working
  directory), robust to whether tests run from `android/app` or the repo root.

Rejected alternatives: **symlink** `shared/` into each platform (breaks on
archive exports / non-Unix checkouts) and **copy as a build resource** (creates
two copies that can silently drift from `shared/`). The relative-path approach
keeps `shared/` the single source of truth — both platforms parse the exact
bytes the Python generator wrote, so a drift between platforms can only come from
the decode logic, which is precisely what the harness is meant to catch.

## Implementation notes / consequences

- **iOS project generation:** the `.xcodeproj` is **generated** from a committed
  `ios/project.yml` via [XcodeGen](https://github.com/yonsm/XcodeGen) and is
  git-ignored. `project.yml` is the reviewable source of truth; contributors run
  `xcodegen generate`. Simulator unit tests need no signing
  (`CODE_SIGNING_ALLOWED=NO`), so the project is team-agnostic.
- **Android build toolchain (bleeding-edge, pinned deliberately):**
  - **AGP 9.2.1** provides **built-in Kotlin compilation** — the standalone
    `org.jetbrains.kotlin.android` plugin is no longer applied (AGP 9 rejects it
    as redundant). The Gradle wrapper is pinned to **9.5.1**; the daemon runs on
    the only installed JDK (**25**) and compiles to JVM 17 bytecode.
  - **JSON via Gson 2.13.2**, not kotlinx.serialization. Reason: kotlinx requires
    its compiler plugin, whose integration under AGP 9's built-in-Kotlin model is
    not yet pinned down here. Gson is reflection-based, needs no compiler plugin,
    and keeps the build free of that uncertainty. Revisit if/when the
    built-in-Kotlin serialization-plugin story is settled.
  - `compileSdk = 35` (the installed platform); `minSdk = 26`.
- **Parity, concretely:** both ports assert against the same fixture file, so
  green on both platforms *is* the parity guarantee. Today: 36 single-pose
  vectors + 4 sequences (40 frames). Re-run after any change to
  `semaphore_alphabet.json` / `semaphore_config.json` (regenerate the fixtures
  first: `uv run shared/tools/gen_test_vectors.py`).
- **Scope:** downstream of the adapter only. The vectors are the adapter's
  *output*, so the harness does not exercise the mirror/y-flip (Epic 3 native
  fixtures) or temporal smoothing/commit timing (Epic 4).

## How to run

```bash
# iOS (from ios/)
xcodegen generate
xcodebuild test -project SemaphoreTranslator.xcodeproj -scheme SemaphoreTranslator \
  -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO

# Android (from android/)
./gradlew :app:testDebugUnitTest
```

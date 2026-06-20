# ADR 0003 — Live debug preview, the app-side contract loader & Compose under AGP 9

- **Status:** Accepted (2026-06-20)
- **Issue:** [#23](https://github.com/skylinetrailcomputing/semaphore-translator/issues/23) ([3.5], Epic 3)
- **Context refs:** spec §3.1/§3.2 (capture/adapter), §4.4 (runtime constants),
  §4.5 (decoder), FR5 / NFR4; ADRs
  [0001](0001-parity-harness-and-native-project-shape.md) (decoder decoupled from
  the wire format; "the app gets its own bundled-asset loader Epic 3+") and
  [0002](0002-android-mlkit-adapter-and-instrumented-tests.md) (the live
  mirror/orientation is a [3.5] on-device smoke); `shared/ADAPTER-CONTRACT.md`.

## Context

[3.5] adds the first real **screen** on both platforms: a live camera preview
with the 6-keypoint skeleton overlaid and the per-frame decoded character. It is
deliberately a *debug* surface — there is no PR-preview deploy for either native
app, so the on-device overlay is the only way a human can confirm the adapter
flips are right on a live signer (autonomy guardrail-d). Standing it up forced
three decisions.

## Decision 1 — The app loads the frozen contract from bundled assets

Until now the contract (`semaphore_alphabet.json` + `semaphore_config.json`) was
parsed **only in the test target**: ADR 0001 kept the decoder decoupled from the
wire format (a plain-value constructor) so the shipping app carried no JSON DTOs
or loader, and the tests reached `shared/` on the host (iOS `#filePath`, Android
JVM `user.dir`). A live app cannot read `../shared/` on a device, so it now needs
its own loader. The config README is explicit — both platforms *load* the
contract; the constants are **never hardcoded** in Swift/Kotlin.

- **Bundle the two JSON files into each app, straight from `shared/`** — no
  committed copy, so `shared/` stays the single source of truth (the ADR 0001
  Decision 2 rule). iOS references `../shared/*.json` as app resources in
  `project.yml`. Android stages just those two files into a generated assets dir
  via a Gradle `Copy` task (`copySharedContract`), wired ahead of `mergeAssets`;
  surgical on purpose, so the PNG fixtures / test vectors / Python tools don't
  land in the APK.
- **Parse with each platform's built-in JSON, no third-party dep.** iOS uses
  `Codable`; Android uses the framework's `org.json` (Gson stays a *test-only*
  dependency, preserving ADR 0001's "no Kotlin compiler-plugin / minimal app
  deps" posture). The app-side `ContractLoader` mirrors the test harness's
  `ReferenceDecoder.make()` / `referenceDecoder()` **exactly**, so the live app
  and the parity fixtures build the decoder one identical way.

## Decision 2 — Enable Jetpack Compose under AGP 9's built-in Kotlin

The Android UI is **Compose** (the documented stack). The open question (flagged
on #23, "same flavor as the kotlinx-serialization uncertainty in ADR 0001") was
whether the standalone Compose compiler plugin even *applies* under AGP 9's
built-in Kotlin, which rejects the standalone `kotlin.android` plugin as
redundant.

**It works.** Applying `org.jetbrains.kotlin.plugin.compose` **pinned to the
built-in Kotlin version (2.2.10 for AGP 9.2.1)** plus `buildFeatures { compose =
true }` compiles cleanly. The version match is load-bearing: the Compose compiler
plugin and the Kotlin compiler must be the same version, so `composeCompiler` in
the version catalog is tied to AGP's bundled Kotlin and must move with it. This
is the opposite resolution to ADR 0001's serialization call (there we avoided a
compiler plugin via reflective Gson; here the Compose plugin is unavoidable and
turned out fine). The View-based fallback (a `PreviewView` + custom overlay
`View`) was the contingency; it was not needed.

UI framework choice is **per-platform and divergent by design** (SwiftUI vs
Compose) — the parity discipline is the shared *contract*, never the UI.

## Decision 3 — Per-frame decode, observer-frame overlay, flips untouched

- **Per-frame, no smoothing.** The screen runs the rules `SemaphoreDecoder` on
  every frame and shows the raw position ids + emitted character. No
  `COMMIT_HOLD_MS` / `SMOOTHING_WINDOW` (Epic 4). The decoder's **mode** state
  (LETTERS ↔ NUMERIC, spec §4.5) *is* threaded across frames — that is the
  decoder's own state machine, not temporal smoothing.
- **"No signer detected" is a freshness watchdog** (NFR4). The capture stream
  simply stops yielding when no full upper-body skeleton is present (the adapter
  returns nil), so absence is a timeout (~500 ms), not an event. Same shape on
  both platforms (iOS `Task` watchdog; Android coroutine `delay` loop).
- **The overlay maps signer-frame → display-frame; it never touches the adapter.**
  `Keypoints` are normalized, y-up, signer's perspective. The preview is shown
  **mirrored** (the natural selfie front-camera view, which *is* the signer's
  perspective), so the overlay maps `screen_x = x·W`, `screen_y = (1 − y)·H` —
  identical convention on both platforms. The single adapter mirror (fixture-
  pinned, Pixel-confirmed in #21/#22) is **never** touched here; a flipped overlay
  is fixed in the preview/overlay display config, not the adapter (a second flip
  would desync the platforms and the parity harness).
- **The live mirror was smoke-verified on a Pixel 9a (#23).** The D pose (signer's
  left arm down, right arm up) read `L 0  R 4` = `D` — the full ML Kit → adapter →
  decoder pipeline correct on real hardware. The first cut drew the overlay with
  `(1 − x)`, which was flipped relative to the **mirrored** `PreviewView` (Android
  mirrors the front camera by default — the initial code wrongly assumed an
  observer-perspective preview); switching the overlay to `x` and mirroring the
  iOS preview to match landed the skeleton on the limbs and put both platforms on
  one convention. Device orientation (portrait) and the iOS preview path remain
  on-device smoke items: no camera/body-pose in the iOS Simulator or the JVM unit
  tests, so the knobs (preview mirroring/rotation, the iOS Vision request
  orientation — still #21's `.up` placeholder, the overlay mapping) are isolated
  and labelled for a real-device eyeball. This is the guardrail-(d) smoke [3.5]
  exists for.

## Implementation notes / consequences

- **iOS:** `PoseCaptureSession` now exposes its `AVCaptureSession` as
  `nonisolated(unsafe) let` so the SwiftUI preview layer can attach to the same
  session it configures (the documented AVFoundation share; AVFoundation is not
  `Sendable`-clean — the same escape the start/stop hops use). The non-Sendable
  session is passed to the preview as the capture *actor* (Sendable), not the bare
  session, to satisfy Swift 6's region-isolation checker. `ContractLoader`'s DTOs
  are `private` nested types so they don't collide with the test target's
  identically named top-level DTOs under `@testable import`.
- **Android:** `PoseCaptureSession.keypoints()` gained an optional `Preview` use
  case so the preview and the analysis stream bind to one front camera together
  (the CameraX analogue of iOS's shared session). `PreviewView` uses COMPATIBLE
  (TextureView) implementation mode so the Compose overlay reliably draws on top
  (a PERFORMANCE-mode SurfaceView can z-order over it). New shipping deps:
  CameraX `camera-view`, Compose (BOM-aligned ui/material3 + activity-compose +
  lifecycle-runtime-compose).
- **Tests unaffected.** No `shared/*.json` changed; the parity harness and the
  native-fixture suites are untouched and green on both platforms (iOS 7, Android
  6). The new UI + capture-preview wiring is compiled and reviewed but, like the
  rest of the live camera path, is not unit-tested.

## How to run

```bash
# iOS — run on a device for the live smoke (no camera/body-pose in the sim).
cd ios && xcodegen generate && open SemaphoreTranslator.xcodeproj

# Android — install the debug app on a device/emulator with a front camera.
cd android && ./gradlew :app:installDebug
```

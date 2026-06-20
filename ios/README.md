# iOS — Semaphore Translator

Native iOS app (Swift / SwiftUI / AVFoundation / Vision; Core ML — the trained
classifier — lands in a later epic). This holds the
**cross-platform parity harness** (Issue #14 — see
[`../docs/adr/0001-parity-harness-and-native-project-shape.md`](../docs/adr/0001-parity-harness-and-native-project-shape.md)),
the **Vision capture → adapter → `Keypoints`** path with its native-fixture
tests (Issue #21), and the **live debug screen** — SwiftUI preview + skeleton
overlay + per-frame decode (Issue #23, ADR
[0003](../docs/adr/0003-live-debug-preview-and-app-contract-loader.md)).

## Project generation

The `.xcodeproj` is **generated** from [`project.yml`](project.yml) with
[XcodeGen](https://github.com/yonsm/XcodeGen) and is git-ignored. `project.yml`
is the committed source of truth.

```bash
brew install xcodegen   # once
cd ios
xcodegen generate       # (re)creates SemaphoreTranslator.xcodeproj
open SemaphoreTranslator.xcodeproj
```

Re-run `xcodegen generate` after editing `project.yml` or adding source files.

## Running the parity tests

```bash
cd ios
xcodegen generate   # if you haven't already
xcodebuild test \
  -project SemaphoreTranslator.xcodeproj \
  -scheme SemaphoreTranslator \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  CODE_SIGNING_ALLOWED=NO
```

Pick any installed simulator for `-destination` (`xcrun simctl list devices`).
Simulator unit tests need no code signing.

## Running the live app

```bash
cd ios && xcodegen generate && open SemaphoreTranslator.xcodeproj
```

Build/run on a **real device** for the live screen: the iOS Simulator has no
camera and does not run body pose. The screen requests the camera permission,
then shows the preview with the skeleton overlay and the per-frame decoded
character. The live mirror/orientation is the **on-device smoke** — confirm the
overlay tracks and the letter is right; if it looks mirrored/rotated, tune the
preview/overlay display config, **not** the adapter (ADR 0003).

## Layout

| Path | What |
|------|------|
| `project.yml` | XcodeGen project definition (source of truth); bundles the `shared/*.json` contract as app resources. |
| `Sources/App/` | SwiftUI live debug screen (#23): `ContentView` (screen + readout), `CameraPreviewView` (preview layer), `PreviewViewModel` (capture + per-frame decode), `SkeletonOverlay` (signer→display overlay). |
| `Sources/Core/` | `SemaphoreDecoder.swift` (decode logic ported from `shared/tools/gen_test_vectors.py`); `Keypoints.swift` (the frozen 6-keypoint boundary); `VisionPoseAdapter.swift` (Apple Vision skeleton → `Keypoints`, the two flips applied once each — `shared/ADAPTER-CONTRACT.md`); `ContractLoader.swift` (app-side loader (#23): decodes the bundled `shared/*.json` and builds the decoder — mirrors the test harness's `ReferenceDecoder.make()`). |
| `Sources/Capture/` | `PoseCaptureSession.swift` — `actor`-wrapped front-camera `AVCaptureSession` → Vision → adapter → `AsyncStream<Keypoints>`, exposing the session for the preview layer (compiled/reviewed; live behaviour is an on-device smoke item). |
| `Tests/` | `ParityTests.swift` (the harness); `SharedContract.swift` (test-only Decodable models + the `shared/` loader); `NativeFixtureTests.swift` (#20 contract); `VisionAdapterTests.swift` (Layer-1: replay recorded Vision skeletons → adapter → `invariants.json`); `VisionCalibrationTests.swift` (Layer-2: live Vision on the committed images, device-only); `Fixtures/vision_skeletons.json` (recorded skeletons). |

## How the tests reach `shared/`

`SharedFiles` (in `Tests/SharedContract.swift`) walks up from this
source tree's compile-time path (`#filePath`) until it finds the repo's
`shared/test_vectors.json`, then loads the JSON directly — no copy, no symlink.
Both platforms parse the exact bytes `shared/tools/gen_test_vectors.py` wrote,
so they can only disagree in the decode logic, which is what the harness checks.

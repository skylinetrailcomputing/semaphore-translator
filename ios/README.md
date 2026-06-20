# iOS — Semaphore Translator

Native iOS app (Swift / SwiftUI / AVFoundation / Vision; Core ML — the trained
classifier — lands in a later epic). This holds the app shell, the
**cross-platform parity harness** (Issue #14 — see
[`../docs/adr/0001-parity-harness-and-native-project-shape.md`](../docs/adr/0001-parity-harness-and-native-project-shape.md)),
and the **Vision capture → adapter → `Keypoints`** path with its native-fixture
tests (Issue #21).

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

## Layout

| Path | What |
|------|------|
| `project.yml` | XcodeGen project definition (source of truth). |
| `Sources/App/` | Minimal SwiftUI app shell. |
| `Sources/Core/` | `SemaphoreDecoder.swift` (decode logic ported from `shared/tools/gen_test_vectors.py`); `Keypoints.swift` (the frozen 6-keypoint boundary); `VisionPoseAdapter.swift` (Apple Vision skeleton → `Keypoints`, the two flips applied once each — `shared/ADAPTER-CONTRACT.md`). |
| `Sources/Capture/` | `PoseCaptureSession.swift` — `actor`-wrapped front-camera `AVCaptureSession` → Vision → adapter → `AsyncStream<Keypoints>` (compiled/reviewed; live behaviour is an on-device smoke item). |
| `Tests/` | `ParityTests.swift` (the harness); `SharedContract.swift` (test-only Decodable models + the `shared/` loader); `NativeFixtureTests.swift` (#20 contract); `VisionAdapterTests.swift` (Layer-1: replay recorded Vision skeletons → adapter → `invariants.json`); `VisionCalibrationTests.swift` (Layer-2: live Vision on the committed images, device-only); `Fixtures/vision_skeletons.json` (recorded skeletons). |

## How the tests reach `shared/`

`SharedFiles` (in `Tests/SharedContract.swift`) walks up from this
source tree's compile-time path (`#filePath`) until it finds the repo's
`shared/test_vectors.json`, then loads the JSON directly — no copy, no symlink.
Both platforms parse the exact bytes `shared/tools/gen_test_vectors.py` wrote,
so they can only disagree in the decode logic, which is what the harness checks.

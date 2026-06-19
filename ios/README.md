# iOS — Semaphore Translator

Native iOS app (Swift / SwiftUI; AVFoundation / Vision / Core ML land in later
epics). Right now this holds the app shell and the **cross-platform parity
harness** (Issue #14) — see [`../docs/adr/0001-parity-harness-and-native-project-shape.md`](../docs/adr/0001-parity-harness-and-native-project-shape.md).

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
| `Sources/Core/` | `SemaphoreDecoder.swift` — the decode logic ported from `shared/tools/gen_test_vectors.py`, decoupled from the wire format (plain-value constructor; no JSON/loader code ships in the app). |
| `Tests/` | `ParityTests.swift` (the harness) and `SharedContract.swift` (test-only Decodable models + the `shared/` loader). |

## How the tests reach `shared/`

`SharedFiles` (in `Tests/SharedContract.swift`) walks up from this
source tree's compile-time path (`#filePath`) until it finds the repo's
`shared/test_vectors.json`, then loads the JSON directly — no copy, no symlink.
Both platforms parse the exact bytes `shared/tools/gen_test_vectors.py` wrote,
so they can only disagree in the decode logic, which is what the harness checks.

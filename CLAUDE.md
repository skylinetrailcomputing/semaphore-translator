# semaphore-translator

A free, on-device app — native **iOS (Swift)** and **Android
(Kotlin)** — that reads flag-semaphore arm positions from the device
camera and decodes them to text. One signer, one frame, on-device,
offline. See `README.md` and `CONTRIBUTING.md` for project-level
context and the contribution flow.

## Stack

Parallel **native** apps over a shared, language-neutral contract — no
cross-platform UI framework (the parity discipline is the point):

- **iOS** — Swift / SwiftUI / AVFoundation / Vision (Core ML for the
  trained classifier in a later epic). Project generated from
  `ios/project.yml` via [XcodeGen](https://github.com/yonsm/XcodeGen).
- **Android** — Kotlin / Jetpack Compose / CameraX / ML Kit / LiteRT.
- **`shared/`** — the frozen, language-neutral contract both platforms
  load verbatim (`*.json`): the alphabet, the adapter/keypoint contracts,
  the parity test vectors, and the native-fixture invariants.
- **`model/`** — off-device training in Python (`uv`).

### Dev tooling — Blender / MPFB for test fixtures

The Epic-3 native-fixture **images** (`shared/native_fixtures/pose_*.png`)
are 3D-rendered humans generated headlessly with **Blender + the
[MPFB](https://static.makehumancommunity.org/mpfb.html) add-on** (MakeHuman
**CC0** assets, clothing included). They exist because Apple Vision / ML Kit
only detect real-ish 3D human *form*, not flat drawings — so the Layer-2
adapter calibration needs a detectable figure. The renderer is
`shared/tools/gen_native_fixtures.py` (see its docstring for the one-time
Blender + MPFB + CC0-clothing-pack setup). Blender is **only** needed to
regenerate those fixtures; it is not required to build or test either app.



## Repo conventions

- **Public OSS repo, MIT-licensed** (see `LICENSE`), copyright Skyline
  Trail Computing LLC. Never commit secrets, credentials, keystores, or
  `keystore.properties` — the `.gitignore` blocks the common shapes, but
  the rule is yours to keep.
- **Conventional Commits** for messages (`feat:`, `fix:`, `docs:`,
  `refactor:`, `chore:`, `test:`); see `CONTRIBUTING.md`.
- **The cross-platform parity test is the gate.** Any change to the
  decode path must keep `shared/test_vectors.json` passing identically on
  both platforms (iOS XCTest + Android JVM unit test). The shared
  contract (`shared/*.json`) is frozen — both apps load it verbatim; the
  per-platform adapter is the only place allowed to know about
  platform-specific skeletons, coordinate origins, or the camera mirror.
- **ADRs** in `docs/adr/` record non-obvious architectural choices
  (see the existing 0001–0014). Dated, not edited after landing.
- **Branch protection on `main`:** PR required before merge, no
  force-push or branch deletion; secret scanning + push protection are
  enabled at the repo level.

## Maintainer-side Claude Code context

A git-ignored `CLAUDE.local.md` alongside this file holds the
maintainer's personal Claude Code context — workspace cross-references,
agent autonomy policy, and similar. Auto-loaded by Claude Code in
addition to this file. Not required to contribute to or build the
project; nothing in it changes the public contract above.

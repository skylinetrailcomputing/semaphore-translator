# semaphore-translator

_(One-paragraph project description. See `README.md` and
`CONTRIBUTING.md` for project-level context and contribution flow.)_

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

- _(License, commit-message style, pre-commit hygiene, branch
  protection — things that apply to anyone contributing.)_

## Maintainer-side Claude Code context

A git-ignored `CLAUDE.local.md` alongside this file holds the
maintainer's personal Claude Code context — workspace cross-references,
agent autonomy policy, and similar. Auto-loaded by Claude Code in
addition to this file. Not required to contribute to or build the
project; nothing in it changes the public contract above.

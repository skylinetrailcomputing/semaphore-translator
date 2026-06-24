# Contributing to semaphore-translator

Thanks for your interest! `semaphore-translator` is a free, open-source
learning project — issues, PRs, and ideas are welcome.

It reads flag-semaphore arm positions from the device camera and decodes
them to text, on-device and offline, on **two native apps** (iOS/Swift
and Android/Kotlin) built in lockstep against a single shared, language-
neutral contract. That **parallel-native parity discipline is the point**
of the project, not an accident — please read the section below before
sending a non-trivial change.

## Before you start

- Read [`README.md`](README.md) for the architecture and current status.
- Read [`docs/semaphore-translator-spec.md`](docs/semaphore-translator-spec.md)
  — the authoritative spec — and skim
  [`docs/adr/`](docs/adr/) for the decisions already made.
- The shared contract in [`shared/`](shared/) (`*.json`: the alphabet,
  the adapter/keypoint contracts, the parity vectors) is **frozen**. Both
  apps load it verbatim. Changing it is a deliberate, cross-platform act —
  open an issue first.

## The parity discipline (read this)

There is **one pipeline**, reimplemented natively on each platform:

```
Camera frame → pose estimation (native) → [adapter] → shared 6-keypoint
struct → arm-angle features → classifier → temporal commit → decoded text
```

The **adapter** is the *only* place allowed to know platform-specific
skeleton schemas, coordinate origins, or the camera mirror. Everything
downstream operates on the shared representation and must be logically
identical across platforms — enforced by a parity test that runs
`shared/test_vectors.json` through each platform's decode path and
asserts identical results.

**If you touch the decode path, the parity test must still pass
identically on both platforms.** A change that only lands on one side is
incomplete.

## Filing issues

- **Bug reports:** include the **platform** (iOS / Android), device
  model + OS version, what you signed vs what was decoded (or the
  observed behavior), and clear repro steps. A short screen recording is
  gold for a camera/pose issue.
- **Feature ideas:** open an issue first before sending a non-trivial PR
  — especially anything touching `shared/` or the decode path, where the
  cross-platform implications are easier to settle in conversation than
  after code is written.
- **Polish, typos, doc fixes:** small fixes are fine as direct PRs, no
  issue needed.

## Building & testing

Neither app needs the other to build; both load the contract from
`shared/` directly. Blender/MPFB (the test-fixture renderer) is **not**
needed to build or test — only to regenerate the native-fixture images.

**iOS** — the `.xcodeproj` is generated from `ios/project.yml` with
[XcodeGen](https://github.com/yonsm/XcodeGen) and is git-ignored:

```bash
brew install xcodegen          # once
cd ios && xcodegen generate    # (re)creates SemaphoreTranslator.xcodeproj
xcodebuild test \
  -project SemaphoreTranslator.xcodeproj \
  -scheme SemaphoreTranslator \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  CODE_SIGNING_ALLOWED=NO
```

**Android** — the Gradle wrapper pins the toolchain; point it at your SDK
via a git-ignored `android/local.properties` (`sdk.dir=...`):

```bash
cd android
./gradlew :app:testDebugUnitTest   # per-commit JVM unit tests (incl. parity)
```

The live camera path and the one-time Layer-2 pose calibration
(`connectedDebugAndroidTest`) need a real device/emulator — see
[`ios/README.md`](ios/README.md) and [`android/README.md`](android/README.md)
for the full per-platform detail, including how each test suite reaches
`shared/`.

## Pull requests

- **Branch from `main`.** Use `<issue-or-topic>-<short-description>`
  branch names.
- **Conventional Commits** for messages — `feat:`, `fix:`, `docs:`,
  `refactor:`, `chore:`, `test:`. Imperative mood; one paragraph of "why"
  if the change isn't self-evident from the diff.
- **Keep PRs small** and one-concern. Easier to review, faster to land.
- **Run the parity test on both platforms** for any decode-path change,
  and say so in the PR description.
- `main` requires a PR (no direct pushes, no force-push, no branch
  deletion). The maintainer reviews and merges.

## Architecture decisions

For cross-cutting choices (a dependency with non-trivial footprint, a
change to the adapter or commit contract, anything that touches
`shared/`), add an **ADR** in `docs/adr/` alongside the PR. Short, ~30–50
lines:

- **Context:** what problem and constraints prompted the decision.
- **Decision:** what was chosen.
- **Consequences:** what becomes easier, harder, or off-limits.

ADRs are dated and not edited after landing. If a later decision
supersedes one, write a new ADR referencing the old.

## Behavior

Be kind. Disagree with ideas, not people. Aggressive or bad-faith
engagement isn't tolerated.

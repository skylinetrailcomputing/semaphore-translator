# Semaphore Translator

A native **iOS (Swift)** + **Android (Kotlin)** app that recognizes
flag-semaphore signals from the device camera and decodes them to text.
Pose estimation runs on-device (Apple Vision / ML Kit); a small
classifier maps two arm angles to a character.

> **Primarily a learning vehicle** — the goal is to learn the full
> on-device ML pipeline (data → train → export → native inference → UX)
> on a tractable problem, while deliberately building **parallel native
> implementations** on both platforms against a single shared contract.
> See [`docs/semaphore-translator-spec.md`](docs/semaphore-translator-spec.md)
> (the authoritative spec, v0.2).

## Status

Scaffolding. Nothing is implemented yet. The immediate blocking task is
**verifying and freezing the shared alphabet contract** — see
[`shared/README.md`](shared/README.md) and Epic 1 in the issue tracker.

- **Publisher:** Skyline Trail Computing LLC. Private repo for now.
- **Distribution posture:** Tier A (free, on-device, no PII of
  consequence, no injury surface) per the workspace
  `closed-beta-fast-path.md`. The legal layer for a closed F&F beta is
  light — a camera purpose string, a short on-device-only privacy note,
  and a minimal EULA. See `DISTRO_CHECKLIST.md`.

## Architecture

A single pipeline, reimplemented natively on each platform, consuming
shared language-neutral artifacts:

```
Camera frame → pose estimation (native) → [adapter] → shared 6-keypoint
struct → feature extraction (arm angles) → classifier (rules | trained)
→ temporal smoothing/commit → decoded text
```

The **adapter** is the only place allowed to know platform-specific
skeleton schemas, coordinate origins, or the camera mirror. Everything
downstream operates on the shared representation and must be logically
identical across platforms — enforced by a parity test over
`shared/test_vectors.json`.

## Layout

| Path | What |
|------|------|
| `docs/` | The shared spec (source of truth for *logic*). |
| `shared/` | Language-neutral source of truth for *data*: alphabet, config constants, parity test vectors. Both platforms load these; nothing here is hardcoded in Swift/Kotlin. |
| `model/` | Off-device Python training + export to Core ML and LiteRT; checked-in exported artifacts. |
| `ios/` | Swift app (SwiftUI, AVFoundation, Vision, Core ML). |
| `android/` | Kotlin app (Compose, CameraX, ML Kit, LiteRT). |

## Build order (parallel-native)

1. Freeze the shared contract (`shared/`).
2. Stand up the adapter spec + parity-test harness for both platforms.
3. Capture + pose + adapter on each platform, in lockstep.
4. Rules-based classifier + decoder on each, gated by the parity test.
5. Minimal UI on each, to parity.
6. Tier-A legal + closed F&F on **both** stores together.
7. ML pipeline (the learning payoff): train once, export to both runtimes,
   wire behind the classifier toggle, measure against the rules baseline.

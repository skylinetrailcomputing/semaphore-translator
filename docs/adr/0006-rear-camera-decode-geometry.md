# ADR 0006 — The rear camera needs no decode-path change (the §3.2 mirror seam)

- **Status:** Proposed (provisional). The decision below is recorded and supported
  by static evidence, the parity harness, a machine-checked twin table, and a
  prior on-device scratch smoke. It flips to **Accepted** only when the live
  per-letter sign-off (see *Live verification protocol*) is attached — the one
  acceptance gate this ADR cannot close by argument.
- **Issue:** [#58](https://github.com/skylinetrailcomputing/semaphore-translator/issues/58)
  ([5.2c], the rear-camera "risk ticket"); part of epic
  [#46](https://github.com/skylinetrailcomputing/semaphore-translator/issues/46).
- **Context refs:** spec §3.2 (the per-platform adapter mirror), §4.2 (the
  signer's-perspective frozen frame), §4.3 (order-insensitive matching), NFR3
  (cross-platform parity). Builds on #56 (lens parameterization) and #57
  (lens-dependent *display* mirror). Unblocks #59 (verify) → #60 (promote
  Interpret).

## Context

Epic 5.2 adds a rear-camera "Interpret" mode (read *another* signer). Spec §3.2
names the horizontal mirror — the flip that lands keypoints in the signer's
perspective — as **the single most error-prone step**, deliberately confined to
the per-platform adapter. It is also the one seam the parity harness is blind to:
`test_vectors.json` / `temporal_vectors.json` are **post-adapter and
camera-agnostic**, so they validate everything *downstream* of the mirror and
nothing about whether the camera-side buffer fed into the adapter has the
expected handedness.

This is the dangerous part: because matching is **order-insensitive on the
unordered octant pair** (§4.3), a horizontal-mirror error does not produce
garbage — it maps each left↔right-asymmetric letter to a *different valid letter*,
its **mirror twin**, silently and plausibly. Get the rear mirror backwards and
A reads as G, T flips the decoder into numeric mode, and the parity harness stays
green throughout.

The working question for #58: **does the rear lens need any adapter / capture-
mirror change, or does the existing front-camera geometry already serve it?**

## Decision — no decode-path change; the existing adapter mirror is correct for both lenses

The rear lens reuses the front-camera decode geometry unchanged. **No change to
the adapters, the decoders, the committers, the capture-mirror configuration, or
the frozen contract.** The rear work in this epic is entirely *display-side*
(#57) and *plumbing* (#56). Per NFR3, were a change ever required it would be
confined to the adapter / capture-mirror config and leave everything downstream
and the parity contract untouched — but the evidence below says none is required.

### Why (the argument)

The silent failure mode is governed by exactly one property: the **chirality
(handedness)** of the *analysis* buffer relative to the signer. If the rear
analysis buffer has the same handedness as the front one — both
observer-perspective — the existing single adapter mirror (`x_out = 1 − x`) is
correct for both and the decode path is unaffected.

1. **Rotation cannot flip handedness.** The capture pipeline rotates the buffer
   upright (`videoRotationAngle = 90` on iOS, `PoseCaptureSession.swift:88-90`;
   ML Kit's `rotationDegrees` on Android). A rotation is orientation-preserving
   (det = +1) and **cannot** convert a left-handed frame to a right-handed one —
   only a reflection can. So a wrong rotation produces a *grossly rotated*
   skeleton that corrupts **every** letter's angles, including the
   mirror-symmetric ones (D, N, R, U) — a **loud** failure, not a selective,
   plausible mirror-twin. (This sharpens #58's original caveat, which worried that
   `videoRotationAngle = 90` "could introduce a handedness difference": a
   *rotation* cannot. The only handedness lever is the mirror flag.)

2. **The mirror flag is pinned non-mirrored for both lenses.**
   - iOS: `connection.isVideoMirrored = false` is set **unconditionally** on the
     `AVCaptureVideoDataOutput` connection (`PoseCaptureSession.swift:96`) — *not*
     gated on `cameraPosition`. Crucially, `automaticallyAdjustsVideoMirroring =
     false` is set first (`:95`); without it AVFoundation would re-enable the
     selfie mirror on the front lens, and the guard would hold for `.back` only by
     accident. The two lines together make the analysis buffer non-mirrored
     regardless of lens.
   - Android: the `ImageAnalysis` use case is never mirrored by CameraX (mirroring
     is a `Preview`-only transform); the analysis path adds none
     (`PoseCaptureSession.kt`), for either `CameraSelector`.

3. **Same scene-true handedness for both lenses (the empirical residual).** With
   mirroring off, both lenses deliver scene-accurate (non-mirrored) frames: a rear
   photo of a person facing the camera and a front capture (mirror off) of a
   person facing the camera both put the subject's right hand on the viewer's left
   — observer perspective, exactly the handedness the Epic-3 native fixtures
   calibrate the `1 − x` flip against. This last step is a platform-behavior
   claim, not a theorem — so it is **pinned empirically** (Epic-3 style), not
   declared settled by argument. The scratch smoke (below) is that pin: a flipped
   rear buffer would have mirror-twinned the asymmetric majority and cratered A–Z
   accuracy; it didn't.

### Named assumptions (empirically grounded, not test-covered)

- **iOS:** `isVideoMirrored = false` yields the same observer-perspective
  chirality on the rear wide-angle camera as on the front. Pinned by the scratch
  smoke on an iPhone 16.
- **Android:** the rear camera delivers `rotationDegrees ∈ {90, 270}` in portrait,
  so the upright-dimension branch of `PoseCaptureSession.kt:101-103` normalizes by
  the right axis. `MlKitCalibrationTest` uses `InputImage.fromBitmap(…, 0)` and so
  **does not exercise** the width/height swap — the only pin for the live rear
  path is the scratch smoke on a Pixel 9a. A device that delivered
  `rotationDegrees = 0` would scramble arm angles — again a *loud*, not silent,
  failure.

## Evidence

### Static (the decode path is byte-unchanged)

`git log` over the Epic-5.2 range (`d7ca307^..HEAD`) shows **zero** changes to the
decode path:

- `VisionPoseAdapter.swift`, `MlKitPoseAdapter.kt` — untouched (the `x_out = 1 − x`
  mirror is identical to pre-5.2).
- `SemaphoreDecoder.swift`, `SemaphoreDecoder.kt`, `Committer.swift`,
  `Committer.kt` — untouched.
- #56 (`d7ca307`) touched only capture plumbing + view models; #57 (`4b66128`)
  touched only preview / overlay (display). Both commit messages assert the
  adapter mirror is untouched, and the log confirms it.

### Parity harness (regression half — not proof of geometry)

iOS `ParityTests` + `TemporalParityTests` and Android `*ParityTest` +
`*TemporalParityTest` are **green** on both platforms. This proves the downstream
decode/temporal logic is byte-for-byte unchanged (NFR3) — it is a *regression*
check, **not** evidence about the camera-side mirror, which it is structurally
blind to. The static diff above is what shows the geometry is unchanged; the live
protocol below is what shows it is *correct*.

### Machine-checked twin table

The mirror-twin map the live protocol keys off is re-derived from
`semaphore_alphabet.json` by `shared/tools/check_mirror_twins.py`
(`uv run shared/tools/check_mirror_twins.py`, self-validating). It derives the
octant reflection from the position **angles** (θ → 180 − θ ⇒
`0↔0, 1↔7, 2↔6, 3↔5, 4↔4`) rather than restating the hand map, then verifies every
class's twin, that the relation is an involution, that the symmetric set is exactly
{D, N, R, U, REST}, and that **L's mirror `{3,7}` is the single unused
distinct-position pair** (the unique canary):

| Sign a… | a flipped rear path reads… | | Sign a… | reads… |
|---|---|---|---|---|
| A | G | | O | W |
| B | F | | Q | Y |
| C | E | | M | S |
| H | Z | | **T** | **NUMERALS** (enters numeric mode — top check) |
| I | X | | **L** | **INDETERMINATE** (canary) |
| J | P | | D, N, R, U, REST | themselves (symmetric — non-discriminating) |
| K | V | | NUMERALS | T (the reverse of T's twin) |

### Prior on-device scratch smoke (anecdotal, but directional)

The throwaway `scratch/rear-lens-smoke` branch (commits `c77189a` "drive rear lens
for #56 capability smoke", `f46e487`/`e4cd71b` rear preview display fixes — all
tagged `[DO NOT MERGE]`, branched from `d7ca307`; never merged) drove the rear lens
against the NATO A–Z reference video
([youtube.com/shorts/rhyFSHz3dwc](https://www.youtube.com/shorts/rhyFSHz3dwc),
signer facing the camera) in portrait on **iPhone 16** and **Pixel 9a**. Decode
was *mostly correct, not mirror-twinned* on both. This is **anecdotal prior
evidence** (informal eyeball off a phone screen, not a per-letter sign-off), and
its only durable artifacts are the three scratch commits above; it is what
motivated the no-change hypothesis, not what closes it.

## Live verification protocol (the remaining acceptance gate)

The static + parity + twin-table evidence shows the geometry is *unchanged* and
that a flip *would* be catchable; only a live read shows it is *correct*. This is
guardrail-(d) work (a human smoke an agent can't perform) and is the gate that
moves this ADR to **Accepted**.

**Setup.**
- **Portrait, top-up**, on **both** target devices (iPhone 16 + Pixel 9a) — the
  per-letter pass is run and recorded on each (parity discipline; the scratch
  smoke covered both, this is the formal repetition). Portrait matches the
  `videoRotationAngle = 90` / `rotationDegrees ∈ {90,270}` assumption; landscape
  would rotate the pose and produce a *false negative* unrelated to the mirror.
- **Executable path (rear is not yet routed to a screen on `main`).** Run a
  rear-pinned verification build — the same pattern as the scratch branch, kept
  off `main` — by flipping the lens default at the two call sites (#56 already
  threads the lens as a parameter):
  - iOS `ContentView`: `PreviewViewModel()` → `PreviewViewModel(cameraPosition: .back)`
  - Android `SemaphoreApp`: the Learn `SemaphoreScreen(...)` call →
    `cameraLens = CameraSelector.DEFAULT_BACK_CAMERA`

  Turn **Developer mode** on (Settings, #50) for the overlay + readout + the
  coordinate probe. Full runtime rear routing lands properly with Interpret (#60).

**Primary check — the coordinate probe (this PR).** With Developer mode on, both
platforms log the post-adapter geometry once per id change (iOS `os.Logger`
category `geometry-probe`; Android Logcat tag `SemaphoreProbe`):

```
lens=rear ids=[1,3] char=I  L(sh.x=0.381 wr.x=0.205) R(sh.x=0.619 wr.x=0.794)
```

A correctly-oriented read has the signer's right shoulder at **greater x** than
the left (`R.sh.x > L.sh.x`), and an arm extended to the signer's right has
`wr.x > sh.x`. A flipped rear buffer inverts both — visible numerically before any
quantization, which is why this beats eyeballing the decoded letter. (The probe
reads *post-adapter* coords on purpose: the adapter transform is byte-pinned by
the native fixtures, so any rear chirality fault surfaces in the consumer without
instrumenting the quarantined capture path.)

**Discriminating per-letter pass.** A horizontal mirror is a single *global*
transform — there is no per-letter flip — so signing one representative per twin
pair is logically complete for the mirror question. Sign and confirm each reads
**itself, not its twin**:

`A, B, C, H, I, J, K, M, O, Q` — each must read itself, not G/F/E/Z/X/P/V/S/W/Y.

- **T last, or in an isolated run.** A wrong mirror on T reads **NUMERALS** and
  switches the decoder to numeric mode, which would contaminate every later check.
  Return to **REST** (arms down) between letters; if numeric mode is entered, sign
  **J** (letters-shift) to recover before continuing.
- **L is the canary:** under a flip it decodes to `{3,7}` (unassigned) →
  INDETERMINATE (`·`), so a flip makes L fail rather than mis-decode.
- **One symmetric control (D or N):** mirror-symmetric, so useless as a mirror
  discriminator — but if it reads itself it confirms rotation + decode are healthy,
  separating "mirror correct" from "mirror correct *and* rotation correct."
- **Dwell/commit:** hold each pose past `COMMIT_HOLD_MS` so it commits once; read
  the committed letter (the hero), not the raw per-frame flicker.

**Scope.** #58 is the *mirror-seam* gate (the discriminating subset above). A full
A–Z accuracy pass is a **pose-quality** check, not a geometry check, and belongs
to #59. Signing the rest of the alphabet opportunistically during this pass is
welcome but not required to close #58.

## Consequences

- **Interpret reuses the front decode path verbatim.** #60 can route the rear lens
  into a functional Interpret screen with no geometry work — only display + UX.
- **The parity contract is untouched.** No fixture regeneration, no port changes;
  the harness stays the downstream regression net it already is.
- **The risk-ticket reasoning is now a checked artifact.** `check_mirror_twins.py`
  machine-verifies the twin table the live protocol and this ADR rest on, so the
  argument can't silently rot if the (frozen) alphabet is ever touched.
- **A dev-only coordinate probe ships** (behind Developer mode, #50), reusable for
  #59. It is consumer-side logging only — no behavior change, nothing logged when
  Developer mode is off.
- **This ADR stays Proposed until the live per-letter sign-off is attached** to
  #58 (both devices). On a *pass*, flip to Accepted and record the probe lines +
  the per-letter results. On a *fail*, the fix is confined to the adapter /
  capture-mirror config (NFR3) and this ADR is rewritten around the pinned flip.

## How to run

```bash
# Twin-table derivation check (off-device, self-validating):
uv run shared/tools/check_mirror_twins.py

# Parity harness (proves downstream unchanged — the regression half):
#   iOS:     xcodebuild test … -only-testing:SemaphoreTranslatorTests/ParityTests \
#                              -only-testing:SemaphoreTranslatorTests/TemporalParityTests
#   Android: ./gradlew testDebugUnitTest --tests "*ParityTest" --tests "*TemporalParityTest"

# Live geometry smoke (on-device, the acceptance gate): rear-pin the lens default
# (see Live verification protocol), Developer mode on, read the geometry-probe /
# SemaphoreProbe log + the discriminating per-letter pass on both devices.
```

# ADR 0011 — The Interpret facing-away flip (an opt-in, post-adapter re-mirror)

- **Status:** Accepted (2026-06-23). The contract + the cross-platform mechanism
  are verified by the dual-platform parity tests below; the on-device behavioural
  smoke in **both orientations** is the remaining guardrail-(d) step (see
  *Consequences*).
- **Issue:** [#78](https://github.com/skylinetrailcomputing/semaphore-translator/issues/78)
  ([6a-9], the reel-in facing-away toggle); parent epic
  [#67](https://github.com/skylinetrailcomputing/semaphore-translator/issues/67) (6a).
  Depends on [#75](https://github.com/skylinetrailcomputing/semaphore-translator/issues/75)
  ([6a-7], the Interpret fork, ADR 0009).
- **Context refs:** spec §3.2 (the adapter owns the mirror) / §4.2 (the frozen
  angle convention) / §4.3 (order-insensitive lookup); ADRs
  [0006](0006-rear-camera-decode-geometry.md) (the rear lens reuses the front
  decode path verbatim; the mirror-twin table) and
  [0009](0009-interpret-timing-profile.md) (the additive, decode-safe Interpret
  fork this mirrors in shape); `shared/ADAPTER-CONTRACT.md` §3b/§4;
  `shared/tools/check_mirror_twins.py` (the twin table this fixture cross-checks).

## Context

Interpret reads *another* signer through the rear lens. Until now it assumed the
signer faces the camera (facing-us): the per-platform adapter applies its single
horizontal mirror so the post-adapter frame is in the signer's perspective, and
the decode reads the letter they mean (ADR 0006 — the rear lens needs no
decode-path change for a facing-us signer).

But a real Interpret target may have their **back** to the camera — a lifeguard
facing the water, a signaller on a ship's stern facing away. Geometrically that
reverses left/right relative to facing-us: run a back-facing signer through the
facing-us adapter and every asymmetric pose decodes as its **mirror twin** (A→G,
B→F, …, T↔NUMERALS; the symmetric D/N/R/U/REST read themselves; L goes
INDETERMINATE — exactly the `check_mirror_twins.py` / ADR-0006 table). The reader
sees confident, wrong letters, not noise.

The fix the ticket scopes is a manual **facing-away** toggle: one extra horizontal
flip before decode. Default stays **facing-us**. This is the **highest-blast-radius
area in the codebase** — chirality/mirror is the #1 cross-platform divergence
source — so the bar is fresh parity vectors + dual on-device smoke in both
orientations, not just "it compiles."

## Decision 1 — The flip is a separate, opt-in, post-adapter re-mirror; the adapter mirror is untouched

`ADAPTER-CONTRACT.md` §3/§4 holds the line that the analysis-buffer → signer's
-perspective mirror happens **exactly once, in the adapter, and nowhere else.**
The facing-away flip does **not** relax that rule and does **not** touch the
adapter:

- **The adapter mirror stays exactly as-is** — still one mirror, still pinned by
  the Epic-3 native fixtures (`VisionCalibrationTests` / `MlKitCalibrationTest`),
  still the only thing that reaches the signer's-perspective frame from a native
  skeleton. Touching it would force re-validating every native fixture and entangle
  a frozen, byte-pinned transform with live UI state.
- **The facing-away flip is a distinct, named transform applied *downstream* of
  the adapter, at the decode call site**, and is the **identity when the toggle is
  off.** So the default (facing-us) path is byte-for-byte unchanged — every
  existing fixture (`test_vectors.json`, the temporal files, the native fixtures)
  is untouched and stays green. New behaviour is purely additive, exactly the
  decode-safe-by-construction shape of the ADR-0009 timing fork.

The transform is `Keypoints.mirroredHorizontally()` on each platform: `x → 1 − x`
for all six keypoints, `y` and `confidence` unchanged. It is the *intentional*
twin of the `mirrorBroken()` test helper — the same arithmetic, but here a
correction rather than a bug, because the signer really is reversed.

Why a flip with **no left/right label swap** is correct: the decoder reads each
arm's angle `atan2(dy, dx)` and looks up the **unordered** `{left_id, right_id}`
pair (spec §4.3), so it never depends on which keypoint is *labelled* left. A pure
`x → 1 − x` reflects each arm angle (`θ → 180 − θ`), reflecting both octant ids;
the unordered pair becomes the mirror-twin pair. Net effect, composed with the
adapter's facing-us mirror: a back-facing signer's two mirrors cancel (net-zero),
and the true letter is read.

## Decision 2 — Selection: a session-local, default-off, Interpret-only toggle, applied at the decode call site

Facing-away is a **situational, per-session** control (you point at one back-facing
signer), not a global preference like developer-mode or show-assist. So it is **not**
persisted in `@AppStorage` / `AppSettings`; it is session-local UI state that resets
to facing-us each time the screen opens (matching the ticket's "default stays
facing-us"), and it is offered **only on the rear (Interpret) lens**:

- **iOS:** `PreviewViewModel.facingAway` (default `false`), toggled by
  `toggleFacingAway()`. `handle(_:)` decodes
  `cameraPosition == .back && facingAway ? kp.mirroredHorizontally() : kp`. The
  toggle pill renders only when `isFacingAwayAvailable` (`.back`).
- **Android:** a `remember { mutableStateOf(false) }` in `CameraScreen`, read in the
  frame collector the same way, the toggle gated to `LENS_FACING_BACK`.

Two gates (the lens *and* the flag) express the Interpret-only invariant from both
ends — the toggle is only reachable on the rear lens, and the decode only applies
the flip on the rear lens, so a future bug can't apply it to the Learn path.

**Toggling resets the committer** (clears the smoothing window + hold) on both
platforms, so stale votes from the previous orientation can't fire a spurious
commit at the flip boundary — the same hard-reset a lens change would do. That
reset, the toggle's default-off, and the pill itself are view-level affordances
with no parity vector (like the drill flash / auto-reset countdown); the *flip
geometry* is what the fixture pins.

## Decision 3 — The parity fixture pins the SHIPPING flip, and is maximally discriminating

`shared/facing_away_vectors.json` (from `gen_facing_away_vectors.py`) carries one
canonical post-adapter pose per symbol (A–Z + NUMERALS + REST, 28 total) with
**both** decode branches:

- `facing_us` — `decode(keypoints)`: the toggle off, the identity. Equals the
  canonical `test_vectors.json` decode of the pose.
- `facing_away` — `decode(mirroredHorizontally(keypoints))`: the toggle on. Equals
  the pose's **mirror twin**.

Both platform harnesses build the keypoints, decode them for `facing_us`, and
decode **`Keypoints.mirroredHorizontally(keypoints)` — the production transform,
not a test reimplementation** — for `facing_away`, asserting identical emits +
position ids. Green on both is the byte-for-byte parity guarantee (spec §6) for the
flip itself.

The fixtures are **single-frame, `LETTERS` mode_before** (like `test_vectors.json`),
and that is sufficient by construction: the flip is a **pre-classify keypoint
transform, orthogonal to the decoder's LETTERS↔NUMERIC mode state machine** (mode
lives in the committer, downstream of `classify`). So NUMERIC composes correctly
with the flip *for free* — a back-facing signer's NUMERALS pose twins to a T pose
through the adapter, the flip restores the true NUMERALS pose, and NUMERIC mode
engages exactly as facing-us; the subsequent digit/letters-shift poses restore the
same way. No facing-away-specific NUMERIC coverage is needed (or meaningful): the
flip doesn't touch mode, and the mode machine is already parity-pinned upstream of
this fixture (`test_vectors.json` sequences + the temporal files). The `T` vector's
`facing_away.mode_after = NUMERIC` is just the twin relationship surfacing
(`twin(T) = NUMERALS`), documentation the single-frame harness ignores — **not** a
hazard a live back-facing signer hits (their *real* T reads `T`, because the flip
twins it back).

The fixture is discriminating by construction: `facing_away` diverges from
`facing_us` on **23 of 28** poses (every asymmetric one), so a port that skips or
botches the flip lands a *different, valid, wrong* letter — caught, not masked. The
generator self-validates three ways before writing: it cross-checks every
`facing_away` branch against the independently-derived ADR-0006 twin table
(`check_mirror_twins.EXPECTED_TWIN`), asserts the flip is involutive
(`flip(flip(kp))` decodes to the original — catching a y-flip or label-swap
mistaken for an x-flip), and re-asserts the symmetric set `{D,N,R,U,REST}` and the
`L → INDETERMINATE` canary, so a future alphabet edit can't quietly gut its
catching power.

## Consequences

- **Interpret can read back-facing signers**, the lifeguard-facing-the-water case,
  without any change to the facing-us path: the flip is identity-when-off, so
  `test_vectors.json` + the temporal + native fixtures stay byte-identical and
  green. The adapter, decoder, classify, and committer **code** is untouched —
  parity risk on the default path is ~0 (the ADR-0006 / ADR-0009 posture).
- **The flip is parity-pinned on both platforms.** New `FacingAwayParity` suites
  (iOS XCTest, Android JUnit) replay `facing_away_vectors.json` through the shipping
  `mirroredHorizontally()`; green on both is the cross-platform guarantee for the
  single highest-blast-radius transform.
- **Persistence is deliberately omitted.** A facing-away flag that silently survived
  into the next session would read every normal (facing-us) signer as mirror twins —
  a quiet footgun. Default-off-per-session trades a re-tap for safety; revisit only
  if field use shows the re-tap is a real burden.
- **Remaining acceptance (guardrail-d):** an on-device smoke on both targets, **both
  orientations** — a facing-us signer reads correctly with the toggle off (today's
  behaviour, unregressed) and a genuinely back-facing signer reads correctly with the
  toggle on. The mechanism is parity-verified; this is the "did a human watch it"
  step the unit tests can't be, and it is load-bearing here precisely because the
  pose estimator's anatomical L/R labelling on a back-facing figure is the one thing
  the synthetic fixture can't exercise.

## How to run

```bash
# Regenerate the fixture (self-validating: re-decodes, cross-checks the twin table,
# asserts involution). The facing-us path must stay byte-identical:
uv run shared/tools/gen_facing_away_vectors.py
uv run shared/tools/gen_test_vectors.py
git diff --exit-code -- shared/test_vectors.json        # facing-us decode unchanged

# Parity (the shipping flip, both platforms):
#   iOS:     xcodebuild test … -only-testing:SemaphoreTranslatorTests/FacingAwayParityTests
#   Android: ./gradlew testDebugUnitTest --tests "*FacingAwayParityTest"
```

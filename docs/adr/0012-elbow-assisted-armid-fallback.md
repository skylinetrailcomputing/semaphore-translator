# ADR 0012 — Elbow-assisted `armId` fallback (a parity-safe proxy for a clipped wrist)

- **Status:** Accepted (2026-06-24). The contract + the cross-platform mechanism
  are verified by the dual-platform parity tests (`test_vectors.json` stays
  byte-identical on the facing-us path; new low-wrist/high-elbow rows pass on both
  ports). The on-device behavioural smoke — and the premise it rests on — is the
  remaining guardrail-(d) step (see *Consequences*).
- **Issue:** [#111](https://github.com/skylinetrailcomputing/semaphore-translator/issues/111)
  ([6a-17], lever C from the [#101](https://github.com/skylinetrailcomputing/semaphore-translator/issues/101)
  pose-friction spike); parent epic
  [#67](https://github.com/skylinetrailcomputing/semaphore-translator/issues/67) (6a).
  Relates to [#7](https://github.com/skylinetrailcomputing/semaphore-translator/issues/7)
  (the trained classifier — the real long-term fix, post-beta).
- **Context refs:** spec §4.2 (the frozen arm vector is shoulder → wrist) / §4.3
  (indeterminate-on-low-confidence; order-insensitive lookup) / §4.4
  (`MIN_KEYPOINT_CONFIDENCE`); `shared/keypoint_contract.json` `confidence_gate`
  (the rule this ADR updates); ADRs
  [0006](0006-rear-camera-decode-geometry.md) and
  [0009](0009-interpret-timing-profile.md) (the additive, decode-safe-by-
  construction posture this mirrors in shape); `docs/spike-101-pose-friction-diagnostic.md`.

## Context

Spec §4.2 freezes one arm vector: **shoulder → wrist**. `armId` snaps its angle
to an octant and gates the arm `indeterminate` when the shoulder *or* wrist falls
below `MIN_KEYPOINT_CONFIDENCE` (§4.3/§4.4). The elbow is in the frozen 12-float
input (`model_input_order`) but the rules decoder never used it.

The #101 diagnostic found the three friction classes (diagonals/propellers, the
across-body sextet H/I/O/W/X/Z, and portrait wingspan) share one root cause: **the
wrist leaves the usable frame** (corner-clip, body-crossing, or side-clip in
portrait). The iOS session-2 observation was the key asymmetry: Apple Vision keeps
the **elbow** in frame at confidence while the clipped **wrist** drops below the
floor — so the arm gates out and the letter won't read, even though a reliable
in-frame proxy for the arm direction is right there. (On Android, ML Kit tends to
drop wrist *and* elbow together, so the fallback rarely fires in the field — but
the shared decoder change is parity-identical on both, see Decision 3.)

Because semaphore arms are held **straight**, `shoulder → elbow` is collinear with
`shoulder → wrist` — same angle, but from the more proximal, lower-variance joint
that survives the clipping. That collinearity is what makes the elbow a sound
proxy, and it is also the load-bearing assumption this ADR has to own (Decision 2).

## Decision 1 — Fall back to shoulder → elbow when the wrist is sub-floor but the elbow is not

`armId` becomes, on all three ports (the Python reference + Swift + Kotlin), in
this exact order:

1. **shoulder below floor → indeterminate.** The shoulder is the common origin of
   *both* candidate vectors, so a low-confidence shoulder is unrecoverable.
2. **wrist at/above floor → shoulder → wrist** (the spec §4.2 primary path,
   unchanged).
3. **wrist below floor, elbow at/above floor → shoulder → elbow** (the fallback).
4. **neither wrist nor elbow clears the floor → indeterminate.**

No left/right label question arises (unlike the ADR-0011 flip): the fallback
substitutes one joint *of the same arm* for another; the order-insensitive pair
lookup (§4.3) is untouched.

## Decision 2 — The straight-arm prior is an accepted, bounded tradeoff; no collinearity guard

The fallback trusts that the arm is straight. A **bent** arm whose wrist is out of
frame while the elbow is visible can point `shoulder → elbow` at a different octant
than the true arm, yielding a confident **wrong** id where the old code returned a
safe `indeterminate`. This was raised independently by two roundtable reviewers
(GPT-5.5 Pro, DeepSeek V4 Pro) and is real. We accept it, bounded, rather than
guard against it:

- **Semaphore arms are straight by the sport's rule.** A bent arm is malformed
  input the system was never going to read correctly — the *existing* shoulder →
  wrist path already mis-reads a bent arm. The fallback extends an existing
  assumption to one more joint; it does not introduce a new error *class*.
- **The temporal committer is the backstop.** Commit needs a 5-frame plurality
  (`SMOOTHING_WINDOW`) held for `COMMIT_HOLD_MS` (600 ms Learn / 350 ms Interpret).
  A *transient* bent-arm fallback is out-voted; only a bent arm with an out-of-frame
  wrist held steady for the full hold mis-commits — a contrived pose.
- **The proposed collinearity guard is self-defeating.** "Only use the elbow when
  it agrees with the wrist within tolerance" fires only when the wrist was usable
  anyway — defeating the clipped-wrist case this exists for. There is no reliable
  wrist angle to validate against precisely when the fallback is needed.

The behaviour is **documented, not hidden**: a `fallback_bent_arm_uses_elbow_octant`
parity vector pins that a bent arm resolves to the *elbow's* octant (the
false-positive made explicit and regression-visible), and the host-Vision `.MOV`
sanity check + the on-device smoke measure how often, in real footage, a fired
fallback's elbow octant disagrees with the wrist octant (Consequences).

## Decision 3 — Parity-safe by construction; the fixture is additive

Every existing `test_vectors.json` row has a full-confidence wrist, so branch 2
always wins on them and **no existing row changes** — the facing-us decode is
byte-identical, no regeneration drift (`git diff --exit-code` after regenerating).
The fixture is only *added to* (the alphabet-freeze discipline: existing rows stay
green; new behaviour gets new rows):

- `fallback_low_wrist_high_elbow` / `fallback_left_arm_low_wrist` — a clipped wrist
  with the elbow in frame resolves (to `P` via ids `[2,4]`), on either arm, proving
  the branch fires identically on both platforms.
- `indeterminate_low_wrist` is **repurposed** to drop the elbow too (`conf 0.3`),
  so it still asserts `null` — now exercising branch 4 (no in-frame proxy) instead
  of the old "low wrist alone" case, which is precisely the new fallback path.
- `fallback_bent_arm_uses_elbow_octant` — the Decision-2 tradeoff, pinned.

The change is in the shared Python reference (`_semaphore_ref.arm_id`) first, then
ported verbatim to both platforms, so the parity harness proves agreement rather
than asserting it.

## Consequences

- **The clipped-wrist friction class can read on iOS** without touching the
  facing-us contract: the fallback is inert on every full-confidence-wrist frame,
  so `test_vectors.json` + the temporal + facing-away + native fixtures stay
  byte-identical and green. No change to the alphabet, octant model, adapter flips,
  or `keypoint_contract.json`'s frozen data shape (only its prose `confidence_gate.rule`
  is corrected to match this ADR).
- **Platform-asymmetric value, symmetric code.** It helps iOS (Vision keeps the
  elbow), largely no-ops on Android (ML Kit drops both together) — but the decoder
  change is parity-identical, and the synthetic vectors exercise the branch on both.
- **The premise is unconfirmed on-device and is the load-bearing risk.** The whole
  lever rests on "Apple Vision keeps `elbow.conf ≥ MIN` while `wrist.conf < MIN` in
  a clipped pose." iOS session 2 inferred this structurally (screenshots, no
  numbers). The host-Vision `.MOV` sanity check (`ios/Tools/extract_vision_video.swift`
  over Brad's clips) measures it off-device; the on-device smoke confirms it live.
  If Vision turns out to drop wrist+elbow together (like Android), this change is
  correct-but-inert and stays only as cheap insurance.
- **Remaining acceptance (guardrail-d):** on-device smoke on iPhone + Pixel —
  reduced friction on L + the H/I/O/W/X/Z sextet + extended/clipping arms, **no
  regression on the easy/cardinal poses**, and a check that the bent-arm
  false-positive doesn't bite in normal use.

## How to run

```bash
# Regenerate the fixture (self-validating: re-decodes + asserts every vector).
# The facing-us decode must stay byte-identical:
uv run shared/tools/gen_test_vectors.py
git diff --exit-code -- shared/test_vectors.json   # only the new/repurposed rows differ — see the PR diff

# Parity (both platforms):
#   iOS:     xcodebuild test … -only-testing:SemaphoreTranslatorTests/ParityTests
#   Android: ./gradlew testDebugUnitTest --tests "*ParityTest"

# Off-device premise sanity check (host Apple Vision over a real clip):
ffmpeg -i <clip>.MOV -vf fps=4 -qscale:v 2 /tmp/frames/%05d.jpg
swift ios/Tools/extract_vision_video.swift /tmp/frames > /tmp/keypoints.jsonl
uv run ios/Tools/analyze_fallback_premise.py <clip-label> /tmp/keypoints.jsonl
#   [B] collinearity on reliable arms, [C] fallback-fires (premise), [D] both-gone (declines)
```

First measurement (2026-06-24, Brad's clips, host Vision): the premise holds — on
the clipping-heavy back-facing clips Vision kept `elbow.conf ≥ MIN` while the wrist
dropped below it in 35 (IMG_1370) and 24 (IMG_1373) arm-frames; on the clean clip
(IMG_1366) just 1, i.e. the fallback is inert when the wrist tracks well. The
straight-arm prior held on reliable arms (`shoulder→elbow` within the 20° octant
tolerance of `shoulder→wrist` 75–90% of the time, medians 1.7–8.6°), with a bounded
bent/transition tail the committer's smoothing absorbs. Host Vision ≠ live, and the
back-facing clips are a friendlier case for the fallback than facing-us Learn, so
the on-device smoke remains the load-bearing confirmation.

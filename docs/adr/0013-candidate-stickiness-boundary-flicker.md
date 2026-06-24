# ADR 0013 — Candidate stickiness: committer-side near-boundary flicker rejection

- **Status:** Accepted (2026-06-24)
- **Issue:** [#113](https://github.com/skylinetrailcomputing/semaphore-translator/issues/113)
  ([6a-19], lever E from the #101 diagnostic)
- **Context refs:** `docs/spike-101-pose-friction-diagnostic.md` (the measurement
  that re-ranked this lever to #1), spec §4.3 (quantize/classify), §4.4 (runtime
  constants); ADRs [0004](0004-temporal-commit-contract.md) (the committer state
  machine + the plurality vote this extends), [0009](0009-interpret-timing-profile.md)
  (the per-fork timing this deliberately does **not** fork).

## Context

The #101 on-device diagnostic (both platforms) overturned the spike's prior: at
usable framing the dominant pose-reading friction is **near-boundary octant
flicker at full keypoint confidence** (bucket 3), not the gated/clipped wrists
that were expected. An arm held within ~20° of an octant boundary, plus
hold-imprecision or low-light sensor noise, flickers between adjacent octants;
and the across-body sextet **H/I/O/W/X/Z are mutual angular neighbours**, so they
flicker among themselves (the measured "X committed O/Z/H/W before X").

The committer (ADR 0004) already smooths with a 5-frame plurality vote, but the X
trail showed it is not enough: a transient adjacent-octant neighbour can win the
plurality for a frame or two and seize the candidate, resetting the held pose's
hold timer (or, worse, committing the wrong neighbour).

### The constraint that picks the lever

The committer votes on the **mode-independent pose *symbol*** (the `classify`
output), never on per-arm angles or ids — and the single-frame `armId` quantizer
is **pinned by `test_vectors.json`** and must stay stateless. So two of the
issue's framings are off the table:

- **Angle-level boundary hysteresis** ("don't switch octant until the angle
  clears the boundary by a margin") cannot live in the committer (the angle is
  already quantized to a symbol by the time it votes) and cannot live in the
  quantizer without making it stateful — which breaks the single-frame parity
  model.
- **Widening `ANGLE_TOLERANCE_DEG`** is parity-coupled, *increases* octant
  collisions, and is the issue's explicit last resort.

The remaining parity-safe lever is **symbol-level temporal hysteresis in the
committer**: make the held candidate stickier so a transient neighbour can't flip
it.

## Decision 1 — Incumbent vote bias (`CANDIDATE_STICKINESS`)

In the committer's plurality vote, the **incumbent** — the candidate currently
being timed — gets a `CANDIDATE_STICKINESS` vote bonus. A challenger must out-vote
the incumbent *by that margin* to displace it, so a 1–2 frame adjacent-octant
neighbour (and even a brief 3-of-5 plurality at stickiness 2) cannot seize the
candidate from a held pose.

Two properties keep it from over-reaching:

- **Only a *determinate* incumbent gets the bonus** — never indeterminate
  (`null`/`nil`). Leaving an indeterminate transition for a new pose is therefore
  never slowed; stickiness protects a *held* pose, it does not make the committer
  sluggish to pick a pose up.
- **Stickiness `0` reduces exactly to the prior plain plurality** (verified: the
  vote still iterates the window in order and keeps the last value with the max
  *effective* count, which is the unchanged most-recent tie-break). So this is a
  clean generalization of ADR 0004's vote, not a new rule.

The starter value is **2** — the minimum that lets a held pose survive a transient
neighbour that reaches a 3-of-5 plurality (effective 2 + 2 = 4 > 3). It is tuned
on-device against the #101 D/X-class footage (paired with the #112 framing/lighting
cue, which reduces how hard stickiness must work).

## Decision 2 — `CANDIDATE_STICKINESS` is flat / non-forking

Unlike the committer's *timing* constants (`COMMIT_HOLD_MS` / `INTER_CHAR_GAP_MS`
/ `SMOOTHING_WINDOW`), which fork by lens under `timing_profiles` (ADR 0009),
stickiness is a **flat** key shared by every profile. Near-boundary angle noise is
a property of the pose estimator and the signer's hold, **not** of the camera lens
or the signer's speed — the same rationale that keeps the geometry constants
(`ANGLE_TOLERANCE_DEG`, `MIN_KEYPOINT_CONFIDENCE`) flat (ADR 0006). It rides in the
flat keys only; both platform loaders read it from the top level on every profile
path, and the reference `timing_for()` adds it unchanged regardless of fork.

## Decision 3 — Parity discipline

- **`test_vectors.json` is byte-unchanged.** The single-frame quantizer is
  untouched; stickiness is purely temporal. (Verified: re-running
  `gen_test_vectors.py` reproduces it identically.)
- **`temporal_vectors.json` + `temporal_vectors_interpret.json` are regenerated**
  from the self-validating `gen_temporal_vectors.py`, whose reference `Committer`
  is the single source of truth the Swift/Kotlin ports mirror. Running the
  generator *is* the correctness check.
- A new sequence — **`incumbent_holds_through_neighbour_blip`** — pins the
  behaviour and is *discriminating*: a 3-frame neighbour blip that would seize the
  candidate and prevent the held pose's commit is out-voted by the bonus, so the
  pose commits on its original hold. A port that dropped `CANDIDATE_STICKINESS`
  emits `""` here, not `"A"`, and fails the parity test.
- The **brief-REST double-letter re-arm boundary shifts by one frame** (the held
  letter's incumbent bonus makes a REST take 4 in-window frames to become the
  candidate, not 3): `same_letter_rest_too_short` is now 3 frames → `"L"` and
  `same_letter_rest_min_rearms` is 4 frames → `"LL"`. The boundary is still pinned
  to a single frame on both sides, and the per-fork `REST_REARM` counts (Learn 5 /
  Interpret 4, ADR 0009) still produce their documented `LL` / `L L` outcomes —
  stickiness shifted the internal frame→dwell arithmetic uniformly, leaving the
  fork values intact.

## Consequences

- The Swift `Committer` and Kotlin `Committer` add a `candidateStickiness`
  constant (carried in `CommitTiming`, sourced from the flat config key) and the
  bias term in `vote()`; the temporal parity harness proves both ports + the
  reference agree byte-for-byte on the regenerated vectors.
- **Known failure mode (the on-device gate):** incumbent bias is double-edged. It
  stabilizes a *correctly* held pose, but in a churny pose where a *wrong*
  neighbour reaches an early plurality it can entrench that wrong read for the
  hold window. Stickiness is kept small (2) to bound this, and the combined
  #112+#113 on-device smoke must confirm that D/X-class flicker drops **without**
  a regression on the clean/cardinal poses and **without** entrenching a wrong
  early read on X. If entrenchment shows, the fallback is a larger / recency-
  weighted `SMOOTHING_WINDOW` (smooths without the directional bias).
- An arm that sits *persistently* past an octant edge (a genuine, sustained
  misquantization) is **not** fixable by any symbol-level vote bias — that residue
  belongs to the #112 framing/lighting cue and, ultimately, the Epic-7 trained
  classifier (#7). #113 is scoped to absorbing *transient* neighbour incursions.

## How to run

```bash
# Regenerate the timed fixtures (self-validating); re-run after changing
# CANDIDATE_STICKINESS or any committer constant.
uv run shared/tools/gen_temporal_vectors.py
# The single-frame fixtures share the reference and must stay byte-identical.
uv run shared/tools/gen_test_vectors.py
```

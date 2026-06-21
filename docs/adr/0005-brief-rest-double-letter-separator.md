# ADR 0005 — A brief REST is the double-letter separator (supersedes ADR 0004 Decision 3)

- **Status:** Accepted (2026-06-21)
- **Supersedes:** [ADR 0004](0004-temporal-commit-contract.md) **Decision 3**
  ("The same-symbol gap is *indeterminate*, not `REST`"). ADR 0004's other
  decisions (1, 2, 4, 5) stand unchanged.
- **Issue:** [#40](https://github.com/skylinetrailcomputing/semaphore-translator/issues/40)
  (Epic 4 follow-up)
- **Context refs:** spec §4.4 (runtime constants), §4.5; ADR 0004 (the committer
  state machine this amends) — Decision 2 (the machine) and Decision 3 (the clause
  being flipped); FR4 (a held pose → exactly one committed character).

## Context

ADR 0004 Decision 3 split doubled-letter separation from the word space **by
contract**: `REST` (both arms straight down) always meant a space, and a doubled
letter (the `LL` in `HELLO`) had to be separated by an **indeterminate
transition** — the arms classifying to `null` mid-motion — never a rest. It
flagged this as a deliberate v1 guess and pre-registered its own revisit:

> Real charts sometimes describe returning the flags to a neutral/"home" position
> between repeated letters... If real signing data later shows users rest (not
> transition) between doubles, **revisit here.**

The #35 on-device smoke (iPhone 16 + Pixel 9a) is that data. Two findings flipped
the guess:

1. **A brief rest *is* the conventional double-letter gesture.** Signalling
   practice returns the flags briefly to the home/down position between repeated
   letters — the same physical pose as the word space, only briefer (sources
   below). The scenario Decision 3 "worried about" is the real one; the v1 guess
   (indeterminate transition) was wrong.
2. **The indeterminate-transition path is the wrong production UX.** Driving an
   arm off-octant to `null` and back (a) is a quirk of the pose model, not a
   gesture a signer expects to need; (b) is platform-inconsistent — on iOS
   (Vision) a keypoint was noticeably harder to push off-octant than on Android
   (ML Kit), so the required wobble differed by platform; and (c) worst,
   **incidental** hold jitter that dips to `null` produced an **unintended**
   double — a steady `A` that briefly wobbled off-octant and back could commit
   `AA`, violating FR4.

## Decision — re-arm the same-symbol gate on a *brief REST*, not on an indeterminate gap

The committer's `gap_ok` (ADR 0004 Decision 2 — the same-symbol re-commit gate)
now re-arms on a **brief REST**, and **indeterminate no longer re-arms**:

| Gesture between two identical letters | ADR 0004 (old) | ADR 0005 (new) |
|---|---|---|
| brief off-octant wobble → `null` → back | doubles (unintended) | **coalesces to one** |
| brief **REST** (both arms down), voted dwell ∈ `[INTER_CHAR_GAP_MS, COMMIT_HOLD_MS)` | one letter | **doubles** (intentional) |
| sustained **REST**, voted dwell `≥ COMMIT_HOLD_MS` | space | space (unchanged) |

One change closes both #40 follow-ups: intentional doubles become the
conventional brief rest, and incidental off-octant jitter coalesces to a single
character (the FR4-robustness half).

### Mechanics (the only change to the Decision-2 machine)

The gap-tracking step keys off the **voted candidate being `REST`** (dwell
measured from when the candidate became `REST`) instead of the candidate being
indeterminate (`null`):

- Voted candidate `REST`, dwell `∈ [INTER_CHAR_GAP_MS, COMMIT_HOLD_MS)` →
  `gap_ok = true` (re-arm), **no emit**.
- Voted candidate `REST`, dwell `≥ COMMIT_HOLD_MS` → the ordinary commit step
  fires `interpret(REST) → space`. The re-arm window is **half-open at the top**,
  so the *same* sustained REST does not also re-arm after its space commits —
  which is what stops a held REST from streaming spaces.
- Voted candidate indeterminate (`null`) → no longer re-arms anything.

`REST` stays an ordinary committable symbol; the gate just *also* watches its
dwell. Everything else in Decision 2 is unchanged: `last_committed` / `gap_ok`
bookkeeping, distinct symbols streaming on their hold alone, and the timer
running off the **voted candidate, not raw frames** (so a port that armed on raw
frames still fails a boundary fixture). `INTER_CHAR_GAP_MS` is **reused** as the
re-arm dwell lower bound — same frozen value (300 ms), no new constant added.

Reset semantics (ADR 0004 Decision 4) are unchanged: a true signer-loss `reset()`
still clears `last_committed`, so a full signer-loss remains a hard boundary
distinct from a rest.

## Consequences

- **Intentional doubles are conventional; incidental doubles coalesce.** `LL` via
  a brief rest; a wobbling held letter stays a single character.
- **A brief rest and a word space are the same pose, told apart by dwell** —
  `[GAP, HOLD)` re-arms, `≥ HOLD` commits a space. This is a genuine new semantic
  choice, so it is encoded in the fixtures and any regression fails the parity
  ports loudly. New / changed `shared/temporal_vectors.json` sequences:
  `same_letter_doubles_via_brief_rest`, `sustained_rest_spaces_between_letters`,
  `coalesce_off_octant_wobble`, and the `same_letter_rest_too_short` /
  `same_letter_rest_min_rearms` boundary pair (the REST-dwell analogue of ADR
  0004's `same_letter_gap_*` pair).
- **"No streamed spaces" is scoped to a *single continuous* REST hold.** The
  half-open top stops one held REST from emitting more than one space. Two
  *separate* REST holds split by an intervening non-REST gap each commit a space —
  the second hold's own dwell re-arms the gate — which is the intended consecutive
  word-boundary behavior, not streaming. (Re-arm keys off REST, so the in-between
  gap can be indeterminate or any non-REST pose; only the deliberate second rest
  produces the second space.)
- **The live layer (#4.5 / #35) is unaffected** — this is internal to the
  committer; the screens still feed `classify` + `tMs` and render `committedText`.
- **The contract is re-frozen.** `shared/temporal_vectors.json` regenerated
  (self-validating) and both ports (`Committer.swift`, `Committer.kt`) updated in
  lockstep; the temporal parity harness is green on both platforms. Spec §4.4 and
  the `INTER_CHAR_GAP_MS` field note are tightened to match.
- **Still pending: on-device re-smoke.** The gesture changed, so #40's checklist
  keeps a device re-smoke (`LL` via a brief rest; a steady letter that wobbles
  stays single) — code/parity green is necessary but not sufficient here.

## Sources (double-letter / brief-rest convention)

Cross-checked against the same source families used in
`shared/ALPHABET-VERIFICATION.md`:

- **americanflags.com** — the rest position is "directly in front of the
  signaler with both arms straight down" (= our `REST`, ids `0,0`), and signing
  starts from it: <https://www.americanflags.com/blog/post/semaphore-flags>
- **rumkin.com** semaphore tool — "Double letters should get a very brief space
  inserted"; the same rest symbol serves "for the rest symbol **and** to split
  double letters" — i.e. a *brief* rest splits a double, a *held* rest is the
  space, exactly the dwell split above:
  <https://rumkin.com/tools/cipher/flag-semaphore/>

## How to run

Same discipline as ADR 0004 — regenerate the self-validating fixtures, then the
parity ports must stay byte-identical:

```bash
uv run shared/tools/gen_temporal_vectors.py
# iOS:     xcodebuild test ... -only-testing:SemaphoreTranslatorTests/TemporalParityTests
# Android: ./gradlew testDebugUnitTest --tests "*TemporalParityTest"
```

# ADR 0004 — The temporal commit contract (committer state machine)

- **Status:** Accepted (2026-06-20)
- **Issue:** [#31](https://github.com/skylinetrailcomputing/semaphore-translator/issues/31) ([4.1], Epic 4)
- **Context refs:** spec §4.3 (quantize/classify), §4.4 (runtime constants),
  §4.5 (numeric-mode FSM), FR3–FR4, FR7; ADRs
  [0001](0001-parity-harness-and-native-project-shape.md) (decoder decoupled from
  the wire format; reference decoder both platforms mirror) and
  [0003](0003-live-debug-preview-and-app-contract-loader.md) (per-frame decode,
  no smoothing yet; the live mode-flip jitter this ADR fixes).

## Context

Epic 4 turns the per-frame decoder into a **temporal committer**: a held pose
must produce exactly one committed character (FR4), and the numeric-mode switch
must stop flipping on a single fleeting frame (the #23 live symptom — a
one-frame NUMERALS or J pose flipped mode instantly). The committer is
**time-dependent**, so — like Epic 1 (alphabet) and #20 (adapter contract) — the
machine and its fixtures are frozen **once, here**, before the two platform ports
(#4.2 iOS, #4.3 Android) diverge. Otherwise the ports become two interpretations
of "hold to commit," not faithful twins.

This is a **gate ticket**: it freezes the contract, splits the decoder so the
committer has a votable unit, plumbs the timing constants, and ships the timed
parity fixtures. It does **not** port the committer (that is #4.2/#4.3) or wire it
into the live capture loop (that is #4.5).

## Decision 1 — The votable unit is the mode-independent pose symbol

The smoother votes on the **`classify` output** — the pose identity (a letter
pose / `NUMERALS` / `REST` / `indeterminate`) — **not** the emitted character.
The emitted character depends on `mode`, and `mode` is exactly what is unstable
frame-to-frame: a steadily-held "A" pose would flicker `A`↔`1` as a noisy mode
wobbled, so voting on the emitted char is incoherent. Voting on the pre-mode pose
is stable; `mode` is then applied **once, at commit**.

This requires exposing the decoder's two stages, which were already present
internally (spec §4.3/§4.5):

- **`classify(kp) -> symbol?`** — mode-independent pose identity (the sorted
  id-pair → symbol lookup, or `nil`/`null` when either arm is indeterminate).
- **`interpret(symbol, mode) -> (emit, mode)`** — the §4.5 numeric-mode FSM.
- **`decodeFrame` stays their composition**, so the existing per-frame parity
  tests (`test_vectors.json`) are untouched — a small, pure refactor with no
  behavior change. The triple-returning helper that also yields the white-box
  position ids is now `classifyArms`, private behind `classify`.

## Decision 2 — The committer state machine

A pure object that takes **injected time** — `process(symbol, t_ms)` and
`reset()` never call `now()` — so the fixtures are deterministic (the same
discipline as the decoder taking plain values, not JSON, in ADR 0001). State:

```
window         ring buffer of the last SMOOTHING_WINDOW votable symbols (null allowed)
mode           LETTERS | NUMERIC                     (decoder mode, §4.5)
candidate      the voted candidate currently being timed
candidate_since t_ms the candidate last changed value (the hold timer's origin)
last_committed the last committed symbol             (the same-symbol lock key)
gap_ok         has an indeterminate gap been seen since the last commit?
indet_since    t_ms the current continuous-indeterminate run began (else null)
```

Per `process(symbol, t_ms)`:

1. **Vote.** Push `symbol` into `window` (evict oldest past `SMOOTHING_WINDOW`).
   `candidate` = the **plurality** value in the window, **ties broken by the
   most-recent occurrence**. `indeterminate` (null) is a legitimate vote value
   and can win. (Plurality, not strict majority: with a window of 5 a 1–2 frame
   flicker — 1–2 of 5 — can never out-vote a held pose, which is the debounce we
   want; a fuller "no clear winner" window simply trends to whichever value is
   freshest, almost always the indeterminate transition.)
   **Partial window (pinned for the ports):** before the buffer holds
   `SMOOTHING_WINDOW` entries — at sequence start, and immediately after
   `reset()` — vote over however many entries are present; a single-entry buffer
   returns that entry. Both ports **must** vote on the partial window rather than
   wait for it to fill; a port that waited would commit the first character
   `SMOOTHING_WINDOW − 1` frames late. Because `COMMIT_HOLD_MS` (6 frames at the
   fixtures' cadence) already exceeds `SMOOTHING_WINDOW` (5), this divergence
   never changes `expected_committed`, only the *frame* a commit lands on — which
   is why the ports assert the per-frame `expected_emit`, not just the rollup
   (see Decision 5).
2. **Hold timer.** If `candidate` changed value, restart it
   (`candidate_since = t_ms`).
3. **Gap tracking.** This timer runs off the **voted `candidate`, not the raw
   incoming `symbol`** — `indet_since` is set when the *candidate* (step 1's
   plurality result) becomes indeterminate and cleared the moment it becomes any
   determinate symbol. Once `t_ms - indet_since >= INTER_CHAR_GAP_MS`, set
   `gap_ok = true`. Pinning it to the voted candidate (not raw frames) is
   load-bearing: a port that armed the gap on raw indeterminate frames would
   re-arm earlier and diverge — the `same_letter_gap_too_short` /
   `same_letter_min_gap_rearms` fixtures (a 3-frame gap that must **not** re-arm
   vs. a 4-frame gap that must) pin this boundary so an off-by-one port fails one
   of them.

   > **Effective gap latency.** Because the candidate must first *flip* to
   > indeterminate (the window takes a few frames to fill with nulls) and the
   > nulls likewise linger a few frames into the next pose, the wall-clock
   > indeterminate motion a signer needs before a doubled letter re-commits is
   > larger than `INTER_CHAR_GAP_MS` alone — roughly `(vote-flip frames) ×
   > frame-interval + INTER_CHAR_GAP_MS`. That extra term scales with the capture
   > frame rate, so it shrinks on faster cameras; the fixtures' 100 ms cadence is
   > illustrative, not a normative part of the contract (the ports replay the
   > exact `t_ms` values, so the boundary is fixed *for the vectors*).
4. **Commit.** If `candidate` is determinate **and** held for
   `t_ms - candidate_since >= COMMIT_HOLD_MS` **and** the same-symbol gate passes
   (`candidate != last_committed` **or** `gap_ok`): run
   `interpret(candidate, mode)` → set `mode`, `last_committed = candidate`,
   `gap_ok = false`, and return the emit. Otherwise return `""`.

Consequences that fall out of this, by design:

- **Mode flips only on a *committed* control pose.** `interpret` runs only at
  commit, so `NUMERALS` and the J-pose letters-shift must themselves be *held*
  to flip mode — the debounced mode switch that fixes #23.
- **A held pose commits exactly once.** After commit, `last_committed` +
  `gap_ok = false` block re-commit for as long as the same candidate is held.
- **Distinct symbols stream; repeats need a gap.** A *different* symbol commits
  on its hold alone (`candidate != last_committed`). The *same* symbol re-commits
  only after an intervening indeterminate gap re-arms `gap_ok` — so `LL`/`AA`
  need a transition between them, but `AB`/`ABA` stream freely.

## Decision 3 — The same-symbol gap is *indeterminate*, not `REST`

> **⚠️ Superseded by [ADR 0005](0005-brief-rest-double-letter-separator.md)
> (2026-06-21).** The #35 on-device smoke confirmed the "revisit" clause at the
> end of this decision: a **brief REST** is the conventional double-letter
> separator, and the indeterminate-transition path produced *unintended* doubles
> from incidental hold jitter. The same-symbol gate now re-arms on a brief REST
> (voted dwell in `[INTER_CHAR_GAP_MS, COMMIT_HOLD_MS)`), not on an indeterminate
> gap; indeterminate no longer re-arms. The *rationale* below — gating
> *same*-symbol re-commit only, so distinct letters stream while doubles need a
> separator — still stands; only the re-arm **trigger** changed. Kept verbatim as
> the historical record.

`INTER_CHAR_GAP_MS` gates **same-symbol re-commit only**, and the gap that
re-arms it is a run of **indeterminate** frames (arms mid-transition, classifying
to null) — **not** a `REST`. `REST` (both arms straight down) is a determinate,
committable symbol that emits a space and persists mode; it is *not* a gap.

This **deviates from the literal §4.4 wording** ("min indeterminate gap before
the *next character* can commit", which read as gating *every* character).
Gating only the *same* symbol is what lets distinct letters stream at speed while
still disambiguating doubled letters; §4.4/§4.5 prose is tightened to match.

> **Deliberate v1 simplification, flagged for review.** Real charts sometimes
> describe returning the flags to a neutral/"home" position between repeated
> letters, which can read as the same physical pose as the space/rest. We split
> them by contract: `REST` always means space; a *doubled letter* is separated by
> an indeterminate transition, not a rest. This keeps the two unambiguous on a
> single-frame-per-symbol decoder. If real signing data later shows users rest
> (not transition) between doubles, revisit here.

## Decision 4 — Reset semantics: short gap vs. hard signer-loss

Two distinct "the signer stopped" cases, deliberately handled at different layers:

- **Short indeterminate gap** (arms between poses): blocks commit (can't commit a
  null candidate) and re-arms the same-symbol gate, but **preserves mode and the
  window**. This lives *inside* the committer — it is just indeterminate votes.
- **True signer-loss** (no upper body in frame): a hard `reset()` that clears the
  window/buffer and sets `mode -> LETTERS` (§4.5). This is driven by the existing
  no-signer **watchdog** (the freshness timeout from ADR 0003) and wired at the
  **live layer (#4.5)** — the committer never decides signer-loss; it is *told*
  to reset. Keeping `reset()` an external call keeps the committer pure.

## Decision 5 — Config plumbing + the timed fixtures

- **Surface the three timing constants.** All four config DTOs parsed only
  `ANGLE_TOLERANCE_DEG` + `MIN_KEYPOINT_CONFIDENCE` and silently dropped the
  rest. `COMMIT_HOLD_MS` / `SMOOTHING_WINDOW` / `INTER_CHAR_GAP_MS` are now parsed
  by the iOS app `ContractLoader.Config`, the iOS test `SemaphoreConfig`, the
  Android app loader, and the Android test `SemaphoreConfig`. Each platform gains
  a small `CommitTiming` value type (and a `makeCommitTiming` loader) — the
  committer's plain-value config, mirroring how the decoder takes plain values;
  the live layer (#4.5) will inject it. No committer is built here.
- **`shared/temporal_vectors.json` is a separate file** from `test_vectors.json`
  (whose row schema and README are explicitly untimed — "one frame per committed
  symbol"). It carries timed frame sequences and the string each commits; the
  same separation native fixtures got their own file. The generator
  `shared/tools/gen_temporal_vectors.py` **emits and self-validates** — it re-runs
  the reference committer over every sequence and asserts `expected_committed`
  before writing, so running it *is* the fixture's correctness check (same as
  #12/#14). It shares the per-frame decode + keypoint geometry with
  `gen_test_vectors.py` via a new `shared/tools/_semaphore_ref.py`, so the two
  fixture sets cannot drift (one Python reference, two generators).
- **Ports assert per-frame, not just the rollup.** Each frame carries
  `expected_symbol` (the votable unit) and `expected_emit` (what commits on that
  exact frame); `expected_committed` is their concatenation. The ports' canonical
  assertion is the **per-frame** `expected_emit` — it pins *when* each commit
  lands, which is what catches a partial-window or off-by-one-gap port whose final
  string happens to match (Decision 2). `expected_committed` is the convenience
  rollup / self-validation anchor.
- **Coverage:** debounce (a 1–2 frame spurious pose never commits), distinct-letter
  streaming, same-symbol repeat needing a gap, the gap **boundary** pinned on both
  sides (a 3-frame gap that must not re-arm, a 4-frame gap that must), both mode
  transitions *through* the committer, a signer-loss reset, and `REST` as an
  ordinary committable space.

## Consequences

- The Swift/Kotlin committer ports (#4.2/#4.3) implement exactly the Decision-2
  machine and pass `temporal_vectors.json` identically — the temporal analogue of
  the per-frame parity guarantee (spec §6).
- `decodeFrame` and the per-frame parity harness are unchanged and stay green;
  the decoder split is pure refactor.
- The live debug screen (ADR 0003) still decodes per-frame; #4.5 swaps in the
  committer and wires `reset()` to the no-signer watchdog.
- The plurality tie-break and the indeterminate-vs-`REST` gap (Decisions 2/3) are
  the two genuine new semantic choices; they are encoded in the fixtures so any
  future change to them fails the parity ports loudly.

## How to run

```bash
# Regenerate the timed fixtures (self-validating); re-run after any contract change.
uv run shared/tools/gen_temporal_vectors.py
# The per-frame fixtures share the same reference and must stay byte-identical.
uv run shared/tools/gen_test_vectors.py
```

# ADR 0009 — Per-fork committer timing profiles (the Interpret timing fork)

- **Status:** Accepted (2026-06-23); Interpret values tuned against footage by
  **#76 (6a-8)** (2026-06-23): `COMMIT_HOLD_MS` 400 → **350**. The contract + the
  cross-platform mechanism are verified by the dual-platform parity tests below; the
  on-device behavioural smoke is the remaining guardrail-(d) step (see
  *Consequences*). Tuning method + before/after: `docs/interpret-timing-tuning.md`.
- **Issue:** [#75](https://github.com/skylinetrailcomputing/semaphore-translator/issues/75)
  ([6a-7], the architectural fork for the Interpret push); parent epic
  [#67](https://github.com/skylinetrailcomputing/semaphore-translator/issues/67) (6a).
- **Context refs:** spec §4.4 (the frozen timing constants); ADRs
  [0004](0004-temporal-commit-contract.md) (the temporal committer + its
  `CommitTiming` value type and timed parity fixtures),
  [0005](0005-brief-rest-double-letter-separator.md) (the brief-REST double-letter
  re-arm), and [0006](0006-rear-camera-decode-geometry.md) (the rear lens reuses
  the front decode path verbatim — the decode/adapter/classify geometry is
  lens-agnostic). Tunes forward into
  [#76](https://github.com/skylinetrailcomputing/semaphore-translator/issues/76) (6a-8).

## Context

The Interpret fork (rear camera, reading *another* signer) and the Learn fork
(front camera, self-signing) have so far shared one global timing: `COMMIT_HOLD_MS`
/ `INTER_CHAR_GAP_MS` / `SMOOTHING_WINDOW` in `semaphore_config.json`, the three
constants the temporal committer's `CommitTiming` carries (ADR 0004). Real-world
signers an Interpret user points at — a lifeguard, a ship — move **faster** than
the deliberate self-signing the Learn timing is tuned for: a 600 ms commit-hold
that feels stable when you sign to yourself can miss letters when you read someone
signing at speed.

The fix is **per-fork timing profiles, never a global retune** — the Learn timing
is frozen and shipped; a separate Interpret profile is what the rear lens selects.
This ADR freezes how the fork is expressed in the contract, selected on each
platform, and parity-tested, so the two ports don't become two interpretations of
"the Interpret fork is faster."

This is **additive and decode-safe by construction.** The committer is already
fully parameterized by `CommitTiming` on all three surfaces (the Python reference
`Committer`, Swift `Committer`, Kotlin `Committer`), so **no committer/decoder/
adapter code changes** — only *which* timing is loaded, and *when*. The classify /
geometry path stays byte-identical and lens-agnostic (ADR 0006).

## Decision 1 — What forks: the three `CommitTiming` constants, and nothing else

A "timing profile" forks exactly the committer's temporal behaviour:
`COMMIT_HOLD_MS`, `INTER_CHAR_GAP_MS`, `SMOOTHING_WINDOW`. The geometry constants
`ANGLE_TOLERANCE_DEG` / `MIN_KEYPOINT_CONFIDENCE` **do not fork** — they feed the
lens-agnostic decoder (ADR 0006), and forking them would mean the rear camera
decodes *geometry* differently, which is a decode-path change this fork explicitly
is not. If a future need to loosen the rear decode geometry appears, it is its own
decision under ADR 0006's umbrella (touching the adapter + native fixtures), not a
"timing profile."

## Decision 2 — Schema: the flat keys are the Learn profile; `timing_profiles` is an additive, fully-specified override

`semaphore_config.json` keeps its flat timing keys **unchanged** — they *are* the
Learn (front-camera) profile, the single source of truth for it. A new additive
`timing_profiles` object holds the non-Learn forks:

```jsonc
"COMMIT_HOLD_MS": 600, "INTER_CHAR_GAP_MS": 200, "SMOOTHING_WINDOW": 5,  // Learn (flat)
"timing_profiles": {
  "interpret": { "COMMIT_HOLD_MS": 350, "INTER_CHAR_GAP_MS": 200, "SMOOTHING_WINDOW": 5 }
}
```

- **The flat keys stay byte-unchanged**, so every existing consumer (the geometry
  params, the Python reference's module-level constants, both test DTOs, the
  existing `temporal_vectors.json`) is untouched. There is **no
  `timing_profiles.learn`** — Learn is the base, avoiding two drifting sources of
  truth for it.
- **Each profile is FULLY SPECIFIED (all three keys), never a delta.** A delta
  would need identical merge semantics ported across Python / Swift / Kotlin —
  exactly the cross-language logic the parity discipline avoids. Full specification
  means each loader reads three keys, no merge. (The starter Interpret block
  differs from Learn only in `COMMIT_HOLD_MS`, but carries all three so #76 can
  tune any of them by editing values + regenerating, with no code change.)
- **A selected profile that is missing or omits a key is fatal — never a silent
  fallback to Learn.** Python `KeyError`, Swift non-optional `Codable`
  (`ContractLoader.makeCommitTiming` throws `missingTimingProfile`), Kotlin
  required `getInt`/`getDouble` throw. Running the rear lens at the wrong speed
  because a contract typo quietly degraded to Learn is the failure this rules out.

## Decision 3 — Selection: a pure lens→profile mapping at the committer-construction site

The profile is derived from the camera lens (the lens *is* the mode selector —
front = Learn, rear = Interpret; ADR 0006: "Interpret's lens selects"), as a
**pure, unit-tested function**, applied at the single site where each platform
builds the committer:

- iOS: `TimingProfile(cameraPosition:)` (`.back → .interpret`, else `.learn`) in
  `PreviewViewModel.start()`, passed **explicitly** to
  `ContractLoader.makeCommitTiming(profile:)`. The view model is re-created per
  lens, so the committer's profile is fixed for its lifetime.
- Android: `TimingProfile.forLensFacing(cameraLens.lensFacing)` in
  `SemaphoreScreen`, with `timingProfile` **in the committer's `remember` key**
  (`remember(decoder, timingProfile)`) so the cached committer is rebuilt with
  fresh timing + state if the active profile ever changes. (Today each route is a
  distinct composition with a fixed lens, so it can't change mid-screen — this is
  defensive against a future in-screen lens toggle.)

The mapping is a pure function (keyed on the lens position / CameraX `lensFacing`
int, not a live capture object) so it is **unit-tested directly** — the parity
tests prove the vectors pass *given* a profile; the mapping test proves the app
*selects* the right one from the lens (the DoD's "Interpret uses the new profile",
closed at the unit level; the on-device smoke closes it at the integration level).
A default of `.learn` on the loader keeps test/legacy callers unchanged; the
production call sites pass the profile explicitly, so a missed default can't
silently run the rear lens at Learn speed. If the lens↔mode coupling ever breaks
(e.g. a rear-camera Learn mode), selection becomes an explicit caller argument — a
localized change at that one site.

## Decision 4 — One self-validating fixture file per profile; Learn's stays byte-identical

`gen_temporal_vectors.py` is parameterized by profile and emits **one file per
profile** — `temporal_vectors.json` (Learn, **byte-for-byte unchanged**) and
`temporal_vectors_interpret.json` (new). The Learn pass reproduces today's exact
constants + authoring, enforced by `git diff --exit-code` on the file. Each
platform gains an `InterpretTemporalParity` test that replays the Interpret file
through a committer built from the **interpret** profile — the temporal-parity
guarantee (spec §6), now for the rear fork too.

### The load-bearing subtlety: `COMMIT_HOLD_MS` also bounds the re-arm window

`COMMIT_HOLD_MS` is **not only** a commit-latency knob — it is the **upper bound of
the brief-REST double-letter re-arm window `[INTER_CHAR_GAP_MS, COMMIT_HOLD_MS)`**
(ADR 0005). Lowering it 600 → 350 *narrows* that window, so the rear-fork timing
genuinely changes the double-letter boundary, not just *when* a commit lands. Two
consequences, both pinned by the generator's self-validation and the parity tests:

1. **The fixture's brief-REST double count `REST_REARM` is profile-dependent.** In
   the brief-REST fixtures the rest is bracketed by held `L` frames, so the trailing
   `L` truncates the exit flush and the *raw* rest count sets the peak: at
   `DT_MS=100` the voted-candidate `REST` dwell steps `200 / 300 / 400 ms` for a
   3 / 4 / 5-frame rest. The Interpret hold gives the re-arm window `[200, 350)` — it
   admits the 4-frame dwell (`300 ms` → re-arm → `"LL"`) but excludes the 5-frame
   dwell (`400 ms ≥ 350` → **space** → `"L L"`). Learn's `[200, 600)` admits the
   5-frame dwell too, so Learn uses `REST_REARM=5` (→ `"LL"`) and Interpret
   `REST_REARM=4`. Every *other* authored count is profile-independent — including
   the re-arm boundary pair (`REST_GAP_TOO_SHORT=2` / `REST_GAP_MIN_REARM=3`), which
   is set by the smoothing vote floor and the shared `INTER_CHAR_GAP_MS`, both
   unchanged. (At the old 400 ms hold the window was `[200, 400)`; the same `4 / 5`
   split holds, so the tune to 350 regenerated the file without changing any frame —
   only the embedded hold metadata moved.)
2. **A discriminating sequence proves the fork is real, not self-consistent.** The
   Interpret file carries `interpret_rest5_spaces_not_doubles`: a `REST_REARM`-Learn
   (5)-frame brief rest — the dwell that *doubles* under Learn's 600 ms hold —
   instead commits a **space** under the 350 ms hold (`"L L"`, not `"LL"`). Both
   platforms assert it, and the unit test also replays its frames through *Learn*
   timing and asserts the opposite `"LL"`, so a port that ignored the Interpret
   `COMMIT_HOLD_MS` would fail loudly.

(The fixtures' `DT_MS=100` is illustrative, not normative — at hold 350 it is below
`SMOOTHING_WINDOW` (5), losing ADR 0004 Decision 2's *nice* "rollup insensitive to
the partial-window choice" property, but **not** correctness: both ports vote on
the partial window identically, mirror the reference, and assert the per-frame
`expected_emit`. A `DT_MS=50` alternative that preserves ≥5 frames/hold was
considered and rejected — it pushes the re-arm boundary pair below
`INTER_CHAR_GAP_MS`, collapsing both boundary fixtures to `"L"` and pinning
nothing.)

## Consequences

- **Interpret reads faster-held letters** without retuning Learn; Learn parity is
  literally byte-identical (`temporal_vectors.json` + `test_vectors.json` both
  `git diff`-clean after regeneration). The decode/adapter/classify/committer
  *code* is untouched — parity risk ~0, consistent with ADR 0006.
- **The fork is parity-pinned on both platforms.** New `InterpretTemporalParity`
  suites (iOS XCTest, Android JUnit) replay the Interpret file and assert the
  lens→profile mapping + the discriminating boundary; green on both is the
  cross-platform guarantee for the rear fork.
- **The Interpret values are tuned against footage (#76 / 6a-8).**
  `COMMIT_HOLD_MS` is **350** (down from the provisional 400). Replaying a real
  ~A–Z fast-signer clip through the canonical decode + commit reference showed the
  fork already lifts capture from **9/26** letters (Learn 600) to **16/26**
  (Interpret), and that the residual misses are *geometry* — 8 letters never
  classify (wide / across-body poses; #110–112) — not timing. 350 matches 400's
  decode **and** its brief-REST double-letter window on that clip while adding
  headroom for slightly-faster field signers; pushing below ~350 buys a couple more
  single letters but narrows the double-letter window, which an all-distinct clip
  can't validate (deferred to a doubles-containing clip). The tune was
  **values-only** — regenerated `temporal_vectors_interpret.json` (at `DT_MS=100`
  not a single frame changed, only the hold metadata), no code change.
  `INTER_CHAR_GAP_MS` / `SMOOTHING_WINDOW` unchanged. Method + before/after +
  reusable harness: `docs/interpret-timing-tuning.md`.
- **Remaining acceptance (guardrail-d):** an on-device smoke on both targets
  (iPhone 16 + Pixel 9a) confirming Interpret (rear) commits visibly faster than
  Learn (front) and Learn is unaffected. The mechanism is parity-verified; this is
  the "did a human watch it" step the unit tests can't be.

## How to run

```bash
# Regenerate both profile fixtures (self-validating); Learn must stay byte-identical.
uv run shared/tools/gen_temporal_vectors.py
git diff --exit-code -- shared/temporal_vectors.json          # Learn unchanged
uv run shared/tools/gen_test_vectors.py
git diff --exit-code -- shared/test_vectors.json              # per-frame unchanged

# Parity + selection tests (both forks):
#   iOS:     xcodebuild test … -only-testing:SemaphoreTranslatorTests/TemporalParityTests \
#                              -only-testing:SemaphoreTranslatorTests/InterpretTemporalParityTests
#   Android: ./gradlew testDebugUnitTest --tests "*TemporalParityTest" \
#                              --tests "*InterpretTemporalParityTest"
```

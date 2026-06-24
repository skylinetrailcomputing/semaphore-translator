# ADR 0014 — Learn "Enable numerals" toggle: a live-layer gate, not a decode-contract change

- **Status:** Accepted (2026-06-24)
- **Issue:** [#127](https://github.com/skylinetrailcomputing/semaphore-translator/issues/127)
  (beta polish)
- **Context refs:** spec §4.5 (the mode machine / `interpret`), §4.4 (runtime
  constants); ADRs [0004](0004-temporal-commit-contract.md) (the committer that owns
  mode and runs `interpret` at commit), [0006](0006-rear-camera-decode-geometry.md)
  (lens-agnostic decode — the rear lens reuses the front decode path verbatim),
  [0008](0008-learn-source-sanitization.md) (the passage sanitiser these surfaces
  read), [0011](0011-interpret-facing-away-flip.md) (the precedent: a user toggle
  whose *gating* is not vectored, only its keypoint math is).

## Context

A beta-polish request: let a learner practise **letters only**. A new **Settings →
"Enable numerals"** toggle (default ON) that, when OFF:

1. **Live decode** — signing must not switch into numeric mode: a committed
   `NUMERALS` control pose should be a no-op so the decoder stays in `LETTERS` for
   the whole session.
2. **Sight-read picker** — stock passages containing a digit (e.g. `ROOM 101`) are
   non-selectable.
3. **Type-a-passage** — a typed passage containing a digit warns (non-blocking).

Two scope decisions, made with the maintainer:

- **Learn-only.** The toggle is a learning-simplicity aid. **Interpret (rear lens)
  ignores it** — there you are reading a real signer whose digits must not be
  silently mis-read as letters. So the gate is front-lens-only.
- **Warn-but-allow** for the typed passage (vs hard-blocking Start). The digit
  simply can't be signed with numerals off, so the drill would stall on it; the
  warning names that, and the user keeps agency.

## Decision

**Gate at the live feed layer, upstream of the frozen decode core — do not thread a
flag into `SemaphoreDecoder`/`Committer`, and add no parity vectors.**

A tiny pure helper on each platform — `NumeralsGate.gate(symbol, suppress)` — maps a
classified `NUMERALS` pose to **indeterminate (`null`)** *before* it reaches the
temporal `Committer`. `null` is already a legitimate vote value that can never commit
or flip mode, so the committer never enters `NUMERIC` and every readable pose still
decodes as its letter. Identity when numerals are enabled.

Front-lens scoping lives at the single call site (the live view model / camera
composable), which seeds `suppress = isFront && !allowNumerals`:

- iOS — `PreviewViewModel` seeds `suppressNumerals` in `init` from the persisted
  `@AppStorage("allowNumerals")` (read via `UserDefaults`, default `true`), gated to
  `cameraPosition == .front`; applied in `handle(_:)`.
- Android — `SemaphoreScreen` takes an `allowNumerals` param (threaded by
  `MainNavHost` to LEARN/DRILL, **not** INTERPRET), computes `suppressNumerals =
  !isRear && !allowNumerals`, and applies it in the frame loop.

The passage surfaces read the same setting and use `PassageSource.containsDigit`
(sanitised output is pure ASCII `A–Z 0–9 SPACE`, so a `0–9` scan is exact) to
disable / warn.

### Why not the committer + a temporal vector?

The committer is a faithful, byte-for-byte parity twin of the Python reference
(`gen_temporal_vectors.py`), proven by `temporal_vectors*.json`. We could add a
`numerals_enabled` flag to all three committers and a discriminating vector. We chose
not to, because **the gate has no nontrivial math**: it is `symbol == "NUMERALS" ?
null : symbol`. A shared fixture exists to catch *divergent computation* across
ports — the `x→1−x` facing-away flip (ADR 0011), the stickiness vote arithmetic (ADR
0013), the quantizer. There is nothing here for two ports to compute differently;
the only thing to get wrong is the trivial equality, which a per-platform unit test
pins directly. Keeping the gate one layer above the contract:

- leaves the frozen decode core (`decoder` + `committer`) and **all of `shared/*.json`
  byte-unchanged** — every existing parity suite stays green, untouched;
- mirrors the established precedent: ADR 0011's facing-away *toggle gating*
  (`isRear && facingAway`) is likewise not vectored — only its flip math is;
- is reversible and low-blast-radius for a beta-window change.

Cross-platform agreement is held by the two ports being textual twins + the
`NumeralsGate` unit tests on each side (the same "kept in lockstep, no parity vector
needed" treatment the live-layer affordances already use — the framing hint, the
auto-reset countdown).

## Consequences

- **Decode core + contract untouched.** No decoder/committer signature change, no
  vector regeneration, `git diff` clean on `shared/`.
- **Interpret is unaffected by construction** — the flag never reaches the rear-lens
  path, and the `!isRear` / front-lens guard is belt-and-suspenders.
- **Setting takes effect on the next Learn entry** (the view model / camera
  composition is rebuilt per entry, same lifetime as the lens + timing profile) —
  consistent with how the other Learn settings (assist figure, etc.) apply.
- **The "Show numerals indicator" display toggle is left independent.** With numerals
  off it simply never triggers (the decoder never enters numeric mode), which is
  harmless; coupling the two was deemed unnecessary.
- **A typed number passage can still be started** (warn-but-allow): the drill will
  stall on the digit target. Accepted per the product call; revisit if testers hit
  it.
- **Tests:** `NumeralsGateTests` (iOS) / `NumeralsGateTest` (Android) pin the gate +
  `containsDigit`; all prior parity suites pass unchanged on both platforms.

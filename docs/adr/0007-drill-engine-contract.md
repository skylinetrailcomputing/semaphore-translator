# ADR 0007 — The Learn drill engine is downstream of the committer (a character-stream contract)

- **Status:** Accepted (2026-06-22). A pure-logic contract with no camera /
  geometry surface, so — unlike [ADR 0006](0006-rear-camera-decode-geometry.md) —
  it needs no live on-device gate: green `DrillParityTests` / `DrillParityTest` on
  both platforms over the shared vectors, plus the self-verifying Python
  generator, **is** the acceptance.
- **Issue:** [#69](https://github.com/skylinetrailcomputing/semaphore-translator/issues/69)
  ([6a-1]); part of epic
  [#67](https://github.com/skylinetrailcomputing/semaphore-translator/issues/67)
  (6a) → [#6](https://github.com/skylinetrailcomputing/semaphore-translator/issues/6).
- **Context refs:** the #67 architecture note (the Learn modes collapse onto one
  engine — *target → session → match-detector* — layered **downstream of the
  existing committer**); [ADR 0004](0004-temporal-commit-contract.md) /
  [ADR 0005](0005-brief-rest-double-letter-separator.md) (the committer this sits
  below); spec §4.4/§4.5 (commit timing, interpret/emit), §6 (parity).

## Context

Epic 6a builds the Learn drill modes (drill a passage, advance per letter,
celebrate at the end). The #67 architecture note collapses every mode onto one new
engine — *target → session → match-detector* — and places it **downstream of the
existing committer**. Before writing a line of it, two things have to be decided:
**where it sits** and **what it consumes** — because the project's entire
cross-platform parity discipline rests on never disturbing the decode / adapter /
commit core. That core's §3.2 mirror seam is the single most error-prone step and
the #1 divergence source (ADR 0006); any new feature that reaches into it inherits
that risk.

## Decision — the engine observes only the committer's emitted characters

The drill engine is a **pure function of (the committer's emitted character, the
current target)**. It never reads keypoints, arm angles, position ids, timing, or
any pre-commit decode state, and it never calls into the adapter / decoder /
committer. Its shared contract has two parts:

- **`shared/drill_contract.json`** — the descriptive frozen contract (target /
  session / match-detector model, the match rule, the stay-until-success
  lifecycle, the reserved seam for later modes).
- **`shared/drill_vectors.json`** — the executable cross-platform enforcement. The
  decisive property: these vectors carry **no keypoints and no timing, only
  committed-character streams** (a target passage + the emits a signer produces →
  the per-step `matched` / `index` / `complete`).

The match rule, in full: ASCII-uppercase both the emit and the current target
(single characters in the frozen ASCII alphabet, so the mapping is
locale-independent and identical across Python / Swift / Kotlin); a hit advances
the target index by one; reaching the target count is `COMPLETE`. Under
stay-until-success — the only 6a-1 lifecycle (iv-a) — a miss is a no-op (never
penalised, never skipped) and emits after `COMPLETE` are no-ops.

### Why

1. **Parity risk ~0 on the core.** Because the engine sits strictly *below* the
   committer and its vectors are character streams, nothing it does can perturb the
   decode / adapter / commit path. The §3.2 mirror seam is upstream and untouched;
   the drill engine structurally *cannot* regress it. This is the same posture as
   ADR 0006 (rear camera) — keep new work off the geometry core — applied to a
   feature rather than a lens.

2. **The vectors decouple from the decode fixtures.** `test_vectors.json` /
   `temporal_vectors.json` are keypoint+timing fixtures that must be regenerated
   when `semaphore_alphabet.json` / `semaphore_config.json` change.
   `drill_vectors.json` is independent of both — it speaks only the committer's
   *output* alphabet (A–Z / 0–9 / SPACE), so a future alphabet or timing change
   can't silently invalidate it.

3. **The match rule is the only contract surface, and it is small.** Later modes —
   timed (iv-b) and delayed-assist (iv-d), both 6a-6 — are *non-emit timeout*
   policies layered over the same target/index core; they don't change the match
   rule. So 6a-1 freezes the rule and merely **reserves** the lifecycle seam,
   rather than guessing at timeout semantics now.

### Scope — what 6a-1 is and isn't

6a-1 is the contract + the parity vectors + a pure-logic `DrillSession` on each
platform + the parity test. It is **not** the session HUD, the stay-until-success
screen, or the celebrate (those are **6a-2**, which wires `DrillSession` into the
screen at the committer's emit seam). It does **not** tokenise or sanitise a
passage into targets — that is the **source** (6a-3); the engine consumes an
already-validated target sequence.

## Consequences

- **The committer's emit seam is the single integration point.** 6a-2 calls
  `session.observe(emitted)` at the existing
  `if !emitted.isEmpty { committedText += emitted }` site on both platforms
  (`PreviewViewModel` / `SemaphoreScreen`) — no new camera / pose / decode wiring.
- **A third reference implementation.** Like the committer (Python reference +
  Swift/Kotlin twins), the drill engine has a Python reference
  (`shared/tools/gen_drill_vectors.py`). The generator asserts each sequence's
  **authored final state** before writing (catching a mis-authored op stream); the
  **per-step** values are recorded from the reference and are the cross-platform
  pins the parity harness enforces — a port that diverges on any step fails even if
  its final state matches. The two platform ports are byte-for-byte twins of the
  reference and of each other.
- **No app-bundle wiring.** `drill_contract.json` + `drill_vectors.json` are
  test/spec artifacts read straight from `shared/` (the same posture as
  `test_vectors.json` / `temporal_vectors.json`); the engine loads no JSON at
  runtime (targets come from the 6a-3 source). The app's bundled resources are
  unchanged.
- **Parity is enforced by the harness alone.** No live gate: green
  `DrillParityTests` / `DrillParityTest` over the same `shared/drill_vectors.json`,
  plus a light `drill_contract.json` parse-and-pin check (states `ACTIVE` →
  `COMPLETE`) anchoring the doc to the code, is the whole acceptance.

## How to run

```bash
# Regenerate + self-verify the vectors (off-device):
uv run shared/tools/gen_drill_vectors.py

# Drill parity — both platforms must be green over the same shared/drill_vectors.json:
#   iOS:     xcodebuild test … -only-testing:SemaphoreTranslatorTests/DrillParityTests
#   Android: ./gradlew testDebugUnitTest --tests "*DrillParityTest"
```

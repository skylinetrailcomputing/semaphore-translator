# ADR 0010 — The readout buffer coalesces spaces (a string→string contract downstream of the committer)

- **Status:** Accepted (2026-06-23). Like [ADR 0007](0007-drill-engine-contract.md)
  and [ADR 0008](0008-learn-source-sanitization.md) this is a pure-logic layer with no
  camera / geometry surface, so its acceptance is green `CommittedTextParityTests` /
  `CommittedTextParityTest` on both platforms over the shared vectors, plus the
  self-verifying Python generator — not a live on-device gate. The behaviour it fixes
  (no leading / no double space in the live readout) is also visible in the existing
  Learn / Interpret on-device smoke.
- **Issue:** none — an untracked beta-polish fix surfaced while smoking the readout.
  Captured here because, like every other cross-platform behavioural rule in this repo,
  it gets a parity vector rather than an inline guard.
- **Context refs:** [ADR 0008](0008-learn-source-sanitization.md) (the *input*-side
  sanitiser this mirrors), [ADR 0004](0004-temporal-commit-contract.md) /
  [ADR 0005](0005-brief-rest-double-letter-separator.md) (the committer that emits the
  tokens this consumes); spec §4.5 (the live layer), §6 (parity).

## Context

The committer (ADR 0004/0005) turns the per-frame decoder into a temporal one: it emits
exactly one token per frame — a letter, a digit, a single `SPACE` (a sustained REST), or
`""` (most frames, and control poses). The live layer (spec §4.5) accumulates those
tokens into the user-visible readout buffer `committedText` with a plain `+=`.

Two space artefacts reach that buffer that a reader never wants to see, both of which the
committer cannot cleanly suppress on its own:

1. **A leading space.** A held REST *before any letter* commits a space: at sequence
   start `lastCommitted == nil`, so a REST candidate passes the `candidate != lastCommitted`
   gate and `interpret("REST")` returns `" "`. The buffer opens with a space.

2. **A double space between words.** ADR 0005's REST design is half-open at the top
   precisely so a *sustained* REST commits one space and then stops. But after that space
   commits, a brief **indeterminate** gap (`null` candidate — a dropped wrist, a transition
   frame) clears the REST run (`restSince = nil`); the next REST run then re-arms the
   same-symbol gate (`gapOk = true`) and commits a **second** space, even though
   `candidate == lastCommitted == "REST"`. Two spaces land between the words.

The committer can't fix these without conflating two different lifetimes. "Is this a
leading or redundant space?" is a property of the **buffer**, and the buffer **outlives**
the committer's no-signer `reset()` (the watchdog resets committer state on signer-loss but
deliberately preserves `committedText` so a word survives a look-away). A committer-level
"have I emitted content yet / was my last emit a space" flag would reset on every
re-acquire and then wrongly swallow a legitimate word-gap space after the signer looks
back. So the rule belongs where the truth lives: at the buffer.

## Decision — buffer append is a frozen, parity-tested string→string function

Appending a committed token to the readout buffer is a **pure function
`CommittedText.append(buffer, emitted) -> buffer`**. It is strictly **downstream** of the
committer and never reads or touches the decode / adapter / committer / drill core. Its
shared contract is the executable cross-platform enforcement:

- **`shared/committed_text_vectors.json`** — vectors of `(emits → expected)`: a sequence of
  committer tokens folded left-to-right from an empty buffer, and the resulting buffer
  string. Generated + self-validated by `shared/tools/gen_committed_text_vectors.py`.

**The rule, in full.** Append a `SPACE` token only when the buffer is **non-empty** *and*
does **not already end in a space** (so: no leading space, and consecutive spaces coalesce
to one). Append any **non-space** token — and `""` — unchanged. A **trailing** space is
**not** trimmed.

### Why this is the live analogue of `sanitize`, not a re-use of it

`PassageSource.sanitize` (ADR 0008) already maps a whole *typed* string to the frozen
alphabet with "whitespace runs collapsed to one SPACE and leading/trailing trimmed." This
is the same shape rule applied to the *decoded* output, but it must run **incrementally**
(one committed token per frame, against a growing buffer) and it must **keep a trailing
space**:

- The signer rests to separate words; the single trailing space is the in-progress
  separator (`"A "` becomes `"A B"` on the next letter). Trimming it — as the batch
  sanitiser does — would make the gap flicker away the instant the signer rests, which
  reads as a bug. So this is *collapse-as-you-go*, not full sanitisation, and it is its own
  small function rather than a call into `sanitize` over the whole buffer each frame.

The two together bracket the drill engine: `sanitize` cleans the *target* the engine
matches against; `CommittedText.append` cleans the *read* the engine observes. They share
the "no leading space, no double space" backbone and each has a Python reference + shared
vectors + per-platform parity test.

### The drill feed is intentionally left raw

The live layer still feeds the **raw** committed token to the drill observer
(`observeDrill` / the Android drill block); only the *buffer append* routes through
`CommittedText.append`. The drill already has its own space-leniency (a `SPACE` that is not
the current target is a no-op, not a miss), which absorbs exactly the leading / duplicate
spaces this rule suppresses — so the buffer and the drill stay coherent in every case with
no change to drill behaviour. Coalescing inside the drill instead would have re-litigated
that leniency for no benefit.

## Consequences

- **A third reference implementation.** Like the committer, the drill engine, and the
  sanitiser, the appender has a Python reference (`gen_committed_text_vectors.py`) the
  Swift/Kotlin ports mirror byte-for-byte. The generator re-folds the reference over every
  authored `(emits → expected)` and asserts each before writing.
- **No new runtime resource.** `committed_text_vectors.json` is a test/spec artifact read
  straight from `shared/` by the parity harness (the same posture as `sanitize_vectors.json`
  / the drill vectors); nothing new is bundled into either app.
- **Parity risk stays ~0 on the core.** The buffer is a downstream consumer of committed
  characters; it touches neither the committer nor the decode/adapter path. The committer
  and its `temporal_vectors.json` are untouched — the leading/double space tokens are still
  *emitted* (semantically the signer did rest); they are just not *recorded* into the
  buffer.
- **Single append site per platform.** iOS `PreviewViewModel.handle(_:)`, Android
  `SemaphoreScreen`'s frame loop. Every readout path (Learn, Interpret, the verbatim
  toggle) reads the one coalesced buffer, so the fix lands everywhere at once.

## How to run

```bash
# Regenerate + self-verify the vectors (off-device):
uv run shared/tools/gen_committed_text_vectors.py

# Coalescing parity — both platforms must be green over the same shared/committed_text_vectors.json:
#   iOS:     xcodebuild test … -only-testing:SemaphoreTranslatorTests/CommittedTextParityTests
#   Android: ./gradlew testDebugUnitTest --tests "*CommittedTextParityTest"
```

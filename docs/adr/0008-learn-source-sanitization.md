# ADR 0008 — The Learn passage source is a sanitiser upstream of the drill engine (a string→string contract)

- **Status:** Accepted (2026-06-22). Like [ADR 0007](0007-drill-engine-contract.md)
  this is a pure-logic layer with no camera / geometry surface, so its acceptance is
  green `SanitizeParityTests` / `SanitizeParityTest` on both platforms over the shared
  vectors, plus the self-verifying Python generator — not a live on-device gate. The
  IA wiring it feeds (custom / sight-read pickers) is smoke-gated on device like the
  prior Learn UI tickets (#60 / #70).
- **Issue:** [#71](https://github.com/skylinetrailcomputing/semaphore-translator/issues/71)
  ([6a-3]); part of epic
  [#67](https://github.com/skylinetrailcomputing/semaphore-translator/issues/67)
  (6a) → [#6](https://github.com/skylinetrailcomputing/semaphore-translator/issues/6).
- **Context refs:** [ADR 0007](0007-drill-engine-contract.md) (the drill engine this
  feeds — it consumes "an already-validated target sequence … the source, 6a-3,
  sanitises a passage to the frozen ASCII alphabet"); the #67 architecture note (Learn
  modes collapse onto *source → engine → HUD*); spec §4.4/§4.5, §6 (parity).

## Context

6a-1 froze the drill engine and 6a-2 wired its HUD/celebrate spine, both driven by a
hardcoded `"HELLO"`. Both deliberately deferred **where the passage comes from** and
**how arbitrary text becomes a clean target sequence** to the source (6a-3). The
drill engine consumes a `String` it splits into single-character targets and trusts
to be already valid (drill_contract `empty_targets` even pins the degenerate empty
case "so the platforms agree", with a note that *the source must not produce it*).

So this ticket owns two things: a **sanitiser** (the only behaviour that must be
byte-for-byte identical across platforms) and the **source IA** (custom-passage entry
+ sight-read stock picker). The #71 DoD: "both sources playable through the 6a-2
engine; parity on sanitization; smoke both."

## Decision — sanitisation is a frozen, parity-tested string→string function

The source is a **pure function `sanitize(String) -> String`** that maps arbitrary
text to the frozen ASCII target alphabet (`A–Z / 0–9 / SPACE`). It is strictly
**upstream** of the drill engine and never reads or touches the decode / adapter /
committer / drill core. Its shared contract has two parts, mirroring the drill layer:

- **`shared/source_contract.json`** — the descriptive frozen contract (the rule, the
  machine-checkable `supported_chars` alphabet, the `max_targets` cap).
- **`shared/sanitize_vectors.json`** — the executable cross-platform enforcement
  (`raw input → sanitised output`), generated + self-validated by
  `shared/tools/gen_sanitize_vectors.py`.

**The rule, in full.** Iterate the input by **scalar / code point** (Swift
`unicodeScalars`, Kotlin `Char`/UTF-16 units, Python code points) — *not* by grapheme
cluster. ASCII-only uppercase by scalar value (`a–z → A–Z`). Keep `A–Z` / `0–9`.
Treat `{SPACE, TAB, LF, CR}` as a word separator: collapse runs to a single `SPACE`
and trim leading/trailing (a pending separator is flushed as one `SPACE` only
immediately before the next kept char, and only after a kept char has already been
emitted). **Drop** everything else — punctuation, accents, other-script letters,
NBSP and other Unicode spaces, emoji, control chars. There is **no transliteration**.

### Why these two non-obvious choices

The cross-vendor plan review for this ticket surfaced both as the real parity teeth;
each has a locking vector in `sanitize_vectors.json`:

1. **ASCII-only uppercase, never the platform's whole-string upper.** Swift
   `String.uppercased()` / Kotlin `String.uppercase()` / Python `str.upper()` are
   locale/Unicode-aware: they expand `ß → "SS"` and fold the Turkish dotless-i.
   Using them would *keep* characters the rule means to drop and diverge across
   platforms. So upper is an explicit per-scalar range check. (Vectors:
   `eszett_dropped_not_SS`, `turkish_dotted_capital_I_dropped`,
   `ascii_i_is_locale_independent`.)

2. **Iterate scalars, not graphemes.** A decomposed base letter + combining mark
   (`e` + U+0301) is **one grapheme but two scalars**. By scalar, all three platforms
   keep the base `e` and drop the mark (`"CAFE"`). If Swift iterated `Character`
   (graphemes) instead of `unicodeScalars`, it would drop the *whole* cluster
   (`"CAF"`) and diverge from Python/Kotlin. (Vector:
   `decomposed_e_acute_keeps_base` vs `precomposed_e_acute_dropped`.) Because every
   *kept* character and every separator is ASCII — a single unit in all three
   encodings — and every non-ASCII scalar is dropped regardless of how it is chunked,
   the three iteration models agree on the output.

3. **Drop, not transliterate.** Mapping `café → CAF`, `READY-SET-GO → READYSETGO` is
   the simplest parity-trivial rule. The cost is silent word-concatenation when a
   user types punctuation between words; the mitigation is the **live sanitised
   preview** in the custom-passage UI (what you see is exactly what the drill runs),
   and stock passages are authored already-clean. Transliteration was rejected: it
   would make the vectors locale-dependent and ambiguous across platforms for little
   beta value.

### Stock passages + the sight-read source

`shared/stock_passages.json` is bundled **content** (not a behavioural contract), but
it lives in `shared/` and is loaded verbatim by both apps so the list is identical.
Each `text` is authored already-clean (`sanitize(text) == text`); the generator and
both parity tests assert that, so a passage edited to include punctuation/lowercase
fails before it can ship silently mangled. "Sight-read" means the picker shows only a
content-neutral `hint`, never the text — the drill HUD reveals one target at a time.
The tests assert the sanitised hint does not contain the passage text.

### IA + the Android navigation state

The Learn hub goes from `[Free practice, Drill→"HELLO"]` to `[Free practice, Type a
passage, Sight-read a passage]` — a flat source picker (the ticket's "Learn
mode/source picker"). Both drill sources feed a **sanitised** string into the
unchanged `ContentView(drillTargets:)` / `SemaphoreScreen(drillTargets:)`. Start is
disabled until the sanitised custom text is non-empty and within `max_targets`, so the
engine never receives an empty sequence.

On Android the active passage is hoisted at the `NavHost` level as
**`rememberSaveable<String?>`** (not a plain `remember`, which would be lost on a
configuration change / process death and leave the restored DRILL route building an
empty-complete session) and the DRILL route **null-guards** (pops back if reached
without a passage). iOS needs none of this: the `NavigationLink` destination carries
the value inline.

## Consequences

- **A third reference implementation.** Like the committer and the drill engine, the
  sanitiser has a Python reference (`gen_sanitize_vectors.py`) the Swift/Kotlin ports
  mirror byte-for-byte. The generator re-runs the reference over every authored
  `(input → expected)`, asserts each before writing, asserts every stock passage is
  already-clean, and cross-checks `supported_chars` against the reference output
  alphabet.
- **`sanitize_vectors.json` is stored `ensure_ascii=True`** (unlike
  `drill_vectors.json`) on purpose: its inputs carry deliberate non-ASCII scalars, so
  escaping them keeps the fixture byte-stable and immune to editor/filesystem Unicode
  normalisation. Every JSON parser decodes the escapes (and surrogate pairs) back to
  the same scalars.
- **One new runtime resource.** Only `stock_passages.json` is bundled at runtime (iOS
  `project.yml`, Android `copySharedContract`); `source_contract.json` /
  `sanitize_vectors.json` are test/spec artifacts read straight from `shared/`, the
  same posture as the drill files. A missing/corrupt `stock_passages.json` fails
  **soft** — the sight-read picker shows an "unavailable" state and the custom source
  stays usable (it is not a consent gate, so it does not fail closed like the
  disclaimer).
- **The cap is pinned three ways.** `PassageSource.maxTargets` / `MAX_TARGETS` and
  `source_contract.json`'s `max_targets` are asserted equal by the parity tests, so
  the code constants and the contract can't drift.
- **Parity risk stays ~0 on the core.** The source is a new layer feeding the engine a
  string; it touches neither the drill engine nor the decode/adapter/commit path.

## How to run

```bash
# Regenerate + self-verify the vectors (off-device); also asserts stock passages clean:
uv run shared/tools/gen_sanitize_vectors.py

# Sanitise parity — both platforms must be green over the same shared/sanitize_vectors.json:
#   iOS:     xcodebuild test … -only-testing:SemaphoreTranslatorTests/SanitizeParityTests
#   Android: ./gradlew testDebugUnitTest --tests "*SanitizeParityTest"
```

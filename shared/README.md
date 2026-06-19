# Shared contract — single source of truth

These language-neutral files define behavior that **must be identical**
across the iOS and Android implementations. Both platforms load and
parse them; neither hardcodes their contents.

| File | Role |
|------|------|
| `semaphore_alphabet.json` | (left,right) arm-position → character mapping, position model, numeric-mode signs + digit map, and the frozen model label order. |
| `semaphore_config.json` | Frozen runtime constants (spec §4.4): angle tolerance, confidence floor, commit/debounce timing, smoothing window. |
| `test_vectors.json` | Input keypoint vectors → expected decoded output. The parity test runs these through both platforms' full feature-extraction + classification path; results must match exactly. |

## ⚠️ The alphabet contract is UNVERIFIED — do not build classification on it yet

`semaphore_alphabet.json` carries `_verification.status = "UNVERIFIED"`.
It must be validated against an **authoritative chart** (not from memory)
before either platform trusts it. **Epic 1.**

Two concrete problems found during scaffolding (2026-06-19):

1. **The spec table and the JSON disagree on the id↔angle mapping.**
   `docs/…spec.md` §4.2 and `semaphore_alphabet.json._position_model`
   assign *different* angles to position IDs 2, 3, 5, and 6 — they are
   **mirror-swapped** (left↔right) between the two files:

   | ID | spec §4.2 | alphabet JSON |
   |----|-----------|---------------|
   | 2  | 0° / out-left  | 180° / out-right |
   | 3  | 45° / up-left  | 135° / up-right  |
   | 5  | 135° / up-right | 45° / up-left   |
   | 6  | 180° / out-right | 0° / out-left  |

   IDs 0/1/4/7 agree. This is exactly the "mirror happens in the wrong
   place" failure the spec warns about (§3.2, §4.2) — already present
   between the two artifacts.

2. **The spec's §4.2 table is internally inconsistent on the
   down-diagonals.** It states `+x` points to the signer's left, angles
   measured CCW — under which −45° is down-*left* and 225° is
   down-*right*. But the table labels id 1 (−45°) as "down-right" and
   id 7 (225°) as "down-left."

**Convention decision (ratified 2026-06-19):** freeze in the **signer's
own perspective** (anchor on the spec's stated convention). The exact
`+x` direction and the id↔angle table are reconciled against the chart
at freeze time.

**Resolution (Epic 1):** pick ONE convention, verify every (left,right)
pair A–Z against an authoritative chart read in the signer's
perspective, confirm the numerals/letters signs and the digit→0 detail
(J vs K — the most-misreported part), make all three representations
agree, then set `_verification.status = "VERIFIED"` with the chart
source + date.

Until that's done, `test_vectors.json` cannot be authored with correct
expected outputs (it currently holds only a structural skeleton).

> **A verification research pass was done 2026-06-19 — see
> [`ALPHABET-VERIFICATION.md`](ALPHABET-VERIFICATION.md).** Headline: an
> independent cross-check against anbg.gov.au found **~10 of 26 letter
> pairs disagree** with the current JSON (I, J, P, Q, R, S, T, U, W, Y),
> so the JSON's letter pairs are likely wrong and must be rebuilt from a
> canonical image chart. The JSON's **numeric** half (A=1…I=9, K=0,
> J=letters-shift) and the 8-octant model are **confirmed correct**.

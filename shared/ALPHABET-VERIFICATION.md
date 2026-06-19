# Alphabet verification

**Status: VERIFIED & FROZEN 2026-06-19** (Epic 1, #8/#9/#10). The
section immediately below is the authoritative freeze result; the
"research notes" after it are the earlier head-start, kept for history
and now superseded where they disagree.

---

## ✅ Freeze result (2026-06-19)

**Method.** Each letter was read **from the canonical chart raster
itself** (`File:Semaphore Signals A-Z`, the chart Wikipedia uses),
viewed directly — not transcribed from memory or a text summary. The
charts are drawn in the **observer's** perspective (you facing the
signer); this was confirmed verbatim by two independent sources:

- Wikipedia: characters "as they would appear when **facing the
  signalperson**".
- dCode: "Reference figures are always presented in **receive mode**:
  the user sending must imagine the character seen from behind."

So each chart pose was converted to the frozen **signer's** perspective
by a horizontal mirror. The mirror was **calibrated** on the three
already-agreed letters A/D/E (e.g. D = signer (L=down, R=up); the chart
shows the *up* flag on the image-left, which fixes image-left = signer's
right arm). The full result was then checked for **self-consistency
against the classic "circles" construction** (e.g. O/P/Q/R/S all fix the
signer's right arm at out-right; T/U/Y fix it at up-right) — it holds for
all 26.

**Frozen frame (#9):** `+x` = signer's **right**, `+y` = up, angle CCW
from `+x`. ids: 0 down (−90), 1 down-right (−45), 2 out-right (0),
3 up-right (45), 4 up (90), 5 up-left (135), 6 out-left (180), 7
down-left (−135). spec §4.2 and the JSON `_position_model` now match.

**Verified A–Z (signer's perspective), names + ids:**

| Ltr | (L, R) names | (L,R) ids | Δ vs draft |
|-----|--------------|-----------|-----------|
| A | down, down-right | 0,1 | — |
| B | down, out-right | 0,2 | — |
| C | down, up-right | 0,3 | — |
| D | down, up | 0,4 | — |
| E | up-left, down | 5,0 | — |
| F | out-left, down | 6,0 | — |
| G | down-left, down | 7,0 | — |
| H | down-right, out-right | 1,2 | — |
| I | down-right, up-right | 1,3 | — (draft was right; text cross-check was wrong) |
| **J** | **out-left, up** | **6,4** | was 4,2 |
| K | up, down-right | 4,1 | — |
| L | up-left, down-right | 5,1 | — |
| M | out-left, down-right | 6,1 | — |
| N | down-left, down-right | 7,1 | — |
| O | up-right, out-right | 3,2 | — |
| **P** | **up, out-right** | **4,2** | was 4,3 |
| **Q** | **up-left, out-right** | **5,2** | was 5,3 |
| **R** | **out-left, out-right** | **6,2** | was 6,3 |
| **S** | **down-left, out-right** | **7,2** | was 7,3 |
| **T** | **up, up-right** | **4,3** | was 4,5 |
| **U** | **up-left, up-right** | **5,3** | was 4,6 |
| V | down-left, up | 7,4 | — |
| **W** | **out-left, up-left** | **6,5** | was 5,6 (transposed) |
| X | down-left, up-left | 7,5 | — |
| **Y** | **out-left, up-right** | **6,3** | was 5,2 |
| Z | out-left, down-left | 6,7 | — |

**Control signs:** NUMERALS = signer (L=up-left 5, R=up 4) — read from
`File:Semaphore Numeric`; was 3,1. REST = (0,0) both down = space.
LETTERS = the J pose (alphabetic shift). Digit map A=1…I=9, **K=0**, J
excluded — confirmed, unchanged.

**Net change vs the unverified draft:** 9 letter pairs (J,P,Q,R,S,T,U,W,Y)
+ NUMERALS. 16 letters + I + REST + digit map already correct.

### Arm-assignment ambiguity (which arm at which angle)

During review (Brad, 2026-06-19) it was observed that published alphabets
disagree on *which arm* sits at *which angle* for some letters (I, O, W,
X). This is structural, not error: those — plus **H** and **Z** — are the
letters whose two flags fall on the **same side** of the body, so one arm
crosses over and the visible pose is identical regardless of which arm is
on top. A semaphore character is fundamentally the **unordered pair** of
flag positions: the 26 letters occupy 26 of the 28 unordered
distinct-position pairs `C(8,2)`; the other two are NUMERALS (`{up,
up-left}`) and the unused `{up-right, down-left}`.

**Decision:** alphabet lookup is **order-insensitive** — canonicalize an
observed `(left,right)` by sorting before lookup; `(a,b)` and `(b,a)` are
the same character. Provably collision-free (no character's reverse is
another character). The ordered pairs above remain the canonical
signer's-perspective reference orientation. Encoded in
`semaphore_alphabet.json._matching` and spec §4.3; `test_vectors.json`
(#12) must include arm-swapped positive cases for H,I,O,W,X,Z.

---

## Earlier research notes (head-start, 2026-06-19 — superseded above)

**Status:** Research done 2026-06-19. **The contract file
`semaphore_alphabet.json` was still UNVERIFIED at the time of these
notes** — an independent cross-check (below) found ~10 letters in
disagreement with an authoritative source. This was a head-start
for Epic 1, not a freeze. (Note: the freeze above resolved letter **I**
*in the draft's favor* — these notes' anbg-text reading of I was a
transcription error, caught by reading the actual image.)

## Convention decision (ratified 2026-06-19)

Freeze the alphabet in the **signer's own perspective** (anchor on the
spec's stated convention, not the camera's). y-up. The mirror + y-flip
live once, in the per-platform adapter. The exact `+x` direction and
the id↔angle table get reconciled against the chart at freeze time
(spec §4.2 currently contradicts `semaphore_alphabet.json` on ids
2/3/5/6, and is internally inconsistent on the down-diagonals — see
`shared/README.md`).

## Confirmed (high confidence — both sources agree)

- **8-octant clock model**, one position per arm: 12 (up), 1:30, 3
  (out), 4:30, 6 (down), 7:30, 9 (out), 10:30. Matches the spec.
- **Numeric digit map: A=1, B=2, C=3, D=4, E=5, F=6, G=7, H=8, I=9,
  K=0.** `0` is the **K** pose, NOT J. The current JSON `digit_map` is
  correct.
- **J is the "Letters"/alphabetic shift** (numeric → letter mode);
  a separate "Numerals" sign enters numeric mode.
- **Rest** = both arms straight down. JSON `REST {left:0,right:0}` ✓.

## Construction ("circles")

From anbg.gov.au, quoted: *"In the first circle, the letters A to C are
made with the right arm, and E to G with the left, and D with either as
convenient. In the second circle, the right arm is kept still at the
letter A position and the left arm makes the movements; similarly in
the remaining circles, the right arm remains fixed while the left arm
moves."* This is deterministic — the next session can DERIVE the full
table from the circle rule + a chart, rather than transcribe pair by
pair.

## ⚠️ Cross-check: current JSON vs. anbg textual table

Both columns are (left arm, right arm) in plain octant words, signer's
perspective. JSON column is decoded via the JSON's own `_position_model`
(0=down,1=down-right,2=out-right,3=up-right,4=up,5=up-left,6=out-left,
7=down-left). anbg column is my translation of anbg's verbal positions
("low/out/high/across", per arm). **Both are second-hand** (the JSON
self-declares UNVERIFIED; the anbg reading came via WebFetch summary and
my word→octant mapping) — the canonical image chart is the arbiter.

| Ltr | JSON (L, R) | anbg (L, R) | |
|-----|-------------|-------------|---|
| A | down, down-right | down, down-right | ok |
| B | down, out-right | down, out-right | ok |
| C | down, up-right | down, up-right | ok |
| D | down, up | down, up | ok |
| E | up-left, down | up-left, down | ok |
| F | out-left, down | out-left, down | ok |
| G | down-left, down | down-left, down | ok |
| H | down-right, out-right | down-right, out-right | ok |
| **I** | down-right, **up-right** | down-right, **up** | ❌ |
| **J** | **up, out-right** | **out-left, up** | ❌ |
| K | up, down-right | up, down-right | ok |
| L | up-left, down-right | up-left, down-right | ok |
| M | out-left, down-right | out-left, down-right | ok |
| N | down-left, down-right | down-left, down-right | ok |
| O | up-right, out-right | up-right, out-right | ok |
| **P** | up, **up-right** | up, **out-right** | ❌ |
| **Q** | up-left, **up-right** | up-left, **out-right** | ❌ |
| **R** | out-left, **up-right** | out-left, **out-right** | ❌ |
| **S** | down-left, **up-right** | down-left, **out-right** | ❌ |
| **T** | up, **up-left** | up, **up-right** | ❌ |
| **U** | **up, out-left** | **up-left, up-right** | ❌ |
| V | down-left, up | down-left, up | ok |
| **W** | **up-left, out-left** | **out-left, up-left** | ❌ (transposed) |
| X | down-left, up-left | down-left, up-left | ok |
| **Y** | **up-left, out-right** | **out-left, up-right** | ❌ |
| Z | out-left, down-left | out-left, down-left | ok |

**Patterns worth noting for the eyeball:**
- **P, Q, R, S** are one "circle": JSON fixes the right arm at
  *up-right*, anbg fixes it at *out-right* — a one-octant shift of the
  whole circle. Exactly one of them is wrong.
- **J** and **W** look like left/right transpositions.
- **I, T, U, Y** differ on one or both arms individually.

The high agreement on 16 letters suggests the JSON is mostly right but
has scattered errors from being authored unverified — but DO NOT assume
anbg is ground truth either. Rebuild against a canonical chart.

## Numerals sign — also flagged

JSON `NUMERALS {left:3, right:1}` (up-right, down-right) does **not**
match anbg's numerals sign ("LH high; RH up" ≈ up-left, up). Sources
describe the numerals sign inconsistently; confirm against the chart.
anbg also gives Annul/Cancel ≈ "LH low; RH high" and Error = "arms
raised and lowered together" — both out of scope for v1 recognition
(spec §9-C) but worth recording.

## Sources

- Australian National Botanic Gardens — Semaphore Flag Signalling
  System: https://www.anbg.gov.au/flags/semaphore.html  (the textual
  table + circle construction above came from here)
- Wikipedia "Flag semaphore":
  https://en.wikipedia.org/wiki/Flag_semaphore  (canonical SVG chart
  per letter — IMAGE-based, the right thing to eyeball; not
  text-extractable)
- dCode semaphore translator (interactive, per-letter):
  https://www.dcode.fr/semaphore-flag
- TUGboat 20(4) 1999, Vít Zýka, "The Semaphore Alphabet" (typographic,
  precise): https://www.tug.org/TUGboat/tb20-4/tb65zyka.pdf  — a good
  tiebreaker source for the next session if JSON-vs-anbg needs settling
  beyond the Wikipedia chart.

## Procedure for the freeze (next session, issues #8–#10)

1. Open the Wikipedia per-letter SVG chart (and/or dCode). Eyeball each
   letter in the signer's perspective.
2. Resolve every ❌ row above; settle the P/Q/R/S circle and the J/W
   transpositions first.
3. Pick the single id↔angle convention; make spec §4.2, the spec's
   direction labels, and the JSON all agree.
4. Confirm the Numerals sign pose.
5. Update `semaphore_alphabet.json`, set `_verification.status =
   VERIFIED` with chart source + date, then author `test_vectors.json`.
6. Branch → PR so the mapping diff gets a human review before merge.

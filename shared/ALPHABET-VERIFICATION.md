# Alphabet verification — research notes (Epic 1 head-start)

**Status:** Research done 2026-06-19. **The contract file
`semaphore_alphabet.json` is still UNVERIFIED and must NOT be trusted
as-is** — an independent cross-check (below) found ~10 letters in
disagreement with an authoritative source. This file is a head-start
for Epic 1, not a freeze. The freeze happens on a branch → PR with a
human eyeball against a canonical image chart (issues #8, #9, #10).

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

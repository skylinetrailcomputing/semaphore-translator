# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Mirror-twin derivation check — the machine-verified backbone of ADR 0006 (#58).

ADR 0006 ("the rear camera needs no decode-path change") rests on this fact: a
horizontal-mirror error on the analysis buffer is *catchable by a live smoke*
because order-insensitive matching (spec §4.3) maps each left↔right-asymmetric
letter to a DIFFERENT VALID letter — its "mirror twin" — rather than to garbage.
The twin table (A→G, B→F, … T→NUMERALS) and the L→INDETERMINATE canary were
derived BY HAND in #58 and the ADR. The live per-letter protocol keys off that
table, so it must be right.

This script re-derives the whole table from `semaphore_alphabet.json` directly —
including deriving the octant reflection map from the position ANGLES
(θ → 180−θ), not hand-tabulating `0↔0,1↔7,2↔6,3↔5,4↔4` — and asserts it matches
the hand table the ADR documents. So the risk-ticket reasoning is checked against
the same frozen alphabet the decoders use, not trusted to arithmetic on a
whiteboard. The live risk is the *table*, not the (frozen) alphabet; this guards
the table.

It is NOT a camera test. A horizontal mirror is a single GLOBAL transform: if the
rear analysis buffer were flipped, every letter below would decode as the twin in
its row. The live smoke signs one representative per twin pair and confirms it
reads itself; this script proves what "reads itself, not the twin" *means*.

Run: `uv run shared/tools/check_mirror_twins.py` — self-validating, non-zero exit
on any mismatch, the same discipline as the gen_*.py fixture generators.
"""

import itertools
import sys

from _semaphore_ref import LETTERS, LOOKUP, NUMERALS, OCTANT, REST

# The hand table exactly as ADR 0006 / issue #58 document it. Maps each pose's
# class to the class a horizontally-mirrored decode path would report. `None` =
# INDETERMINATE (the mirrored pose is not a valid class). This is the side under
# test: the script must re-derive it from geometry + the alphabet and agree.
EXPECTED_TWIN = {
    "A": "G",
    "B": "F",
    "C": "E",
    "D": "D",
    "E": "C",
    "F": "B",
    "G": "A",
    "H": "Z",
    "I": "X",
    "J": "P",
    "K": "V",
    "L": None,
    "M": "S",
    "N": "N",
    "O": "W",
    "P": "J",
    "Q": "Y",
    "R": "R",
    "S": "M",
    "T": "NUMERALS",
    "U": "U",
    "V": "K",
    "W": "O",
    "X": "I",
    "Y": "Q",
    "Z": "H",
    "NUMERALS": "T",
    "REST": "REST",
}
# The four mirror-symmetric letters (self-mapping) — useless as mirror
# discriminators, but a non-mirror sanity/rotation control in the live protocol.
EXPECTED_SYMMETRIC = {"D", "N", "R", "U", "REST"}
# L is the unique canary: its mirror pair is the one C(8,2) distinct-position pair
# no class occupies, so a flip makes L fail to decode (INDETERMINATE).
CANARY = "L"


def derive_reflection():
    """id → id under a horizontal mirror, derived from the octant ANGLES
    (θ → 180−θ), so this is an independent check on the by-hand octant map rather
    than a restatement of it."""

    def norm(a):  # fold to (-180, 180]
        return (a + 180) % 360 - 180

    angle_to_id = {norm(a): i for i, a in OCTANT.items()}
    if len(angle_to_id) != len(OCTANT):
        sys.exit("octant angles are not distinct after normalization")
    refl = {}
    for i, a in OCTANT.items():
        mirrored = norm(180 - a)
        if mirrored not in angle_to_id:
            sys.exit(f"octant {i} ({a}°) mirrors to {mirrored}°, not an octant")
        refl[i] = angle_to_id[mirrored]
    return refl


def pair_of(sym):
    if sym == "NUMERALS":
        return NUMERALS["left"], NUMERALS["right"]
    if sym == "REST":
        return REST["left"], REST["right"]
    return LETTERS[sym]["left"], LETTERS[sym]["right"]


def twin_of(sym, refl):
    """The class a mirrored decode path reports for `sym`, or None (INDETERMINATE)
    if the mirrored pose is unassigned. Uses the same order-insensitive lookup the
    decoders use."""
    left, right = pair_of(sym)
    key = tuple(sorted((refl[left], refl[right])))
    return LOOKUP.get(key)


def main():
    errors = []
    refl = derive_reflection()

    # 1. The geometry-derived reflection matches the by-hand octant map the ADR cites.
    expected_refl = {0: 0, 1: 7, 2: 6, 3: 5, 4: 4, 5: 3, 6: 2, 7: 1}
    if refl != expected_refl:
        errors.append(f"reflection map: derived {refl} != documented {expected_refl}")

    # 2. Every class's twin matches the hand table; involution holds for valid twins.
    classes = list(LETTERS) + ["NUMERALS", "REST"]
    for sym in classes:
        got = twin_of(sym, refl)
        want = EXPECTED_TWIN.get(sym, "‹unlisted›")
        if got != want:
            errors.append(f"twin({sym}): derived {got!r} != documented {want!r}")
        if got is not None and twin_of(got, refl) != sym:
            errors.append(f"twin not involutive: twin(twin({sym})) != {sym}")

    # 3. The symmetric set is exactly the self-mapping classes (no more, no fewer).
    derived_symmetric = {s for s in classes if twin_of(s, refl) == s}
    if derived_symmetric != EXPECTED_SYMMETRIC:
        errors.append(
            f"symmetric set: derived {sorted(derived_symmetric)} != "
            f"documented {sorted(EXPECTED_SYMMETRIC)}"
        )

    # 4. The canary: L's mirror is the single unused distinct-position pair → invalid.
    if twin_of(CANARY, refl) is not None:
        errors.append(
            f"canary {CANARY}: mirror twin {twin_of(CANARY, refl)!r} is valid; "
            "expected INDETERMINATE"
        )
    used = {tuple(sorted(pair_of(s))) for s in classes}
    all_distinct_pairs = {p for p in itertools.combinations(range(8), 2)}
    unused = all_distinct_pairs - used
    canary_pair = tuple(sorted(refl[i] for i in pair_of(CANARY)))
    if unused != {canary_pair}:
        errors.append(
            f"expected exactly one unused distinct-position pair (the canary "
            f"{canary_pair}); found unused = {sorted(unused)}"
        )

    if errors:
        print("MIRROR-TWIN CHECK FAILED:", file=sys.stderr)
        for e in errors:
            print("  -", e, file=sys.stderr)
        sys.exit(1)

    # Success: print the table so this doubles as living documentation.
    print("Mirror-twin check OK — derived from semaphore_alphabet.json (ADR 0006).")
    print(f"  octant reflection (θ→180−θ): {refl}")
    print("  twin table (a flipped rear path would read the RHS):")
    for sym in list(LETTERS) + ["NUMERALS", "REST"]:
        twin = twin_of(sym, refl)
        tag = ""
        if sym == CANARY:
            tag = "   ← canary (INDETERMINATE under a flip)"
        elif twin == sym:
            tag = "   (symmetric: non-discriminating)"
        print(f"    {sym:>8} → {('INDETERMINATE' if twin is None else twin):<13}{tag}")


if __name__ == "__main__":
    main()

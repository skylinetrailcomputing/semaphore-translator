# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Generate shared/assist_cue_vectors.json — parity fixtures for the Learn assist
TRANSITION cues (Epic 6a, #100 / 6a-13).

6a-5 (#73) shipped a *static* per-target assist figure. This extends it to also
teach the inter-target transitions the protocol requires: a drop-to-REST re-arm
before a repeated target, a NUMERALS pre-pose before a digit signed from letter
mode, and a J/LETTERS pre-pose before a letter signed from numeric mode. Like the
figure itself, the cue logic is strictly read-only over the frozen alphabet and
sits *beside* the decode/commit core (ADR 0007 posture) — no decode/adapter/commit
change, so the per-frame / committer parity risk stays ~0.

The cues are a pure function of the **target sequence** + current index + an
**implied-mode walk** from the start of the passage. The single correctness risk
is that this implied-mode walk match the committer's actual mode state machine
(NUMERALS enters numeric; the J/LETTERS pose returns to letters; REST/space and
indeterminate persist mode — spec §4.5). This generator binds the two together: it
runs the canonical pose sequence (the cue pre-poses + the target poses) through the
SAME reference `interpret` the committer parity fixtures use (_semaphore_ref.py),
and asserts, per target, that the resulting mode equals the assist's implied mode
AND that the emitted character equals the target. So a drift between the assist
walk and the committer's mode rules fails HERE, at generation time, before the
fixture is ever written (same discipline as the other generators).

The reference cue logic below is THE reference the Swift `AssistGeometry.cues` /
`impliedMode` and the Kotlin twin mirror; the recorded per-index cue lists are the
cross-platform pins the `AssistCueParity` harness enforces on each port.

    uv run shared/tools/gen_assist_cue_vectors.py
"""

import json
from pathlib import Path

from _semaphore_ref import (
    ALPHABET,
    DIGIT_MAP,
    NUMERALS,
    OCTANT,
    REST,
    interpret,
)

SHARED = Path(__file__).resolve().parent.parent

LETTERS = ALPHABET["letters"]

# symbol -> ordered (left_id, right_id). The 26 letters (J included) + the two
# control poses the cues draw: NUMERALS (numeric shift) and REST (rest / space).
PAIRS = {sym: (ids["left"], ids["right"]) for sym, ids in LETTERS.items()}
PAIRS["NUMERALS"] = (NUMERALS["left"], NUMERALS["right"])
PAIRS["REST"] = (REST["left"], REST["right"])

# digit char -> the letter pose that produces it in numeric mode (reverse of the
# A=1..I=9, K=0 digit map). The decoder's digit_map is letter->digit; the figure
# needs digit->letter to draw the digit's arm pose.
DIGIT_TO_LETTER = {digit: letter for letter, digit in DIGIT_MAP.items()}


# --- reference assist-cue logic (#100; the Swift/Kotlin ports mirror this) -----


def pose_of(symbol):
    """The two arm angles (signer's frame, degrees) for a contract symbol."""
    left, right = PAIRS[symbol]
    return (OCTANT[left], OCTANT[right])


def target_pose(ch):
    """The target's OWN arm pose: a letter directly, a digit via the reverse digit
    map (un-suppressed now that the NUMERALS pre-cue conveys the mode-switch, #73),
    SPACE -> REST. None for anything else (never reached for a sanitised passage)."""
    if ch == " ":
        return pose_of("REST")
    if ch.isalpha() and ch.isascii():
        return pose_of(ch.upper())
    if ch.isdigit() and ch.isascii():
        return pose_of(DIGIT_TO_LETTER[ch])
    return None


def implied_mode_before(targets, index):
    """The mode the signer is in just BEFORE producing targets[index], walking the
    canonical path from LETTERS at the start of the passage. Mirrors the committer's
    mode rules: a digit ends the signer in numeric mode, a letter in letter mode,
    SPACE/REST (and indeterminate) persist the current mode (spec §4.5)."""
    mode = "LETTERS"
    for i in range(min(index, len(targets))):
        t = targets[i]
        if t.isdigit():
            mode = "NUMERIC"
        elif t.isalpha():
            mode = "LETTERS"
        # space / other: mode persists
    return mode


def cues(targets, index):
    """The ordered assist filmstrip for targets[index]: an optional transition
    pre-cue, then the target's own pose. Returns [] for an out-of-range index or a
    target with no pose (defensive; a sanitised passage never has one).

    The three pre-cues are mutually exclusive by construction: when the current
    target repeats the previous one it is the same character, so the implied mode is
    already the target's mode (the mode-switch poses, which break the same-symbol
    lock, are never both needed) — so at most ONE pre-cue ever precedes the pose."""
    if index < 0 or index >= len(targets):
        return []
    ch = targets[index]
    tp = target_pose(ch)
    if tp is None:
        return []
    out = []
    mode = implied_mode_before(targets, index)
    if index > 0 and ch.upper() == targets[index - 1].upper() and ch != " ":
        # Double (same) target: drop to a brief REST so the re-arm fires (ADR 0005);
        # a timing cue, not a pose to hold (the view renders it distinctly).
        out.append(("rest_between_doubles", pose_of("REST")))
    elif ch.isdigit() and mode == "LETTERS":
        out.append(("numerals_shift", pose_of("NUMERALS")))
    elif ch.isalpha() and mode == "NUMERIC":
        out.append(("letters_shift", pose_of("J")))
    out.append(("target", tp))
    return out


# --- committer cross-check: bind the implied-mode walk to interpret() -----------


def assert_walk_matches_committer(name, targets):
    """Run the canonical pose sequence (the cue pre-poses + each target pose) through
    the reference `interpret` and assert, per target, that (a) the committer's mode
    just before the target equals the assist's implied mode and (b) the committer
    emits exactly the target. This is what guarantees the assist's implied-mode walk
    matches the committer's actual mode state machine (the #100 correctness risk)."""
    mode = "LETTERS"
    for i, ch in enumerate(targets):
        assert implied_mode_before(targets, i) == mode, (
            name,
            "implied-mode walk disagrees with the committer at index",
            i,
            "walk says",
            implied_mode_before(targets, i),
            "committer is in",
            mode,
        )
        if ch == " ":
            emit, mode = interpret("REST", mode)
        elif ch.isdigit():
            if mode == "LETTERS":
                _, mode = interpret("NUMERALS", mode)  # the numerals_shift cue
            emit, mode = interpret(DIGIT_TO_LETTER[ch], mode)
        elif ch.isalpha():
            if mode == "NUMERIC":
                _, mode = interpret("J", mode)  # the letters_shift cue
            emit, mode = interpret(ch.upper(), mode)
        else:
            raise SystemExit(f"{name}: unsanitised target {ch!r}")
        assert emit == ch, (name, "committer emitted", emit, "want target", ch, "at", i)


# --- sequence builder + self-validation ----------------------------------------

sequence_vectors = []


def run_cue(name, targets, note=None):
    """Record the per-index cue list for `targets`, after binding the implied-mode
    walk to the reference committer. Running this IS the fixture's correctness check
    (the cross-check above + the contract-derived poses)."""
    assert_walk_matches_committer(name, targets)
    steps = []
    for i in range(len(targets)):
        steps.append(
            {
                "index": i,
                "target": targets[i],
                "implied_mode_before": implied_mode_before(targets, i),
                "cues": [
                    {
                        "kind": kind,
                        "left_angle_deg": pose[0],
                        "right_angle_deg": pose[1],
                    }
                    for kind, pose in cues(targets, i)
                ],
            }
        )
    entry = {"name": name, "targets": targets, "steps": steps}
    if note:
        entry["_note"] = note
    sequence_vectors.append(entry)


# 1. a plain letter passage with a double letter: only the second L gets a cue (the
#    drop-to-REST re-arm); every other step is the bare target pose.
run_cue(
    "letters_hello",
    "HELLO",
    note="All letters in letter mode -> no mode cues. The repeated L (index 3) gets "
    "the rest_between_doubles drop cue so the second L re-commits (ADR 0005); every "
    "other step is just the target pose.",
)

# 2. the minimal double-letter case.
run_cue(
    "double_letter_AA",
    "AA",
    note="Index 1 repeats index 0 -> rest_between_doubles before the A pose.",
)

# 3. letter -> digit: NUMERALS pre-pose before the digit's letter pose.
run_cue(
    "letter_to_number_A1",
    "A1",
    note="At index 1 the target is a digit and the implied mode is LETTERS, so the "
    "NUMERALS pre-pose fires, then the digit's own pose (1 -> the A pose).",
)

# 4. digit -> letter: J/LETTERS pre-pose before the letter pose.
run_cue(
    "number_to_letter_1A",
    "1A",
    note="Index 0 (digit from LETTERS) -> NUMERALS then the digit pose; index 1 "
    "(letter from NUMERIC) -> the J/LETTERS shift then the letter pose.",
)

# 5. the ticket's ABBA: double B in the middle, letters throughout.
run_cue(
    "abba",
    "ABBA",
    note="rest_between_doubles fires only at index 2 (the second B); A->B and B->A "
    "are distinct poses that commit on their own hold.",
)

# 6. the ticket's mixed run: exercises all three cues plus a space in one sequence.
run_cue(
    "mixed_AB12C_AA",
    "AB12C AA",
    note="A,B letters (no cue); 1 from LETTERS -> NUMERALS; 2 from NUMERIC -> none "
    "(already numeric); C from NUMERIC -> J/LETTERS; space (no cue); A; A repeated "
    "-> rest. Pins the full cue ordering across a realistic mixed passage.",
)

# 7. a passage that STARTS on a digit: NUMERALS fires at index 0 (start mode is
#    LETTERS), and 7 -> the G pose.
run_cue(
    "leading_digit_7",
    "7",
    note="Start mode is LETTERS, so a leading digit gets NUMERALS first; 7 -> the G "
    "pose (the reverse of the A=1..I=9 digit map).",
)

# 8. a digit run: NUMERALS once, then the run stays in numeric mode (no re-shift),
#    including the K=0 reverse-map and a double digit.
run_cue(
    "digit_run_100",
    "100",
    note="NUMERALS at index 0 only; index 1 (0 -> the K pose) is already numeric so "
    "no cue; index 2 repeats the 0 -> rest_between_doubles. Pins K=0 and that a "
    "digit run shifts to numeric just once.",
)

# 9. space does NOT reset numeric mode: only one NUMERALS for '1 2'.
run_cue(
    "digit_space_digit_1_2",
    "1 2",
    note="1 from LETTERS -> NUMERALS; the space is REST and persists numeric mode "
    "(it does not reset it), so the trailing 2 needs NO second NUMERALS — pins that "
    "REST/space preserves mode exactly as the committer does (spec §4.5).",
)

# 10. alternating letter/digit/letter: a mode shift in BOTH directions back-to-back.
run_cue(
    "alternating_A1A",
    "A1A",
    note="A (no cue); 1 -> NUMERALS; A -> J/LETTERS. Stresses both mode-shift cues "
    "in one short sequence.",
)

# 11. a space target between letters: spaces never carry a cue (their pose is REST).
run_cue(
    "letters_space_A_B",
    "A B",
    note="The space target draws the REST pose with no pre-cue; A and B are plain "
    "letter poses. Confirms a SPACE target is never given a transition cue.",
)

# 12. a longer numeric run after letters: NUMERALS once for '123'.
run_cue(
    "letters_then_digit_run_GO123",
    "GO123",
    note="G,O letters; 1 from LETTERS -> NUMERALS; 2 and 3 stay numeric (no cue). "
    "A second pin that a numeric run shifts mode exactly once.",
)


# --- assemble + write ----------------------------------------------------------

out = {
    "$schema_version": "1.0",
    "_README": (
        "Assist TRANSITION-cue parity vectors (Epic 6a, #100 / 6a-13): a target "
        "passage -> the per-index ordered assist filmstrip (the optional transition "
        "pre-cue + the target's own pose) the Learn drill assist draws. Like the "
        "6a-5 figure, the cues are read-only over the frozen alphabet and sit BESIDE "
        "the decode/commit core (ADR 0007), so they carry NO keypoints and NO timing "
        "— only target characters and contract-derived arm angles. Both platform "
        "ports compute AssistGeometry.cues / impliedMode over each sequence and MUST "
        "produce identical per-index results."
    ),
    "_generated_by": (
        "shared/tools/gen_assist_cue_vectors.py (uv run). Generated, not hand-edited. "
        "Before writing, the generator runs the canonical pose sequence (the cue "
        "pre-poses + each target pose) through the SAME reference interpret() the "
        "committer fixtures use (_semaphore_ref.py) and asserts, per target, that the "
        "committer's mode matches the assist's implied-mode walk and that the "
        "committer emits exactly the target — so the assist walk cannot drift from "
        "the committer's mode rules (the #100 correctness risk). Re-run after any "
        "change to semaphore_alphabet.json."
    ),
    "_format": {
        "sequence_vectors": (
            "Each is {name, targets, steps:[...]}. One step per target index "
            "(0..len(targets)-1) — the active drill positions the assist renders."
        ),
        "targets": (
            "The drill's target sequence as a plain string (frozen ASCII alphabet: "
            "A-Z / 0-9 / SPACE), split into single-character targets in signing "
            "order — already sanitised by the source (6a-3)."
        ),
        "steps": (
            "Each step is {index, target, implied_mode_before, cues}. Assert "
            "AssistGeometry.impliedMode(targets, index) == implied_mode_before and "
            "AssistGeometry.cues(targets, index) == cues (kinds + angles, in order)."
        ),
        "implied_mode_before": (
            "LETTERS or NUMERIC — the mode the signer is in just before producing "
            "this target, walking the canonical path from the start. The "
            "correctness-critical pin: it equals the committer's mode at that point."
        ),
        "cues": (
            "Ordered filmstrip for this target. Each is {kind, left_angle_deg, "
            "right_angle_deg}. kind is one of: rest_between_doubles (drop-to-REST "
            "re-arm before a repeated target; a timing cue, drawn distinctly — not a "
            "pose to hold), numerals_shift (the NUMERALS pose, before a digit signed "
            "from letter mode), letters_shift (the J/LETTERS pose, before a letter "
            "signed from numeric mode), target (the target's own arm pose). At most "
            "one pre-cue precedes the always-present target step."
        ),
        "left_angle_deg": "Signer's-frame angle (deg, CCW from +x = signer's right) of the LEFT arm.",
        "right_angle_deg": "Signer's-frame angle of the RIGHT arm.",
        "name": "Human-readable label for debugging. Ignored by the parity harness.",
        "_note": "Optional human explanation. Ignored by the parity harness.",
    },
    "sequence_vectors": sequence_vectors,
}

dest = SHARED / "assist_cue_vectors.json"
dest.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n")

n_steps = sum(len(s["steps"]) for s in sequence_vectors)
n_cues = sum(len(st["cues"]) for s in sequence_vectors for st in s["steps"])
print(f"wrote {dest.relative_to(SHARED.parent)}")
print(f"  sequence_vectors: {len(sequence_vectors)} ({n_steps} steps, {n_cues} cues total)")
for s in sequence_vectors:
    pre = sum(1 for st in s["steps"] if len(st["cues"]) > 1)
    print(f"    {s['name']!r:28} targets={s['targets']!r:12} steps={len(s['steps'])} pre-cues={pre}")
print("  all sequences cross-checked: implied-mode walk == committer interpret(), emits == targets")

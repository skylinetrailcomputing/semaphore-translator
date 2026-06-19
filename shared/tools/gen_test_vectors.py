# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Generate shared/test_vectors.json from the frozen alphabet + config.

Per pose, places the six post-adapter keypoints so each arm vector hits
its exact canonical octant angle, then runs the generated geometry back
through an embedded reference decoder (quantize -> order-insensitive
lookup -> numeric-mode state machine) and asserts the emitted output
matches the intended symbol before writing. Running this script IS the
fixture's correctness check; re-run after any change to
semaphore_alphabet.json or semaphore_config.json.

Frame (spec sec 4.2, post-adapter): x increases toward the signer's
right, y increases up, angle measured CCW from +x. Position ids 0..7 sit
at -90, -45, 0, 45, 90, 135, 180, -135 degrees. The mirror + y-flip that
reach this frame live only in the per-platform adapter (spec 3.2); these
vectors are defined as the adapter's output, so neither flip is applied
here.

    uv run shared/tools/gen_test_vectors.py
"""

import json
import math
from pathlib import Path

SHARED = Path(__file__).resolve().parent.parent
ALPHABET = json.loads((SHARED / "semaphore_alphabet.json").read_text())
CONFIG = json.loads((SHARED / "semaphore_config.json").read_text())

TOL = CONFIG["ANGLE_TOLERANCE_DEG"]
MIN_CONF = CONFIG["MIN_KEYPOINT_CONFIDENCE"]

# id -> angle, read from the alphabet's position model (single source of truth)
OCTANT = {
    p["id"]: p["angle_deg"]
    for p in ALPHABET["_position_model"]["positions"].values()
}
LETTERS = ALPHABET["letters"]
DIGIT_MAP = ALPHABET["numeric_mode"]["digit_map"]
NUMERALS = ALPHABET["control_signals"]["NUMERALS"]
REST = ALPHABET["control_signals"]["REST"]


# --- reference decoder (mirrors spec 4.3 + 4.5; both platforms must match) ---


def build_lookup():
    """Order-insensitive (sorted-pair) lookup; fails loud on any collision."""
    table = {}
    pairs = [(sym, ids["left"], ids["right"]) for sym, ids in LETTERS.items()]
    pairs.append(("NUMERALS", NUMERALS["left"], NUMERALS["right"]))
    pairs.append(("REST", REST["left"], REST["right"]))
    for sym, left, right in pairs:
        key = tuple(sorted((left, right)))
        if key in table:
            raise SystemExit(
                f"alphabet collision under order-insensitive match: "
                f"{sym} and {table[key]} both map to {key}"
            )
        table[key] = sym
    return table


LOOKUP = build_lookup()


def circ_diff(a, b):
    return abs(((a - b + 180) % 360) - 180)


def quantize(angle):
    """Snap to nearest octant; None if farther than TOL from every octant."""
    best_id, best = None, 1e9
    for id_, oct_angle in OCTANT.items():
        d = circ_diff(angle, oct_angle)
        if d < best:
            best, best_id = d, id_
    return best_id if best <= TOL else None


def arm_id(shoulder, wrist):
    """Position id for one arm, or None if indeterminate (angle or confidence)."""
    if shoulder[2] < MIN_CONF or wrist[2] < MIN_CONF:
        return None
    angle = math.degrees(math.atan2(wrist[1] - shoulder[1], wrist[0] - shoulder[0]))
    return quantize(angle)


def classify(kp):
    left = arm_id(kp["left_shoulder"], kp["left_wrist"])
    right = arm_id(kp["right_shoulder"], kp["right_wrist"])
    sym = None
    if left is not None and right is not None:
        sym = LOOKUP.get(tuple(sorted((left, right))))
    return left, right, sym


def interpret(sym, mode):
    """Map a classified symbol to (emitted_char, new_mode). Spec 4.5, with
    REST -> ' ' (space; mode persists) per the 2026-06-19 decision."""
    if sym is None:
        return "", mode  # indeterminate: emit nothing, mode unchanged
    if sym == "NUMERALS":
        return "", "NUMERIC"  # numerals sign
    if sym == "J" and mode == "NUMERIC":
        return "", "LETTERS"  # J pose = letters sign, but ONLY as the exit from numeric mode
    if sym == "REST":
        return " ", mode  # space; numeric mode is NOT reset by a rest
    if mode == "NUMERIC" and sym in DIGIT_MAP:
        return DIGIT_MAP[sym], mode
    return sym, mode  # in LETTERS mode the J pose lands here -> 'J'


def decode_frame(kp, mode):
    left, right, sym = classify(kp)
    emit, new_mode = interpret(sym, mode)
    return emit, new_mode, [left, right]


# --- keypoint geometry (post-adapter: x -> signer's right, y up) ---

R_SH = (0.60, 0.55)  # right_shoulder; note right_shoulder.x > left_shoulder.x
L_SH = (0.40, 0.55)  # left_shoulder
L_ARM = 0.22  # shoulder -> wrist length (normalized); elbow at the midpoint


def _rd(v):
    return round(v, 6)


def make_kp(
    left_id,
    right_id,
    *,
    left_angle=None,
    right_angle=None,
    left_wrist_conf=1.0,
    right_wrist_conf=1.0,
):
    """Six keypoints placing each arm at its octant angle (or an override)."""
    la = OCTANT[left_id] if left_angle is None else left_angle
    ra = OCTANT[right_id] if right_angle is None else right_angle

    def arm(sh, angle, wrist_conf):
        th = math.radians(angle)
        cos, sin = math.cos(th), math.sin(th)
        elbow = [_rd(sh[0] + 0.5 * L_ARM * cos), _rd(sh[1] + 0.5 * L_ARM * sin), 1.0]
        wrist = [_rd(sh[0] + L_ARM * cos), _rd(sh[1] + L_ARM * sin), wrist_conf]
        return [_rd(sh[0]), _rd(sh[1]), 1.0], elbow, wrist

    l_sh, l_el, l_wr = arm(L_SH, la, left_wrist_conf)
    r_sh, r_el, r_wr = arm(R_SH, ra, right_wrist_conf)
    return {
        "left_shoulder": l_sh,
        "left_elbow": l_el,
        "left_wrist": l_wr,
        "right_shoulder": r_sh,
        "right_elbow": r_el,
        "right_wrist": r_wr,
    }


def validate_ranges(kp, where):
    for name, (x, y, c) in kp.items():
        for val, lbl in ((x, "x"), (y, "y"), (c, "confidence")):
            if not 0.0 <= val <= 1.0:
                raise SystemExit(f"{where}: {name}.{lbl}={val} out of [0,1]")


# --- vector builders ---

single_pose_vectors = []
sequence_vectors = []


def add_single(name, kp, mode_before, note=None):
    validate_ranges(kp, name)
    emit, mode_after, ids = decode_frame(kp, mode_before)
    entry = {
        "name": name,
        "keypoints": kp,
        "mode_before": mode_before,
        "expected": emit,
        "expected_position_ids": ids,
    }
    if mode_after != mode_before:
        entry["mode_after"] = mode_after
    if note:
        entry["_note"] = note
    single_pose_vectors.append(entry)
    return emit, ids


def run_sequence(name, mode_start, steps):
    """steps: list of (frame_name, keypoints, expected_emit, note)."""
    mode = mode_start
    frames = []
    for frame_name, kp, want, note in steps:
        validate_ranges(kp, f"{name}/{frame_name}")
        emit, mode, ids = decode_frame(kp, mode)
        assert emit == want, (name, frame_name, "got", repr(emit), "want", repr(want))
        frame = {
            "name": frame_name,
            "keypoints": kp,
            "expected": emit,
            "expected_position_ids": ids,
            "mode_after": mode,
        }
        if note:
            frame["_note"] = note
        frames.append(frame)
    sequence_vectors.append({"name": name, "mode_start": mode_start, "frames": frames})


def kp_letter(letter):
    ids = LETTERS[letter]
    return make_kp(ids["left"], ids["right"])


def kp_numerals():
    return make_kp(NUMERALS["left"], NUMERALS["right"])


def kp_rest():
    return make_kp(REST["left"], REST["right"])


# 1. one clean vector per letter A-Z, in the frozen label order
for letter in [c for c in ALPHABET["label_order"] if c in LETTERS]:
    ids = LETTERS[letter]
    note = None
    if letter == "J":
        note = (
            "J pose in LETTERS mode -> the letter 'J'. The same pose is the "
            "letters-shift, but only when exiting numeric mode (spec 4.5)."
        )
    emit, got = add_single(letter, kp_letter(letter), "LETTERS", note)
    assert got == [ids["left"], ids["right"]], (letter, got)
    assert emit == letter, (letter, emit)

# NUMERALS pose and REST pose
emit, got = add_single(
    "NUMERALS",
    kp_numerals(),
    "LETTERS",
    "Numerals sign: switches the decoder to NUMERIC mode, emits nothing (spec 4.5).",
)
assert got == [NUMERALS["left"], NUMERALS["right"]] and emit == ""
emit, got = add_single(
    "REST",
    kp_rest(),
    "LETTERS",
    "Both arms down = space/rest (chart 'Space'): emits ' ', mode persists.",
)
assert got == [0, 0] and emit == " "

# 2. arm-swapped positives for the same-side letters (order-insensitive match)
for letter in ALPHABET["_matching"]["ambiguous_letters"]:
    ids = LETTERS[letter]
    li, ri = ids["left"], ids["right"]
    assert li != ri, (letter, "ambiguous letters must have distinct ids")
    emit, got = add_single(
        f"{letter}_arm_swapped",
        make_kp(ri, li),  # arms reversed vs the canonical orientation
        "LETTERS",
        f"Arm-swapped {letter}: (left,right)=({ri},{li}); order-insensitive "
        f"match still decodes {letter} (spec 4.3, _matching).",
    )
    assert got == [ri, li], (letter, got)
    assert emit == letter, (letter, emit)

# 3. indeterminate cases
emit, got = add_single(
    "indeterminate_angle",
    make_kp(2, 2, right_angle=22.5),  # right arm midway between id 2 (0) and id 3 (45)
    "LETTERS",
    "Right arm at 22.5deg is 22.5deg from both id 2 (0deg) and id 3 (45deg), "
    f"beyond ANGLE_TOLERANCE_DEG ({TOL}): that arm is indeterminate, no emit.",
)
assert emit == "" and got[1] is None, ("indeterminate_angle", emit, got)
emit, got = add_single(
    "indeterminate_low_confidence",
    make_kp(2, 4, right_wrist_conf=0.3),  # valid octant, but wrist below the floor
    "LETTERS",
    f"Right wrist confidence 0.3 < MIN_KEYPOINT_CONFIDENCE ({MIN_CONF}): that "
    "arm is indeterminate despite a valid octant geometry, no emit.",
)
assert emit == "" and got[1] is None, ("indeterminate_low_confidence", emit, got)

# 4. sequences exercising both numeric-mode transitions (spec 4.5)
run_sequence(
    "letters_numeric_letters",
    "LETTERS",
    [
        ("A", kp_letter("A"), "A", "Letter mode: A pose -> 'A'."),
        ("NUMERALS", kp_numerals(), "", "Numerals sign -> NUMERIC mode, emits nothing."),
        ("A_as_1", kp_letter("A"), "1", "Same A pose, now NUMERIC -> digit '1'."),
        ("B_as_2", kp_letter("B"), "2", "B pose in NUMERIC -> '2'."),
        ("LETTERS_shift", kp_letter("J"), "", "J pose = letters sign -> LETTERS, emits nothing."),
        ("A_again", kp_letter("A"), "A", "A pose, LETTERS mode again -> 'A'."),
    ],
)
run_sequence(
    "rest_spaces_and_high_digits",
    "LETTERS",
    [
        ("H", kp_letter("H"), "H", None),
        ("I", kp_letter("I"), "I", None),
        ("REST_space", kp_rest(), " ", "REST -> space; mode persists (still LETTERS)."),
        ("NUMERALS", kp_numerals(), "", None),
        ("H_as_8", kp_letter("H"), "8", "H pose in NUMERIC -> '8'."),
        ("I_as_9", kp_letter("I"), "9", "I pose in NUMERIC -> '9'."),
        ("K_as_0", kp_letter("K"), "0", "K pose in NUMERIC -> '0' (K=0)."),
        ("REST_in_numeric", kp_rest(), " ", "REST inside NUMERIC -> space; numeric mode is NOT reset by a rest."),
        ("LETTERS_shift", kp_letter("J"), "", "J pose = letters sign -> LETTERS."),
        ("REST_space2", kp_rest(), " ", "Trailing REST -> space."),
    ],
)
# literal J mid-word: in letter mode the J pose is the letter J, not a shift
run_sequence(
    "letter_j_in_word",
    "LETTERS",
    [
        ("A", kp_letter("A"), "A", "A pose -> 'A' (spelling AJAR)."),
        ("J_as_letter", kp_letter("J"), "J", "J pose in LETTERS mode -> the letter 'J' (no shift)."),
        ("A2", kp_letter("A"), "A", None),
        ("R", kp_letter("R"), "R", None),
    ],
)
# edge: numbers then a J-word -> two J poses in a row across the boundary
run_sequence(
    "numeric_then_j_word",
    "LETTERS",
    [
        ("NUMERALS", kp_numerals(), "", "-> NUMERIC."),
        ("one", kp_letter("A"), "1", "A pose in NUMERIC -> '1'."),
        ("LETTERS_shift", kp_letter("J"), "", "First J pose: letters-shift (NUMERIC -> LETTERS), emits nothing."),
        ("J_as_letter", kp_letter("J"), "J", "Second J pose, now in LETTERS -> the letter 'J' (spelling JAR)."),
        ("A", kp_letter("A"), "A", None),
        ("R", kp_letter("R"), "R", None),
    ],
)


# --- assemble + write ---

out = {
    "$schema_version": "1.0",
    "_README": (
        "Parity test vectors: post-adapter keypoint sets -> expected decoded "
        "output. Both platforms run these through their full feature-extraction "
        "+ classification + decode path and MUST produce identical results (spec "
        "sec 6 parity guarantee). These vectors are defined as the per-platform "
        "adapter's OUTPUT, so they do NOT exercise the adapter's mirror/y-flip "
        "(tested separately with native fixtures, Epic 3) nor temporal "
        "smoothing/commit timing (Epic 4) -- one frame per committed symbol."
    ),
    "_generated_by": (
        "shared/tools/gen_test_vectors.py (uv run). Generated, not hand-edited: "
        "regenerate after any change to semaphore_alphabet.json or "
        "semaphore_config.json. The generator re-decodes every vector and asserts "
        "the expected output before writing, so running it is the fixture's "
        "correctness check."
    ),
    "_decode_contract": (
        "Per frame: angle = atan2(wrist.y - shoulder.y, wrist.x - shoulder.x) per "
        "arm (spec 4.2); snap to nearest octant, indeterminate if > "
        "ANGLE_TOLERANCE_DEG from every octant or a defining keypoint is below "
        "MIN_KEYPOINT_CONFIDENCE (4.3); look up the SORTED (left,right) id pair "
        "order-insensitively (4.3); then apply the numeric-mode state machine "
        "(4.5): NUMERALS pose -> NUMERIC (emit ''); the J pose is the letters-"
        "shift ONLY in numeric mode (NUMERIC -> LETTERS, emit '') and is the "
        "letter 'J' in letter mode; REST -> ' ' (mode persists); A-I/K in "
        "NUMERIC -> digits 1-9/0; else the letter."
    ),
    "_format": {
        "keypoints": (
            "Object with the six shared keypoints, each [x, y, confidence], "
            "normalized to [0,1], y-up, signer's perspective (post-adapter). "
            "Keys: left_shoulder, left_elbow, left_wrist, right_shoulder, "
            "right_elbow, right_wrist."
        ),
        "mode_before": (
            "single_pose_vectors only: decoder mode entering the frame "
            "('LETTERS' or 'NUMERIC'). Sequences thread mode from 'mode_start'."
        ),
        "expected": (
            "Emitted string for the frame: a letter A-Z, a digit 0-9, ' ' for "
            "REST/space, or '' when nothing is emitted (a mode-switch pose or an "
            "indeterminate arm)."
        ),
        "expected_position_ids": (
            "[left_id, right_id] white-box check of the quantization step before "
            "lookup; an entry is null when that arm is indeterminate."
        ),
        "name": "Human-readable label for debugging. Ignored by the parity harness.",
        "_note": "Optional human explanation. Ignored by the parity harness.",
        "mode_after": (
            "Optional documentation of decoder mode after the frame (present "
            "where it changes, and on every sequence frame). Ignored by the "
            "harness, which derives mode itself."
        ),
        "sequence_vectors": (
            "Each is {name, mode_start, frames:[...]}. Frames are decoded in "
            "order, threading decoder mode from mode_start; each frame has the "
            "same shape as a single_pose vector (minus mode_before)."
        ),
    },
    "single_pose_vectors": single_pose_vectors,
    "sequence_vectors": sequence_vectors,
}

dest = SHARED / "test_vectors.json"
dest.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n")

n_swapped = sum(1 for v in single_pose_vectors if v["name"].endswith("_arm_swapped"))
n_indet = sum(1 for v in single_pose_vectors if v["name"].startswith("indeterminate"))
print(f"wrote {dest.relative_to(SHARED.parent)}")
print(
    f"  single_pose_vectors: {len(single_pose_vectors)} "
    f"(A-Z + NUMERALS + REST, {n_swapped} arm-swapped, {n_indet} indeterminate)"
)
print(
    f"  sequence_vectors: {len(sequence_vectors)} "
    f"({sum(len(s['frames']) for s in sequence_vectors)} frames total)"
)
print("  all vectors re-decoded and asserted OK")

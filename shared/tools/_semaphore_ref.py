# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Shared reference decoder + keypoint geometry for the fixture generators.

This is the single Python reference both `gen_test_vectors.py` (per-frame parity
fixtures) and `gen_temporal_vectors.py` (timed committer fixtures) import, so the
two generators cannot drift from each other — the same drift hazard the Swift /
Kotlin parity harness guards against on the device side. The functions here are
the canonical reference the Swift `SemaphoreDecoder` and Kotlin `SemaphoreDecoder`
mirror.

Frame (spec §4.2, post-adapter): x increases toward the signer's right, y
increases up, angle measured CCW from +x. Position ids 0..7 sit at -90, -45, 0,
45, 90, 135, 180, -135 degrees. The mirror + y-flip that reach this frame live
only in the per-platform adapter (spec §3.2); these vectors are defined as the
adapter's output, so neither flip is applied here.

Not a runnable script — import it from a generator.
"""

import json
import math
from pathlib import Path

SHARED = Path(__file__).resolve().parent.parent
ALPHABET = json.loads((SHARED / "semaphore_alphabet.json").read_text())
CONFIG = json.loads((SHARED / "semaphore_config.json").read_text())

TOL = CONFIG["ANGLE_TOLERANCE_DEG"]
MIN_CONF = CONFIG["MIN_KEYPOINT_CONFIDENCE"]

# Temporal-commit constants (spec §4.4; used by gen_temporal_vectors.py). These
# flat constants ARE the Learn (front-camera) profile -- keep their names/values
# frozen; gen_test_vectors.py imports from this module too (though not these).
COMMIT_HOLD_MS = CONFIG["COMMIT_HOLD_MS"]
SMOOTHING_WINDOW = CONFIG["SMOOTHING_WINDOW"]
INTER_CHAR_GAP_MS = CONFIG["INTER_CHAR_GAP_MS"]

# Per-fork timing profiles (ADR 0009; used by gen_temporal_vectors.py). The flat
# constants above are the implicit "learn" profile; each named entry under
# timing_profiles fully specifies all three timing keys (never a delta). Selecting
# a profile that is absent, or whose entry omits a key, is a fatal KeyError --
# never a silent fallback to learn (both platform loaders enforce the same).
_LEARN_TIMING = {
    "COMMIT_HOLD_MS": COMMIT_HOLD_MS,
    "INTER_CHAR_GAP_MS": INTER_CHAR_GAP_MS,
    "SMOOTHING_WINDOW": SMOOTHING_WINDOW,
}
TIMING_PROFILES = CONFIG.get("timing_profiles", {})


def timing_for(profile=None):
    """Committer timing for a fork profile, as the reference Committer's kwargs
    (smoothing_window, commit_hold_ms, inter_char_gap_ms). None / "learn" -> the
    flat constants; any other name -> its fully-specified timing_profiles entry
    (fatal KeyError if the entry or a key is missing -- no silent fallback)."""
    t = _LEARN_TIMING if profile in (None, "learn") else TIMING_PROFILES[profile]
    return {
        "smoothing_window": t["SMOOTHING_WINDOW"],
        "commit_hold_ms": t["COMMIT_HOLD_MS"],
        "inter_char_gap_ms": t["INTER_CHAR_GAP_MS"],
    }

# id -> angle, read from the alphabet's position model (single source of truth)
OCTANT = {
    p["id"]: p["angle_deg"] for p in ALPHABET["_position_model"]["positions"].values()
}
LETTERS = ALPHABET["letters"]
DIGIT_MAP = ALPHABET["numeric_mode"]["digit_map"]
NUMERALS = ALPHABET["control_signals"]["NUMERALS"]
REST = ALPHABET["control_signals"]["REST"]


# --- reference decoder (mirrors spec §4.3 + §4.5; both platforms must match) ---


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


def arm_id(shoulder, elbow, wrist):
    """Position id for one arm, or None if indeterminate.

    The primary arm vector is shoulder->wrist. When the wrist is below the
    confidence floor but the elbow is not, fall back to the collinear
    shoulder->elbow vector (#111, lever C from the #101 diagnostic): semaphore
    arms are held straight, so the two vectors share an angle, and the elbow is
    the more proximal, lower-variance joint that survives the frame-clipping /
    body-crossing that gates the wrist. The shoulder is the common origin for
    both vectors, so a low-confidence shoulder is unrecoverable -> indeterminate,
    as is a low wrist with no in-frame elbow proxy."""
    if shoulder[2] < MIN_CONF:
        return None
    if wrist[2] >= MIN_CONF:
        tip = wrist
    elif elbow[2] >= MIN_CONF:
        tip = elbow
    else:
        return None
    angle = math.degrees(math.atan2(tip[1] - shoulder[1], tip[0] - shoulder[0]))
    return quantize(angle)


def classify(kp):
    """Stage 1: the mode-independent pose symbol (the committer's votable unit).

    Returns (left_id, right_id, symbol); symbol is a letter / NUMERALS / REST, or
    None when either arm is indeterminate."""
    left = arm_id(kp["left_shoulder"], kp["left_elbow"], kp["left_wrist"])
    right = arm_id(kp["right_shoulder"], kp["right_elbow"], kp["right_wrist"])
    sym = None
    if left is not None and right is not None:
        sym = LOOKUP.get(tuple(sorted((left, right))))
    return left, right, sym


def interpret(sym, mode):
    """Stage 2: map a classified symbol to (emitted_char, new_mode). Spec §4.5,
    with REST -> ' ' (space; mode persists) per the 2026-06-19 decision."""
    if sym is None:
        return "", mode  # indeterminate: emit nothing, mode unchanged
    if sym == "NUMERALS":
        return "", "NUMERIC"  # numerals sign
    if sym == "J" and mode == "NUMERIC":
        return (
            "",
            "LETTERS",
        )  # J pose = letters sign, but ONLY as the exit from numeric mode
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
    left_shoulder_conf=1.0,
    right_shoulder_conf=1.0,
    left_elbow_conf=1.0,
    right_elbow_conf=1.0,
    left_elbow_angle=None,
    right_elbow_angle=None,
):
    """Six keypoints placing each arm at its octant angle (or an override).

    The elbow sits at the shoulder->wrist midpoint, so by default it is collinear
    with the wrist and shares the arm's octant angle -- which is what makes the
    #111 elbow fallback exact on these fixtures. Drop *_elbow_conf alongside
    *_wrist_conf to model a pose where neither the wrist nor its proxy is in frame
    (the fallback then declines). Set *_elbow_angle to bend the elbow OFF the arm
    line (ADR 0012, Decision 2): a bent arm whose wrist is sub-floor resolves to
    the elbow's octant, not the wrist's -- the accepted straight-arm-prior tradeoff."""
    la = OCTANT[left_id] if left_angle is None else left_angle
    ra = OCTANT[right_id] if right_angle is None else right_angle

    def arm(sh, angle, wrist_conf, shoulder_conf, elbow_conf, elbow_angle):
        th = math.radians(angle)
        eth = math.radians(angle if elbow_angle is None else elbow_angle)
        elbow = [
            _rd(sh[0] + 0.5 * L_ARM * math.cos(eth)),
            _rd(sh[1] + 0.5 * L_ARM * math.sin(eth)),
            elbow_conf,
        ]
        wrist = [_rd(sh[0] + L_ARM * math.cos(th)), _rd(sh[1] + L_ARM * math.sin(th)), wrist_conf]
        return [_rd(sh[0]), _rd(sh[1]), shoulder_conf], elbow, wrist

    l_sh, l_el, l_wr = arm(
        L_SH, la, left_wrist_conf, left_shoulder_conf, left_elbow_conf, left_elbow_angle
    )
    r_sh, r_el, r_wr = arm(
        R_SH, ra, right_wrist_conf, right_shoulder_conf, right_elbow_conf, right_elbow_angle
    )
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

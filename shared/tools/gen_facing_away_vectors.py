# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Generate shared/facing_away_vectors.json — the Interpret facing-away parity fixtures.

ADR 0011 ("Interpret facing-away flip"): Interpret offers an opt-in toggle for a
signer whose BACK is to the camera (a lifeguard facing the water). Default is
facing-us; the toggle applies ONE extra horizontal flip (x -> 1 - x) to the
post-adapter keypoints before decode, cancelling the adapter's signer's-perspective
mirror so a back-facing signer reads as the letter they mean rather than its mirror
twin.

That extra flip is the #1 cross-platform divergence source (chirality), so it gets
its own parity fixtures. Each vector carries one canonical post-adapter pose plus
BOTH decode branches:

  - facing_us:   decode(kp)        -> the symbol itself (the flag is OFF; identity)
  - facing_away: decode(flip(kp))  -> the symbol's MIRROR TWIN (the flag is ON)

A horizontal flip reflects every arm angle (theta -> 180 - theta), so the
order-insensitive lookup (spec 4.3) maps each pose to its mirror twin — exactly the
ADR-0006 / `check_mirror_twins.py` table this generator cross-checks against. The
discipline is the catch: a platform that botches (or skips) the flip lands a
DIFFERENT, detectably-wrong letter for every asymmetric pose, not garbage.

Both platform parity harnesses build `kp` from `keypoints`, decode it for the
facing_us branch, and decode `Keypoints.mirroredHorizontally(kp)` (the SHIPPING
flip) for the facing_away branch — so this fixture pins the production transform,
not a test-only reimplementation.

The reference decoder + keypoint geometry live in _semaphore_ref.py, shared with the
other generators so the fixtures cannot drift. `flip_kp` below is the Python twin of
each platform's `mirroredHorizontally()`.

    uv run shared/tools/gen_facing_away_vectors.py
"""

import json

from _semaphore_ref import (  # the single Python reference the generators share
    ALPHABET,
    LETTERS,
    NUMERALS,
    REST,
    SHARED,
    decode_frame,
    interpret,
    make_kp,
    validate_ranges,
)
from check_mirror_twins import EXPECTED_TWIN  # the ADR-0006 twin table, cross-check


MODE_BEFORE = "LETTERS"  # Interpret starts in LETTERS, like every live session


def flip_kp(kp):
    """One horizontal flip: x -> 1 - x per keypoint, y + confidence unchanged. The
    Python twin of each platform's shipping `Keypoints.mirroredHorizontally()`. Round
    to 6 decimals, matching `make_kp`, so the JSON the platforms read is byte-stable."""
    return {
        name: [round(1.0 - x, 6), y, c] for name, (x, y, c) in kp.items()
    }


def branch(kp, name):
    """Decode one keypoint set in MODE_BEFORE and package the {expected, ids, mode_after}
    branch the harness asserts against (mode_after is documentation; the single-frame
    harness checks emit + ids, exactly like the test_vectors single-pose harness)."""
    validate_ranges(kp, name)
    emit, mode_after, ids = decode_frame(kp, MODE_BEFORE)
    out = {"expected": emit, "expected_position_ids": ids}
    if mode_after != MODE_BEFORE:
        out["mode_after"] = mode_after
    return out, emit, ids


def kp_for(symbol):
    if symbol == "NUMERALS":
        ids = NUMERALS
    elif symbol == "REST":
        ids = REST
    else:
        ids = LETTERS[symbol]
    return make_kp(ids["left"], ids["right"])


# --- build one vector per symbol (A-Z in label order, then NUMERALS, REST) ---

vectors = []
symbols = [c for c in ALPHABET["label_order"] if c in LETTERS] + ["NUMERALS", "REST"]

for symbol in symbols:
    kp = kp_for(symbol)
    flipped = flip_kp(kp)

    us, us_emit, us_ids = branch(kp, f"{symbol}/facing_us")
    away, away_emit, away_ids = branch(flipped, f"{symbol}/facing_away")

    # facing_us is the flag OFF: it must reproduce the canonical decode of the pose.
    assert us_emit == interpret(symbol, MODE_BEFORE)[0], (
        symbol, "facing_us emit drifted from the canonical decode", us_emit
    )

    # facing_away is the flag ON: the flip must land the pose's MIRROR TWIN. Cross-check
    # the geometric result against the independently-derived ADR-0006 twin table, then
    # against the emit that twin produces in MODE_BEFORE. EXPECTED_TWIN[L] is None
    # (INDETERMINATE) -> interpret(None) -> "" — the L canary.
    twin = EXPECTED_TWIN[symbol]
    want_away_emit = interpret(twin, MODE_BEFORE)[0]
    assert away_emit == want_away_emit, (
        symbol, "facing_away emit", repr(away_emit), "!= twin", twin, repr(want_away_emit)
    )

    # The flip is an involution: flipping twice is the identity decode. Guards against a
    # platform implementing a y-flip or a label-swap by mistake instead of a clean x-flip.
    rt_emit, _, rt_ids = decode_frame(flip_kp(flipped), MODE_BEFORE)
    assert (rt_emit, rt_ids) == (us_emit, us_ids), (symbol, "flip is not involutive")

    vectors.append(
        {
            "name": symbol,
            "keypoints": kp,
            "mode_before": MODE_BEFORE,
            "facing_us": us,
            "facing_away": away,
        }
    )

# Sanity on the discriminating structure, so a future alphabet edit can't quietly gut
# the fixture's catching power.
symmetric = {s["name"] for s in vectors if s["facing_away"]["expected"] == s["facing_us"]["expected"]}
assert symmetric == {"D", "N", "R", "U", "REST"}, ("symmetric set drifted", sorted(symmetric))
canary = next(v for v in vectors if v["name"] == "L")
assert canary["facing_away"]["expected"] == "" and None not in canary["facing_away"]["expected_position_ids"], (
    "L canary: facing-away must be INDETERMINATE (empty emit) with two valid reflected ids"
)


# --- assemble + write ---

out = {
    "$schema_version": "1.0",
    "_README": (
        "Interpret facing-away parity fixtures (ADR 0011, issue #78). Each vector is "
        "ONE canonical post-adapter pose with BOTH decode branches: facing_us "
        "(decode the keypoints as-is — the toggle OFF, the identity) and facing_away "
        "(decode the keypoints after a single horizontal flip x->1-x — the toggle ON). "
        "Both platforms build the keypoints, decode them for facing_us, and decode "
        "Keypoints.mirroredHorizontally(keypoints) — the SHIPPING flip — for "
        "facing_away, and MUST produce identical emits + position ids (spec 6 parity "
        "guarantee). The facing_away branch is each pose's MIRROR TWIN, because a "
        "horizontal flip reflects both arm angles (theta->180-theta); this is the "
        "highest-blast-radius (chirality) area, which is why it has its own fixtures."
    ),
    "_generated_by": (
        "shared/tools/gen_facing_away_vectors.py (uv run). Generated, not hand-edited: "
        "regenerate after any change to semaphore_alphabet.json or semaphore_config.json. "
        "The generator re-decodes every vector, cross-checks the facing_away branch "
        "against the ADR-0006 mirror-twin table (check_mirror_twins.EXPECTED_TWIN), and "
        "asserts the flip is involutive before writing — running it is the fixture's "
        "correctness check."
    ),
    "_decode_contract": (
        "facing_us: decode(keypoints) per the test_vectors.json _decode_contract. "
        "facing_away: decode(flip(keypoints)), flip = x->1-x per keypoint (y + "
        "confidence unchanged), applied ONCE. The flip is the post-adapter, opt-in "
        "re-mirror Interpret applies for a back-facing signer (ADR 0011); it is NOT a "
        "second adapter mirror (the adapter's single mirror is unchanged and still "
        "happens exactly once — ADAPTER-CONTRACT.md 3b)."
    ),
    "_format": {
        "keypoints": (
            "Object with the six shared keypoints, each [x, y, confidence], normalized "
            "to [0,1], y-up, signer's perspective (post-adapter). Keys: left_shoulder, "
            "left_elbow, left_wrist, right_shoulder, right_elbow, right_wrist."
        ),
        "mode_before": "Decoder mode entering the frame ('LETTERS' for every vector here).",
        "facing_us": (
            "The toggle OFF: {expected, expected_position_ids[, mode_after]} for "
            "decode(keypoints). Equals the canonical test_vectors.json decode of the pose."
        ),
        "facing_away": (
            "The toggle ON: {expected, expected_position_ids[, mode_after]} for "
            "decode(mirroredHorizontally(keypoints)). Equals the pose's mirror twin."
        ),
        "expected": (
            "Emitted string for the branch: a letter A-Z, a digit 0-9, ' ' for "
            "REST/space, or '' (a mode-switch pose or an indeterminate pair)."
        ),
        "expected_position_ids": (
            "[left_id, right_id] white-box check of quantization before lookup; null "
            "where an arm is indeterminate (none here — every reflected arm is a valid "
            "octant; L's facing_away is '' because the reflected PAIR is unassigned)."
        ),
        "mode_after": (
            "Optional documentation of decoder mode after the frame (present where it "
            "changes — e.g. a NUMERALS branch -> NUMERIC). Ignored by the harness, which "
            "checks emit + ids on this single-frame fixture."
        ),
        "name": "Human-readable label (the symbol). Ignored by the parity harness.",
    },
    "vectors": vectors,
}

dest = SHARED / "facing_away_vectors.json"
dest.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n")

n_twin = sum(1 for v in vectors if v["facing_away"]["expected"] != v["facing_us"]["expected"])
print(f"wrote {dest.relative_to(SHARED.parent)}")
print(f"  vectors: {len(vectors)} (A-Z + NUMERALS + REST)")
print(f"  facing_away diverges from facing_us on {n_twin}/{len(vectors)} (the asymmetric poses)")
print(f"  symmetric (self-twin): {sorted(symmetric)}; L canary -> INDETERMINATE under the flip")
print("  all vectors re-decoded, cross-checked vs the ADR-0006 twin table, and asserted OK")

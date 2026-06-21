# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Generate shared/temporal_vectors.json — timed fixtures for the committer.

The temporal committer (ADR 0004) is time-dependent, so its parity fixtures
cannot be the per-frame test_vectors.json (whose harness is explicitly untimed,
one frame per committed symbol). This generates a separate file of timed frame
sequences and the string each sequence commits, so both platform ports (#4.2 /
#4.3) drive the SAME state machine against the SAME timed vectors.

Each sequence is authored as (pose, frame-count) steps; this script renders the
keypoints, classifies each frame to its votable pose symbol (_semaphore_ref),
feeds (symbol, t_ms) through the reference Committer below, and asserts the
committed output matches the intended string before writing. Running this script
IS the fixture's correctness check (same discipline as gen_test_vectors.py) —
re-run after any change to semaphore_alphabet.json or semaphore_config.json.

The Committer here is THE reference the Swift / Kotlin committer ports mirror; the
per-frame decode + keypoint geometry it leans on live in _semaphore_ref.py, shared
with gen_test_vectors.py so the two fixture sets cannot drift.

    uv run shared/tools/gen_temporal_vectors.py
"""

import json

from _semaphore_ref import (
    COMMIT_HOLD_MS,
    INTER_CHAR_GAP_MS,
    LETTERS,
    NUMERALS,
    REST,
    SHARED,
    SMOOTHING_WINDOW,
    classify,
    interpret,
    make_kp,
    validate_ranges,
)


# --- reference committer (ADR 0004; the Swift/Kotlin ports mirror this) -------


class Committer:
    """Pure temporal committer. `process(symbol, t_ms)` and `reset()` never read
    the clock — time is injected — so these fixtures are deterministic.

    State machine (ADR 0004):
      * Vote: a ring buffer of the last SMOOTHING_WINDOW votable pose symbols
        (None = indeterminate is a legitimate vote value). The candidate is the
        plurality value; ties break to the most-recent occurrence.
      * Commit: a determinate candidate held continuously for >= COMMIT_HOLD_MS
        (wall-clock) commits — run interpret(symbol, mode) for (emit, new mode).
        Mode therefore flips only on a *committed* control pose (debounced).
      * Same-symbol gate: a distinct symbol commits on its hold alone; the SAME
        symbol re-commits only after an intervening *brief REST* whose voted-
        candidate dwell lands in [INTER_CHAR_GAP_MS, COMMIT_HOLD_MS) -- the
        conventional double-letter separator (ADR 0005, superseding ADR 0004
        Decision 3). An indeterminate gap no longer re-arms, so incidental
        off-octant hold jitter can't double a held letter. A REST held to
        >= COMMIT_HOLD_MS commits a space instead (the re-arm window is half-open
        at the top), so a sustained REST never streams spaces.
      * Reset: a short indeterminate gap blocks commit but preserves mode +
        window (handled here); a hard reset() (true signer-loss) clears the
        window and sets mode -> LETTERS, and is called by the live no-signer
        watchdog (#4.5), never by the committer itself.
    """

    _UNSET = object()  # sentinel distinct from every symbol, including None

    def __init__(self, *, smoothing_window, commit_hold_ms, inter_char_gap_ms):
        self.n = smoothing_window
        self.hold = commit_hold_ms
        self.gap = inter_char_gap_ms
        self.reset()

    def reset(self):
        """Hard reset (true signer-loss, §4.5): clear the window, mode -> LETTERS."""
        self.window = []
        self.mode = "LETTERS"
        self.candidate = Committer._UNSET  # voted candidate currently being timed
        self.candidate_since = None
        self.last_committed = None  # last committed symbol (same-symbol lock key)
        self.gap_ok = True  # has a re-arming brief REST been seen since the last commit?
        self.rest_since = None  # start of the current continuous-REST run (else None)

    def _vote(self):
        """Plurality over the window; ties break to the most-recent occurrence.
        None (indeterminate) is a legitimate vote value."""
        counts = {}
        for v in self.window:
            counts[v] = counts.get(v, 0) + 1
        best = max(counts.values())
        winner = None
        for v in self.window:  # last value with the max count == most recent
            if counts[v] == best:
                winner = v
        return winner

    def process(self, symbol, t_ms):
        """Feed one classified pose symbol at wall-clock t_ms. Returns the string
        committed on THIS frame ('' when nothing commits)."""
        self.window.append(symbol)
        if len(self.window) > self.n:
            self.window.pop(0)

        candidate = self._vote()
        if candidate != self.candidate:  # candidate changed -> restart the hold timer
            self.candidate = candidate
            self.candidate_since = t_ms

        # A brief REST -- voted candidate == REST, dwell in [gap, hold) -- re-arms
        # the same-symbol lock WITHOUT committing a space: the conventional
        # double-letter separator (ADR 0005, superseding ADR 0004 Decision 3). The
        # window is half-open at the top because at >= hold the REST commits a space
        # (the commit step below) instead; that half-open top also stops a sustained
        # REST from re-arming after its space commits and streaming spaces. An
        # indeterminate gap no longer re-arms, so incidental off-octant hold jitter
        # can't double a held letter (FR4). Like the hold timer, this runs off the
        # VOTED candidate, not the raw incoming symbol (ADR 0004 Decision 2).
        if candidate == "REST":
            if self.rest_since is None:
                self.rest_since = t_ms
            if self.gap <= t_ms - self.rest_since < self.hold:
                self.gap_ok = True
        else:
            self.rest_since = None

        # Commit: a determinate candidate, held long enough, past the same-symbol gate.
        if candidate is not None and t_ms - self.candidate_since >= self.hold:
            if candidate != self.last_committed or self.gap_ok:
                emit, self.mode = interpret(candidate, self.mode)
                self.last_committed = candidate
                self.gap_ok = False
                return emit
        return ""


# --- keypoint authoring -------------------------------------------------------

DT_MS = 100  # frame interval; >= COMMIT_HOLD_MS / DT_MS frames make a held pose commit
HELD = 12  # frames to hold a pose so it reliably wins the vote and commits once
SPURIOUS = 2  # a 1-2 frame flicker must never out-vote a held pose (window of 5)
WOBBLE = 5  # indeterminate off-octant frames *within* a held letter; post-#40 these
# must NOT double it (they WOULD have when an indeterminate gap re-armed; ADR 0005)
REST_REARM = 5  # a brief REST that re-arms the same-symbol gate but does NOT commit
# a space -- its voted-candidate dwell lands in [INTER_CHAR_GAP_MS, COMMIT_HOLD_MS)
REST_SPACE = HELD  # a sustained REST (voted dwell >= COMMIT_HOLD_MS) commits a space

# Re-arm boundary, empirically pinned against the reference committer for the
# frozen constants (WINDOW=5, HOLD=600, GAP=300, DT=100): a REST run of
# REST_GAP_TOO_SHORT frames does NOT re-arm the same-symbol gate (its voted-
# candidate dwell stays < INTER_CHAR_GAP_MS once you account for window-flush),
# REST_GAP_MIN_REARM does. The re-arm timer runs off the VOTED candidate, not raw
# frames, so the REST run spans window-flush on both entry and exit (ADR 0004
# Decision 2) -- a port that armed on raw frames would re-arm earlier and fail one
# of these two boundary fixtures.
REST_GAP_TOO_SHORT = 3  # -> single commit ("L")
REST_GAP_MIN_REARM = 4  # -> re-commit ("LL")

# Both arms at 22.5deg sit exactly between octants 2 (0deg) and 3 (45deg), beyond
# ANGLE_TOLERANCE_DEG from either: classify -> None. This is the "indeterminate"
# pose (arms mid-transition / incidental hold jitter), which post-#40 NO LONGER
# re-arms the same-symbol gate -- distinct from REST (both arms straight down = a
# committable space, which now does the re-arming; ADR 0005).
INDETERMINATE = "<indeterminate>"
RESET = "<reset>"


def render(pose):
    """Keypoints for an authored pose token, or None for the reset event."""
    if pose == RESET:
        return None
    if pose == INDETERMINATE:
        return make_kp(2, 2, left_angle=22.5, right_angle=22.5)
    if pose == "NUMERALS":
        return make_kp(NUMERALS["left"], NUMERALS["right"])
    if pose == "REST":
        return make_kp(REST["left"], REST["right"])
    ids = LETTERS[pose]
    return make_kp(ids["left"], ids["right"])


# --- sequence builder + self-validation --------------------------------------

sequence_vectors = []


def run_committer(name, steps, expected, note=None):
    """steps: list of (pose_token, count). Renders + runs the reference committer,
    asserts the committed concatenation == expected, and records the fixture."""
    committer = Committer(
        smoothing_window=SMOOTHING_WINDOW,
        commit_hold_ms=COMMIT_HOLD_MS,
        inter_char_gap_ms=INTER_CHAR_GAP_MS,
    )
    frames = []
    committed = []
    t = 0
    for pose, count in steps:
        for _ in range(count):
            if pose == RESET:
                committer.reset()
                frames.append({"reset": True, "t_ms": t, "expected_emit": ""})
            else:
                kp = render(pose)
                validate_ranges(kp, f"{name}@{t}ms")
                symbol = classify(kp)[2]  # the votable unit (None when indeterminate)
                emit = committer.process(symbol, t)
                committed.append(emit)
                frames.append(
                    {
                        "keypoints": kp,
                        "t_ms": t,
                        "expected_symbol": symbol,
                        "expected_emit": emit,
                    }
                )
            t += DT_MS
    got = "".join(committed)
    assert got == expected, (name, "got", repr(got), "want", repr(expected))
    entry = {
        "name": name,
        "mode_start": "LETTERS",
        "frames": frames,
        "expected_committed": expected,
    }
    if note:
        entry["_note"] = note
    sequence_vectors.append(entry)


# 1. debounce: a 1-2 frame spurious pose never wins the vote, so it never commits;
#    and holding a committed pose does not re-commit it.
run_committer(
    "debounce_spurious_pose",
    [("A", HELD), ("B", SPURIOUS), ("A", HELD)],
    "A",
    "A commits once; a 2-frame B flicker never out-votes the held A (window of "
    f"{SMOOTHING_WINDOW}); holding A afterwards does not re-commit it.",
)

# 2. distinct letters stream on their holds alone — no gap needed between them.
run_committer(
    "distinct_letters_stream",
    [("A", HELD), ("B", HELD), ("C", HELD)],
    "ABC",
    "Each distinct held pose commits on its own hold; distinct symbols need no "
    "intervening gap.",
)

# 3. the SAME symbol repeats only across a BRIEF REST (ADR 0005, superseding ADR
#    0004 Decision 3): a rest whose voted dwell lands in [GAP, HOLD) re-arms the
#    lock without committing a space -- the conventional double-letter separator.
run_committer(
    "same_letter_doubles_via_brief_rest",
    [("L", HELD), ("REST", REST_REARM), ("L", HELD)],
    "LL",
    "First L commits; a brief REST (dwell in [INTER_CHAR_GAP_MS, COMMIT_HOLD_MS)) "
    "re-arms the lock WITHOUT committing a space; the second L then commits -> "
    "'LL'. Without the rest a held L commits exactly once (case 1).",
)

# 3a/3b. the brief-REST re-arm BOUNDARY, pinned on both sides so an off-by-one (or
# raw-frame vs voted-candidate) implementation in a port fails one of the two. See
# the REST_GAP_TOO_SHORT / REST_GAP_MIN_REARM notes above and ADR 0004 Decision 2.
run_committer(
    "same_letter_rest_too_short",
    [("L", HELD), ("REST", REST_GAP_TOO_SHORT), ("L", HELD)],
    "L",
    f"A {REST_GAP_TOO_SHORT}-frame REST does NOT re-arm: its voted-candidate dwell "
    "stays < INTER_CHAR_GAP_MS (the run is shorter once you account for window-"
    "flush), so the second L is suppressed -> single 'L'. Teeth: a port arming the "
    "gate on raw frames would (wrongly) re-commit here.",
)
run_committer(
    "same_letter_rest_min_rearms",
    [("L", HELD), ("REST", REST_GAP_MIN_REARM), ("L", HELD)],
    "LL",
    f"One frame longer ({REST_GAP_MIN_REARM}) is the minimal REST that DOES re-arm "
    "-> 'LL'. Paired with same_letter_rest_too_short this pins the boundary to a "
    "single frame.",
)

# 3c. coalesce: an indeterminate off-octant wobble *within* a held letter no longer
#     re-arms (the FR4-robustness half of #40), so a steady letter that jitters off
#     an octant and back reads as ONE character -- it would have DOUBLED pre-#40.
run_committer(
    "coalesce_off_octant_wobble",
    [("A", HELD), (INDETERMINATE, WOBBLE), ("A", HELD)],
    "A",
    f"A held A wobbles off-octant for {WOBBLE} indeterminate frames and returns. "
    "Indeterminate no longer re-arms the same-symbol gate (ADR 0005), so this "
    "coalesces to a single 'A'. Pre-#40 the indeterminate gap re-armed and this "
    "doubled to 'AA' -- the unintended-double bug from the #35 smoke.",
)

# 3d. contrast to 3: a SUSTAINED REST (dwell >= COMMIT_HOLD_MS) commits a space,
#     so the same letter on either side reads as 'L L', not 'LL'.
run_committer(
    "sustained_rest_spaces_between_letters",
    [("L", HELD), ("REST", REST_SPACE), ("L", HELD)],
    "L L",
    "A sustained REST commits one space (it crosses COMMIT_HOLD_MS); the second L "
    "then commits as a distinct symbol after that space -> 'L L'. Distinguishes a "
    "brief rest (double, case 3) from a sustained rest (space + letter).",
)

# 4. both numeric-mode transitions go THROUGH the committer (debounced mode switch).
run_committer(
    "mode_transitions_through_committer",
    [("NUMERALS", HELD), ("A", HELD), ("B", HELD), ("J", HELD), ("A", HELD)],
    "12A",
    "Held NUMERALS commits -> NUMERIC (emit ''); A/B -> '1'/'2'; held J pose is "
    "the letters-shift -> LETTERS (emit ''); A -> 'A'. Mode flips only on a "
    "committed control pose, so a fleeting NUMERALS/J frame can't flip it (#23).",
)

# 5. true signer-loss hard-resets mode to LETTERS and clears window + lock.
run_committer(
    "signer_loss_resets_mode",
    [("NUMERALS", HELD), ("A", HELD), (RESET, 1), ("A", HELD)],
    "1A",
    "In NUMERIC the A pose commits '1'; a signer-loss reset() clears state and "
    "sets mode -> LETTERS, so the next A commits the letter 'A' (and re-commits "
    "despite the prior A, proving the same-symbol lock was cleared).",
)

# 6. REST is an ordinary committable symbol: a held REST commits one space, debounced.
run_committer(
    "rest_commits_one_space",
    [("REST", HELD)],
    " ",
    "Both arms down = REST -> a single committed space; holding it does not "
    "stream spaces (debounce applies to REST like any symbol).",
)


# --- assemble + write ---------------------------------------------------------

out = {
    "$schema_version": "1.0",
    "_README": (
        "Temporal parity vectors: timed frame sequences -> the string the "
        "committer commits. The committer (ADR 0004) is time-dependent, so these "
        "are kept separate from test_vectors.json (whose per-frame harness is "
        "untimed). Both platform ports (#4.2/#4.3) feed each frame's votable pose "
        "symbol + t_ms through their committer and MUST produce identical output. "
        "Assert the PER-FRAME expected_emit (it pins WHEN each commit lands, "
        "catching a partial-window or off-by-one-gap port whose final string still "
        "matches); expected_committed is the convenience rollup. Frames are "
        "post-adapter keypoints (the adapter's mirror/y-flip is NOT exercised "
        "here; that is the Epic-3 native fixtures)."
    ),
    "_generated_by": (
        "shared/tools/gen_temporal_vectors.py (uv run). Generated, not hand-"
        "edited: regenerate after any change to semaphore_alphabet.json or "
        "semaphore_config.json. The generator runs the reference committer over "
        "every sequence and asserts expected_committed before writing, so running "
        "it is the fixture's correctness check."
    ),
    "_commit_contract": (
        "Per ADR 0004: vote the votable pose symbol (classify output; null = "
        "indeterminate is a vote value) over a ring buffer of the last "
        f"SMOOTHING_WINDOW ({SMOOTHING_WINDOW}) frames -> plurality candidate, "
        "ties to the most-recent occurrence. A determinate candidate held "
        f"continuously >= COMMIT_HOLD_MS ({COMMIT_HOLD_MS}) commits: run "
        "interpret(symbol, mode) (spec §4.5) for (emit, new mode), so mode flips "
        "only on a committed control pose. A distinct symbol commits on its hold "
        "alone; the SAME symbol re-commits only after an intervening brief REST "
        f"whose voted dwell lands in [INTER_CHAR_GAP_MS ({INTER_CHAR_GAP_MS}), "
        f"COMMIT_HOLD_MS ({COMMIT_HOLD_MS})) -- the conventional double-letter "
        "separator (ADR 0005, superseding ADR 0004 Decision 3); an indeterminate "
        "gap no longer re-arms. A REST held >= COMMIT_HOLD_MS commits a space "
        "instead. reset() (true signer-loss) clears the window and sets mode -> "
        "LETTERS."
    ),
    "_format": {
        "sequence_vectors": (
            "Each is {name, mode_start, frames:[...], expected_committed}. "
            "mode_start is always 'LETTERS' (the committer resets to it). Frames "
            "are processed in order; expected_committed is the concatenation of "
            "every frame's expected_emit."
        ),
        "frames": (
            "A pose frame is {keypoints, t_ms, expected_symbol, expected_emit}; a "
            "reset frame is {reset:true, t_ms, expected_emit:''} and the harness "
            "calls committer.reset() instead of process()."
        ),
        "keypoints": (
            "The six shared keypoints, each [x, y, confidence], normalized to "
            "[0,1], y-up, signer's perspective (post-adapter). Same shape as "
            "test_vectors.json."
        ),
        "t_ms": "Injected wall-clock time for the frame, in milliseconds.",
        "expected_symbol": (
            "The votable pose symbol classify() returns for this frame: a letter, "
            "'NUMERALS', 'REST', or null (indeterminate). The committer votes on "
            "this, not on the emitted character. Absent on reset frames."
        ),
        "expected_emit": (
            "The string committed on this exact frame: '' on most frames, the "
            "committed letter/digit/space on the frame a commit fires."
        ),
        "expected_committed": "The full decoded string: concatenation of every expected_emit.",
        "name": "Human-readable label for debugging. Ignored by the parity harness.",
        "_note": "Optional human explanation. Ignored by the parity harness.",
    },
    "sequence_vectors": sequence_vectors,
}

dest = SHARED / "temporal_vectors.json"
dest.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n")

n_frames = sum(len(s["frames"]) for s in sequence_vectors)
print(f"wrote {dest.relative_to(SHARED.parent)}")
print(f"  sequence_vectors: {len(sequence_vectors)} ({n_frames} frames total)")
for s in sequence_vectors:
    print(f"    {s['name']}: {len(s['frames'])} frames -> {s['expected_committed']!r}")
print("  all sequences re-run through the reference committer and asserted OK")

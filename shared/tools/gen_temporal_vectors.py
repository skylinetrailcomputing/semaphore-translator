# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Generate the timed committer fixtures, one file per timing profile.

The temporal committer (ADR 0004) is time-dependent, so its parity fixtures
cannot be the per-frame test_vectors.json (whose harness is explicitly untimed,
one frame per committed symbol). This generates timed frame sequences and the
string each sequence commits, so both platform ports (#4.2 / #4.3) drive the SAME
state machine against the SAME timed vectors.

Per-fork timing profiles (ADR 0009): the committer's timing forks by camera lens
-- the front-camera Learn profile (the frozen flat constants in
semaphore_config.json) and the rear-camera Interpret profile (the
timing_profiles.interpret override, a faster commit for faster real-world
signers). Each profile gets its own self-validating fixture file:

  * temporal_vectors.json            -> the Learn profile (the default / flat keys)
  * temporal_vectors_interpret.json  -> the Interpret profile

The Learn file is byte-for-byte unchanged by the introduction of the fork (Learn
timing and authoring are untouched); only a second file is added. COMMIT_HOLD_MS
is not merely a commit-latency knob -- it is also the UPPER bound of the
double-letter re-arm window [INTER_CHAR_GAP_MS, COMMIT_HOLD_MS) -- so the
Interpret profile's narrower window needs its own REST_REARM frame count
(REST_REARM_INTERPRET below); every other authored count is profile-independent.

Each sequence is authored as (pose, frame-count) steps; this script renders the
keypoints, classifies each frame to its votable pose symbol (_semaphore_ref),
feeds (symbol, t_ms) through the reference Committer below, and asserts the
committed output matches the intended string before writing. Running this script
IS the fixtures' correctness check (same discipline as gen_test_vectors.py) --
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
    timing_for,
    validate_ranges,
)


# --- reference committer (ADR 0004; the Swift/Kotlin ports mirror this) -------


class Committer:
    """Pure temporal committer. `process(symbol, t_ms)` and `reset()` never read
    the clock — time is injected — so these fixtures are deterministic.

    State machine (ADR 0004):
      * Vote: a ring buffer of the last SMOOTHING_WINDOW votable pose symbols
        (None = indeterminate is a legitimate vote value). The candidate is the
        plurality value; ties break to the most-recent occurrence. The incumbent
        (the candidate currently being timed) gets a CANDIDATE_STICKINESS vote
        bonus so a transient adjacent-octant neighbor can't displace a held pose
        (ADR 0013); the bonus is given only to a DETERMINATE incumbent (never
        None), so picking up a new pose from an indeterminate transition is never
        slowed.
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

    def __init__(self, *, smoothing_window, commit_hold_ms, inter_char_gap_ms, candidate_stickiness):
        self.n = smoothing_window
        self.hold = commit_hold_ms
        self.gap = inter_char_gap_ms
        self.stickiness = candidate_stickiness
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
        None (indeterminate) is a legitimate vote value.

        The current candidate (the incumbent being timed) gets a
        CANDIDATE_STICKINESS vote bonus, so a transient adjacent-octant neighbor
        cannot out-vote a held pose (ADR 0013). The bonus is applied per
        most-recent-tie-break by iterating the window in order and keeping the
        last value with the max *effective* count -- which, with stickiness 0,
        reduces exactly to the prior plain-plurality vote. Only a DETERMINATE
        incumbent gets the bonus (never None / _UNSET), so leaving an
        indeterminate transition for a new pose is never slowed."""
        counts = {}
        for v in self.window:
            counts[v] = counts.get(v, 0) + 1
        inc = self.candidate if self.candidate is not Committer._UNSET else None
        best_eff, winner = -1, None
        for v in self.window:  # window order -> ties resolve to the most-recent value
            eff = counts[v] + (self.stickiness if (v is not None and v == inc) else 0)
            if eff >= best_eff:
                best_eff, winner = eff, v
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
        # VOTED candidate, not the raw incoming symbol (ADR 0004 Decision 2). While
        # the candidate is REST, rest_since == candidate_since (both were set when
        # the candidate became REST), so the dwell measured here is the same dwell
        # the commit step checks against COMMIT_HOLD_MS -- which is what lets the
        # half-open top (< hold) hand off to the space commit at >= hold.
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
# a space -- its voted-candidate dwell lands in [INTER_CHAR_GAP_MS, COMMIT_HOLD_MS).
# This is the LEARN value (hold=600). REST_REARM is the ONE authored count that
# forks: COMMIT_HOLD_MS is the UPPER bound of that re-arm window. With
# CANDIDATE_STICKINESS=2 (ADR 0013) the held "L" incumbent carries a +2 vote bonus,
# so a brief REST must reach 4 in-window frames before it becomes the voted
# candidate (see the boundary block below); the voted-candidate dwell then steps
# 300/400ms for 4/5 brief-REST frames (the incumbent bias holds REST a few frames
# into the L-return, and the trailing held "L" truncates the exit flush, so the raw
# count -- not 600ms -- sets the peak). The Interpret hold (350) gives the window
# [200, 350): it admits the 4-frame dwell (peak 300ms -> re-arm -> "LL") but excludes
# the 5-frame dwell (peak 400ms >= 350 -> SPACE -> "L L"). Learn's window [200, 600)
# admits the 5-frame dwell too, so Learn doubles on 5 frames and Interpret on 4 --
# the one authored count that forks (ADR 0009). Stickiness shifted the internal
# frame->dwell arithmetic by one frame but left these fork values (5 / 4) intact.
REST_REARM_INTERPRET = 4
REST_SPACE = HELD  # a sustained REST (voted dwell >= COMMIT_HOLD_MS) commits a space

# Re-arm boundary, empirically pinned against the reference committer for the
# frozen constants (WINDOW=5, HOLD=600, GAP=200, DT=100, CANDIDATE_STICKINESS=2).
# The floor is set by the smoothing vote, not the gap, and CANDIDATE_STICKINESS
# (ADR 0013) raises it by one frame: the held "L" incumbent carries a +2 vote
# bonus, so a brief REST must reach 4 in-window frames to overcome it (effREST 4 >
# effL 1+2) before it can become the voted candidate at all -- one more than the
# plain-plurality floor of 3 --
#   * REST_GAP_TOO_SHORT (3): REST tops out at 3/5 (effREST 3 < effL 2+2=4), so it
#     never becomes the voted candidate and the gate never re-arms -> single "L".
#   * REST_GAP_MIN_REARM (4): REST reaches 4/5, overcomes the +2 incumbent bonus,
#     becomes the candidate, and its voted-candidate dwell (extended by the
#     incumbent bias holding REST a few frames into the L-return, ADR 0004
#     Decision 2 + ADR 0013) reaches INTER_CHAR_GAP_MS -> re-commit ("LL").
# The pair still pins the boundary to a single frame: the low side guards the
# stickiness-adjusted vote floor (a port that forgot the +2 bonus and let REST win
# on 3/5 fails it), the high side guards the dwell ">=" comparison (a port using
# ">" instead of ">=", or a wrong exit-flush assumption, fails it). Like ADR 0004
# Decision 2, the dwell keys off the VOTED candidate, not raw frames -- mirror that
# from this reference. Both bounds are unaffected by the Interpret hold (350):
# INTER_CHAR_GAP_MS and the (flat, non-forking) stickiness are shared, so the
# 3/4-frame boundary holds for both profiles (ADR 0009 / ADR 0013).
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


# --- sequence builder + self-validation (per profile) ------------------------


def build_profile(profile, dest_name, rest_rearm):
    """Render + run + self-validate every sequence for one timing profile, then
    write its fixture file. `rest_rearm` is the profile's brief-REST double count
    (the one authored count that forks; see REST_REARM). Returns the sequence
    list for the run summary."""
    timing = timing_for(profile)
    window = timing["smoothing_window"]
    hold = timing["commit_hold_ms"]
    gap = timing["inter_char_gap_ms"]
    sequence_vectors = []

    def run(name, steps, expected, note=None):
        """steps: list of (pose_token, count). Renders + runs the reference
        committer for THIS profile, asserts the committed concatenation ==
        expected, and records the fixture."""
        committer = Committer(**timing)
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
        assert got == expected, (profile, name, "got", repr(got), "want", repr(expected))
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
    run(
        "debounce_spurious_pose",
        [("A", HELD), ("B", SPURIOUS), ("A", HELD)],
        "A",
        "A commits once; a 2-frame B flicker never out-votes the held A (window of "
        f"{window}); holding A afterwards does not re-commit it.",
    )

    # 1a. candidate stickiness (ADR 0013, lever E from #101): a NEIGHBOUR blip long
    #     enough to win plain plurality (3 of a 5-window) still does NOT displace the
    #     held incumbent, because the incumbent carries a +CANDIDATE_STICKINESS vote
    #     bonus. This is the near-boundary-flicker fix: an arm sitting ~20deg off an
    #     octant edge flickers to an adjacent symbol for a few frames, and without
    #     stickiness that flicker seizes the candidate and resets the incumbent's hold
    #     timer. The blip starts after only 2 lead frames so it lands BEFORE the commit
    #     fires under EITHER hold (Learn 600 / Interpret 350) -- so the vector
    #     discriminates stickiness on both profiles, not just Learn (a 4-frame lead
    #     would let the faster Interpret hold commit "A" before the blip mattered).
    run(
        "incumbent_holds_through_neighbour_blip",
        [("A", 2), ("B", 3), ("A", 8)],
        "A",
        "A is held 2 frames (the candidate), then a 3-frame B neighbour blip wins "
        f"plain plurality (3 of the {window}-frame window) but is out-voted by A's "
        "+CANDIDATE_STICKINESS incumbent bonus, so A stays the candidate and commits "
        "on its original hold (ADR 0013). Without the bonus the blip seizes the "
        "candidate and resets A's hold timer: under the Learn hold (600ms) A can't "
        "re-reach its hold before the sequence ends, so a stickiness-less port commits "
        "'' (not 'A'); under the faster Interpret hold (350ms) A eventually re-commits "
        "but on a LATER frame, which the per-frame expected_emit assertion (ADR 0004 "
        "Decision 5) still catches. Either way a port that dropped CANDIDATE_STICKINESS "
        "fails this vector. The bonus is given only to a DETERMINATE incumbent, so it "
        "never slows leaving an indeterminate transition (see coalesce_off_octant_wobble).",
    )

    # 2. distinct letters stream on their holds alone — no gap needed between them.
    run(
        "distinct_letters_stream",
        [("A", HELD), ("B", HELD), ("C", HELD)],
        "ABC",
        "Each distinct held pose commits on its own hold; distinct symbols need no "
        "intervening gap.",
    )

    # 3. the SAME symbol repeats only across a BRIEF REST (ADR 0005, superseding ADR
    #    0004 Decision 3): a rest whose voted dwell lands in [GAP, HOLD) re-arms the
    #    lock without committing a space -- the conventional double-letter separator.
    #    `rest_rearm` is the per-profile count (REST_REARM=5 for Learn, =4 for
    #    Interpret): COMMIT_HOLD_MS is the window's upper bound, so the faster
    #    Interpret hold needs the shorter rest to stay below it (ADR 0009). The
    #    5-frame rest that DOES double under Learn instead spaces under Interpret --
    #    pinned by the interpret-only `interpret_rest5_spaces_not_doubles` below.
    run(
        "same_letter_doubles_via_brief_rest",
        [("L", HELD), ("REST", rest_rearm), ("L", HELD)],
        "LL",
        "First L commits; a brief REST (dwell in [INTER_CHAR_GAP_MS, COMMIT_HOLD_MS)) "
        "re-arms the lock WITHOUT committing a space; the second L then commits -> "
        "'LL'. Without the rest a held L commits exactly once (case 1).",
    )

    # 3a/3b. the brief-REST re-arm BOUNDARY, pinned on both sides so an off-by-one in a
    # port's dwell math fails one of the two. (For REST this does NOT also catch a
    # raw-frame vs voted-candidate re-arm -- they give identical output here; see the
    # REST_GAP_TOO_SHORT / REST_GAP_MIN_REARM notes above and ADR 0004 Decision 2.)
    run(
        "same_letter_rest_too_short",
        [("L", HELD), ("REST", REST_GAP_TOO_SHORT), ("L", HELD)],
        "L",
        f"A {REST_GAP_TOO_SHORT}-frame REST does NOT re-arm: it tops out at "
        f"{REST_GAP_TOO_SHORT}/{window} votes, which cannot overcome the held L "
        "incumbent's +CANDIDATE_STICKINESS bonus (ADR 0013), so REST never becomes the "
        "voted candidate and the same-symbol gate never re-arms -> single 'L'. Paired "
        "with same_letter_rest_min_rearms this pins the boundary to one frame (this "
        "side guards the stickiness-adjusted vote floor).",
    )
    run(
        "same_letter_rest_min_rearms",
        [("L", HELD), ("REST", REST_GAP_MIN_REARM), ("L", HELD)],
        "LL",
        f"One frame longer ({REST_GAP_MIN_REARM}) is the minimal REST that DOES re-arm: "
        "REST reaches 4/5, overcomes the held L's +CANDIDATE_STICKINESS bonus to become "
        "the candidate, and its voted dwell (extended by the incumbent bias holding REST "
        "a few frames into the L-return, ADR 0013) reaches INTER_CHAR_GAP_MS -> 'LL'. "
        "Paired with same_letter_rest_too_short this pins the boundary to one frame "
        "(this side guards the dwell '>=' comparison).",
    )

    # 3c. coalesce: an indeterminate off-octant wobble *within* a held letter no longer
    #     re-arms (the FR4-robustness half of #40), so a steady letter that jitters off
    #     an octant and back reads as ONE character -- it would have DOUBLED pre-#40.
    run(
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
    run(
        "sustained_rest_spaces_between_letters",
        [("L", HELD), ("REST", REST_SPACE), ("L", HELD)],
        "L L",
        "A sustained REST commits one space (it crosses COMMIT_HOLD_MS); the second L "
        "then commits as a distinct symbol after that space -> 'L L'. Distinguishes a "
        "brief rest (double, case 3) from a sustained rest (space + letter).",
    )

    # 4. both numeric-mode transitions go THROUGH the committer (debounced mode switch).
    run(
        "mode_transitions_through_committer",
        [("NUMERALS", HELD), ("A", HELD), ("B", HELD), ("J", HELD), ("A", HELD)],
        "12A",
        "Held NUMERALS commits -> NUMERIC (emit ''); A/B -> '1'/'2'; held J pose is "
        "the letters-shift -> LETTERS (emit ''); A -> 'A'. Mode flips only on a "
        "committed control pose, so a fleeting NUMERALS/J frame can't flip it (#23).",
    )

    # 5. true signer-loss hard-resets mode to LETTERS and clears window + lock.
    run(
        "signer_loss_resets_mode",
        [("NUMERALS", HELD), ("A", HELD), (RESET, 1), ("A", HELD)],
        "1A",
        "In NUMERIC the A pose commits '1'; a signer-loss reset() clears state and "
        "sets mode -> LETTERS, so the next A commits the letter 'A' (and re-commits "
        "despite the prior A, proving the same-symbol lock was cleared).",
    )

    # 6. REST is an ordinary committable symbol: a held REST commits one space, debounced.
    run(
        "rest_commits_one_space",
        [("REST", HELD)],
        " ",
        "Both arms down = REST -> a single committed space; holding it does not "
        "stream spaces (debounce applies to REST like any symbol).",
    )

    # 7. (interpret only) the discriminating boundary: a REST_REARM-LEARN (5)-frame
    #    brief rest -- the dwell that re-arms (doubles) under the Learn 600ms hold --
    #    instead CROSSES this profile's 350ms hold and commits a SPACE, so the same
    #    input that gives 'LL' under Learn gives 'L L' here. This proves the fork is a
    #    real contract difference (not just self-consistent): a port that ignored the
    #    Interpret COMMIT_HOLD_MS (kept 600) would emit 'LL' and fail this assertion.
    if profile == "interpret":
        run(
            "interpret_rest5_spaces_not_doubles",
            [("L", HELD), ("REST", REST_REARM), ("L", HELD)],
            "L L",
            f"A {REST_REARM}-frame brief REST -- the count that DOUBLES under the Learn "
            f"profile (hold 600) -- has a voted-candidate dwell that reaches this "
            f"profile's hold ({hold}ms), so REST commits a SPACE instead of re-arming: "
            "'L L', not 'LL'. The discriminating boundary that pins the per-fork "
            "COMMIT_HOLD_MS (ADR 0009); a port using the Learn hold would emit 'LL'.",
        )

    # --- assemble + write -----------------------------------------------------

    out = {"$schema_version": "1.0"}
    if profile != "learn":
        out["_profile"] = profile
    out["_README"] = (
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
    )
    out["_generated_by"] = (
        "shared/tools/gen_temporal_vectors.py (uv run). Generated, not hand-"
        "edited: regenerate after any change to semaphore_alphabet.json or "
        "semaphore_config.json. The generator runs the reference committer over "
        "every sequence and asserts expected_committed before writing, so running "
        "it is the fixture's correctness check."
    )
    out["_commit_contract"] = (
        "Per ADR 0004: vote the votable pose symbol (classify output; null = "
        "indeterminate is a vote value) over a ring buffer of the last "
        f"SMOOTHING_WINDOW ({window}) frames -> plurality candidate, "
        "ties to the most-recent occurrence. A determinate candidate held "
        f"continuously >= COMMIT_HOLD_MS ({hold}) commits: run "
        "interpret(symbol, mode) (spec §4.5) for (emit, new mode), so mode flips "
        "only on a committed control pose. A distinct symbol commits on its hold "
        "alone; the SAME symbol re-commits only after an intervening brief REST "
        f"whose voted dwell lands in [INTER_CHAR_GAP_MS ({gap}), "
        f"COMMIT_HOLD_MS ({hold})) -- the conventional double-letter "
        "separator (ADR 0005, superseding ADR 0004 Decision 3); an indeterminate "
        "gap no longer re-arms. A REST held >= COMMIT_HOLD_MS commits a space "
        "instead. reset() (true signer-loss) clears the window and sets mode -> "
        "LETTERS."
    )
    out["_format"] = {
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
    }
    out["sequence_vectors"] = sequence_vectors

    dest = SHARED / dest_name
    dest.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n")
    return sequence_vectors


# Profile -> (fixture file, brief-REST double count). The Learn profile reproduces
# the frozen fixtures byte-for-byte (its timing + authoring are untouched); the
# Interpret profile (ADR 0009) is a second file with a faster hold and its own
# REST_REARM (the one authored count that forks -- see REST_REARM above).
PROFILES = [
    ("learn", "temporal_vectors.json", REST_REARM),
    ("interpret", "temporal_vectors_interpret.json", REST_REARM_INTERPRET),
]

def main():
    for profile, dest_name, rest_rearm in PROFILES:
        seqs = build_profile(profile, dest_name, rest_rearm)
        n_frames = sum(len(s["frames"]) for s in seqs)
        print(f"wrote shared/{dest_name}  (profile: {profile})")
        print(f"  sequence_vectors: {len(seqs)} ({n_frames} frames total)")
        for s in seqs:
            print(f"    {s['name']}: {len(s['frames'])} frames -> {s['expected_committed']!r}")
        print("  all sequences re-run through the reference committer and asserted OK")


# Guarded so the canonical Committer / authoring constants can be imported without
# regenerating (and overwriting) the fixture files -- the #76 tuning harness
# (tune_interpret_timing.py) imports Committer from here. Running the module still
# regenerates byte-identically (verified by `git diff --exit-code`, ADR 0009).
if __name__ == "__main__":
    main()

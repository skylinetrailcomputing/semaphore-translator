# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Generate shared/drill_vectors.json — parity fixtures for the Learn drill engine.

The drill engine (Epic 6a, #69) sits strictly DOWNSTREAM of the temporal committer
(ADR 0004 / 0005): it observes only the characters the committer emits and decides
hit / no-hit against a target sequence. Because it never touches keypoints, timing,
or any decode state, these fixtures are plain committed-character streams — there
is deliberately no decode/geometry import here (unlike gen_test_vectors.py /
gen_temporal_vectors.py, which lean on _semaphore_ref). That decoupling is the
whole point: the decode/adapter/commit core stays untouched (parity risk ~0).

Each sequence is authored as a target passage + the committed emits the signer
produces; this script runs the reference DrillSession below, records the per-step
(matched, index, complete), and ASSERTS the intended final state before writing.
Running this script IS the fixture's correctness check (same discipline as the
other generators) — re-run after any change to the drill contract.

The DrillSession here is THE reference the Swift `DrillSession` and Kotlin
`DrillSession` ports mirror; see shared/drill_contract.json +
docs/adr/0007-drill-engine-contract.md.

    uv run shared/tools/gen_drill_vectors.py
"""

import json
from pathlib import Path

SHARED = Path(__file__).resolve().parent.parent


# --- reference drill engine (#69; the Swift/Kotlin ports mirror this) ---------


class DrillSession:
    """Pure, downstream-of-committer drill engine. `observe(emit)` is a function of
    the committer's emitted character and the current target only — no clock, no
    keypoints, no decode state.

    Contract (shared/drill_contract.json):
      * Targets: the passage split into single-character targets, in signing order
        (already sanitised to the frozen ASCII alphabet by the source, 6a-3).
      * Match: ASCII-uppercase both sides; hit iff normalized(emit) == normalized
        current target.
      * Stay-until-success (iv-a, the only 6a-1 lifecycle): a hit advances the
        index; a miss is a no-op (never penalise, never skip). After the index
        reaches the target count the session is COMPLETE and further emits no-op.
    """

    def __init__(self, targets):
        self.targets = list(targets)  # split passage string into 1-char targets
        self.index = 0

    @property
    def is_complete(self):
        return self.index >= len(self.targets)

    @property
    def current_target(self):
        return None if self.is_complete else self.targets[self.index]

    def observe(self, emit):
        """Feed one NON-EMPTY committed emit. Returns (matched, index, complete)."""
        target = self.current_target
        if target is None:  # after COMPLETE, emits are no-ops
            return (False, self.index, True)
        matched = emit.upper() == target.upper()
        if matched:
            self.index += 1
        return (matched, self.index, self.is_complete)

    def reset(self):
        self.index = 0


# --- sequence builder + self-validation --------------------------------------

RESET = object()  # an op sentinel: the harness calls session.reset(), not observe()

sequence_vectors = []


def run_drill(name, targets, ops, *, expect_index, expect_complete, note=None):
    """Run the reference DrillSession over `ops` (each a committed emit string, or
    the RESET sentinel = a caller-driven reset), record per-step decisions, and
    assert the final (index, complete) match the authored intent before writing.
    The authored final-state assert catches a mis-authored op stream; the PER-STEP
    fields are the cross-platform pins the parity harness enforces on each port."""
    session = DrillSession(targets)
    steps = []
    for op in ops:
        if op is RESET:
            session.reset()
            steps.append(
                {
                    "reset": True,
                    "expected_index": session.index,
                    "expected_complete": session.is_complete,
                }
            )
        else:
            matched, index, complete = session.observe(op)
            steps.append(
                {
                    "emit": op,
                    "expected_matched": matched,
                    "expected_index": index,
                    "expected_complete": complete,
                }
            )
    assert session.index == expect_index, (name, "index", session.index, "want", expect_index)
    assert session.is_complete == expect_complete, (
        name,
        "complete",
        session.is_complete,
        "want",
        expect_complete,
    )
    entry = {
        "name": name,
        "targets": targets,
        "steps": steps,
        "expected_final_index": session.index,
        "expected_final_complete": session.is_complete,
    }
    if note:
        entry["_note"] = note
    sequence_vectors.append(entry)


# 1. a clean run: every emit matches the next target in order -> COMPLETE.
run_drill(
    "clean_run_hello",
    "HELLO",
    ["H", "E", "L", "L", "O"],
    expect_index=5,
    expect_complete=True,
    note="Every committed letter matches the next target; the session advances "
    "five times and completes. The baseline happy path.",
)

# 2. stay-until-success: a wrong letter is a no-op (no advance, no skip); the
#    signer simply tries again. Proves the 6a-1 lifecycle (iv-a).
run_drill(
    "wrong_letter_stays_until_success",
    "AB",
    ["C", "A", "Z", "B"],
    expect_index=2,
    expect_complete=True,
    note="C misses target A (index stays 0); A hits (->1); Z misses target B "
    "(stays 1); B hits (->2, complete). Wrong letters never advance and are "
    "never penalised -- the defining stay-until-success behaviour.",
)

# 3. case-insensitive match: a lowercase passage is matched by the committer's
#    uppercase emits via ASCII-uppercase normalization on both sides.
run_drill(
    "case_insensitive_match",
    "hi",
    ["H", "I"],
    expect_index=2,
    expect_complete=True,
    note="Lowercase targets, uppercase committer emits: both sides are ASCII-"
    "uppercased before comparison, so 'h'==('H') and 'i'==('I'). Pins the "
    "normalization rule identically across Python / Swift / Kotlin.",
)

# 4. SPACE is an ordinary matchable target (a held/sustained REST commits a space,
#    which the drill matches like any other character).
run_drill(
    "space_is_a_target",
    "A B",
    ["A", " ", "B"],
    expect_index=3,
    expect_complete=True,
    note="Targets split to ['A', ' ', 'B']; the committed space ' ' matches the "
    "space target. SPACE is a first-class character in a drill passage, not a "
    "separator the engine skips.",
)

# 5. a digit target: the mode-switch commits (NUMERALS / J letters-shift) return
#    '' and are never observed, so the drill sees only the emitted digit.
run_drill(
    "digit_target",
    "7",
    ["7"],
    expect_index=1,
    expect_complete=True,
    note="To sign a digit the signer enters numeric mode, but those control-pose "
    "commits emit '' and are invisible to the drill. The drill sees only the "
    "committed '7', which matches the digit target.",
)

# 6. a doubled target letter: each committed L advances one target. Upstream, the
#    brief-REST separator (ADR 0005) is what produced two distinct L commits; the
#    drill just sees two emits and advances twice.
run_drill(
    "double_letter_target",
    "LL",
    ["L", "L"],
    expect_index=2,
    expect_complete=True,
    note="Two committed L's advance through two L targets. The committer's double-"
    "letter separator (brief REST, ADR 0005) is what yields two L commits; the "
    "drill is agnostic to how they were produced.",
)

# 7. emits after COMPLETE are no-ops (no match, no advance past the end).
run_drill(
    "emits_after_complete_are_noops",
    "A",
    ["A", "B"],
    expect_index=1,
    expect_complete=True,
    note="A hits and completes the one-target session (->1). The trailing B is a "
    "no-op: matched False, index stays 1, complete stays True. The engine never "
    "advances past its target count.",
)

# 8. you cannot complete a drill without producing the right character.
run_drill(
    "wrong_letter_never_completes",
    "A",
    ["B", "C"],
    expect_index=0,
    expect_complete=False,
    note="Neither B nor C matches target A, so the index never advances and the "
    "session never completes. Stay-until-success means progress requires the "
    "correct sign.",
)

# 9. a realistic mixed passage: letters + space + digit through one session.
run_drill(
    "mixed_letters_space_digit",
    "GO 2",
    ["G", "O", " ", "2"],
    expect_index=4,
    expect_complete=True,
    note="Targets ['G', 'O', ' ', '2']; the committed letters, space, and digit "
    "each advance one target in order -> complete. Exercises all three frozen-"
    "alphabet character kinds in a single drill.",
)

# 10. reset() replays the session from the first target (the 6a-2 replay path). A
#     port that forgets to zero the index, or resets to the wrong value, fails here
#     -- reset is otherwise invisible to the per-emit vectors.
run_drill(
    "reset_replays_from_start",
    "ABC",
    ["A", "B", RESET, "A", "B", "C"],
    expect_index=3,
    expect_complete=True,
    note="A, B advance to index 2; reset() returns the index to 0 (not complete); "
    "then A, B, C run cleanly to completion. Pins caller-driven reset across "
    "platforms (the committer vectors likewise exercise reset).",
)

# 11. a zero-target session is immediately COMPLETE (index 0). The source (6a-3)
#     should not produce an empty target sequence; this pins the degenerate input
#     so all three platforms agree rather than diverging on an empty passage.
run_drill(
    "empty_targets_autocompletes",
    "",
    [],
    expect_index=0,
    expect_complete=True,
    note="DrillSession('') has zero targets, so 0 >= 0 makes it COMPLETE from the "
    "start with no steps. A degenerate input the source (6a-3) must avoid; pinned "
    "only so the platforms agree on it.",
)


# --- assemble + write ---------------------------------------------------------

out = {
    "$schema_version": "1.0",
    "_README": (
        "Drill-engine parity vectors: a target passage + the committed-character "
        "stream a signer produces -> the per-step (matched, index, complete) the "
        "drill engine decides. The drill engine (Epic 6a, #69) is strictly "
        "DOWNSTREAM of the committer, so -- unlike test_vectors.json / "
        "temporal_vectors.json -- these carry NO keypoints and NO timing, only "
        "emitted characters. Both platform ports run a DrillSession over each "
        "sequence and MUST produce identical per-step results. Assert the PER-STEP "
        "fields (they pin WHERE each advance lands, catching an off-by-one or a "
        "miss-advances-anyway port whose final index still matches); "
        "expected_final_* is the convenience rollup."
    ),
    "_generated_by": (
        "shared/tools/gen_drill_vectors.py (uv run). Generated, not hand-edited: "
        "the generator runs the reference DrillSession over every sequence and "
        "asserts the AUTHORED final (index, complete) before writing -- catching a "
        "mis-authored op stream. The per-step fields are recorded from the "
        "reference and are the cross-platform pins the parity harness enforces on "
        "each port (a port that diverges on any step fails even if its final state "
        "matches). Re-run after any change to the drill contract "
        "(shared/drill_contract.json)."
    ),
    "_drill_contract": (
        "Per shared/drill_contract.json + ADR 0007: the engine consumes an "
        "already-validated target sequence (the source, 6a-3, sanitises a passage "
        "to the frozen ASCII alphabet A-Z / 0-9 / SPACE). It observes only NON-"
        "EMPTY committer emits. Match: ASCII-uppercase both the emit and the "
        "current target; hit iff equal. Under stay-until-success (iv-a, the only "
        "6a-1 lifecycle) a hit advances the target index by one and a miss is a "
        "no-op; reaching the target count is COMPLETE (celebrate), after which "
        "further emits no-op. Later modes (timed iv-b, delayed-assist iv-d; 6a-6) "
        "add non-emit timeout transitions over this same target/index core and do "
        "not change the match rule."
    ),
    "_format": {
        "sequence_vectors": (
            "Each is {name, targets, steps:[...], expected_final_index, "
            "expected_final_complete}. Steps are observed in order; "
            "expected_final_* is the session state after the last step."
        ),
        "targets": (
            "The drill's target sequence as a plain string; the engine splits it "
            "into single-character targets in order (frozen ASCII alphabet: A-Z / "
            "0-9 / SPACE). Already validated -- the source (6a-3), not the engine, "
            "sanitises a passage into this."
        ),
        "steps": (
            "Each step is EITHER an emit step {emit, expected_matched, "
            "expected_index, expected_complete} -- feed `emit` (one non-empty "
            "committed character) to session.observe and assert (matched, index, "
            "complete) -- OR a reset step {reset:true, expected_index, "
            "expected_complete}, where the harness calls session.reset() instead "
            "of observe() and asserts the index/complete afterwards (mirrors the "
            "reset frames in temporal_vectors.json)."
        ),
        "reset": "Present and true only on a reset step; the harness calls session.reset().",
        "emit": (
            "One non-empty character as returned by the committer (absent on reset "
            "steps). Empty committer returns (mode switches, no-commit frames) are "
            "never observed by the drill, so they do not appear here."
        ),
        "expected_matched": "True iff normalized(emit) == normalized(current target); absent on reset steps.",
        "expected_index": (
            "The current target index AFTER this step (advances by one on a hit; "
            "unchanged on a miss)."
        ),
        "expected_complete": "True once the index has reached the target count (the celebrate signal).",
        "expected_final_index": "The session's index after the last step (rollup of the per-step expected_index).",
        "expected_final_complete": "Whether the session is complete after the last step (rollup).",
        "name": "Human-readable label for debugging. Ignored by the parity harness.",
        "_note": "Optional human explanation. Ignored by the parity harness.",
    },
    "sequence_vectors": sequence_vectors,
}

dest = SHARED / "drill_vectors.json"
dest.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n")

n_steps = sum(len(s["steps"]) for s in sequence_vectors)
print(f"wrote {dest.relative_to(SHARED.parent)}")
print(f"  sequence_vectors: {len(sequence_vectors)} ({n_steps} steps total)")
for s in sequence_vectors:
    final = "COMPLETE" if s["expected_final_complete"] else f"index {s['expected_final_index']}"
    print(f"    {s['name']}: {len(s['steps'])} steps -> {final}")
print("  all sequences re-run through the reference DrillSession and asserted OK")

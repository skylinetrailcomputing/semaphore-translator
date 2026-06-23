# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Generate shared/committed_text_vectors.json -- parity fixtures for the readout buffer.

The committer (ADR 0004/0005) emits one token per frame: a letter, a digit, a single
SPACE (a sustained REST), or "" (most frames / a control pose). The live layer
accumulates those tokens into the user-visible readout buffer (`committedText`). The
one behaviour that must be byte-for-byte identical across iOS / Android / this Python
reference is HOW a SPACE token joins that buffer:

  * never a LEADING space -- the buffer must begin with content (a letter/digit), and
  * never two spaces in a row -- consecutive SPACE tokens COALESCE to one.

This is the live/incremental analogue of the passage SOURCE sanitiser (ADR 0008,
`gen_sanitize_vectors.py`): sanitize() trims+collapses whitespace over a whole typed
string in one batch; CommittedText.append() enforces the same "no leading space, no
double space" shape one committed token at a time as the read unfolds. (It does NOT
trim a *trailing* space: a single trailing space is the in-progress word separator the
signer just rested to make, and showing it is correct -- the next letter turns "A "
into "A B". So this is collapse-as-you-go, not full sanitisation.)

Two upstream facts make the rule necessary -- a clean half-open REST design (ADR 0005)
still lets two SPACE tokens through:
  1. A held REST before any letter commits a space (`candidate != lastCommitted` with
     `lastCommitted == nil`) -> a LEADING space.
  2. After a space commits, a brief INDETERMINATE gap clears the REST run; a following
     REST run re-arms the same-symbol gate (`gapOk`) and commits a SECOND space, even
     though `candidate == lastCommitted == REST` -> a DOUBLE space.
The committer can't suppress these cleanly: "is this a leading/redundant space?" is a
property of the *buffer*, which OUTLIVES the committer's no-signer reset(), not of the
committer's per-session state. So the rule lives at the buffer, and the buffer is the
source of truth this reference models. See docs/adr/0010-committed-text-space-coalescing.md.

The reference `append_committed()` below is THE reference the Swift `CommittedText.append`
and Kotlin `CommittedText.append` ports mirror. The vectors fold a sequence of emitted
tokens left-to-right from an empty buffer and pin the final buffer string.

Running this script IS the fixture's correctness check (same discipline as
gen_sanitize_vectors.py): each authored (emits -> expected) is re-folded through the
reference and asserted before writing. There is no decode/geometry import -- this layer
speaks only the committer's output alphabet (A-Z / 0-9 / SPACE / "").

    uv run shared/tools/gen_committed_text_vectors.py
"""

import json
from pathlib import Path

SHARED = Path(__file__).resolve().parent.parent


# --- reference appender (the Swift/Kotlin ports mirror this) -------------------


def append_committed(buffer, emitted):
    """Append one committer token to the readout buffer, coalescing spaces.

    `emitted` is a single committed token (a letter, a digit, a single SPACE, or ""
    -- the live layer only calls this for a non-empty token, but "" is a harmless
    no-op here). The space rule:
      * a SPACE is dropped when the buffer is empty (no leading space) or already
        ends in a space (no double space);
      * any non-space token (and "") appends unchanged.
    """
    if emitted == " ":
        if buffer and not buffer.endswith(" "):
            return buffer + emitted
        return buffer
    return buffer + emitted


# --- authored cases + self-validation -----------------------------------------

cases = []


def case(name, emits, expected, note=None):
    """Fold `emits` through the reference appender from an empty buffer, assert the
    result equals the authored `expected`, and record the vector. The assert catches a
    mis-authored expected; the recorded (emits, expected) is the cross-platform pin the
    parity harness enforces on each port."""
    buffer = ""
    for token in emits:
        buffer = append_committed(buffer, token)
    assert buffer == expected, (name, "got", repr(buffer), "want", repr(expected))
    entry = {"name": name, "emits": emits, "expected": expected}
    if note:
        entry["_note"] = note
    cases.append(entry)


# Nothing / content-only happy paths.
case("empty_sequence", [], "", note="No tokens -> empty buffer.")
case("single_letter", ["A"], "A")
case("word_no_spaces", ["H", "E", "L", "L", "O"], "HELLO",
     note="Letters append verbatim; coalescing only ever touches spaces.")
case("digits_are_content", ["1", "2", "3"], "123",
     note="A digit is content like a letter -- it can anchor the buffer and reset the space gate.")

# Leading-space suppression (a held REST before any content).
case("single_leading_space_dropped", [" "], "",
     note="A held REST before any letter commits a space; the buffer must not start with it.")
case("many_leading_spaces_dropped", [" ", " ", " "], "",
     note="Every leading space is dropped while the buffer is still empty.")
case("leading_space_then_letter", [" ", "A"], "A",
     note="Leading space dropped; the letter anchors the buffer.")
case("leading_spaces_then_word", [" ", " ", "H", "I"], "HI")
case("leading_space_then_digit", [" ", "1"], "1")

# Consecutive-space coalescing (the brief-indeterminate-gap double-space bug).
case("letter_then_single_space_kept", ["A", " "], "A ",
     note="A trailing word-separator space IS kept (in-progress); only LEADING/DOUBLE spaces go.")
case("double_space_coalesces", ["A", " ", " "], "A ",
     note="The second space (REST -> space -> indeterminate gap -> REST -> space) coalesces away.")
case("triple_space_coalesces", ["A", " ", " ", " "], "A ")
case("word_gap_single_space", ["H", "I", " ", "Y", "O"], "HI YO")
case("word_gap_double_space_coalesces", ["H", "I", " ", " ", "Y", "O"], "HI YO",
     note="The headline bug: a glitchy rest between words must still yield ONE separator.")

# Combined + state-reset cases.
case("leading_and_double_space", [" ", "H", "I", " ", " ", "Y", "O"], "HI YO",
     note="Leading space dropped AND the inter-word double space coalesced, in one fold.")
case("space_resets_after_letter", ["A", " ", "B", " ", "C"], "A B C",
     note="A non-space token clears the 'ends in space' state, so the next space is a fresh separator.")
case("space_after_coalesce_then_letter", ["A", " ", " ", "B"], "A B",
     note="Coalesce the double space, then the letter joins after the single surviving space.")
case("only_spaces", [" ", " ", " "], "", note="All REST, no content -> empty (never a lone space).")

# Robustness: an empty token (control-pose frame) is a no-op even though the live
# layer guards against calling with one. Locks the helper as a standalone pure fn.
case("empty_token_is_noop", ["A", "", " ", "", "B"], "A B",
     note="\"\" appends nothing and does not disturb the space gate.")


# --- assemble + write ---------------------------------------------------------

out = {
    "$schema_version": "1.0",
    "_README": (
        "Committed-text (readout buffer) space-coalescing parity vectors: a sequence of "
        "committer tokens (`emits`) folded left-to-right from an empty buffer through "
        "CommittedText.append -> the final buffer (`expected`). Both platform ports run "
        "their CommittedText.append and MUST reproduce `expected` exactly. The rule: "
        "never a leading space, never two spaces in a row (a single trailing space is "
        "kept). The human rationale is docs/adr/0010-committed-text-space-coalescing.md; "
        "the upstream emitter is shared/temporal_vectors.json (the committer)."
    ),
    "_generated_by": (
        "shared/tools/gen_committed_text_vectors.py (uv run). Generated, not hand-edited: "
        "the reference append_committed() is re-folded over every authored (emits -> "
        "expected) and asserted before writing. Re-run after any change to the buffer "
        "space rule."
    ),
    "_rule": (
        "Per ADR 0010, the live/incremental analogue of the passage-source sanitiser "
        "(ADR 0008): append a SPACE token only when the buffer is non-empty and does not "
        "already end in a space; append any non-space token (and \"\") unchanged. A "
        "trailing space is NOT trimmed (it is the in-progress word separator)."
    ),
    "_format": {
        "cases": "Each is {name, emits, expected}. Fold `emits` through CommittedText.append from \"\" and assert == `expected`.",
        "emits": "Ordered committer tokens to append (each a letter, digit, single SPACE, or \"\").",
        "expected": "The resulting buffer string after folding all of `emits`.",
        "name": "Human-readable label for debugging. Ignored by the parity harness.",
        "_note": "Optional human explanation. Ignored by the parity harness.",
    },
    "cases": cases,
}

dest = SHARED / "committed_text_vectors.json"
dest.write_text(json.dumps(out, indent=2, ensure_ascii=True) + "\n")

print(f"wrote {dest.relative_to(SHARED.parent)}")
print(f"  cases: {len(cases)}")
print("  all cases re-folded through the reference append_committed() and asserted OK")

# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Generate shared/sanitize_vectors.json -- parity fixtures for the Learn passage SOURCE.

The source (Epic 6a, #71 / 6a-3) sits UPSTREAM of the drill engine (ADR 0007): it
turns arbitrary user/stock text into the already-validated target string the engine
consumes. The one behaviour that must be byte-for-byte identical across iOS /
Android / this Python reference is SANITISATION; these vectors are the executable
enforcement (raw input -> sanitised output). The frozen rule lives in
shared/source_contract.json; the human rationale in
docs/adr/0008-learn-source-sanitization.md.

The reference `sanitize()` below is THE reference the Swift `PassageSource.sanitize`
and Kotlin `PassageSource.sanitize` ports mirror. Two parity hazards drive its
shape (both caught by the vectors here):

  1. Per-scalar ASCII uppercase, NOT str.upper(). ASCII 'i'.upper() is 'I' on any
     platform, but the eszett scalar (U+00DF) whole-string-uppercases to 'SS' and
     Turkish locale folds dotless-i; using the locale-aware whole-string upper would
     KEEP characters the rule drops and diverge. So upper is an explicit ord range
     check.

  2. Iterate by scalar / code point, not by grapheme cluster. Python iterates code
     points; Kotlin iterates UTF-16 Chars; Swift MUST iterate String.unicodeScalars
     (NOT Character). A decomposed 'e' + U+0301 is ONE grapheme but TWO scalars: by
     scalar all three keep the 'e' and drop the combining mark ('CAFE'); by grapheme
     Swift would drop the whole cluster ('CAF'), diverging. The decomposed-e-acute
     vector locks this.

Non-ASCII test inputs are built with chr() so the exact scalars are unambiguous and
this source file stays pure ASCII (no literal that an editor could normalize).

Running this script IS the fixture's correctness check (same discipline as
gen_drill_vectors.py): each authored (input -> expected) is re-run through the
reference and asserted before writing, and shared/stock_passages.json is asserted
already-clean. There is no decode/geometry import -- the source speaks only the
committer's output alphabet.

    uv run shared/tools/gen_sanitize_vectors.py
"""

import json
from pathlib import Path

SHARED = Path(__file__).resolve().parent.parent

SEPARATORS = frozenset({0x20, 0x09, 0x0A, 0x0D})  # SPACE, TAB, LF, CR


# --- reference sanitiser (#71; the Swift/Kotlin ports mirror this) ------------


def sanitize(s):
    """Map arbitrary text to the frozen ASCII target alphabet (A-Z / 0-9 / SPACE),
    in signing order, whitespace collapsed + trimmed.

    Contract (shared/source_contract.json):
      * Iterate by code point (Python) / scalar (Swift unicodeScalars) / UTF-16 Char
        (Kotlin) -- NOT grapheme clusters.
      * ASCII-only upper: a-z -> A-Z by ord; everything else unchanged. Never
        str.upper().
      * Keep A-Z / 0-9. Treat {SPACE,TAB,LF,CR} as a separator -> collapse runs to a
        single SPACE, trim leading/trailing (a pending separator is flushed as one
        SPACE only immediately before the next kept char, and only after a kept char
        has already been emitted). Drop everything else (no transliteration).
    """
    out = []
    has_kept = False
    pending_space = False
    for ch in s:
        v = ord(ch)
        c = v - 0x20 if 0x61 <= v <= 0x7A else v  # ASCII-only a-z -> A-Z
        if c in SEPARATORS:
            if has_kept:
                pending_space = True
        elif 0x41 <= c <= 0x5A or 0x30 <= c <= 0x39:  # A-Z / 0-9
            if pending_space:
                out.append(" ")
            out.append(chr(c))
            has_kept = True
            pending_space = False
        # else: drop silently
    return "".join(out)


# --- authored cases + self-validation -----------------------------------------

cases = []


def case(name, raw, expected, note=None):
    """Run the reference sanitiser over `raw`, assert it equals the authored
    `expected`, and record the vector. The assert catches a mis-authored expected;
    the recorded (input, expected) is the cross-platform pin the parity harness
    enforces on each port."""
    got = sanitize(raw)
    assert got == expected, (name, "got", repr(got), "want", repr(expected))
    entry = {"name": name, "input": raw, "expected": expected}
    if note:
        entry["_note"] = note
    cases.append(entry)


# Happy path + casing.
case("empty", "", "", note="Empty in, empty out.")
case("already_clean_idempotent", "HELLO", "HELLO", note="Clean input is unchanged.")
case("ascii_lowercase", "hello", "HELLO", note="a-z -> A-Z by ASCII ord, not locale.")
case("mixed_case", "MiXeD cAsE 99", "MIXED CASE 99")
case("digits_only", "123", "123", note="Digits are first-class targets.")
case("letters_space_digit", "GO 2", "GO 2", note="Mirrors a drill_vectors passage.")
case("letters_meet_number", "Room 101", "ROOM 101")

# Whitespace: collapse + trim. These lock the pending-space state machine
# (GPT-5.5 roundtable HIGH: 'flush only after a kept char has been emitted').
case("leading_spaces_trimmed", "  A", "A", note="Leading separators never flush.")
case("leading_tab_trimmed", "\tA", "A")
case("trailing_spaces_trimmed", "A  ", "A", note="Trailing separators never flush.")
case("internal_run_collapses", "A   B", "A B")
case("tab_newline_collapse", "A\t\nB", "A B", note="TAB+LF run -> one SPACE.")
case("leading_trailing_internal", " A B ", "A B")
case("multiword_messy", "  ABC  DEF  ", "ABC DEF")
case("only_separators_empty", "\n\t  ", "", note="A run of only separators -> empty (no leading flush).")
case("crlf_collapse", "A\r\nB", "A B", note="Windows CRLF (CR+LF) run collapses to one SPACE (clipboard paste).")
case("lone_cr_separator", "A\rB", "A B", note="A bare CR is a separator like LF.")

# Drop non-ASCII / punctuation (NO transliteration, NO punctuation-as-separator).
case("punct_concatenates", "READY-SET-GO", "READYSETGO",
     note="Dropped punctuation concatenates words by design; the live preview is the mitigation.")
case("slash_dropped", "SOS/HELP", "SOSHELP")
case("apostrophe_dropped", "you're", "YOURE", note="Apostrophe dropped (not signable).")
case("punct_only_empty", ".....", "", note="All-punctuation -> empty-after-sanitize.")
case("nbsp_dropped", "A" + chr(0xA0) + "B", "AB",
     note="NBSP (U+00A0) is NOT a separator -- dropped like any non-kept scalar, so words join.")

# Unicode / locale hazards -- the parity teeth. Non-ASCII inputs are built with
# chr() so the exact scalars are unambiguous.
case("eszett_dropped_not_SS", chr(0xDF), "",
     note="U+00DF (eszett) is DROPPED, not expanded to 'SS'. Catches a port using locale-aware whole-string upper.")
case("eszett_in_word", chr(0xDF) + "abc", "ABC")
case("precomposed_e_acute_dropped", "caf" + chr(0xE9), "CAF",
     note="Precomposed e-acute (U+00E9) is one scalar, dropped.")
case("decomposed_e_acute_keeps_base", "cafe" + chr(0x301), "CAFE",
     note="Decomposed 'e' + U+0301 is ONE grapheme, TWO scalars: keep the base 'e', drop the mark. "
          "Catches a Swift port iterating Character instead of unicodeScalars.")
case("turkish_dotted_capital_I_dropped", chr(0x130), "",
     note="U+0130 (Turkish dotted capital I) is dropped; it is NOT folded to ASCII 'I'.")
case("ascii_i_is_locale_independent", "i", "I",
     note="ASCII 'i' -> 'I' on every platform regardless of locale.")
case("emoji_dropped", chr(0x1F600) + "A", "A",
     note="A non-BMP emoji (surrogate pair in UTF-16) is dropped whole; the trailing 'A' survives.")
case("emoji_between_letters", "A" + chr(0x1F600) + "B", "AB")


# --- assert shared/stock_passages.json is authored already-clean --------------

stock = json.loads((SHARED / "stock_passages.json").read_text())
source_contract = json.loads((SHARED / "source_contract.json").read_text())
max_targets = source_contract["max_targets"]
supported = set(source_contract["supported_chars"])

# Cross-check the contract's supported_chars against this reference's output alphabet.
ref_supported = {" "} | {chr(c) for c in range(0x30, 0x3A)} | {chr(c) for c in range(0x41, 0x5B)}
assert supported == ref_supported, ("supported_chars drift", sorted(supported ^ ref_supported))

# Every authored expected output uses only supported characters.
for entry in cases:
    bad = set(entry["expected"]) - supported
    assert not bad, ("expected uses unsupported chars", entry["name"], bad)

seen_ids = set()
for p in stock["passages"]:
    pid, hint, text = p["id"], p["hint"], p["text"]
    assert pid and pid not in seen_ids, ("stock id missing/duplicate", pid)
    seen_ids.add(pid)
    assert hint, ("stock hint empty", pid)
    assert text, ("stock text empty", pid)
    assert sanitize(text) == text, ("stock text not already-clean", pid, repr(text), repr(sanitize(text)))
    assert len(text) <= max_targets, ("stock text over max_targets", pid, len(text))
    # Sight-read: no WORD of the passage may appear in the sanitised hint (a
    # word-level check, stronger than a whole-string substring check -- catches a
    # hint that leaks part of a multi-word passage).
    hint_words = set(sanitize(hint).split())
    leaked = hint_words & set(text.split())
    assert not leaked, ("stock hint reveals a passage word", pid, leaked)


# --- assemble + write ---------------------------------------------------------

out = {
    "$schema_version": "1.0",
    "_README": (
        "Sanitisation parity vectors for the Learn passage source (Epic 6a, #71): a "
        "raw input string -> the sanitised target string the source feeds the drill "
        "engine. Both platform ports run PassageSource.sanitize over each input and "
        "MUST produce `expected` exactly. The frozen rule is shared/source_contract."
        "json; the engine that consumes the result is shared/drill_contract.json."
    ),
    "_generated_by": (
        "shared/tools/gen_sanitize_vectors.py (uv run). Generated, not hand-edited: "
        "the reference sanitize() is re-run over every authored (input -> expected) "
        "and asserted before writing, shared/stock_passages.json is asserted "
        "already-clean, and the contract's supported_chars is cross-checked against "
        "the reference output alphabet. Re-run after any change to the sanitise rule "
        "(shared/source_contract.json) or the stock passages."
    ),
    "_encoding": (
        "Written with ensure_ascii=True (UNLIKE drill_vectors.json) ON PURPOSE: the "
        "test INPUTS carry deliberate non-ASCII scalars (eszett, e-acute precomposed "
        "AND decomposed, Turkish dotted-I, emoji), so storing them as escapes keeps "
        "the fixture byte-stable and immune to any editor/filesystem Unicode "
        "normalisation. Every JSON parser decodes the escapes (and surrogate pairs) "
        "back to the same scalars, so parity is preserved."
    ),
    "_sanitize_rule": (
        "Per shared/source_contract.json + ADR 0008: iterate by scalar/code point "
        "(Swift unicodeScalars, Kotlin Char, Python code point) -- NOT graphemes; "
        "ASCII-only upper (a-z -> A-Z by ord, never locale-aware whole-string upper); "
        "keep A-Z / 0-9; collapse {SPACE,TAB,LF,CR} runs to one SPACE and trim "
        "leading/trailing; drop everything else (no transliteration)."
    ),
    "_format": {
        "cases": "Each is {name, input, expected}. Feed `input` to PassageSource.sanitize and assert == `expected`.",
        "input": "The raw passage text (may contain non-ASCII scalars stored as escapes).",
        "expected": "The sanitised target string (frozen ASCII alphabet: A-Z / 0-9 / single internal SPACEs, trimmed).",
        "name": "Human-readable label for debugging. Ignored by the parity harness.",
        "_note": "Optional human explanation. Ignored by the parity harness.",
    },
    "cases": cases,
}

dest = SHARED / "sanitize_vectors.json"
dest.write_text(json.dumps(out, indent=2, ensure_ascii=True) + "\n")

print(f"wrote {dest.relative_to(SHARED.parent)}")
print(f"  cases: {len(cases)}")
print(f"  stock_passages: {len(stock['passages'])} (all asserted already-clean, within max_targets={max_targets})")
print("  all cases re-run through the reference sanitize() and asserted OK")

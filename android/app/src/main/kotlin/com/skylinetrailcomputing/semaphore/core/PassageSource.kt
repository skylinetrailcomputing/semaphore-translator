package com.skylinetrailcomputing.semaphore.core

/**
 * The Learn passage SOURCE sanitiser (Epic 6a, #71 / 6a-3) -- turns arbitrary
 * user/stock text into the already-validated target string the [DrillSession]
 * (ADR 0007) consumes. A faithful Kotlin twin of the reference `sanitize()` in
 * `shared/tools/gen_sanitize_vectors.py` and of the Swift `PassageSource`. The
 * cross-platform enforcement is `shared/sanitize_vectors.json` (the
 * `SanitizeParityTest` harness); the frozen rule is `shared/source_contract.json` +
 * `docs/adr/0008-learn-source-sanitization.md`. Strictly upstream of the drill
 * engine; never touches the decode/adapter/commit core.
 *
 * Two parity-critical choices (see ADR 0008), both pinned by the vectors:
 *  * Iterate [Char] (UTF-16 code units). A non-BMP scalar is two surrogate Chars;
 *    both fall outside the kept/separator sets and are dropped, matching Python code
 *    points / Swift `unicodeScalars`. (A decomposed base letter + combining mark is
 *    two Chars here too, so the base letter is kept -- the case a Swift port must NOT
 *    break by iterating `Character`.)
 *  * ASCII-only uppercase by code unit (a-z -> A-Z); never [String.uppercase], which
 *    is locale-aware (expands the eszett to "SS", folds the Turkish dotless-i) and
 *    would keep characters the rule means to drop.
 */
object PassageSource {
    /**
     * Soft UI cap on a drill passage's sanitised length -- a beta safety bound, not a
     * decode constant. Kept in lockstep with iOS's `PassageSource.maxTargets` and with
     * `shared/source_contract.json`'s `max_targets` (the `SanitizeParityTest` asserts
     * this equals the contract value, so the three can't drift).
     */
    const val MAX_TARGETS = 200

    /**
     * Map arbitrary text to the frozen ASCII target alphabet (A-Z / 0-9 / SPACE), in
     * signing order, with whitespace runs collapsed to one SPACE and leading/trailing
     * whitespace trimmed. Everything else (punctuation, accents, emoji, NBSP, control
     * chars) is dropped -- no transliteration.
     */
    fun sanitize(input: String): String {
        val out = StringBuilder()
        var hasKept = false
        var pendingSpace = false
        for (ch in input) {
            val v = ch.code
            val c = if (v in 0x61..0x7A) v - 0x20 else v // ASCII-only a-z -> A-Z
            when {
                // SPACE, TAB, LF, CR -- word separators. Flush as one SPACE only
                // before the next kept char, and only after a kept char has already
                // been emitted -> leading/trailing trimmed, internal runs collapsed.
                c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D ->
                    if (hasKept) pendingSpace = true
                // A-Z, 0-9 -- kept.
                c in 0x41..0x5A || c in 0x30..0x39 -> {
                    if (pendingSpace) out.append(' ')
                    out.append(c.toChar())
                    hasKept = true
                    pendingSpace = false
                }
                // else: drop silently
            }
        }
        return out.toString()
    }

    /**
     * Whether a (already-sanitised) passage contains a digit target -- the signal the
     * Learn surfaces use to honour the "Enable numerals" setting (#127): when numerals
     * are OFF, a sight-read passage with a digit is non-selectable and a typed passage
     * with a digit warns. Sanitised output is pure ASCII `A-Z 0-9 SPACE`, so a plain
     * `0-9` check is exact. Kept in lockstep with iOS's `PassageSource.containsDigit`.
     */
    fun containsDigit(sanitized: String): Boolean = sanitized.any { it in '0'..'9' }
}

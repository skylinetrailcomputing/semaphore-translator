package com.skylinetrailcomputing.semaphore.core

/**
 * The readout-buffer appender (ADR 0010) -- joins one committer token to the
 * user-visible `committedText` buffer, coalescing spaces. A faithful Kotlin twin of the
 * reference `append_committed()` in `shared/tools/gen_committed_text_vectors.py` and of
 * the Swift `CommittedText.append`. The cross-platform enforcement is
 * `shared/committed_text_vectors.json` (the `CommittedTextParityTest` harness).
 *
 * This is the live/incremental analogue of [PassageSource.sanitize] (ADR 0008): the
 * source sanitiser trims+collapses whitespace over a whole typed string in one batch;
 * this enforces the same "no leading space, no double space" shape one committed token
 * at a time as a read unfolds. The committer (ADR 0004/0005) emits exactly one token per
 * frame -- a letter, a digit, a single SPACE (a sustained REST), or `""` -- and even its
 * clean half-open REST design still lets two SPACE tokens through:
 *   1. a held REST *before any letter* commits a LEADING space, and
 *   2. a brief indeterminate gap between two REST runs re-arms the same-symbol gate and
 *      commits a SECOND space between words.
 * "Is this a leading/redundant space?" is a property of the *buffer*, which outlives the
 * committer's no-signer [Committer.reset], not of the committer's per-session state -- so
 * the rule lives here, at the buffer, and never touches the decode/adapter/commit core.
 */
object CommittedText {
    /**
     * Append [emitted] (one committer token) to [buffer], coalescing spaces: a SPACE
     * joins only when the buffer is non-empty and does not already end in a space (no
     * leading space, no two in a row); any non-space token -- and `""` -- appends
     * unchanged. A *trailing* space is deliberately NOT trimmed: it is the in-progress
     * word separator the signer just rested to make ("A " becomes "A B" on the next
     * letter).
     */
    fun append(buffer: String, emitted: String): String {
        if (emitted == " " && (buffer.isEmpty() || buffer.last() == ' ')) return buffer
        return buffer + emitted
    }
}

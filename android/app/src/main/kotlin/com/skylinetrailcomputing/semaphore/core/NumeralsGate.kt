package com.skylinetrailcomputing.semaphore.core

/**
 * The Learn-only numerals gate (#127) -- a live-layer input filter that lets the
 * user practise letters without ever switching into numeric mode. When the "Enable
 * numerals" setting is OFF (Learn / front lens only), a classified `NUMERALS`
 * control pose is mapped to indeterminate (`null`) *before* it reaches the temporal
 * [Committer], so the committer never commits the mode switch and the decoder stays
 * in `LETTERS` for the whole session. Identity when numerals are enabled.
 *
 * This sits ONE layer above the frozen decode core: the [SemaphoreDecoder] and
 * [Committer] (the parity-vector-proven ports of the Python reference) are
 * untouched, exactly as the facing-away *toggle gating* is (only its `x->1-x`
 * keypoint math is vectored, because that math is nontrivial -- this gate has none).
 * It is a pure function so it unit-tests directly, and it is kept in lockstep with
 * iOS's `NumeralsGate.gate` (no shared vector needed -- there is no math to diverge
 * on, only the trivial symbol equality below).
 *
 * Front-lens scoping lives at the call site ([com.skylinetrailcomputing.semaphore.ui]'s
 * `SemaphoreScreen` seeds `suppress` from `!isRear && !allowNumerals`): Interpret (rear
 * lens) always honours numerals, because there you are reading a real signer whose
 * digits must not be silently mis-read as letters.
 */
object NumeralsGate {
    /**
     * The votable pose symbol the committer should see this frame. Returns `null`
     * (indeterminate -- a legitimate vote value that can never commit or flip mode) in
     * place of a `NUMERALS` pose when [suppress] is true; otherwise returns [symbol]
     * unchanged. Only the `NUMERALS` control pose is affected -- letters and `REST`
     * always pass through, so with numerals suppressed every readable pose still
     * decodes as its letter.
     */
    fun gate(symbol: String?, suppress: Boolean): String? =
        if (suppress && symbol == "NUMERALS") null else symbol
}

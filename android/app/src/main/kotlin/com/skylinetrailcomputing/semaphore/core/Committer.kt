package com.skylinetrailcomputing.semaphore.core

/**
 * The temporal committer (ADR 0004, #4.3) -- a faithful Kotlin twin of the
 * reference `Committer` in `shared/tools/gen_temporal_vectors.py` and of the
 * Swift `Committer` (#4.2). It turns the per-frame decoder into a temporal one:
 * a held pose produces exactly one committed character (FR4), and the
 * numeric-mode switch stops flipping on a single fleeting frame (the #23
 * symptom). The temporal parity harness proves the two platform committers, and
 * the Python reference the fixtures were generated against, agree byte-for-byte.
 *
 * Pure + injected-time: [process] and [reset] never read the clock -- time is the
 * caller's `tMs` argument -- so `temporal_vectors.json` is deterministic (the same
 * discipline as [SemaphoreDecoder] taking plain values, not JSON). The live layer
 * (#4.5) feeds it the per-frame timestamp (#4.4) and drives [reset] from the
 * no-signer watchdog.
 *
 * It wraps [SemaphoreDecoder] because it owns `mode` and must run [interpret] *at
 * commit* (so mode flips only on a committed control pose). The caller runs the
 * mode-independent [classify] per frame -- the votable unit -- and feeds the
 * resulting symbol here; `classify` is pre-vote, `interpret` is at-commit, which
 * is exactly the reference's split.
 */
class Committer(
    private val decoder: SemaphoreDecoder,
    timing: CommitTiming,
) {
    private val smoothingWindow = timing.smoothingWindow
    private val commitHoldMs = timing.commitHoldMs
    private val interCharGapMs = timing.interCharGapMs

    /** Ring buffer of the last [smoothingWindow] votable symbols (null = indeterminate is a vote value). */
    private val window = ArrayDeque<String?>()

    /** Decoder mode (§4.5); flipped only by [interpret] at commit. */
    private var mode = Mode.LETTERS

    /**
     * The voted candidate currently being timed. [candidateSet] distinguishes
     * "no candidate yet" from a candidate of null -- the reference's `_UNSET`
     * sentinel -- so the very first frame still starts the hold timer.
     */
    private var candidate: String? = null
    private var candidateSet = false

    /** `tMs` at which [candidate] last changed value (the hold timer's origin). */
    private var candidateSince = 0L

    /** The last committed symbol (the same-symbol lock key). */
    private var lastCommitted: String? = null

    /** Has a re-arming brief REST been seen since the last commit (same-symbol gate)? */
    private var gapOk = true

    /** `tMs` the current continuous-REST run began, else null. */
    private var restSince: Long? = null

    /**
     * The current decoder mode (§4.5), for the live mode badge. Read-only -- mode
     * flips solely inside [process], at commit.
     */
    val currentMode: Mode
        get() = mode

    init {
        reset()
    }

    /**
     * Hard reset (true signer-loss, §4.5): clear the window, mode -> LETTERS.
     * Driven by the live no-signer watchdog (#4.5), never by the committer itself
     * -- a short indeterminate gap is handled inside [process] and deliberately
     * preserves mode and the window.
     */
    fun reset() {
        window.clear()
        mode = Mode.LETTERS
        candidate = null
        candidateSet = false
        candidateSince = 0L
        lastCommitted = null
        gapOk = true
        restSince = null
    }

    /**
     * Feed one classified pose symbol (null = indeterminate) at wall-clock [tMs].
     * Returns the string committed on *this* frame: `""` on most frames, the
     * committed letter/digit/space when a commit fires. A control-pose commit
     * (NUMERALS, or the J letters-shift) flips mode but also returns `""` -- so
     * the return is the emitted text, never a "did something commit" flag.
     */
    fun process(symbol: String?, tMs: Long): String {
        window.addLast(symbol)
        if (window.size > smoothingWindow) window.removeFirst()

        val candidate = vote()
        if (!candidateSet || candidate != this.candidate) { // changed -> restart hold timer
            this.candidate = candidate
            candidateSet = true
            candidateSince = tMs
        }

        // A brief REST re-arms the same-symbol lock -- the conventional double-letter
        // separator (ADR 0005, superseding ADR 0004 Decision 3). The voted candidate
        // being REST, with its dwell in [interCharGapMs, commitHoldMs), re-arms
        // WITHOUT committing a space; at >= commitHoldMs the REST commits a space
        // instead (the commit step below), so the window is half-open at the top --
        // which also stops a sustained REST re-arming after its space commits and
        // streaming spaces. An indeterminate gap no longer re-arms, so incidental
        // off-octant hold jitter can't double a held letter (FR4). Like the hold
        // timer, this runs off the VOTED candidate, not the raw incoming symbol -- a
        // port arming on raw frames would re-arm earlier and diverge (ADR 0004
        // Decision 2; the same_letter_rest_* fixtures pin the boundary).
        if (candidate == "REST") {
            val since = restSince ?: tMs
            restSince = since
            val dwell = (tMs - since).toDouble()
            if (dwell >= interCharGapMs && dwell < commitHoldMs) gapOk = true
        } else {
            restSince = null
        }

        // Commit: a determinate candidate, held long enough, past the same-symbol gate.
        if (candidate != null && (tMs - candidateSince).toDouble() >= commitHoldMs) {
            if (candidate != lastCommitted || gapOk) {
                val (emit, newMode) = decoder.interpret(candidate, mode)
                mode = newMode
                lastCommitted = candidate
                gapOk = false
                return emit
            }
        }
        return ""
    }

    /**
     * Plurality over the window; ties break to the most-recent occurrence. null
     * (indeterminate) is a legitimate vote value and can win. Votes over the
     * partial window before it fills (sequence start / post-[reset]) so the first
     * character is not committed `smoothingWindow - 1` frames late (ADR 0004
     * Decision 2).
     */
    private fun vote(): String? {
        val counts = HashMap<String?, Int>()
        for (value in window) counts[value] = (counts[value] ?: 0) + 1
        val best = counts.values.max()
        var winner: String? = null
        for (value in window) if (counts[value] == best) winner = value
        return winner
    }
}

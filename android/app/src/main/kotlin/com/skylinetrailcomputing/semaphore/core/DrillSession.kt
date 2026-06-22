package com.skylinetrailcomputing.semaphore.core

/**
 * One [DrillSession.observe] decision: did this emit match the current target, the
 * resulting target index, and whether the session is now complete.
 */
data class DrillStep(val matched: Boolean, val index: Int, val complete: Boolean)

/**
 * The Learn drill engine (Epic 6a, #69) -- a faithful Kotlin twin of the reference
 * `DrillSession` in `shared/tools/gen_drill_vectors.py` and of the Swift
 * `DrillSession`. It sits strictly DOWNSTREAM of the [Committer] (ADR 0004/0005):
 * it observes only the characters the committer emits and never reads keypoints,
 * timing, or any decode state, so it cannot perturb the decode/adapter/commit core
 * (parity risk ~0). See `shared/drill_contract.json` +
 * `docs/adr/0007-drill-engine-contract.md`; the cross-platform enforcement is
 * `shared/drill_vectors.json` (the `DrillParityTest` harness).
 *
 * Pure logic: [observe] is a function of the emitted character and the current
 * target only. Stay-until-success (the 6a-1 lifecycle, iv-a) is the advance policy
 * -- a matching emit advances, a wrong one is a no-op. The session HUD + celebrate
 * (6a-2) and later modes (6a-6) layer over this same target/index core; they do
 * not change the match rule.
 */
class DrillSession(targets: String) {
    /** The passage split into single-character targets, in signing order. */
    private val targets: List<Char> = targets.toList()

    /** Index of the current (not-yet-matched) target; `== count` once complete. */
    var index = 0
        private set

    val count: Int
        get() = targets.size

    val isComplete: Boolean
        get() = index >= targets.size

    /** The character the signer must produce next, or null once complete -- for the 6a-2 HUD. */
    val currentTarget: Char?
        get() = if (isComplete) null else targets[index]

    /**
     * Feed one NON-EMPTY committed character (the [Committer.process] return).
     * Returns this step's match decision and the resulting session state. After
     * COMPLETE, further emits are no-ops (contract: `after_complete`).
     */
    fun observe(emit: String): DrillStep {
        val target = currentTarget ?: return DrillStep(matched = false, index = index, complete = true)
        val matched = emit.uppercase() == target.toString().uppercase()
        if (matched) index += 1
        return DrillStep(matched = matched, index = index, complete = isComplete)
    }

    /** Restart the session at the first target (e.g. replay). Caller-driven, like [Committer.reset]. */
    fun reset() {
        index = 0
    }
}

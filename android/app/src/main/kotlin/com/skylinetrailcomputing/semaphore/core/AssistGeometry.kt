package com.skylinetrailcomputing.semaphore.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * The pose of one semaphore target as two arm angles, in the **signer's**
 * perspective (degrees, CCW from `+x` = signer's right, y-up) — read straight from
 * the frozen `semaphore_alphabet.json`. The assist figure (#73 / 6a-5) draws its
 * arms at *exactly* these angles, so the teaching aid is perspective-correct by
 * construction and cannot drift from the contract. The iOS twin is `AssistPose`.
 */
data class AssistPose(val leftAngleDeg: Double, val rightAngleDeg: Double)

/** A point in the normalized `[0,1]` figure box (screen, y-down convention). */
data class AssistPoint(val x: Double, val y: Double)

/**
 * One step in the assist filmstrip for a drill target (#100 / 6a-13). [json] matches
 * `assist_cue_vectors.json`'s `kind` field, so the parity harness maps the fixture
 * straight onto this. The iOS twin is `AssistCueKind`.
 */
enum class AssistCueKind(val json: String) {
    /**
     * Drop to a brief REST so a repeated target re-commits (the inter-char-gap
     * re-arm, ADR 0005). A *timing* cue — the view draws it as a "drop", not a pose
     * to hold (its [AssistCue.pose] is REST only so the layout has something to show).
     */
    REST_BETWEEN_DOUBLES("rest_between_doubles"),

    /** The NUMERALS pose: enter numeric mode before a digit signed from letter mode. */
    NUMERALS_SHIFT("numerals_shift"),

    /** The J/LETTERS pose: return to letter mode before a letter signed from numeric mode. */
    LETTERS_SHIFT("letters_shift"),

    /** The target's own arm pose (always the final step of a filmstrip). */
    TARGET("target"),
}

/** One filmstrip step: what to show, drawn at [pose]'s contract angles. The iOS twin is `AssistCue`. */
data class AssistCue(val kind: AssistCueKind, val pose: AssistPose)

/**
 * The stick-figure layout for one [AssistPose], in a `[0,1]` box with the
 * **display** (screen, y-down) convention already applied — the same
 * signer→display mapping `SkeletonOverlay` uses for the mirrored front preview.
 * The composable scales these by its canvas size; keeping the geometry here (not
 * in the `Canvas` lambda) is what lets the unit test pin "rendered pose matches the
 * alphabet angles". The iOS twin is `AssistFigurePoints`.
 */
data class AssistFigurePoints(
    val neck: AssistPoint,
    val hip: AssistPoint,
    val head: AssistPoint,
    val leftShoulder: AssistPoint,
    val rightShoulder: AssistPoint,
    val leftWrist: AssistPoint,
    val rightWrist: AssistPoint,
)

/**
 * Maps a drill target character to its semaphore arm pose and lays out the
 * contract-derived assist stick figure (#73 / 6a-5). A pure class built from the
 * already-parsed alphabet maps ([ContractLoader.makeAssistGeometry]), so the figure
 * logic is unit-testable with no `Context` (the JVM `src/test` constraint, #37) and
 * no camera. Strictly read-only over the frozen contract — like the drill engine
 * (ADR 0007), it sits *beside* the decode core and cannot perturb it. The Swift
 * twin is `AssistGeometry`.
 *
 * @param octantAngles position id (0-7) -> canonical octant angle in degrees.
 * @param symbolPairs ordered `(left, right)` position ids per symbol: the 26
 *   letters, `REST`, and `NUMERALS`. Ordered on purpose — the figure draws each arm
 *   from its own shoulder, so (unlike the decoder's order-insensitive match) which
 *   id is left vs right must survive. That is why the figure is built from a fresh
 *   alphabet parse rather than the decoder, whose lookup collapses the pair.
 * @param digitToLetter digit char -> the letter symbol whose pose produces it in
 *   numeric mode (the reverse of the A=1..I=9, K=0 digit map). The figure needs
 *   digit→letter to draw a digit's arm pose; the decoder only carries letter→digit.
 */
class AssistGeometry(
    private val octantAngles: Map<Int, Double>,
    private val symbolPairs: Map<String, Pair<Int, Int>>,
    private val digitToLetter: Map<Char, String>,
) {
    /**
     * The target's own arm pose, or `null` for a target with no pose. A letter
     * (A-Z) maps directly; a digit maps to the letter pose that produces it (#100
     * un-suppressed the digit case — the NUMERALS pre-cue from [cues] now conveys
     * the mode-switch a static pose alone can't); SPACE is REST.
     */
    fun pose(target: Char?): AssistPose? {
        if (target == null) return null
        return when {
            target == ' ' -> poseOfSymbol("REST")
            target.code >= 128 -> null
            target.isLetter() -> poseOfSymbol(target.uppercaseChar().toString())
            target.isDigit() -> digitToLetter[target]?.let { poseOfSymbol(it) }
            else -> null // punctuation: no pose
        }
    }

    /** The arm pose for a named contract symbol (a letter, `REST`, or `NUMERALS`). */
    private fun poseOfSymbol(symbol: String): AssistPose? {
        val pair = symbolPairs[symbol] ?: return null
        val left = octantAngles[pair.first] ?: return null
        val right = octantAngles[pair.second] ?: return null
        return AssistPose(left, right)
    }

    // --- Transition cues (#100 / 6a-13) ---

    /**
     * The mode the signer is in just **before** producing `targets[index]`, walking
     * the canonical path from `LETTERS` at the start of the passage. Mirrors the
     * committer's mode rules (spec §4.5): a digit ends the signer in numeric mode, a
     * letter in letter mode, and SPACE/REST (like an indeterminate frame) persist
     * the current mode. `gen_assist_cue_vectors.py` cross-checks this against the
     * reference `interpret`, so it cannot drift from the committer.
     */
    fun impliedMode(targets: String, index: Int): Mode {
        var mode = Mode.LETTERS
        for (i in 0 until index.coerceAtMost(targets.length)) {
            val t = targets[i]
            when {
                t.code >= 128 -> {} // non-ASCII: persists
                t.isDigit() -> mode = Mode.NUMERIC
                t.isLetter() -> mode = Mode.LETTERS
                // space / other: mode persists
            }
        }
        return mode
    }

    /**
     * The ordered assist filmstrip for `targets[index]` (#100): an optional
     * transition pre-cue, then the target's own pose. Empty for an out-of-range index
     * or a target with no pose (defensive; a sanitised passage never has one).
     *
     * The three pre-cues are mutually exclusive by construction: a repeated target is
     * the same character, so the implied mode already matches the target's mode (and
     * the mode-switch poses, which themselves break the same-symbol lock, are never
     * both needed) — so at most one pre-cue ever precedes the target step.
     */
    fun cues(targets: String, index: Int): List<AssistCue> {
        if (index < 0 || index >= targets.length) return emptyList()
        val target = targets[index]
        val targetPose = pose(target) ?: return emptyList()

        val result = mutableListOf<AssistCue>()
        val mode = impliedMode(targets, index)
        when {
            index > 0 &&
                target.uppercaseChar() == targets[index - 1].uppercaseChar() &&
                target != ' ' ->
                // Double (same) target: a brief drop to REST re-arms the commit (ADR 0005).
                poseOfSymbol("REST")?.let {
                    result.add(AssistCue(AssistCueKind.REST_BETWEEN_DOUBLES, it))
                }
            target.code < 128 && target.isDigit() && mode == Mode.LETTERS ->
                poseOfSymbol("NUMERALS")?.let {
                    result.add(AssistCue(AssistCueKind.NUMERALS_SHIFT, it))
                }
            target.code < 128 && target.isLetter() && mode == Mode.NUMERIC ->
                poseOfSymbol("J")?.let { // the J pose = the LETTERS shift
                    result.add(AssistCue(AssistCueKind.LETTERS_SHIFT, it))
                }
        }
        result.add(AssistCue(AssistCueKind.TARGET, targetPose))
        return result
    }

    companion object {
        /**
         * Lay out the stick figure for [pose] in a normalized `[0,1]` box, screen
         * (y-down) convention. [mirroredFront] follows the preview: the front Learn
         * drill is mirrored (selfie), so the signer's right arm (octant `0°`) maps
         * to screen-right and the user mirrors the figure directly — the same
         * convention as `SkeletonOverlay`'s `at`. Pure + static so the test pins the
         * signer-frame-angle → screen-direction mapping the `Canvas` then draws.
         */
        fun endpoints(pose: AssistPose, mirroredFront: Boolean = true): AssistFigurePoints {
            val shoulderY = 0.56
            val halfSpan = 0.13
            val armLen = 0.28

            val lShoulder = Pair(0.5 - halfSpan, shoulderY)
            val rShoulder = Pair(0.5 + halfSpan, shoulderY)
            val lWrist = wrist(lShoulder, pose.leftAngleDeg, armLen)
            val rWrist = wrist(rShoulder, pose.rightAngleDeg, armLen)
            val neck = Pair(0.5, shoulderY)
            val head = Pair(0.5, shoulderY + 0.22)
            val hip = Pair(0.5, shoulderY - 0.34)

            // Signer-frame (y-up) → screen box (y-down). Front preview is mirrored,
            // so `+x` already matches screen-right; only y-up is undone for both.
            fun project(p: Pair<Double, Double>): AssistPoint {
                val x = if (mirroredFront) p.first else (1 - p.first)
                return AssistPoint(x, 1 - p.second)
            }
            return AssistFigurePoints(
                neck = project(neck),
                hip = project(hip),
                head = project(head),
                leftShoulder = project(lShoulder),
                rightShoulder = project(rShoulder),
                leftWrist = project(lWrist),
                rightWrist = project(rWrist),
            )
        }

        private fun wrist(
            shoulder: Pair<Double, Double>,
            angleDeg: Double,
            length: Double,
        ): Pair<Double, Double> {
            val radians = angleDeg * Math.PI / 180
            return Pair(shoulder.first + length * cos(radians), shoulder.second + length * sin(radians))
        }
    }
}

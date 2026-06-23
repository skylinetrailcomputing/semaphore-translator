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
 * @param letterPairs ordered `(left, right)` position ids per symbol: the 26
 *   letters + `REST`. Ordered on purpose — the figure draws each arm from its own
 *   shoulder, so (unlike the decoder's order-insensitive match) which id is left vs
 *   right must survive. That is why the figure is built from a fresh alphabet parse
 *   rather than the decoder, whose lookup collapses the pair.
 * @param digitMap letter symbol -> digit string (A-I -> 1-9, K -> 0), reversed to
 *   map a digit *target* back to the arm pose the signer holds for it.
 */
class AssistGeometry(
    private val octantAngles: Map<Int, Double>,
    private val letterPairs: Map<String, Pair<Int, Int>>,
    private val digitMap: Map<String, String>,
) {
    /**
     * The two arm angles for a drill target, or `null` for a target with no pose.
     * Targets are the frozen drill alphabet A-Z / 0-9 / SPACE: a letter maps
     * directly; a digit reverses [digitMap] to its letter pose (the arms *are* in
     * that position — the NUMERALS mode-switch that precedes a digit is signaled
     * separately and not taught by the figure); SPACE is REST (both arms down).
     */
    fun pose(target: Char?): AssistPose? {
        if (target == null) return null
        val pair =
            when {
                target == ' ' -> letterPairs["REST"]
                target.code < 128 && target.isLetter() ->
                    letterPairs[target.uppercaseChar().toString()]
                target.code < 128 && target.isDigit() -> {
                    val digit = target.toString()
                    digitMap.entries.firstOrNull { it.value == digit }?.let { letterPairs[it.key] }
                }
                else -> null
            } ?: return null
        val left = octantAngles[pair.first] ?: return null
        val right = octantAngles[pair.second] ?: return null
        return AssistPose(left, right)
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

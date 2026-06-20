package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.Alphabet
import com.skylinetrailcomputing.semaphore.core.Keypoint
import com.skylinetrailcomputing.semaphore.core.Keypoints
import com.skylinetrailcomputing.semaphore.core.NativeInvariant
import com.skylinetrailcomputing.semaphore.core.SemaphoreConfig
import com.skylinetrailcomputing.semaphore.core.SemaphoreDecoder
import com.skylinetrailcomputing.semaphore.core.SharedFiles
import kotlin.math.abs

// Shared test-only helpers for the fixture suites (parity + native fixtures):
// the single decoder construction, the map -> typed `Keypoints` bridge, and the
// native-fixture invariant evaluator. None of this ships in the app module.

/**
 * Builds [SemaphoreDecoder] from the frozen shared contract. The single
 * construction shared by `ParityTest` and `NativeFixtureTest`.
 */
fun referenceDecoder(): SemaphoreDecoder {
    val alphabet = SharedFiles.load<Alphabet>("semaphore_alphabet.json")
    val config = SharedFiles.load<SemaphoreConfig>("semaphore_config.json")

    val octantAngles = alphabet.positionModel.positions.values.associate { it.id to it.angleDeg }
    val symbolPairs =
        buildMap {
            alphabet.letters.forEach { (symbol, ids) -> put(symbol, ids.left to ids.right) }
            put(
                "NUMERALS",
                alphabet.controlSignals.numerals.left to alphabet.controlSignals.numerals.right,
            )
            put("REST", alphabet.controlSignals.rest.left to alphabet.controlSignals.rest.right)
        }

    return SemaphoreDecoder(
        octantAngles = octantAngles,
        symbolPairs = symbolPairs,
        digitMap = alphabet.numericMode.digitMap,
        angleToleranceDeg = config.angleToleranceDeg,
        minKeypointConfidence = config.minKeypointConfidence,
    )
}

/**
 * Build the typed [Keypoints] the decoder consumes from a fixture's
 * `[name: [x, y, confidence]]` map (the shape used by both `test_vectors.json`
 * and `invariants.json`). The map is a fixture artifact; the shipping adapter
 * builds [Keypoints] directly from native pose output (Epic 3). A missing name
 * is a malformed fixture and fails the test loudly.
 */
fun keypointsFrom(map: Map<String, List<Double>>): Keypoints {
    fun point(name: String): Keypoint {
        val v = requireNotNull(map[name]) { "missing keypoint $name" }
        return Keypoint(v[0], v[1], v[2])
    }
    return Keypoints(
        leftShoulder = point("left_shoulder"),
        leftElbow = point("left_elbow"),
        leftWrist = point("left_wrist"),
        rightShoulder = point("right_shoulder"),
        rightElbow = point("right_elbow"),
        rightWrist = point("right_wrist"),
    )
}

// --- native-fixture invariant evaluation ---

/** Resolve a `<keypoint>.<x|y|confidence>` operand, or null if it is malformed. */
private fun valueOf(operand: String, kp: Keypoints): Double? {
    val parts = operand.split(".")
    if (parts.size != 2) return null
    val point =
        when (parts[0]) {
            "left_shoulder" -> kp.leftShoulder
            "left_elbow" -> kp.leftElbow
            "left_wrist" -> kp.leftWrist
            "right_shoulder" -> kp.rightShoulder
            "right_elbow" -> kp.rightElbow
            "right_wrist" -> kp.rightWrist
            else -> return null
        }
    return when (parts[1]) {
        "x" -> point.x
        "y" -> point.y
        "confidence" -> point.confidence
        else -> null
    }
}

/**
 * Evaluate one invariant against a [Keypoints], or null if it is malformed
 * (unknown operand/kind, or `abs_diff_lt` missing its `value`).
 */
fun evaluateInvariant(inv: NativeInvariant, kp: Keypoints): Boolean? {
    val lhs = valueOf(inv.lhs, kp) ?: return null
    val rhs = valueOf(inv.rhs, kp) ?: return null
    return when (inv.kind) {
        "gt" -> lhs > rhs
        "lt" -> lhs < rhs
        "abs_diff_lt" -> inv.value?.let { abs(lhs - rhs) < it }
        else -> null
    }
}

/**
 * A correct output with the horizontal mirror wrong (x not flipped to the
 * signer's perspective): every x becomes `1 - x`.
 */
fun mirrorBroken(kp: Keypoints): Keypoints =
    Keypoints(
        leftShoulder = kp.leftShoulder.copy(x = 1 - kp.leftShoulder.x),
        leftElbow = kp.leftElbow.copy(x = 1 - kp.leftElbow.x),
        leftWrist = kp.leftWrist.copy(x = 1 - kp.leftWrist.x),
        rightShoulder = kp.rightShoulder.copy(x = 1 - kp.rightShoulder.x),
        rightElbow = kp.rightElbow.copy(x = 1 - kp.rightElbow.x),
        rightWrist = kp.rightWrist.copy(x = 1 - kp.rightWrist.x),
    )

/** A correct output with the y-flip wrong (still y-down): every y becomes `1 - y`. */
fun yFlipBroken(kp: Keypoints): Keypoints =
    Keypoints(
        leftShoulder = kp.leftShoulder.copy(y = 1 - kp.leftShoulder.y),
        leftElbow = kp.leftElbow.copy(y = 1 - kp.leftElbow.y),
        leftWrist = kp.leftWrist.copy(y = 1 - kp.leftWrist.y),
        rightShoulder = kp.rightShoulder.copy(y = 1 - kp.rightShoulder.y),
        rightElbow = kp.rightElbow.copy(y = 1 - kp.rightElbow.y),
        rightWrist = kp.rightWrist.copy(y = 1 - kp.rightWrist.y),
    )

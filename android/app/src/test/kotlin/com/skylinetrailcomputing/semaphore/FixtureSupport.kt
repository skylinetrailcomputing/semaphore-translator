package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.Alphabet
import com.skylinetrailcomputing.semaphore.core.Keypoint
import com.skylinetrailcomputing.semaphore.core.Keypoints
import com.skylinetrailcomputing.semaphore.core.SemaphoreConfig
import com.skylinetrailcomputing.semaphore.core.SemaphoreDecoder
import com.skylinetrailcomputing.semaphore.core.SharedFiles

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

// The native-fixture invariant evaluator (`evaluateInvariant`) now lives in the
// `sharedTest` source set (`NativeInvariantEval.kt`) so the instrumented Layer-2
// calibration shares it; the broken-flip generators below stay here because only
// the JVM `NativeFixtureTest` (the contract self-test) uses them.

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

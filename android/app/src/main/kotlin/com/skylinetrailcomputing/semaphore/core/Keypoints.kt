package com.skylinetrailcomputing.semaphore.core

/**
 * One pose keypoint in the frozen post-adapter frame: a normalized position plus
 * the estimator's per-keypoint confidence. Coordinate frame is the contract's --
 * x increases toward the SIGNER'S RIGHT, y is UP, both in [0,1]; confidence in
 * [0,1], passed through unmodified. See `shared/keypoint_contract.json`.
 */
data class Keypoint(val x: Double, val y: Double, val confidence: Double)

/**
 * The 6 named keypoints the per-platform adapter PRODUCES and the decoder
 * CONSUMES -- the in-code embodiment of `shared/keypoint_contract.json`. A plain
 * value type with no JSON/wire coupling: the shipping adapter (Epic 3) builds it
 * directly from native pose output, and the decoder takes it as-is.
 *
 * Property order is the contract's `keypoints.names` order, and `flatten()`
 * matches `model_input_order` -- both frozen in `keypoint_contract.json`.
 */
data class Keypoints(
    val leftShoulder: Keypoint,
    val leftElbow: Keypoint,
    val leftWrist: Keypoint,
    val rightShoulder: Keypoint,
    val rightElbow: Keypoint,
    val rightWrist: Keypoint,
) {
    /**
     * The frozen 12-float model input vector (`model_input_order.floats`): each
     * keypoint's x then y, in `keypoints.names` order, confidence excluded.
     * Pinned now so both the Core ML/LiteRT exports and both adapters index
     * identically; only exercised once the Epic-5 classifier lands.
     */
    fun flatten(): List<Double> =
        listOf(
            leftShoulder.x, leftShoulder.y,
            leftElbow.x, leftElbow.y,
            leftWrist.x, leftWrist.y,
            rightShoulder.x, rightShoulder.y,
            rightElbow.x, rightElbow.y,
            rightWrist.x, rightWrist.y,
        )
}

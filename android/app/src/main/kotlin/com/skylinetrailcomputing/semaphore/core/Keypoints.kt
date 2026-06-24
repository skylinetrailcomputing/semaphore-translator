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

    /**
     * The Interpret **facing-away** flip (ADR 0011): one horizontal mirror,
     * `x -> 1 - x` per keypoint, `y` and `confidence` unchanged. Applied
     * **downstream of the adapter, at the decode call site**, only when the
     * Interpret facing-away toggle is on -- a *separate, opt-in* re-mirror for a
     * signer whose back is to the camera (a lifeguard facing the water), never the
     * adapter's own (single, quarantined) mirror. It cancels that mirror for a
     * reversed signer (net-zero flips), so the true letter is read instead of its
     * mirror twin. The deliberate twin of the `mirrorBroken()` test helper -- same
     * arithmetic, a correction here rather than a bug. Pure (no label swap: the
     * decoder's lookup is order-insensitive, §4.3); pinned across platforms by
     * `shared/facing_away_vectors.json`. The Swift twin is the same name.
     */
    fun mirroredHorizontally(): Keypoints {
        fun flip(k: Keypoint) = k.copy(x = 1.0 - k.x)
        return Keypoints(
            leftShoulder = flip(leftShoulder),
            leftElbow = flip(leftElbow),
            leftWrist = flip(leftWrist),
            rightShoulder = flip(rightShoulder),
            rightElbow = flip(rightElbow),
            rightWrist = flip(rightWrist),
        )
    }
}

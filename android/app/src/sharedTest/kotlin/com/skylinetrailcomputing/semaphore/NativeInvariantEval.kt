package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.Keypoints
import com.skylinetrailcomputing.semaphore.core.NativeInvariant
import kotlin.math.abs

// The native-fixture invariant DSL evaluator, shared verbatim by the local-JVM
// Layer-1 regression test (`MlKitAdapterTest`) and the instrumented Layer-2
// calibration (`MlKitCalibrationTest`) so both judge `invariants.json` the same
// way. The invariant set is a CONTRACT (`shared/native_fixtures/NATIVE-FIXTURES.md`
// §4); evaluating it in one place keeps the two layers from drifting.

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

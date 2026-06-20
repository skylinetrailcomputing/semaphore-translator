package com.skylinetrailcomputing.semaphore.capture

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.skylinetrailcomputing.semaphore.core.MlKitJoint
import com.skylinetrailcomputing.semaphore.core.MlKitSkeleton

/**
 * The ML Kit binding: reduce an ML Kit [Pose] to the six [MlKitSkeleton] joints
 * the adapter consumes (the `LEFT_SHOULDER`… mapping from
 * `shared/ADAPTER-CONTRACT.md` §5), in ML Kit's native image-pixel frame.
 *
 * This is the **only** production code that touches ML Kit pose types, so the
 * pure `MlKitPoseAdapter` — and therefore the Layer-1 JVM regression test that
 * replays through it — stays free of any ML Kit / Android dependency. Both the
 * live capture path ([PoseCaptureSession]) and the instrumented Layer-2
 * calibration (`MlKitCalibrationTest`) go through this single extraction.
 *
 * Returns `null` if any of the six upper-body landmarks is absent (a frame
 * without a full upper-body skeleton — no decode this frame).
 */
fun Pose.toMlKitSkeleton(): MlKitSkeleton? {
    fun joint(type: Int): MlKitJoint? {
        val landmark = getPoseLandmark(type) ?: return null
        val p = landmark.position
        return MlKitJoint(p.x.toDouble(), p.y.toDouble(), landmark.inFrameLikelihood.toDouble())
    }
    val ls = joint(PoseLandmark.LEFT_SHOULDER) ?: return null
    val le = joint(PoseLandmark.LEFT_ELBOW) ?: return null
    val lw = joint(PoseLandmark.LEFT_WRIST) ?: return null
    val rs = joint(PoseLandmark.RIGHT_SHOULDER) ?: return null
    val re = joint(PoseLandmark.RIGHT_ELBOW) ?: return null
    val rw = joint(PoseLandmark.RIGHT_WRIST) ?: return null
    return MlKitSkeleton(
        leftShoulder = ls, leftElbow = le, leftWrist = lw,
        rightShoulder = rs, rightElbow = re, rightWrist = rw,
    )
}

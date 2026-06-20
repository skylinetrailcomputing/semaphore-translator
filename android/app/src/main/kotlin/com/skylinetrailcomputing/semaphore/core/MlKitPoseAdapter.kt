package com.skylinetrailcomputing.semaphore.core

/**
 * One joint as reported by Android ML Kit Pose Detection, in ML Kit's **native**
 * frame: image-PIXEL coordinates, top-left origin (`x` toward the image's right,
 * `y` **down**), anatomical L/R labels (ML Kit defines left/right from the
 * subject's perspective). This is the adapter's *input* frame — pre-normalize,
 * pre-flip. `confidence` is the landmark's in-frame likelihood, passed through
 * unmodified.
 */
data class MlKitJoint(val x: Double, val y: Double, val confidence: Double)

/**
 * The six ML Kit landmarks the adapter consumes, reduced from a `Pose` to the
 * six the shared contract needs (`shared/ADAPTER-CONTRACT.md` §5). Both the live
 * capture path and the recorded Layer-1 fixture produce this. The ML Kit
 * `Pose` -> `MlKitSkeleton` extraction lives in the capture path (the only place
 * that imports ML Kit), so this value type — and the adapter below — stay free
 * of any ML Kit / Android dependency and run on the local JVM.
 */
data class MlKitSkeleton(
    val leftShoulder: MlKitJoint,
    val leftElbow: MlKitJoint,
    val leftWrist: MlKitJoint,
    val rightShoulder: MlKitJoint,
    val rightElbow: MlKitJoint,
    val rightWrist: MlKitJoint,
)

/**
 * The Android half of the per-platform adapter (`shared/ADAPTER-CONTRACT.md` §3,
 * spec §3.2): converts an ML Kit skeleton into the frozen post-adapter
 * `Keypoints` frame — normalized `[0,1]`, **y-up**, **signer's perspective**
 * (`+x` = signer's right). The normalize + two flips, each applied **exactly once
 * here and nowhere else**, are empirically pinned by the Epic-3 native fixtures
 * (`MlKitCalibrationTest` runs real ML Kit on `shared/native_fixtures` and
 * asserts `invariants.json`):
 *
 * - **normalize — px -> `[0,1]`.** ML Kit reports image-pixel coordinates;
 *   dividing by the (rotation-corrected) image width/height puts them in `[0,1]`.
 * - **y-flip — REAL negation `y_out = 1 − y/h`.** ML Kit uses a top-left origin
 *   (y-down), the **opposite** of Apple Vision (already y-up, so iOS's y-flip is
 *   a no-op). This asymmetry is exactly why the flip is quarantined here and
 *   pinned per platform (`ADAPTER-CONTRACT.md` §3a).
 * - **horizontal mirror — `x_out = 1 − x/w`.** The capture buffer is kept
 *   **non-mirrored** (observer perspective, matching the fixture images), so a
 *   single mirror lands the output in the signer's perspective: the signer's
 *   right arm has `right_shoulder.x > left_shoulder.x`.
 * - **labels — trusted as-is.** On a non-mirrored figure ML Kit assigns
 *   anatomical left/right correctly, so `LEFT_*`/`RIGHT_*` map straight to
 *   `left_*`/`right_*`. The native fixture is what guards this assumption.
 *
 * Per-keypoint confidence is passed through **unmodified**; the
 * `MIN_KEYPOINT_CONFIDENCE` floor is applied downstream, not here
 * (`ADAPTER-CONTRACT.md` §4).
 */
class MlKitPoseAdapter {
    /**
     * The frozen transform. Pure (no ML Kit dependency on the call), so the
     * Layer-1 regression test can replay a recorded skeleton through the exact
     * path live capture uses. [imageWidth]/[imageHeight] are the
     * rotation-corrected dimensions of the image ML Kit analysed — for a static
     * bitmap, its width/height; for a live frame, the upright dimensions after
     * the analysis rotation is applied.
     */
    fun adapt(skeleton: MlKitSkeleton, imageWidth: Int, imageHeight: Int): Keypoints {
        val w = imageWidth.toDouble()
        val h = imageHeight.toDouble()
        // normalize, then mirror (x) and y-flip (y), each applied exactly once.
        fun project(j: MlKitJoint): Keypoint =
            Keypoint(x = 1.0 - j.x / w, y = 1.0 - j.y / h, confidence = j.confidence)
        return Keypoints(
            leftShoulder = project(skeleton.leftShoulder),
            leftElbow = project(skeleton.leftElbow),
            leftWrist = project(skeleton.leftWrist),
            rightShoulder = project(skeleton.rightShoulder),
            rightElbow = project(skeleton.rightElbow),
            rightWrist = project(skeleton.rightWrist),
        )
    }
}

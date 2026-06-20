package com.skylinetrailcomputing.semaphore

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.skylinetrailcomputing.semaphore.core.MlKitJoint
import com.skylinetrailcomputing.semaphore.core.MlKitPoseAdapter
import com.skylinetrailcomputing.semaphore.core.MlKitSkeleton
import com.skylinetrailcomputing.semaphore.core.Mode
import com.skylinetrailcomputing.semaphore.core.NativeFixtures
import com.skylinetrailcomputing.semaphore.core.SharedFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layer-1 native-fixture regression (Issue #22, Epic 3 — per-commit, emulator-free).
 *
 * Replays the recorded ML Kit skeletons (`src/test/resources/mlkit_skeletons.json`,
 * captured during Layer-2 calibration on the committed `shared/native_fixtures`
 * images) through `MlKitPoseAdapter` and asserts each pose satisfies its
 * `invariants.json` relations and decodes to the expected position ids. This is
 * the fast guard that the Android adapter keeps applying the normalize + two flips
 * correctly; `MlKitCalibrationTest` is the slower, device-only Layer-2 pass that
 * *pins* them against the live estimator. The Swift `VisionAdapterTests` is the
 * iOS counterpart.
 */
class MlKitAdapterTest {
    private val adapter = MlKitPoseAdapter()

    /**
     * One recorded ML Kit skeleton: the six joints in ML Kit's native frame
     * (image-pixel, top-left origin) plus the image dimensions captured alongside
     * them, so Layer-1 replays the exact normalize the live path performs.
     */
    private data class RecordedPose(
        @SerializedName("image_width") val imageWidth: Int,
        @SerializedName("image_height") val imageHeight: Int,
        @SerializedName("left_shoulder") val leftShoulder: List<Double>,
        @SerializedName("left_elbow") val leftElbow: List<Double>,
        @SerializedName("left_wrist") val leftWrist: List<Double>,
        @SerializedName("right_shoulder") val rightShoulder: List<Double>,
        @SerializedName("right_elbow") val rightElbow: List<Double>,
        @SerializedName("right_wrist") val rightWrist: List<Double>,
    )

    private data class RecordedSkeletons(val skeletons: Map<String, RecordedPose>)

    private fun loadRecorded(): Map<String, RecordedPose> {
        val stream =
            javaClass.getResourceAsStream("/mlkit_skeletons.json")
                ?: error("mlkit_skeletons.json not found on the test classpath")
        return stream.reader().use { Gson().fromJson(it, RecordedSkeletons::class.java).skeletons }
    }

    private fun skeletonOf(rec: RecordedPose): MlKitSkeleton {
        fun joint(v: List<Double>) = MlKitJoint(v[0], v[1], v[2])
        return MlKitSkeleton(
            leftShoulder = joint(rec.leftShoulder),
            leftElbow = joint(rec.leftElbow),
            leftWrist = joint(rec.leftWrist),
            rightShoulder = joint(rec.rightShoulder),
            rightElbow = joint(rec.rightElbow),
            rightWrist = joint(rec.rightWrist),
        )
    }

    @Test
    fun recordedSkeletonsSatisfyInvariantsAndDecode() {
        val fixtures = SharedFiles.load<NativeFixtures>("native_fixtures/invariants.json")
        assertFalse("no poses loaded", fixtures.poses.isEmpty())
        val recorded = loadRecorded()
        val decoder = referenceDecoder()

        for (pose in fixtures.poses) {
            val rec =
                requireNotNull(recorded[pose.name]) { "no recorded ML Kit skeleton for ${pose.name}" }
            val keypoints = adapter.adapt(skeletonOf(rec), rec.imageWidth, rec.imageHeight)

            for (inv in pose.invariants) {
                val label = "${pose.name}: ${inv.lhs} ${inv.kind} ${inv.rhs}"
                val passed =
                    requireNotNull(evaluateInvariant(inv, keypoints)) { "malformed invariant $label" }
                assertTrue("adapted recorded skeleton violates $label", passed)
            }

            val result = decoder.decodeFrame(keypoints, Mode.LETTERS)
            assertEquals(
                "recorded ${pose.name} decodes to wrong position ids",
                listOf<Int?>(pose.expectedPositionIds.left, pose.expectedPositionIds.right),
                result.ids,
            )
        }
    }
}

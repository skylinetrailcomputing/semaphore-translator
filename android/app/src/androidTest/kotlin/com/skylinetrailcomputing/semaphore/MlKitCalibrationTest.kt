package com.skylinetrailcomputing.semaphore

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.gson.Gson
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.skylinetrailcomputing.semaphore.capture.toMlKitSkeleton
import com.skylinetrailcomputing.semaphore.core.MlKitJoint
import com.skylinetrailcomputing.semaphore.core.MlKitPoseAdapter
import com.skylinetrailcomputing.semaphore.core.MlKitSkeleton
import com.skylinetrailcomputing.semaphore.core.NativeFixtures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer-2 native-fixture calibration (Issue #22, Epic 3 — the flip-pinning pass).
 *
 * Runs the **real** ML Kit body-pose estimator on the committed
 * `shared/native_fixtures/pose_*.png` images (packaged as androidTest assets),
 * pushes the result through `MlKitPoseAdapter`, and asserts `invariants.json`.
 * This is the only thing that pins the adapter's normalize + y-flip + horizontal
 * mirror against live estimator output — `test_vectors.json` can't, since it is
 * defined as the adapter's *output* (`NATIVE-FIXTURES.md` §1). The Swift
 * `VisionCalibrationTests` is the iOS counterpart.
 *
 * **This is the repo's first instrumented test** — ML Kit needs the Android
 * runtime, so it runs on a device/emulator via `connectedAndroidTest`, NOT in the
 * per-commit JVM suite. Scoped as one-time calibration. The skeleton it logs
 * (tag `MlKitCalibration`) is frozen into `mlkit_skeletons.json` to feed the
 * camera-free Layer-1 regression (`MlKitAdapterTest`) — the device analogue of
 * iOS's `extract_vision_skeleton.swift`.
 */
@RunWith(AndroidJUnit4::class)
class MlKitCalibrationTest {
    @Test
    fun liveMlKitSatisfiesInvariantsAndEmitsSkeleton() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixtures =
            context.assets.open("invariants.json").use { stream ->
                Gson().fromJson(stream.reader(), NativeFixtures::class.java)
            }
        assertFalse("no poses loaded", fixtures.poses.isEmpty())

        val adapter = MlKitPoseAdapter()
        val detector =
            PoseDetection.getClient(
                PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                    .build()
            )
        try {
            for (pose in fixtures.poses) {
                val bitmap =
                    context.assets.open(pose.image).use { BitmapFactory.decodeStream(it) }
                        ?: error("could not decode ${pose.image}")
                val input = InputImage.fromBitmap(bitmap, 0)
                // STREAM_MODE populates per-landmark inFrameLikelihood (single-image
                // mode leaves it 0.0); feed the frame a few times so the tracker
                // stabilizes before we read the confidences.
                var detected: Pose? = null
                repeat(8) { detected = Tasks.await(detector.process(input)) }
                val skeleton =
                    requireNotNull(detected?.toMlKitSkeleton()) {
                        "ML Kit returned no full upper-body skeleton for ${pose.image} " +
                            "(see NATIVE-FIXTURES.md §6 — swap in a detectable image)"
                    }
                val keypoints = adapter.adapt(skeleton, input.width, input.height)

                // Emit the captured native skeleton so it can be frozen into
                // app/src/test/resources/mlkit_skeletons.json (the Layer-1 fixture).
                Log.i(TAG, skeletonJson(pose.name, skeleton, input.width, input.height))

                for (inv in pose.invariants) {
                    val label = "${pose.name}: ${inv.lhs} ${inv.kind} ${inv.rhs}"
                    val passed =
                        requireNotNull(evaluateInvariant(inv, keypoints)) {
                            "malformed invariant $label"
                        }
                    assertTrue("live ML Kit output violates $label", passed)
                }
            }
        } finally {
            detector.close()
        }
    }

    /** Render one captured skeleton as the JSON block frozen into the Layer-1 fixture. */
    private fun skeletonJson(name: String, s: MlKitSkeleton, width: Int, height: Int): String {
        fun row(label: String, j: MlKitJoint) =
            "      \"%s\": [%.1f, %.1f, %.4f]".format(label, j.x, j.y, j.confidence)
        return buildString {
            append("CAPTURED_SKELETON \"$name\": {\n")
            append("      \"image_width\": $width,\n")
            append("      \"image_height\": $height,\n")
            append(row("left_shoulder", s.leftShoulder)).append(",\n")
            append(row("left_elbow", s.leftElbow)).append(",\n")
            append(row("left_wrist", s.leftWrist)).append(",\n")
            append(row("right_shoulder", s.rightShoulder)).append(",\n")
            append(row("right_elbow", s.rightElbow)).append(",\n")
            append(row("right_wrist", s.rightWrist)).append("\n")
            append("    }")
        }
    }

    private companion object {
        const val TAG = "MlKitCalibration"
    }
}

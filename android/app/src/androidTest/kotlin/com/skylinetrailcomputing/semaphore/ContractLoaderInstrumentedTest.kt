package com.skylinetrailcomputing.semaphore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skylinetrailcomputing.semaphore.core.ContractLoader
import com.skylinetrailcomputing.semaphore.core.Keypoint
import com.skylinetrailcomputing.semaphore.core.Keypoints
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin

/**
 * Exercises the **production** [ContractLoader] against the app's staged assets
 * (Issue #37). The JVM `ConfigContractTest` deserializes the contract through the
 * test-only `SemaphoreConfig` DTO via `SharedFiles`, and the parity harness builds
 * its decoder via `referenceDecoder()` (also a `SharedFiles` mirror) — so a
 * key-string typo or an asset-staging (`copySharedContract`) plumbing bug in the
 * *shipping* `ContractLoader.makeCommitTiming` / `makeDecoder` would slip past
 * both.
 *
 * `makeCommitTiming` / `makeDecoder` take a `Context` and read `context.assets`,
 * which only exists on a real Android runtime, so this is instrumented (the
 * `targetContext` is the app under test, whose assets carry the staged
 * `shared` JSON contract). It runs via `connectedAndroidTest` alongside
 * `MlKitCalibrationTest` and adds no new test dependency (Robolectric avoided).
 *
 * Unlike the JVM suites it cannot reach the host `shared/` via `SharedFiles`
 * (`user.dir` is a host path; this runs on-device), so the decoder smoke uses
 * hand-built keypoints rather than the `test_vectors.json` fixtures.
 */
@RunWith(AndroidJUnit4::class)
class ContractLoaderInstrumentedTest {
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    /** Pins the committer-timing values on the production asset path. */
    @Test
    fun makeCommitTimingFromAppAssets() {
        val timing = ContractLoader.makeCommitTiming(appContext)
        assertEquals(5, timing.smoothingWindow)
        assertEquals(600.0, timing.commitHoldMs, 0.0)
        assertEquals(200.0, timing.interCharGapMs, 0.0)
    }

    /**
     * The shipping decoder loader. Classifying a hand-built REST (both arms straight
     * down) and an 'A' pose proves `makeDecoder` assembled a functionally-correct
     * decoder from the staged alphabet — the order-insensitive symbol pairs incl.
     * the REST control signal, and the octant angles — not merely that the JSON
     * parses. Post-adapter frame: y-up, signer's perspective; angle CCW from +x, so
     * id 0 (straight down) is −90°, id 1 (down-right) is −45° (see the alphabet
     * `_position_model`). REST = (0, 0); A = (0, 1).
     */
    @Test
    fun makeDecoderFromAppAssetsClassifiesCanonicalPoses() {
        val decoder = ContractLoader.makeDecoder(appContext)
        assertEquals("REST", decoder.classify(pose(leftAngle = -90.0, rightAngle = -90.0)))
        assertEquals("A", decoder.classify(pose(leftAngle = -90.0, rightAngle = -45.0)))
    }

    /**
     * Six keypoints placing each arm at the given octant angle, mirroring the
     * reference geometry in `shared/tools/_semaphore_ref.py` (`make_kp`): shoulders
     * fixed, wrist a normalized arm-length out along the angle, elbow at the
     * midpoint, full confidence. Only the shoulder/wrist drive `classify`.
     */
    private fun pose(leftAngle: Double, rightAngle: Double): Keypoints {
        fun arm(shoulder: Keypoint, angleDeg: Double): Triple<Keypoint, Keypoint, Keypoint> {
            val th = Math.toRadians(angleDeg)
            val wrist =
                Keypoint(shoulder.x + ARM_LEN * cos(th), shoulder.y + ARM_LEN * sin(th), 1.0)
            val elbow =
                Keypoint(
                    shoulder.x + 0.5 * ARM_LEN * cos(th),
                    shoulder.y + 0.5 * ARM_LEN * sin(th),
                    1.0,
                )
            return Triple(shoulder, elbow, wrist)
        }
        val (lSh, lEl, lWr) = arm(Keypoint(0.40, 0.55, 1.0), leftAngle)
        val (rSh, rEl, rWr) = arm(Keypoint(0.60, 0.55, 1.0), rightAngle)
        return Keypoints(
            leftShoulder = lSh,
            leftElbow = lEl,
            leftWrist = lWr,
            rightShoulder = rSh,
            rightElbow = rEl,
            rightWrist = rWr,
        )
    }

    private companion object {
        /** Normalized shoulder→wrist length (matches `_semaphore_ref.L_ARM`). */
        const val ARM_LEN = 0.22
    }
}

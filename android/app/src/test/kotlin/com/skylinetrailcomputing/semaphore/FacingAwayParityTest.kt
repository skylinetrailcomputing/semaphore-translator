package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.FacingAwayVectors
import com.skylinetrailcomputing.semaphore.core.Mode
import com.skylinetrailcomputing.semaphore.core.SharedFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Cross-platform parity for the Interpret **facing-away** flip (#78, ADR 0011).
 *
 * Replays `shared/facing_away_vectors.json`: each vector is one canonical
 * post-adapter pose with two decode branches. `facing_us` decodes the keypoints
 * as-is (the toggle off — the identity); `facing_away` decodes
 * `Keypoints.mirroredHorizontally()` — the **shipping** flip, the same call the
 * live Interpret path makes — and must land the pose's mirror twin. The Swift
 * `FacingAwayParityTests` asserts the same file, so green on both is the
 * byte-for-byte parity guarantee (spec §6) for the highest-blast-radius
 * (chirality) transform.
 *
 * This pins the production flip, not a test reimplementation: it is the only
 * fixture that exercises `mirroredHorizontally()`, exactly as the parity harness
 * runs the live decode path everywhere else.
 */
class FacingAwayParityTest {
    @Test
    fun facingAwayVectors() {
        val decoder = referenceDecoder()
        val vectors = SharedFiles.load<FacingAwayVectors>("facing_away_vectors.json")
        assertFalse("no facing-away vectors loaded", vectors.vectors.isEmpty())

        for (vector in vectors.vectors) {
            val mode = Mode.valueOf(vector.modeBefore)
            val kp = keypointsFrom(vector.keypoints)

            // facing_us — the toggle off: decode the keypoints unchanged.
            val us = decoder.decodeFrame(kp, mode)
            assertEquals("facing_us emit for ${vector.name}", vector.facingUs.expected, us.emit)
            assertEquals(
                "facing_us ids for ${vector.name}",
                vector.facingUs.expectedPositionIds,
                us.ids,
            )

            // facing_away — the toggle on: decode the SHIPPING flip's output.
            val away = decoder.decodeFrame(kp.mirroredHorizontally(), mode)
            assertEquals("facing_away emit for ${vector.name}", vector.facingAway.expected, away.emit)
            assertEquals(
                "facing_away ids for ${vector.name}",
                vector.facingAway.expectedPositionIds,
                away.ids,
            )
        }
    }

    /**
     * The flip is an involution: flipping twice is the identity decode. Guards a port
     * that implemented a y-flip or a left/right label swap in place of a clean
     * `x -> 1 - x` — both would pass *some* facing_away vectors but fail here.
     */
    @Test
    fun flipIsInvolution() {
        val decoder = referenceDecoder()
        val vectors = SharedFiles.load<FacingAwayVectors>("facing_away_vectors.json")

        for (vector in vectors.vectors) {
            val mode = Mode.valueOf(vector.modeBefore)
            val kp = keypointsFrom(vector.keypoints)
            val once = decoder.decodeFrame(kp, mode)
            val twice = decoder.decodeFrame(kp.mirroredHorizontally().mirroredHorizontally(), mode)
            assertEquals("flip not involutive (emit) for ${vector.name}", once.emit, twice.emit)
            assertEquals("flip not involutive (ids) for ${vector.name}", once.ids, twice.ids)
        }
    }
}

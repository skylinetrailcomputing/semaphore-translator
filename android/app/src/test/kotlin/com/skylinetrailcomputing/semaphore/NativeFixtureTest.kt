package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.Mode
import com.skylinetrailcomputing.semaphore.core.NativeFixtures
import com.skylinetrailcomputing.semaphore.core.SharedFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Native-fixture contract tests (Issue #20, Epic 3).
 *
 * `shared/native_fixtures/invariants.json` freezes the geometric assertions
 * every platform adapter's output must satisfy on a set of known poses -- the
 * only thing that can catch a wrong y-flip or mirror, since the parity harness
 * runs on the adapter's *output* (see `shared/native_fixtures/NATIVE-FIXTURES.md`).
 *
 * Until the per-platform adapters exist (#21/#22), these tests prove the contract
 * is well-formed and *has teeth*: the synthetic `reference_post_adapter` satisfies
 * every invariant and decodes to `expected_position_ids`, while a mirror-broken
 * and a y-flip-broken variant each violate at least one invariant. The Swift
 * `NativeFixtureTests` asserts the same against the same file.
 */
class NativeFixtureTest {
    private fun loadFixtures(): NativeFixtures {
        val fixtures = SharedFiles.load<NativeFixtures>("native_fixtures/invariants.json")
        assertFalse("no poses loaded", fixtures.poses.isEmpty())
        return fixtures
    }

    /**
     * The reference output for each pose satisfies all its invariants and decodes
     * to the declared position ids (cross-tie to the same decoder the parity
     * harness uses, via the typed `Keypoints` from #19).
     */
    @Test
    fun referenceSatisfiesInvariantsAndDecodes() {
        val decoder = referenceDecoder()
        for (pose in loadFixtures().poses) {
            val reference = keypointsFrom(pose.referencePostAdapter)

            for (inv in pose.invariants) {
                val label = "${pose.name}: ${inv.lhs} ${inv.kind} ${inv.rhs}"
                val passed = requireNotNull(evaluateInvariant(inv, reference)) { "malformed invariant $label" }
                assertTrue("reference violates $label", passed)
            }

            val result = decoder.decodeFrame(reference, Mode.LETTERS)
            assertEquals(
                "reference decodes to wrong position ids for ${pose.name}",
                listOf<Int?>(pose.expectedPositionIds.left, pose.expectedPositionIds.right),
                result.ids,
            )
        }
    }

    /**
     * Each invariant set has teeth: a wrong horizontal mirror and a wrong y-flip
     * must each break at least one invariant -- otherwise the fixture couldn't
     * catch the exact bug class Epic 3 risks.
     */
    @Test
    fun invariantsCatchBrokenFlips() {
        for (pose in loadFixtures().poses) {
            val reference = keypointsFrom(pose.referencePostAdapter)
            val broken = listOf("mirror" to mirrorBroken(reference), "y-flip" to yFlipBroken(reference))

            for ((label, variant) in broken) {
                val anyViolated =
                    pose.invariants.any { inv ->
                        requireNotNull(evaluateInvariant(inv, variant)) {
                            "malformed invariant in ${pose.name}"
                        } == false
                    }
                assertTrue("${pose.name}: $label-broken output passed every invariant", anyViolated)
            }
        }
    }
}

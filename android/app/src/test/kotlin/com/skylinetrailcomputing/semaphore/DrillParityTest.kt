package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.DrillContract
import com.skylinetrailcomputing.semaphore.core.DrillSession
import com.skylinetrailcomputing.semaphore.core.DrillVectors
import com.skylinetrailcomputing.semaphore.core.SharedFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Cross-platform DRILL parity harness (Epic 6a, #69).
 *
 * Drives every sequence in `shared/drill_vectors.json` through a [DrillSession]
 * and asserts, per step, the `expected_matched` / `expected_index` /
 * `expected_complete` the reference (`shared/tools/gen_drill_vectors.py`) recorded
 * -- plus the rolled-up final index / complete. The Swift `DrillParityTests`
 * asserts the SAME file, so green on both is the drill-engine analogue of the
 * per-frame parity guarantee (spec §6): the two engines are byte-for-byte twins of
 * each other and of the Python reference.
 *
 * The drill engine is strictly downstream of the committer, so these vectors are
 * plain committed-character streams -- no keypoints, no timing -- which is exactly
 * why the decode/adapter/commit core stays untouched (ADR 0007).
 */
class DrillParityTest {
    @Test
    fun drillVectors() {
        val vectors = SharedFiles.load<DrillVectors>("drill_vectors.json")
        assertFalse("no drill vectors loaded", vectors.sequenceVectors.isEmpty())

        for (sequence in vectors.sequenceVectors) {
            val session = DrillSession(sequence.targets)

            sequence.steps.forEachIndexed { i, step ->
                if (step.reset) {
                    session.reset()
                    val label = "${sequence.name}#$i:reset"
                    assertEquals("index mismatch for $label", step.expectedIndex, session.index)
                    assertEquals(
                        "complete mismatch for $label",
                        step.expectedComplete,
                        session.isComplete,
                    )
                } else {
                    val emit = requireNotNull(step.emit) { "emit step missing emit (${sequence.name}#$i)" }
                    val expectedMatched =
                        requireNotNull(step.expectedMatched) {
                            "emit step missing expected_matched (${sequence.name}#$i)"
                        }
                    val label = "${sequence.name}#$i:'$emit'"
                    val result = session.observe(emit)
                    assertEquals("matched mismatch for $label", expectedMatched, result.matched)
                    assertEquals("index mismatch for $label", step.expectedIndex, result.index)
                    assertEquals("complete mismatch for $label", step.expectedComplete, result.complete)
                }
            }

            assertEquals(
                "final index mismatch for ${sequence.name}",
                sequence.expectedFinalIndex,
                session.index,
            )
            assertEquals(
                "final complete mismatch for ${sequence.name}",
                sequence.expectedFinalComplete,
                session.isComplete,
            )
        }
    }

    /**
     * The descriptive contract file parses and pins the session states the engine
     * implements (ACTIVE -> COMPLETE). Anchors `drill_contract.json` to the code so
     * the frozen doc can't silently drift from the implementation.
     */
    @Test
    fun drillContractStates() {
        val contract = SharedFiles.load<DrillContract>("drill_contract.json")
        assertEquals("1.0", contract.version)
        assertEquals(listOf("ACTIVE", "COMPLETE"), contract.states)
    }
}

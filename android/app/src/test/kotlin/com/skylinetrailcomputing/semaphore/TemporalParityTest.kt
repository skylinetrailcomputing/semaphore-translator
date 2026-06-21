package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.Committer
import com.skylinetrailcomputing.semaphore.core.SharedFiles
import com.skylinetrailcomputing.semaphore.core.TemporalVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Cross-platform TEMPORAL parity harness (Issue #33 / #4.3, Epic 4).
 *
 * Drives every sequence in `shared/temporal_vectors.json` through
 * `classify` -> `Committer` (ADR 0004) and asserts, per frame, the votable
 * `expected_symbol` (a decoder-split cross-check) and the `expected_emit` (the
 * canonical assertion -- it pins *when* each commit lands, catching a
 * partial-window or off-by-one-gap port whose final string still matches), plus
 * the rolled-up `expected_committed`. The Swift `TemporalParityTests` asserts the
 * same file, so green on both is the temporal analogue of the per-frame parity
 * guarantee (spec §6): the two committers are byte-for-byte twins of each other
 * and of the Python reference the fixtures were generated against.
 */
class TemporalParityTest {
    @Test
    fun temporalVectors() {
        val decoder = referenceDecoder()
        val timing = referenceTiming()
        val vectors = SharedFiles.load<TemporalVectors>("temporal_vectors.json")
        assertFalse("no temporal vectors loaded", vectors.sequenceVectors.isEmpty())

        for (sequence in vectors.sequenceVectors) {
            assertEquals(
                "the committer always starts in LETTERS; ${sequence.name} starts elsewhere",
                "LETTERS",
                sequence.modeStart,
            )
            val committer = Committer(decoder, timing)
            val committed = StringBuilder()

            sequence.frames.forEachIndexed { i, frame ->
                val label = "${sequence.name}#$i@${frame.tMs}ms"
                if (frame.reset) {
                    committer.reset()
                    assertEquals("reset frame must emit '' ($label)", "", frame.expectedEmit)
                } else {
                    val map = requireNotNull(frame.keypoints) { "pose frame missing keypoints ($label)" }
                    val symbol = decoder.classify(keypointsFrom(map))
                    assertEquals("classify mismatch for $label", frame.expectedSymbol, symbol)
                    val emit = committer.process(symbol, frame.tMs)
                    assertEquals("emit mismatch for $label", frame.expectedEmit, emit)
                    committed.append(emit)
                }
            }

            assertEquals(
                "committed string mismatch for ${sequence.name}",
                sequence.expectedCommitted,
                committed.toString(),
            )
        }
    }
}

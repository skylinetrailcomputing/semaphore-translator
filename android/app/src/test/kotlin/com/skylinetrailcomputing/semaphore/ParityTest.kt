package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.Alphabet
import com.skylinetrailcomputing.semaphore.core.Mode
import com.skylinetrailcomputing.semaphore.core.SemaphoreConfig
import com.skylinetrailcomputing.semaphore.core.SemaphoreDecoder
import com.skylinetrailcomputing.semaphore.core.SharedFiles
import com.skylinetrailcomputing.semaphore.core.TestVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Cross-platform parity harness (Issue #14, Epic 2).
 *
 * Runs every vector in `shared/test_vectors.json` through the full
 * feature-extraction → classification → decode path and asserts the emitted
 * string and white-box position ids match the fixtures exactly. The Swift
 * `ParityTests` asserts against the same file, so green on both platforms is
 * the byte-for-byte parity guarantee (spec §6).
 *
 * Scope is downstream of the adapter: these vectors are the adapter's *output*
 * (post-mirror, post-y-flip, normalized, y-up, signer's perspective), so this
 * harness does not exercise the flips (Epic 3 native fixtures) or temporal
 * smoothing/commit timing (Epic 4).
 */
class ParityTest {
    private fun makeDecoder(): SemaphoreDecoder {
        val alphabet = SharedFiles.load<Alphabet>("semaphore_alphabet.json")
        val config = SharedFiles.load<SemaphoreConfig>("semaphore_config.json")
        return SemaphoreDecoder(alphabet, config)
    }

    @Test
    fun singlePoseVectors() {
        val decoder = makeDecoder()
        val vectors = SharedFiles.load<TestVectors>("test_vectors.json")
        assertFalse("no single-pose vectors loaded", vectors.singlePoseVectors.isEmpty())

        for (vector in vectors.singlePoseVectors) {
            val mode = Mode.valueOf(vector.modeBefore)
            val result = decoder.decodeFrame(vector.keypoints, mode)
            assertEquals("emit mismatch for ${vector.name}", vector.expected, result.emit)
            assertEquals(
                "position ids mismatch for ${vector.name}",
                vector.expectedPositionIds,
                result.ids,
            )
        }
    }

    @Test
    fun sequenceVectors() {
        val decoder = makeDecoder()
        val vectors = SharedFiles.load<TestVectors>("test_vectors.json")
        assertFalse("no sequence vectors loaded", vectors.sequenceVectors.isEmpty())

        for (sequence in vectors.sequenceVectors) {
            var mode = Mode.valueOf(sequence.modeStart)
            for (frame in sequence.frames) {
                val result = decoder.decodeFrame(frame.keypoints, mode)
                val label = "${sequence.name}/${frame.name}"
                assertEquals("emit mismatch for $label", frame.expected, result.emit)
                assertEquals(
                    "position ids mismatch for $label",
                    frame.expectedPositionIds,
                    result.ids,
                )
                mode = result.mode // thread decoder mode through the sequence
            }
        }
    }
}

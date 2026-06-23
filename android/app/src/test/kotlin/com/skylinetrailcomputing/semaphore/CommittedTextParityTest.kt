package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.CommittedText
import com.skylinetrailcomputing.semaphore.core.CommittedTextVectors
import com.skylinetrailcomputing.semaphore.core.SharedFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Cross-platform readout-buffer COALESCING parity harness (ADR 0010).
 *
 * Drives every case in `shared/committed_text_vectors.json` through the **shipping**
 * [CommittedText.append] (the same function the live layer calls at the commit site --
 * NOT a test-local reimplementation), folding each case's `emits` left-to-right from an
 * empty buffer, and asserts the `expected` the reference
 * (`shared/tools/gen_committed_text_vectors.py`) recorded. The Swift
 * `CommittedTextParityTests` asserts the SAME file, so green on both is the buffer-layer
 * analogue of the sanitise / drill / per-frame parity guarantees (spec §6): the two
 * appenders are byte-for-byte twins of each other and of the Python reference.
 *
 * The buffer is strictly downstream of the committer and never touches the
 * decode/adapter/commit core, so -- like the sanitise vectors -- these are plain
 * string->string fixtures (ADR 0010).
 */
class CommittedTextParityTest {
    @Test
    fun committedTextVectors() {
        val vectors = SharedFiles.load<CommittedTextVectors>("committed_text_vectors.json")
        assertFalse("no committed-text vectors loaded", vectors.cases.isEmpty())

        for (c in vectors.cases) {
            var buffer = ""
            for (token in c.emits) buffer = CommittedText.append(buffer, token)
            assertEquals("committed-text mismatch for ${c.name}", c.expected, buffer)
            // The rule's two invariants, independent of the exact expected string.
            assertFalse("${c.name} produced a leading space", buffer.startsWith(" "))
            assertFalse("${c.name} produced consecutive spaces", buffer.contains("  "))
        }
    }
}

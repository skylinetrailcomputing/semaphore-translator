package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.PassageSource
import com.skylinetrailcomputing.semaphore.core.SanitizeVectors
import com.skylinetrailcomputing.semaphore.core.SharedFiles
import com.skylinetrailcomputing.semaphore.core.SourceContract
import com.skylinetrailcomputing.semaphore.core.StockPassagesDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform SANITISE parity harness (Epic 6a, #71 / 6a-3).
 *
 * Drives every case in `shared/sanitize_vectors.json` through the **shipping**
 * [PassageSource.sanitize] (the same function the Learn custom/stock sources call --
 * NOT a test-local reimplementation) and asserts the `expected` the reference
 * (`shared/tools/gen_sanitize_vectors.py`) recorded. The Swift `SanitizeParityTests`
 * asserts the SAME file, so green on both is the source-layer analogue of the
 * drill / per-frame parity guarantees (spec §6): the two sanitisers are
 * byte-for-byte twins of each other and of the Python reference.
 *
 * The source is strictly upstream of the drill engine and never touches the
 * decode/adapter/commit core, so -- like the drill vectors -- these are plain
 * string->string fixtures (ADR 0008).
 */
class SanitizeParityTest {
    @Test
    fun sanitizeVectors() {
        val vectors = SharedFiles.load<SanitizeVectors>("sanitize_vectors.json")
        assertFalse("no sanitize vectors loaded", vectors.cases.isEmpty())
        val supported =
            SharedFiles.load<SourceContract>("source_contract.json")
                .supportedChars.joinToString("").toSet()

        for (c in vectors.cases) {
            val got = PassageSource.sanitize(c.input)
            assertEquals("sanitize mismatch for ${c.name}", c.expected, got)
            // The output never leaves the frozen supported alphabet.
            assertTrue(
                "${c.name} produced characters outside supported_chars",
                got.toSet().all { it in supported },
            )
            // Idempotent: sanitising the output is a no-op.
            assertEquals("sanitize not idempotent for ${c.name}", got, PassageSource.sanitize(got))
        }
    }

    /**
     * Parse-pin the descriptive contract to the code so the frozen doc can't drift:
     * the version, the exact 37-char output alphabet, and the cap (pinned to the
     * shipping [PassageSource.MAX_TARGETS], so the contract + both platforms agree).
     */
    @Test
    fun sourceContractPin() {
        val contract = SharedFiles.load<SourceContract>("source_contract.json")
        assertEquals("1.0", contract.version)
        val expected = listOf(" ") + (0..9).map { it.toString() } + ('A'..'Z').map { it.toString() }
        assertEquals(expected, contract.supportedChars)
        assertEquals(contract.maxTargets, PassageSource.MAX_TARGETS)
    }

    /**
     * The bundled sight-read passages must be authored already-clean and well-formed
     * -- the same invariants `gen_sanitize_vectors.py` asserts at authoring time, so a
     * malformed stock edit fails here too rather than shipping silently mangled.
     * (Parsed with Gson, not the shipping `org.json` loader, which is only a stub in
     * local JVM tests; that loader is exercised in the on-device smoke.)
     */
    @Test
    fun stockPassagesAreWellFormed() {
        val passages = SharedFiles.load<StockPassagesDto>("stock_passages.json").passages
        assertFalse("no stock passages loaded", passages.isEmpty())
        val ids = mutableSetOf<String>()
        for (p in passages) {
            assertTrue("empty stock id", p.id.isNotEmpty())
            assertTrue("duplicate stock id ${p.id}", ids.add(p.id))
            assertTrue("empty hint for ${p.id}", p.hint.isNotEmpty())
            assertTrue("empty text for ${p.id}", p.text.isNotEmpty())
            assertEquals(
                "stock text not already-clean for ${p.id}", p.text, PassageSource.sanitize(p.text))
            assertTrue(
                "stock text over cap for ${p.id}", p.text.length <= PassageSource.MAX_TARGETS)
            // Sight-read: the hint must not reveal the passage text.
            assertFalse(
                "stock hint reveals text for ${p.id}",
                PassageSource.sanitize(p.hint).contains(p.text))
        }
    }
}

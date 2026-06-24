package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.NumeralsGate
import com.skylinetrailcomputing.semaphore.core.PassageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Learn-only numerals gate (#127) -- the live-layer filter that
 * lets the user practise letters without switching into numeric mode. The gate sits
 * above the frozen, parity-vector-proven decode core ([NumeralsGate] never touches
 * the decoder/committer), so it has no shared fixture; it is a trivial pure function
 * kept in lockstep with iOS's `NumeralsGate` (see `NumeralsGateTests.swift`). These
 * pin the exact behaviour both ports must share. [PassageSource.containsDigit] -- the
 * signal the Learn passage surfaces gate on -- is covered here too.
 */
class NumeralsGateTest {
    @Test
    fun suppressDropsOnlyNumerals() {
        // With numerals suppressed, only the NUMERALS control pose becomes
        // indeterminate; every letter and REST passes through, so each readable pose
        // still decodes as its letter.
        assertNull(NumeralsGate.gate("NUMERALS", suppress = true))
        assertEquals("A", NumeralsGate.gate("A", suppress = true))
        assertEquals("J", NumeralsGate.gate("J", suppress = true))
        assertEquals("REST", NumeralsGate.gate("REST", suppress = true))
        assertNull(NumeralsGate.gate(null, suppress = true))
    }

    @Test
    fun notSuppressedIsIdentity() {
        // The default / numerals-on path is exact identity, including the NUMERALS pose.
        assertEquals("NUMERALS", NumeralsGate.gate("NUMERALS", suppress = false))
        assertEquals("A", NumeralsGate.gate("A", suppress = false))
        assertEquals("REST", NumeralsGate.gate("REST", suppress = false))
        assertNull(NumeralsGate.gate(null, suppress = false))
    }

    @Test
    fun containsDigit() {
        assertTrue(PassageSource.containsDigit("ROOM 101"))
        assertTrue(PassageSource.containsDigit("0"))
        assertFalse(PassageSource.containsDigit("HELLO WORLD"))
        assertFalse(PassageSource.containsDigit(""))
    }
}

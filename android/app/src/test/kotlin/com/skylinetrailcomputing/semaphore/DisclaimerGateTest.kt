package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.SharedFiles
import com.skylinetrailcomputing.semaphore.ui.DisclaimerDocument
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the first-launch disclaimer gate's logic ([6b-9], #87): the
 * SHA-256 consent hash and the `needsConsent` decision. Reads the canonical
 * `shared/disclaimer.json` directly (the #14 single-source posture) so the bytes
 * hashed here are the exact bytes bundled into the app. Stays on the pure
 * companion functions — `decode`/`loadFromAssets` use `org.json`, which isn't
 * available in a local-JVM unit test (it's exercised on-device at smoke). The iOS
 * twin is `DisclaimerGateTests`; both pin the same empty-input vector so the two
 * platforms' hashes agree byte-for-byte.
 */
class DisclaimerGateTest {
    private val disclaimerBytes: ByteArray =
        File(SharedFiles.directory, "disclaimer.json").readBytes()

    /**
     * Empty-input SHA-256 — a fixed vector that pins the algorithm and the
     * lowercase, zero-padded hex format, so iOS and Android derive identical
     * consent keys from identical bytes. Independent of the doc's content.
     */
    @Test
    fun sha256HexMatchesKnownVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DisclaimerDocument.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun contentHashIsDeterministicLowercaseHex() {
        val first = DisclaimerDocument.sha256Hex(disclaimerBytes)
        val second = DisclaimerDocument.sha256Hex(disclaimerBytes)
        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
    }

    @Test
    fun needsConsentDecision() {
        val hash = DisclaimerDocument.sha256Hex(disclaimerBytes)
        // Never accepted, and a stale hash from an older doc, both re-prompt;
        // the matching hash skips the gate.
        assertTrue(DisclaimerDocument.needsConsent("", hash))
        assertTrue(DisclaimerDocument.needsConsent("deadbeef", hash))
        assertFalse(DisclaimerDocument.needsConsent(hash, hash))
    }
}

import XCTest

@testable import SemaphoreTranslator

/// Unit tests for the first-launch disclaimer gate's logic ([6b-9], #87): the
/// SHA-256 consent hash and the `needsConsent` decision. Reads the canonical
/// `shared/disclaimer.json` directly (the #14 single-source posture) so the bytes
/// hashed here are the exact bytes bundled into the app. The Android twin is
/// `DisclaimerGateTest`; both pin the same empty-input vector so the two
/// platforms' hashes agree byte-for-byte.
final class DisclaimerGateTests: XCTestCase {
    private func disclaimerData() throws -> Data {
        try Data(contentsOf: SharedFiles.directory.appendingPathComponent("disclaimer.json"))
    }

    /// Empty-input SHA-256 — a fixed vector that pins the algorithm and the
    /// lowercase, zero-padded hex format, so iOS and Android derive identical
    /// consent keys from identical bytes. Independent of the doc's content, so it
    /// never needs updating when the disclaimer text changes.
    func testSha256HexMatchesKnownVector() {
        XCTAssertEqual(
            DisclaimerDocument.sha256Hex(Data()),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    func testContentHashIsDeterministicLowercaseHex() throws {
        let data = try disclaimerData()
        let first = DisclaimerDocument.sha256Hex(data)
        let second = DisclaimerDocument.sha256Hex(data)
        XCTAssertEqual(first, second)
        XCTAssertEqual(first.count, 64)
        XCTAssertTrue(first.allSatisfy { "0123456789abcdef".contains($0) })
        // The decoded doc's contentHash is exactly this hash over its raw bytes.
        XCTAssertEqual(try DisclaimerDocument.decode(data).contentHash, first)
    }

    func testDecodeReadsTheDisplayedFields() throws {
        let doc = try DisclaimerDocument.decode(disclaimerData())
        XCTAssertFalse(doc.version.isEmpty)
        XCTAssertFalse(doc.title.isEmpty)
        XCTAssertFalse(doc.body.isEmpty)
        XCTAssertFalse(doc.agreement.isEmpty)
        XCTAssertFalse(doc.acceptLabel.isEmpty)
        XCTAssertEqual(doc.eula.scheme, "https")
        XCTAssertEqual(doc.privacy.scheme, "https")
    }

    func testNeedsConsentDecision() throws {
        let hash = DisclaimerDocument.sha256Hex(try disclaimerData())
        // Never accepted, and a stale hash from an older doc, both re-prompt;
        // the matching hash skips the gate.
        XCTAssertTrue(DisclaimerDocument.needsConsent(acceptedHash: "", documentHash: hash))
        XCTAssertTrue(DisclaimerDocument.needsConsent(acceptedHash: "deadbeef", documentHash: hash))
        XCTAssertFalse(DisclaimerDocument.needsConsent(acceptedHash: hash, documentHash: hash))
    }
}

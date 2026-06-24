import XCTest

@testable import SemaphoreTranslator

/// Unit tests for the Learn-only numerals gate (#127) — the live-layer filter that
/// lets the user practise letters without switching into numeric mode. The gate sits
/// above the frozen, parity-vector-proven decode core (`SemaphoreDecoder` /
/// `Committer`), so it has no shared fixture; it is a trivial pure function kept in
/// lockstep with Android's `NumeralsGate` (see `NumeralsGateTest.kt`). These pin the
/// exact behaviour both ports must share. `PassageSource.containsDigit` — the signal
/// the Learn passage surfaces gate on — is covered here too.
final class NumeralsGateTests: XCTestCase {
    func testSuppressDropsOnlyNumerals() {
        // With numerals suppressed, only the NUMERALS control pose becomes
        // indeterminate; every letter and REST passes through unchanged, so each
        // readable pose still decodes as its letter.
        XCTAssertNil(NumeralsGate.gate("NUMERALS", suppress: true))
        XCTAssertEqual(NumeralsGate.gate("A", suppress: true), "A")
        XCTAssertEqual(NumeralsGate.gate("J", suppress: true), "J")
        XCTAssertEqual(NumeralsGate.gate("REST", suppress: true), "REST")
        XCTAssertNil(NumeralsGate.gate(nil, suppress: true))
    }

    func testNotSuppressedIsIdentity() {
        // The default / numerals-on path is exact identity, including the NUMERALS pose.
        XCTAssertEqual(NumeralsGate.gate("NUMERALS", suppress: false), "NUMERALS")
        XCTAssertEqual(NumeralsGate.gate("A", suppress: false), "A")
        XCTAssertEqual(NumeralsGate.gate("REST", suppress: false), "REST")
        XCTAssertNil(NumeralsGate.gate(nil, suppress: false))
    }

    func testContainsDigit() {
        XCTAssertTrue(PassageSource.containsDigit("ROOM 101"))
        XCTAssertTrue(PassageSource.containsDigit("0"))
        XCTAssertFalse(PassageSource.containsDigit("HELLO WORLD"))
        XCTAssertFalse(PassageSource.containsDigit(""))
    }
}

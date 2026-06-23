import XCTest

@testable import SemaphoreTranslator

/// Cross-platform readout-buffer COALESCING parity harness (ADR 0010).
///
/// Drives every case in `shared/committed_text_vectors.json` through the **shipping**
/// `CommittedText.append` (the same function the live layer calls at the commit site —
/// NOT a test-local reimplementation), folding each case's `emits` left-to-right from an
/// empty buffer, and asserts the `expected` the reference
/// (`shared/tools/gen_committed_text_vectors.py`) recorded. The Kotlin
/// `CommittedTextParityTest` asserts the SAME file, so green on both is the buffer-layer
/// analogue of the sanitise / drill / per-frame parity guarantees (spec §6): the two
/// appenders are byte-for-byte twins of each other and of the Python reference.
///
/// The buffer is strictly downstream of the committer and never touches the
/// decode/adapter/commit core, so — like the sanitise vectors — these are plain
/// string→string fixtures (ADR 0010).
final class CommittedTextParityTests: XCTestCase {
    func testCommittedTextVectors() throws {
        let vectors = try SharedFiles.load(
            CommittedTextVectors.self, "committed_text_vectors.json")
        XCTAssertFalse(vectors.cases.isEmpty, "no committed-text vectors loaded")

        for c in vectors.cases {
            var buffer = ""
            for token in c.emits { buffer = CommittedText.append(buffer, token) }
            XCTAssertEqual(buffer, c.expected, "committed-text mismatch for \(c.name)")
            // The rule's two invariants, independent of the exact expected string.
            XCTAssertFalse(buffer.hasPrefix(" "), "\(c.name) produced a leading space")
            XCTAssertFalse(buffer.contains("  "), "\(c.name) produced consecutive spaces")
        }
    }
}

import XCTest

@testable import SemaphoreTranslator

/// Cross-platform SANITISE parity harness (Epic 6a, #71 / 6a-3).
///
/// Drives every case in `shared/sanitize_vectors.json` through the **shipping**
/// `PassageSource.sanitize` (the same function the Learn custom/stock sources call —
/// NOT a test-local reimplementation) and asserts the `expected` the reference
/// (`shared/tools/gen_sanitize_vectors.py`) recorded. The Kotlin `SanitizeParityTest`
/// asserts the SAME file, so green on both is the source-layer analogue of the
/// drill / per-frame parity guarantees (spec §6): the two sanitisers are
/// byte-for-byte twins of each other and of the Python reference.
///
/// The source is strictly upstream of the drill engine and never touches the
/// decode/adapter/commit core, so — like the drill vectors — these are plain
/// string→string fixtures (ADR 0008).
final class SanitizeParityTests: XCTestCase {
    func testSanitizeVectors() throws {
        let vectors = try SharedFiles.load(SanitizeVectors.self, "sanitize_vectors.json")
        XCTAssertFalse(vectors.cases.isEmpty, "no sanitize vectors loaded")

        let supported = Set(
            try SharedFiles.load(SourceContract.self, "source_contract.json")
                .supportedChars.joined())

        for c in vectors.cases {
            let got = PassageSource.sanitize(c.input)
            XCTAssertEqual(got, c.expected, "sanitize mismatch for \(c.name)")
            // The output never leaves the frozen supported alphabet.
            XCTAssertTrue(
                Set(got).isSubset(of: supported),
                "\(c.name) produced characters outside supported_chars")
            // Idempotent: sanitising the output is a no-op.
            XCTAssertEqual(
                PassageSource.sanitize(got), got, "sanitize not idempotent for \(c.name)")
        }
    }

    /// Parse-pin the descriptive contract to the code so the frozen doc can't drift:
    /// the version, the exact 37-char output alphabet, and the cap (pinned to the
    /// shipping `PassageSource.maxTargets`, so the contract + both platforms agree).
    func testSourceContractPin() throws {
        let contract = try SharedFiles.load(SourceContract.self, "source_contract.json")
        XCTAssertEqual(contract.version, "1.0")
        let expectedChars = [
            " ", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        ]
        XCTAssertEqual(contract.supportedChars, expectedChars)
        XCTAssertEqual(PassageSource.maxTargets, contract.maxTargets)
    }

    /// The bundled sight-read passages must be authored already-clean and well-formed
    /// — the same invariants `gen_sanitize_vectors.py` asserts at authoring time, so a
    /// malformed stock edit fails here too rather than shipping silently mangled.
    func testStockPassagesAreWellFormed() throws {
        let stock = try SharedFiles.load(StockPassages.self, "stock_passages.json")
        XCTAssertFalse(stock.passages.isEmpty, "no stock passages loaded")
        var ids = Set<String>()
        for p in stock.passages {
            XCTAssertFalse(p.id.isEmpty, "empty stock id")
            XCTAssertTrue(ids.insert(p.id).inserted, "duplicate stock id \(p.id)")
            XCTAssertFalse(p.hint.isEmpty, "empty hint for \(p.id)")
            XCTAssertFalse(p.text.isEmpty, "empty text for \(p.id)")
            XCTAssertEqual(
                PassageSource.sanitize(p.text), p.text,
                "stock text not already-clean for \(p.id)")
            XCTAssertLessThanOrEqual(
                p.text.count, PassageSource.maxTargets, "stock text over cap for \(p.id)")
            // Sight-read: no WORD of the passage may appear in the sanitised hint
            // (word-level — stronger than a whole-string substring check, so a hint
            // can't leak part of a multi-word passage).
            let hintWords = Set(PassageSource.sanitize(p.hint).split(separator: " ").map(String.init))
            let textWords = Set(p.text.split(separator: " ").map(String.init))
            XCTAssertTrue(
                hintWords.isDisjoint(with: textWords),
                "stock hint reveals a passage word for \(p.id): \(hintWords.intersection(textWords))")
        }
    }
}

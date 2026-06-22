import XCTest

@testable import SemaphoreTranslator

/// Cross-platform DRILL parity harness (Epic 6a, #69).
///
/// Drives every sequence in `shared/drill_vectors.json` through a `DrillSession`
/// and asserts, per step, the `expected_matched` / `expected_index` /
/// `expected_complete` the reference (`shared/tools/gen_drill_vectors.py`)
/// recorded — plus the rolled-up final index / complete. The Kotlin
/// `DrillParityTest` asserts the SAME file, so green on both is the drill-engine
/// analogue of the per-frame parity guarantee (spec §6): the two engines are
/// byte-for-byte twins of each other and of the Python reference.
///
/// The drill engine is strictly downstream of the committer, so these vectors are
/// plain committed-character streams — no keypoints, no timing — which is exactly
/// why the decode/adapter/commit core stays untouched (ADR 0007).
final class DrillParityTests: XCTestCase {
    func testDrillVectors() throws {
        let vectors = try SharedFiles.load(DrillVectors.self, "drill_vectors.json")
        XCTAssertFalse(vectors.sequenceVectors.isEmpty, "no drill vectors loaded")

        for sequence in vectors.sequenceVectors {
            let session = DrillSession(targets: sequence.targets)

            for (i, step) in sequence.steps.enumerated() {
                if step.reset == true {
                    session.reset()
                    let label = "\(sequence.name)#\(i):reset"
                    XCTAssertEqual(session.index, step.expectedIndex, "index mismatch for \(label)")
                    XCTAssertEqual(
                        session.isComplete, step.expectedComplete, "complete mismatch for \(label)")
                    continue
                }
                let emit = try XCTUnwrap(step.emit, "emit step missing emit (\(sequence.name)#\(i))")
                let expectedMatched = try XCTUnwrap(
                    step.expectedMatched, "emit step missing expected_matched (\(sequence.name)#\(i))")
                let label = "\(sequence.name)#\(i):'\(emit)'"
                let result = session.observe(emit)
                XCTAssertEqual(result.matched, expectedMatched, "matched mismatch for \(label)")
                XCTAssertEqual(result.index, step.expectedIndex, "index mismatch for \(label)")
                XCTAssertEqual(
                    result.complete, step.expectedComplete, "complete mismatch for \(label)")
            }

            XCTAssertEqual(
                session.index, sequence.expectedFinalIndex,
                "final index mismatch for \(sequence.name)")
            XCTAssertEqual(
                session.isComplete, sequence.expectedFinalComplete,
                "final complete mismatch for \(sequence.name)")
        }
    }

    /// The descriptive contract file parses and pins the session states the engine
    /// implements (ACTIVE → COMPLETE). Anchors `drill_contract.json` to the code so
    /// the frozen doc can't silently drift from the implementation.
    func testDrillContractStates() throws {
        let contract = try SharedFiles.load(DrillContract.self, "drill_contract.json")
        XCTAssertEqual(contract.version, "1.0")
        XCTAssertEqual(contract.states, ["ACTIVE", "COMPLETE"])
    }
}

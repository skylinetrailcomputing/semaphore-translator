import XCTest

@testable import SemaphoreTranslator

/// Cross-platform parity harness for the Learn assist TRANSITION cues (#100 / 6a-13).
///
/// Drives every sequence in `shared/assist_cue_vectors.json` through the shipping
/// `AssistGeometry` (built via `ContractLoader.makeAssistGeometry` against
/// `Bundle.main`, like `AssistGeometryTests`, so a key-string / resource bug fails
/// here too) and asserts, per target index, the `implied_mode_before` and the full
/// ordered `cues` (kind + arm angles) the reference
/// (`shared/tools/gen_assist_cue_vectors.py`) recorded. The Kotlin
/// `AssistCueParityTest` asserts the SAME file, so green on both means the two cue
/// engines are byte-for-byte twins of each other and of the Python reference — and
/// the reference itself was cross-checked against the committer's `interpret` at
/// generation time, which is what binds the implied-mode walk to the committer.
///
/// The cues are strictly downstream of the alphabet and beside the decode/commit
/// core (ADR 0007 posture), so these vectors carry no keypoints and no timing —
/// only target characters and contract angles.
final class AssistCueParityTests: XCTestCase {
    func testAssistCueVectors() throws {
        let geometry = try ContractLoader.makeAssistGeometry()
        let vectors = try SharedFiles.load(AssistCueVectors.self, "assist_cue_vectors.json")
        XCTAssertFalse(vectors.sequenceVectors.isEmpty, "no assist cue vectors loaded")

        for sequence in vectors.sequenceVectors {
            for step in sequence.steps {
                let label = "\(sequence.name)#\(step.index)('\(step.target)')"

                let expectedMode = try XCTUnwrap(
                    Mode(rawValue: step.impliedModeBefore),
                    "unknown implied mode '\(step.impliedModeBefore)' for \(label)")
                XCTAssertEqual(
                    geometry.impliedMode(targets: sequence.targets, before: step.index),
                    expectedMode, "implied mode mismatch for \(label)")

                let expected = try step.cues.map { cue -> AssistCue in
                    let kind = try XCTUnwrap(
                        AssistCueKind(rawValue: cue.kind), "unknown cue kind '\(cue.kind)' for \(label)")
                    return AssistCue(
                        kind: kind,
                        pose: AssistPose(
                            leftAngleDeg: cue.leftAngleDeg, rightAngleDeg: cue.rightAngleDeg))
                }
                let actual = geometry.cues(targets: sequence.targets, index: step.index)
                XCTAssertEqual(actual, expected, "cue mismatch for \(label)")
            }
        }
    }
}

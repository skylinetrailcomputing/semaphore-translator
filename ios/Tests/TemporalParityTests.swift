import XCTest

@testable import SemaphoreTranslator

/// Cross-platform TEMPORAL parity harness (Issue #32 / #4.2, Epic 4).
///
/// Drives every sequence in `shared/temporal_vectors.json` through
/// `classify` → `Committer` (ADR 0004) and asserts, per frame, the votable
/// `expected_symbol` (a decoder-split cross-check) and the `expected_emit` (the
/// canonical assertion — it pins *when* each commit lands, catching a
/// partial-window or off-by-one-gap port whose final string still matches), plus
/// the rolled-up `expected_committed`. The Kotlin `TemporalParityTest` asserts
/// the same file, so green on both is the temporal analogue of the per-frame
/// parity guarantee (spec §6): the two committers are byte-for-byte twins of each
/// other and of the Python reference the fixtures were generated against.
final class TemporalParityTests: XCTestCase {
    func testTemporalVectors() throws {
        let decoder = try ReferenceDecoder.make()
        let timing = try referenceTiming()
        let vectors = try SharedFiles.load(TemporalVectors.self, "temporal_vectors.json")
        XCTAssertFalse(vectors.sequenceVectors.isEmpty, "no temporal vectors loaded")

        for sequence in vectors.sequenceVectors {
            XCTAssertEqual(
                sequence.modeStart, "LETTERS",
                "the committer always starts in LETTERS; \(sequence.name) starts elsewhere")
            let committer = Committer(decoder: decoder, timing: timing)
            var committed = ""

            for (i, frame) in sequence.frames.enumerated() {
                let label = "\(sequence.name)#\(i)@\(frame.tMs)ms"
                if frame.reset == true {
                    committer.reset()
                    XCTAssertEqual(frame.expectedEmit, "", "reset frame must emit '' (\(label))")
                    continue
                }
                let map = try XCTUnwrap(frame.keypoints, "pose frame missing keypoints (\(label))")
                let kp = try makeKeypoints(from: map, label)
                let symbol = decoder.classify(kp)
                XCTAssertEqual(symbol, frame.expectedSymbol, "classify mismatch for \(label)")
                let emit = committer.process(symbol, at: frame.tMs)
                XCTAssertEqual(emit, frame.expectedEmit, "emit mismatch for \(label)")
                committed += emit
            }

            XCTAssertEqual(
                committed, sequence.expectedCommitted,
                "committed string mismatch for \(sequence.name)")
        }
    }
}

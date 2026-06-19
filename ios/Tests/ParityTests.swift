import XCTest

@testable import SemaphoreTranslator

/// Cross-platform parity harness (Issue #14, Epic 2).
///
/// Runs every vector in `shared/test_vectors.json` through the full
/// feature-extraction → classification → decode path and asserts the emitted
/// string and white-box position ids match the fixtures exactly. The Kotlin
/// `ParityTest` asserts against the same file, so green on both platforms is
/// the byte-for-byte parity guarantee (spec §6).
///
/// Scope is downstream of the adapter: these vectors are the adapter's *output*
/// (post-mirror, post-y-flip, normalized, y-up, signer's perspective), so this
/// harness does not exercise the flips (Epic 3 native fixtures) or temporal
/// smoothing/commit timing (Epic 4).
final class ParityTests: XCTestCase {
    private func makeDecoder() throws -> SemaphoreDecoder {
        let alphabet = try SharedFiles.load(Alphabet.self, "semaphore_alphabet.json")
        let config = try SharedFiles.load(SemaphoreConfig.self, "semaphore_config.json")
        return SemaphoreDecoder(alphabet: alphabet, config: config)
    }

    func testSinglePoseVectors() throws {
        let decoder = try makeDecoder()
        let vectors = try SharedFiles.load(TestVectors.self, "test_vectors.json")
        XCTAssertFalse(vectors.singlePoseVectors.isEmpty, "no single-pose vectors loaded")

        for vector in vectors.singlePoseVectors {
            let mode = try XCTUnwrap(Mode(rawValue: vector.modeBefore), "bad mode in \(vector.name)")
            let result = decoder.decodeFrame(vector.keypoints, mode: mode)
            XCTAssertEqual(result.emit, vector.expected, "emit mismatch for \(vector.name)")
            XCTAssertEqual(
                result.ids, vector.expectedPositionIds, "position ids mismatch for \(vector.name)")
        }
    }

    func testSequenceVectors() throws {
        let decoder = try makeDecoder()
        let vectors = try SharedFiles.load(TestVectors.self, "test_vectors.json")
        XCTAssertFalse(vectors.sequenceVectors.isEmpty, "no sequence vectors loaded")

        for sequence in vectors.sequenceVectors {
            var mode = try XCTUnwrap(
                Mode(rawValue: sequence.modeStart), "bad mode_start in \(sequence.name)")
            for frame in sequence.frames {
                let result = decoder.decodeFrame(frame.keypoints, mode: mode)
                let label = "\(sequence.name)/\(frame.name)"
                XCTAssertEqual(result.emit, frame.expected, "emit mismatch for \(label)")
                XCTAssertEqual(
                    result.ids, frame.expectedPositionIds, "position ids mismatch for \(label)")
                mode = result.mode  // thread decoder mode through the sequence
            }
        }
    }
}

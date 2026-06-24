import XCTest

@testable import SemaphoreTranslator

/// Cross-platform parity for the Interpret **facing-away** flip (#78, ADR 0011).
///
/// Replays `shared/facing_away_vectors.json`: each vector is one canonical
/// post-adapter pose with two decode branches. `facing_us` decodes the keypoints
/// as-is (the toggle off — the identity); `facing_away` decodes
/// `Keypoints.mirroredHorizontally()` — the **shipping** flip, the same call the
/// live Interpret path makes — and must land the pose's mirror twin. The Kotlin
/// `FacingAwayParityTest` asserts the same file, so green on both is the
/// byte-for-byte parity guarantee (spec §6) for the highest-blast-radius
/// (chirality) transform.
///
/// This pins the production flip, not a test reimplementation: it is the only
/// fixture that exercises `mirroredHorizontally()`, exactly as the parity harness
/// runs the live decode path everywhere else.
final class FacingAwayParityTests: XCTestCase {
    func testFacingAwayVectors() throws {
        let decoder = try ReferenceDecoder.make()
        let vectors = try SharedFiles.load(FacingAwayVectors.self, "facing_away_vectors.json")
        XCTAssertFalse(vectors.vectors.isEmpty, "no facing-away vectors loaded")

        for vector in vectors.vectors {
            let mode = try XCTUnwrap(Mode(rawValue: vector.modeBefore), "bad mode in \(vector.name)")
            let kp = try makeKeypoints(from: vector.keypoints, vector.name)

            // facing_us — the toggle off: decode the keypoints unchanged.
            let us = decoder.decodeFrame(kp, mode: mode)
            XCTAssertEqual(us.emit, vector.facingUs.expected, "facing_us emit for \(vector.name)")
            XCTAssertEqual(
                us.ids, vector.facingUs.expectedPositionIds, "facing_us ids for \(vector.name)")

            // facing_away — the toggle on: decode the SHIPPING flip's output.
            let away = decoder.decodeFrame(kp.mirroredHorizontally(), mode: mode)
            XCTAssertEqual(
                away.emit, vector.facingAway.expected, "facing_away emit for \(vector.name)")
            XCTAssertEqual(
                away.ids, vector.facingAway.expectedPositionIds, "facing_away ids for \(vector.name)")
        }
    }

    /// The flip is an involution: flipping twice is the identity decode. Guards a port
    /// that implemented a y-flip or a left/right label swap in place of a clean
    /// `x → 1 − x` — both would pass *some* facing_away vectors but fail here.
    func testFlipIsInvolution() throws {
        let decoder = try ReferenceDecoder.make()
        let vectors = try SharedFiles.load(FacingAwayVectors.self, "facing_away_vectors.json")

        for vector in vectors.vectors {
            let mode = try XCTUnwrap(Mode(rawValue: vector.modeBefore))
            let kp = try makeKeypoints(from: vector.keypoints, vector.name)
            let once = decoder.decodeFrame(kp, mode: mode)
            let twice = decoder.decodeFrame(
                kp.mirroredHorizontally().mirroredHorizontally(), mode: mode)
            XCTAssertEqual(twice.emit, once.emit, "flip not involutive (emit) for \(vector.name)")
            XCTAssertEqual(twice.ids, once.ids, "flip not involutive (ids) for \(vector.name)")
        }
    }
}

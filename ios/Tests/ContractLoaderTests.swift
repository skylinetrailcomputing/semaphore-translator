import XCTest

@testable import SemaphoreTranslator

/// Exercises the **production** `ContractLoader` against the real app bundle
/// (Issue #37). The config guard suite (`ConfigContractTests`) deserializes the
/// contract through the test-only `SemaphoreConfig` DTO via `SharedFiles`, and the
/// parity harness builds its decoder via `ReferenceDecoder.make()` (also a
/// `SharedFiles` mirror) — so a key-string typo or a bundle-resource plumbing bug
/// in the *shipping* `ContractLoader.makeCommitTiming` / `makeDecoder` would slip
/// past both. This closes that drift window: the test target hosts the app, so
/// `Bundle.main` is the app bundle and the `../shared/*.json` resources declared in
/// `ios/project.yml` are reachable exactly as they are on-device.
final class ContractLoaderTests: XCTestCase {
    /// The shipping committer-timing loader, parsed from the bundled
    /// `semaphore_config.json`. Pins the values so a key-string drift (the keys are
    /// `SMOOTHING_WINDOW` / `COMMIT_HOLD_MS` / `INTER_CHAR_GAP_MS`) or a missing
    /// resource fails loudly on the production path, not just the test DTO.
    func testMakeCommitTimingFromAppBundle() throws {
        let timing = try ContractLoader.makeCommitTiming()
        XCTAssertEqual(timing.smoothingWindow, 5)
        XCTAssertEqual(timing.commitHoldMs, 600)
        XCTAssertEqual(timing.interCharGapMs, 200)
    }

    /// The shipping decoder loader. Running every `single_pose_vectors` fixture
    /// through the **bundle-built** decoder proves the production path assembles a
    /// functionally-correct decoder — octant angles, the order-insensitive symbol
    /// pairs (incl. NUMERALS/REST), and the digit map all keyed correctly from the
    /// bundled alphabet — not merely that the JSON parses. (`SharedFiles` resolves
    /// the repo's `shared/` via `#filePath`, reachable from the simulator just as in
    /// `ParityTests`; only the decoder under test comes from `Bundle.main`.)
    func testMakeDecoderFromAppBundleMatchesFixtures() throws {
        let decoder = try ContractLoader.makeDecoder()
        let vectors = try SharedFiles.load(TestVectors.self, "test_vectors.json")
        XCTAssertFalse(vectors.singlePoseVectors.isEmpty, "no single-pose vectors loaded")

        for vector in vectors.singlePoseVectors {
            let mode = try XCTUnwrap(Mode(rawValue: vector.modeBefore), "bad mode in \(vector.name)")
            let kp = try makeKeypoints(from: vector.keypoints, vector.name)
            let result = decoder.decodeFrame(kp, mode: mode)
            XCTAssertEqual(result.emit, vector.expected, "emit mismatch for \(vector.name)")
            XCTAssertEqual(
                result.ids, vector.expectedPositionIds, "position ids mismatch for \(vector.name)")
        }
    }
}

import XCTest

@testable import SemaphoreTranslator

/// Layer-1 native-fixture regression (Issue #21, Epic 3 — per-commit, camera-free).
///
/// Replays the recorded Apple Vision skeletons (`Fixtures/vision_skeletons.json`,
/// captured during Layer-2 calibration on the committed `shared/native_fixtures`
/// images) through `VisionPoseAdapter` and asserts each pose satisfies its
/// `invariants.json` relations and decodes to the expected position ids. This is
/// the fast guard that the iOS adapter keeps applying the two flips correctly;
/// `VisionCalibrationTests` is the slower Layer-2 pass that *pins* them against
/// the live estimator. The Kotlin `NativeFixtureTest` is the Android counterpart.
final class VisionAdapterTests: XCTestCase {
    func testRecordedSkeletonsSatisfyInvariantsAndDecode() throws {
        let fixtures = try loadNativeFixtures()
        XCTAssertFalse(fixtures.poses.isEmpty, "no poses loaded")
        let recorded = try recordedVisionSkeletons()
        let adapter = VisionPoseAdapter()
        let decoder = try ReferenceDecoder.make()

        for pose in fixtures.poses {
            let map = try XCTUnwrap(
                recorded[pose.name], "no recorded Vision skeleton for \(pose.name)")
            let keypoints = adapter.adapt(try visionSkeleton(from: map, pose.name))

            for inv in pose.invariants {
                let label = "\(pose.name): \(inv.lhs) \(inv.kind) \(inv.rhs)"
                let passed = try XCTUnwrap(evaluate(inv, on: keypoints), "malformed invariant \(label)")
                XCTAssertTrue(passed, "adapted recorded skeleton violates \(label)")
            }

            let result = decoder.decodeFrame(keypoints, mode: .letters)
            let expected: [Int?] = [pose.expectedPositionIds.left, pose.expectedPositionIds.right]
            XCTAssertEqual(
                result.ids, expected, "recorded \(pose.name) decodes to wrong position ids")
        }
    }
}

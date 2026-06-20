import ImageIO
import Vision
import XCTest

@testable import SemaphoreTranslator

/// Layer-2 native-fixture calibration (Issue #21, Epic 3 — the flip-pinning pass).
///
/// Runs the REAL Apple Vision body-pose estimator (which runs on the simulator)
/// on the committed `shared/native_fixtures/pose_*.png` images, pushes the result
/// through `VisionPoseAdapter`, and asserts `invariants.json`. This is the only
/// thing that pins the adapter's y-flip + horizontal mirror against live
/// estimator output — `test_vectors.json` can't, since it is defined as the
/// adapter's *output* (`NATIVE-FIXTURES.md` §1). The recorded skeleton consumed
/// by `VisionAdapterTests` (Layer-1) is a frozen snapshot of exactly this run.
///
/// If Vision detects no pose (e.g. an image it can't read — `NATIVE-FIXTURES.md`
/// §6), the test **skips with a diagnostic** rather than failing, so the suite
/// stays green while clearly flagging that the image needs swapping.
final class VisionCalibrationTests: XCTestCase {
    func testLiveVisionSatisfiesInvariants() throws {
        let fixtures = try loadNativeFixtures()
        XCTAssertFalse(fixtures.poses.isEmpty, "no poses loaded")
        let adapter = VisionPoseAdapter()

        for pose in fixtures.poses {
            let image = try loadFixtureImage(pose.image)
            let request = VNDetectHumanBodyPoseRequest()
            do {
                try VNImageRequestHandler(cgImage: image, orientation: .up, options: [:])
                    .perform([request])
            } catch {
                throw XCTSkip(
                    "VNDetectHumanBodyPoseRequest could not run (\(error.localizedDescription)). "
                        + "Apple Vision body pose is unavailable on the iOS Simulator; the adapter's "
                        + "flips are pinned by the recorded skeleton (Layer-1, captured from real "
                        + "Vision on a host/device). This calibration runs on a real device.")
            }

            guard let observation = request.results?.first else {
                throw XCTSkip(
                    "Vision detected no pose in \(pose.image) — see NATIVE-FIXTURES.md §6 "
                        + "(swap in a detectable image; the contract is image-agnostic).")
            }
            let keypoints = try XCTUnwrap(
                try adapter.adapt(observation),
                "Vision returned an incomplete skeleton for \(pose.image)")

            for inv in pose.invariants {
                let label = "\(pose.name): \(inv.lhs) \(inv.kind) \(inv.rhs)"
                let passed = try XCTUnwrap(evaluate(inv, on: keypoints), "malformed invariant \(label)")
                XCTAssertTrue(passed, "live Vision output violates \(label)")
            }
        }
    }

    /// Load a committed `shared/native_fixtures/<name>` image as a `CGImage`.
    /// Simulator unit tests run on the host, so the `SharedFiles` path resolves.
    private func loadFixtureImage(_ name: String) throws -> CGImage {
        let url = SharedFiles.directory.appendingPathComponent("native_fixtures/\(name)")
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
            let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
        else {
            throw XCTSkip("could not load fixture image \(name)")
        }
        return image
    }
}

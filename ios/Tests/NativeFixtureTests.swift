import XCTest

@testable import SemaphoreTranslator

/// Native-fixture contract tests (Issue #20, Epic 3).
///
/// `shared/native_fixtures/invariants.json` freezes the geometric assertions
/// every platform adapter's output must satisfy on a set of known poses — the
/// only thing that can catch a wrong y-flip or mirror, since the parity harness
/// runs on the adapter's *output* (see `shared/native_fixtures/NATIVE-FIXTURES.md`).
///
/// Until the per-platform adapters exist ([#21]/[#22]), these tests prove the
/// contract is well-formed and *has teeth*: the synthetic `reference_post_adapter`
/// satisfies every invariant and decodes to `expected_position_ids`, while a
/// mirror-broken and a y-flip-broken variant each violate at least one invariant.
/// The Kotlin `NativeFixtureTest` asserts the same against the same file.
final class NativeFixtureTests: XCTestCase {
    private func loadFixtures() throws -> NativeFixtures {
        let fixtures = try SharedFiles.load(NativeFixtures.self, "native_fixtures/invariants.json")
        XCTAssertFalse(fixtures.poses.isEmpty, "no poses loaded")
        return fixtures
    }

    /// The reference output for each pose satisfies all its invariants and
    /// decodes to the declared position ids (cross-tie to the same decoder the
    /// parity harness uses, via the typed `Keypoints` from #19).
    func testReferenceSatisfiesInvariantsAndDecodes() throws {
        let decoder = try ReferenceDecoder.make()
        for pose in try loadFixtures().poses {
            let reference = try makeKeypoints(from: pose.referencePostAdapter, pose.name)

            for inv in pose.invariants {
                let label = "\(pose.name): \(inv.lhs) \(inv.kind) \(inv.rhs)"
                let passed = try XCTUnwrap(evaluate(inv, on: reference), "malformed invariant \(label)")
                XCTAssertTrue(passed, "reference violates \(label)")
            }

            let result = decoder.decodeFrame(reference, mode: .letters)
            let expectedIds: [Int?] = [pose.expectedPositionIds.left, pose.expectedPositionIds.right]
            XCTAssertEqual(
                result.ids, expectedIds,
                "reference decodes to wrong position ids for \(pose.name)")
        }
    }

    /// Each invariant set has teeth: a wrong horizontal mirror and a wrong y-flip
    /// must each break at least one invariant — otherwise the fixture couldn't
    /// catch the exact bug class Epic 3 risks.
    func testInvariantsCatchBrokenFlips() throws {
        for pose in try loadFixtures().poses {
            let reference = try makeKeypoints(from: pose.referencePostAdapter, pose.name)

            for (label, broken) in [
                ("mirror", mirrorBroken(reference)), ("y-flip", yFlipBroken(reference)),
            ] {
                let anyViolated = try pose.invariants.contains { inv in
                    try XCTUnwrap(evaluate(inv, on: broken), "malformed invariant in \(pose.name)")
                        == false
                }
                XCTAssertTrue(
                    anyViolated, "\(pose.name): \(label)-broken output passed every invariant")
            }
        }
    }
}

import XCTest

@testable import SemaphoreTranslator

/// The "light shared check" for the contract-derived assist figure (#73 / 6a-5):
/// the rendered pose must match the frozen `semaphore_alphabet.json` angles. Two
/// halves — the target→angles mapping (built through the *shipping*
/// `ContractLoader.makeAssistGeometry` against `Bundle.main`, like
/// `ContractLoaderTests`, so a key-string / resource bug fails here too) and the
/// signer-frame-angle → screen-direction layout the `Canvas` draws. The Kotlin
/// twin is `AssistGeometryTest`; both assert the same numbers read from the same
/// `shared/` file, so the two figures agree by construction.
final class AssistGeometryTests: XCTestCase {
    private func geometry() throws -> AssistGeometry {
        try ContractLoader.makeAssistGeometry()
    }

    // MARK: - pose() against the contract angles

    func testLetterPosesMatchContractAngles() throws {
        let g = try geometry()
        // A = (left 0 = −90 down, right 1 = −45 down-right)
        XCTAssertEqual(g.pose(for: "A"), AssistPose(leftAngleDeg: -90, rightAngleDeg: -45))
        // R = (left 6 = 180 out-left, right 2 = 0 out-right)
        XCTAssertEqual(g.pose(for: "R"), AssistPose(leftAngleDeg: 180, rightAngleDeg: 0))
        // U = (left 5 = 135 up-left, right 3 = 45 up-right)
        XCTAssertEqual(g.pose(for: "U"), AssistPose(leftAngleDeg: 135, rightAngleDeg: 45))
        // G = (left 7 = −135 down-left, right 0 = −90 down)
        XCTAssertEqual(g.pose(for: "G"), AssistPose(leftAngleDeg: -135, rightAngleDeg: -90))
    }

    func testLowercaseTargetMapsLikeUppercase() throws {
        let g = try geometry()
        XCTAssertEqual(g.pose(for: "a"), g.pose(for: "A"))
    }

    func testDigitTargetsReverseTheDigitMap() throws {
        let g = try geometry()
        XCTAssertEqual(g.pose(for: "1"), g.pose(for: "A"))
        XCTAssertEqual(g.pose(for: "7"), g.pose(for: "G"))
        // K = 0 is the only non-sequential digit mapping — exercise it explicitly.
        XCTAssertEqual(g.pose(for: "0"), g.pose(for: "K"))
    }

    func testSpaceIsRest() throws {
        let g = try geometry()
        XCTAssertEqual(g.pose(for: " "), AssistPose(leftAngleDeg: -90, rightAngleDeg: -90))
    }

    func testUnsupportedTargetsHaveNoPose() throws {
        let g = try geometry()
        XCTAssertNil(g.pose(for: "!"))
        XCTAssertNil(g.pose(for: nil))
    }

    // MARK: - endpoints(): signer-frame angle → screen direction (mirrored front)

    func testEndpointsMapAnglesToScreenDirections() {
        // Out-right (0°): wrist to screen-right of the shoulder, same height.
        let outRight = AssistGeometry.endpoints(for: AssistPose(leftAngleDeg: 0, rightAngleDeg: 0))
        XCTAssertGreaterThan(outRight.rightWrist.x, outRight.rightShoulder.x)
        XCTAssertEqual(outRight.rightWrist.y, outRight.rightShoulder.y, accuracy: 1e-9)
        // Up (90°): smaller screen-y (display origin is top-left).
        let up = AssistGeometry.endpoints(for: AssistPose(leftAngleDeg: 90, rightAngleDeg: 90))
        XCTAssertLessThan(up.rightWrist.y, up.rightShoulder.y)
        // Out-left (180°): wrist to screen-left of the shoulder.
        let outLeft = AssistGeometry.endpoints(for: AssistPose(leftAngleDeg: 180, rightAngleDeg: 180))
        XCTAssertLessThan(outLeft.rightWrist.x, outLeft.rightShoulder.x)
        // Down (−90°): larger screen-y.
        let down = AssistGeometry.endpoints(for: AssistPose(leftAngleDeg: -90, rightAngleDeg: -90))
        XCTAssertGreaterThan(down.rightWrist.y, down.rightShoulder.y)
    }

    func testRightShoulderIsScreenRightUnderMirroredFront() {
        // The signer's right shoulder sits at greater screen-x than the left — the
        // mirrored-selfie convention the user copies (same as SkeletonOverlay).
        let pts = AssistGeometry.endpoints(for: AssistPose(leftAngleDeg: -90, rightAngleDeg: -90))
        XCTAssertGreaterThan(pts.rightShoulder.x, pts.leftShoulder.x)
    }
}

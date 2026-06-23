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

    func testDigitTargetsMapToTheirLetterPose() throws {
        // #100 un-suppressed digits: a digit's own pose is the letter pose that
        // produces it in numeric mode (the reverse of A=1..I=9, K=0). The NUMERALS
        // pre-cue from `cues(_:index:)` is what now conveys the mode-switch, so the
        // bare pose is no longer misleading on its own.
        let g = try geometry()
        XCTAssertEqual(g.pose(for: "1"), g.pose(for: "A"))  // 1 -> A pose
        XCTAssertEqual(g.pose(for: "7"), g.pose(for: "G"))  // 7 -> G pose
        XCTAssertEqual(g.pose(for: "0"), g.pose(for: "K"))  // 0 -> K pose
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

    /// An *asymmetric* pose (R: left out-left 180°, right out-right 0°) verifies
    /// each arm independently — a left/right shoulder swap is invisible when both
    /// arms share an angle, so the symmetric cases above can't catch it.
    func testEndpointsPlaceEachArmOnItsOwnShoulder() {
        let r = AssistGeometry.endpoints(for: AssistPose(leftAngleDeg: 180, rightAngleDeg: 0))
        XCTAssertLessThan(r.leftWrist.x, r.leftShoulder.x)  // left arm out to screen-left
        XCTAssertGreaterThan(r.rightWrist.x, r.rightShoulder.x)  // right arm out to screen-right
        XCTAssertEqual(r.leftWrist.y, r.leftShoulder.y, accuracy: 1e-9)
        XCTAssertEqual(r.rightWrist.y, r.rightShoulder.y, accuracy: 1e-9)
    }

    /// Pin one concrete coordinate so a drift in the `armLen` / `halfSpan` layout
    /// constants fails loudly (the direction checks alone wouldn't notice). For
    /// `(0°, 0°)`, mirrored-front: right shoulder at x = 0.5 + 0.13, right wrist a
    /// full arm-length (0.28) further right, both at the shoulder row y = 1 − 0.56.
    func testEndpointsPinConcreteCoordinates() {
        let pts = AssistGeometry.endpoints(for: AssistPose(leftAngleDeg: 0, rightAngleDeg: 0))
        XCTAssertEqual(pts.leftShoulder.x, 0.37, accuracy: 1e-9)
        XCTAssertEqual(pts.rightShoulder.x, 0.63, accuracy: 1e-9)
        XCTAssertEqual(pts.rightShoulder.y, 0.44, accuracy: 1e-9)
        XCTAssertEqual(pts.rightWrist.x, 0.91, accuracy: 1e-9)
        XCTAssertEqual(pts.rightWrist.y, 0.44, accuracy: 1e-9)
    }

    func testRightShoulderIsScreenRightUnderMirroredFront() {
        // The signer's right shoulder sits at greater screen-x than the left — the
        // mirrored-selfie convention the user copies (same as SkeletonOverlay).
        let pts = AssistGeometry.endpoints(for: AssistPose(leftAngleDeg: -90, rightAngleDeg: -90))
        XCTAssertGreaterThan(pts.rightShoulder.x, pts.leftShoulder.x)
    }
}

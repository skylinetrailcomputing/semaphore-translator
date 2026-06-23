import CoreGraphics
import Foundation

/// The pose of one semaphore target as two arm angles, in the **signer's**
/// perspective (degrees, CCW from `+x` = signer's right, y-up) — read straight
/// from the frozen `semaphore_alphabet.json`. The assist figure (#73 / 6a-5)
/// draws its arms at *exactly* these angles, so the teaching aid is
/// perspective-correct by construction and cannot drift from the contract.
struct AssistPose: Equatable {
    let leftAngleDeg: Double
    let rightAngleDeg: Double
}

/// The stick-figure layout for one `AssistPose`, in a normalized `[0,1]` box with
/// the **display** (screen, y-down) convention already applied — the same
/// signer→display mapping `SkeletonOverlay` uses for the mirrored front preview.
/// The view scales these by its canvas size; keeping the geometry here (not in the
/// `Canvas` closure) is what lets the unit test pin "rendered pose matches the
/// alphabet angles". The Android twin is `AssistGeometry.endpoints`.
struct AssistFigurePoints: Equatable {
    let neck: CGPoint
    let hip: CGPoint
    let head: CGPoint
    let leftShoulder: CGPoint
    let rightShoulder: CGPoint
    let leftWrist: CGPoint
    let rightWrist: CGPoint
}

/// Maps a drill target character to its semaphore arm pose and lays out the
/// contract-derived assist stick figure (#73 / 6a-5). A pure value type built from
/// the already-parsed alphabet maps (`ContractLoader.makeAssistGeometry`), so the
/// figure logic is unit-testable with no camera / bundle. Strictly read-only over
/// the frozen contract — like the drill engine (ADR 0007), it sits *beside* the
/// decode core and cannot perturb it. The Kotlin twin is `core.AssistGeometry`.
struct AssistGeometry {
    /// Position id (0–7) → canonical octant angle in degrees (signer's frame).
    let octantAngles: [Int: Double]
    /// Ordered `(left, right)` position ids per symbol: the 26 letters + `REST`.
    /// Ordered on purpose — the figure draws each arm from its own shoulder, so
    /// (unlike the decoder's order-*insensitive* match) which id is left vs right
    /// must survive. That is why the figure is built from a fresh alphabet parse
    /// rather than the decoder, whose `lookup` collapses the pair.
    let letterPairs: [String: (left: Int, right: Int)]
    /// Letter symbol → digit string in numeric mode (A–I → 1–9, K → 0). Reversed
    /// to map a digit *target* back to the arm pose the signer holds for it.
    let digitMap: [String: String]

    /// The two arm angles for a drill target, or `nil` for a target with no pose.
    /// Targets are the frozen drill alphabet A–Z / 0–9 / SPACE: a letter maps
    /// directly; a digit reverses `digitMap` to its letter pose (the arms *are* in
    /// that position — the NUMERALS mode-switch that precedes a digit is signaled
    /// separately and not taught by the figure); SPACE is REST (both arms down).
    func pose(for target: Character?) -> AssistPose? {
        guard let target else { return nil }
        let pair: (left: Int, right: Int)?
        if target == " " {
            pair = letterPairs["REST"]
        } else if let symbol = letterSymbol(for: target) {
            pair = letterPairs[symbol]
        } else {
            pair = nil
        }
        guard let p = pair,
            let left = octantAngles[p.left],
            let right = octantAngles[p.right]
        else { return nil }
        return AssistPose(leftAngleDeg: left, rightAngleDeg: right)
    }

    /// The alphabet symbol whose pose a target holds: the letter itself
    /// (ASCII-uppercased) for A–Z, or the digit's letter via the reversed digit
    /// map. Non-ASCII / punctuation → `nil` (no figure). Targets arrive ASCII and
    /// already uppercased from the source sanitiser, so the upper is defensive.
    private func letterSymbol(for target: Character) -> String? {
        if target.isASCII, target.isLetter {
            return String(target).uppercased()
        }
        if target.isASCII, target.isNumber {
            let digit = String(target)
            return digitMap.first { $0.value == digit }?.key
        }
        return nil
    }

    // MARK: - Figure layout

    /// Lay out the stick figure for `pose` in a normalized `[0,1]` box, screen
    /// (y-down) convention. `mirroredFront` follows the preview: the front Learn
    /// drill is mirrored (selfie), so the signer's right arm (octant `0°`) maps to
    /// screen-right and the user mirrors the figure directly — the same convention
    /// as `SkeletonOverlay.displayPoint`. Pure + `static` so the test pins the
    /// signer-frame-angle → screen-direction mapping the `Canvas` then draws.
    static func endpoints(for pose: AssistPose, mirroredFront: Bool = true) -> AssistFigurePoints {
        let shoulderY = 0.56
        let halfSpan = 0.13
        let armLen = 0.28

        let lShoulder = (x: 0.5 - halfSpan, y: shoulderY)
        let rShoulder = (x: 0.5 + halfSpan, y: shoulderY)
        let lWrist = wrist(from: lShoulder, angleDeg: pose.leftAngleDeg, length: armLen)
        let rWrist = wrist(from: rShoulder, angleDeg: pose.rightAngleDeg, length: armLen)
        let neck = (x: 0.5, y: shoulderY)
        let head = (x: 0.5, y: shoulderY + 0.22)
        let hip = (x: 0.5, y: shoulderY - 0.34)

        // Signer-frame (y-up) → screen box (y-down). Front preview is mirrored, so
        // `+x` already matches screen-right; only y-up is undone for both lenses.
        func project(_ p: (x: Double, y: Double)) -> CGPoint {
            let x = mirroredFront ? p.x : (1 - p.x)
            return CGPoint(x: x, y: 1 - p.y)
        }
        return AssistFigurePoints(
            neck: project(neck), hip: project(hip), head: project(head),
            leftShoulder: project(lShoulder), rightShoulder: project(rShoulder),
            leftWrist: project(lWrist), rightWrist: project(rWrist))
    }

    private static func wrist(
        from shoulder: (x: Double, y: Double), angleDeg: Double, length: Double
    ) -> (x: Double, y: Double) {
        let radians = angleDeg * .pi / 180
        return (x: shoulder.x + length * cos(radians), y: shoulder.y + length * sin(radians))
    }
}

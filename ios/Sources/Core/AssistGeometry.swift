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

/// One step in the assist filmstrip for a drill target (#100 / 6a-13). The
/// `rawValue` strings match `assist_cue_vectors.json`'s `kind` field, so the parity
/// harness maps the fixture straight onto this. The Android twin is `AssistCueKind`.
enum AssistCueKind: String, Equatable {
    /// Drop to a brief REST so a repeated target re-commits (the inter-char-gap
    /// re-arm, ADR 0005). A *timing* cue — the view draws it as a "drop", not a
    /// pose to hold (its `pose` is REST only so the layout has something to show).
    case restBetweenDoubles = "rest_between_doubles"
    /// The NUMERALS pose: enter numeric mode before a digit signed from letter mode.
    case numeralsShift = "numerals_shift"
    /// The J/LETTERS pose: return to letter mode before a letter signed from numeric mode.
    case lettersShift = "letters_shift"
    /// The target's own arm pose (always the final step of a filmstrip).
    case target
}

/// One filmstrip step: what to show, drawn at `pose`'s contract angles. The Android
/// twin is `AssistCue`.
struct AssistCue: Equatable {
    let kind: AssistCueKind
    let pose: AssistPose
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
    /// Ordered `(left, right)` position ids per symbol: the 26 letters, `REST`, and
    /// `NUMERALS`. Ordered on purpose — the figure draws each arm from its own
    /// shoulder, so (unlike the decoder's order-*insensitive* match) which id is
    /// left vs right must survive. That is why the figure is built from a fresh
    /// alphabet parse rather than the decoder, whose `lookup` collapses the pair.
    let symbolPairs: [String: (left: Int, right: Int)]
    /// Digit char → the letter symbol whose pose produces it in numeric mode (the
    /// reverse of the A=1..I=9, K=0 digit map). The figure needs digit→letter to
    /// draw a digit's arm pose; the decoder only carries letter→digit.
    let digitToLetter: [Character: String]

    /// The target's own arm pose, or `nil` for a target with no pose. A letter
    /// (A–Z) maps directly; a digit maps to the letter pose that produces it (#100
    /// un-suppressed the digit case — the NUMERALS pre-cue from `cues(_:index:)` now
    /// conveys the mode-switch a static pose alone can't); SPACE is REST.
    func pose(for target: Character?) -> AssistPose? {
        guard let target else { return nil }
        if target == " " { return pose(ofSymbol: "REST") }
        guard target.isASCII else { return nil }
        if target.isLetter { return pose(ofSymbol: String(target).uppercased()) }
        if target.isNumber { return digitToLetter[target].flatMap { pose(ofSymbol: $0) } }
        return nil  // punctuation: no pose
    }

    /// The arm pose for a named contract symbol (a letter, `REST`, or `NUMERALS`).
    private func pose(ofSymbol symbol: String) -> AssistPose? {
        guard let p = symbolPairs[symbol],
            let left = octantAngles[p.left],
            let right = octantAngles[p.right]
        else { return nil }
        return AssistPose(leftAngleDeg: left, rightAngleDeg: right)
    }

    // MARK: - Transition cues (#100 / 6a-13)

    /// The mode the signer is in just **before** producing `targets[index]`, walking
    /// the canonical path from `LETTERS` at the start of the passage. Mirrors the
    /// committer's mode rules (spec §4.5): a digit ends the signer in numeric mode, a
    /// letter in letter mode, and SPACE/REST (like an indeterminate frame) persist
    /// the current mode. `gen_assist_cue_vectors.py` cross-checks this against the
    /// reference `interpret`, so it cannot drift from the committer.
    func impliedMode(targets: String, before index: Int) -> Mode {
        var mode: Mode = .letters
        let chars = Array(targets)
        for i in 0..<min(max(index, 0), chars.count) {
            let t = chars[i]
            guard t.isASCII else { continue }
            if t.isNumber {
                mode = .numeric
            } else if t.isLetter {
                mode = .letters
            }
            // space / other: mode persists
        }
        return mode
    }

    /// The ordered assist filmstrip for `targets[index]` (#100): an optional
    /// transition pre-cue, then the target's own pose. Empty for an out-of-range
    /// index or a target with no pose (defensive; a sanitised passage never has one).
    ///
    /// The three pre-cues are mutually exclusive by construction: a repeated target
    /// is the same character, so the implied mode already matches the target's mode
    /// (and the mode-switch poses, which themselves break the same-symbol lock, are
    /// never both needed) — so at most one pre-cue ever precedes the target step.
    func cues(targets: String, index: Int) -> [AssistCue] {
        let chars = Array(targets)
        guard index >= 0, index < chars.count else { return [] }
        let target = chars[index]
        guard let targetPose = pose(for: target) else { return [] }

        var result: [AssistCue] = []
        let mode = impliedMode(targets: targets, before: index)
        if index > 0,
            String(target).uppercased() == String(chars[index - 1]).uppercased(),
            target != " "
        {
            // Double (same) target: a brief drop to REST re-arms the commit (ADR 0005).
            if let rest = pose(ofSymbol: "REST") {
                result.append(AssistCue(kind: .restBetweenDoubles, pose: rest))
            }
        } else if target.isASCII, target.isNumber, mode == .letters {
            if let numerals = pose(ofSymbol: "NUMERALS") {
                result.append(AssistCue(kind: .numeralsShift, pose: numerals))
            }
        } else if target.isASCII, target.isLetter, mode == .numeric {
            if let letters = pose(ofSymbol: "J") {  // the J pose = the LETTERS shift
                result.append(AssistCue(kind: .lettersShift, pose: letters))
            }
        }
        result.append(AssistCue(kind: .target, pose: targetPose))
        return result
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

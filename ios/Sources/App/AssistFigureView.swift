import SwiftUI

/// The contract-derived assist filmstrip (#73 / 6a-5 + transition cues #100): the
/// ordered steps to make for the current drill target — an optional transition
/// pre-cue (a NUMERALS / J-LETTERS pose, or a drop-to-REST), then the target's own
/// pose, drawn left→right with a chevron between. A single-step filmstrip (the
/// common case: just the target) renders as the original full-size figure, so the
/// 6a-5 look is unchanged when no transition is needed. The caller gates the whole
/// thing to Learn-drill + front lens + the "Show assist figure" setting (see
/// `ContentView`). The Android twin is `SemaphoreScreen.AssistFilmstrip`.
struct AssistFilmstripView: View {
    let cues: [AssistCue]

    var body: some View {
        if cues.count <= 1 {
            // No transition: the original single full-size figure (6a-5 look).
            if let cue = cues.first {
                AssistFigureView(pose: cue.pose)
                    .frame(height: 132)
                    .padding(.vertical, 2)
            }
        } else {
            HStack(spacing: 8) {
                ForEach(Array(cues.enumerated()), id: \.offset) { i, cue in
                    if i > 0 {
                        Image(systemName: "chevron.right")
                            .font(.title3.bold())
                            .foregroundStyle(.white.opacity(0.7))
                            .accessibilityHidden(true)
                    }
                    AssistCueCell(cue: cue)
                }
            }
            .frame(height: 122)
            .padding(.vertical, 2)
        }
    }
}

/// One filmstrip cell: the figure (or the drop-to-rest indicator for a double
/// letter) over a short caption naming the step. Sized for the two-step layout.
private struct AssistCueCell: View {
    let cue: AssistCue

    var body: some View {
        VStack(spacing: 4) {
            Group {
                if cue.kind == .restBetweenDoubles {
                    RestDropIndicator()
                } else {
                    AssistFigureView(pose: cue.pose)
                }
            }
            .frame(width: 92, height: 92)
            if let caption {
                Text(caption)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.8))
            }
        }
    }

    /// A short label under the pre-cue steps; the target step needs none (the card's
    /// big glyph already names it).
    private var caption: String? {
        switch cue.kind {
        case .numeralsShift: return "Numbers"
        case .lettersShift: return "Letters"
        case .restBetweenDoubles: return "Rest"
        case .target: return "Sign"
        }
    }
}

/// The drop-to-REST cue, drawn distinctly from the arm-pose figures (#100): two
/// downward chevrons that read as "drop your arms briefly", NOT a pose to hold.
private struct RestDropIndicator: View {
    var body: some View {
        VStack(spacing: -6) {
            Image(systemName: "chevron.down")
            Image(systemName: "chevron.down")
        }
        .font(.system(size: 30, weight: .bold))
        .foregroundStyle(.white.opacity(0.75))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityHidden(true)
    }
}

/// The contract-derived assist figure (#73 / 6a-5): a compact stick figure whose
/// two arms are drawn at the *exact* `semaphore_alphabet.json` angles for one pose
/// (`AssistGeometry.endpoints`), so the teaching aid is perspective-correct by
/// construction and can't drift from the contract. Used for each pose step of the
/// `AssistFilmstripView`. Left arm cyan / right arm orange — the same legend as
/// `SkeletonOverlay`, drawn in the same mirrored-front convention so the user
/// mirrors the pose directly. The Android twin is `SemaphoreScreen.AssistFigure`.
struct AssistFigureView: View {
    let pose: AssistPose

    private static let leftColor = Color.cyan
    private static let rightColor = Color.orange
    private static let bodyColor = Color.white.opacity(0.85)

    var body: some View {
        let pts = AssistGeometry.endpoints(for: pose)
        return Canvas { context, size in
            // Draw into a centered square so the arm angles are never distorted by a
            // non-square frame (a 45° arm must look 45°).
            let side = min(size.width, size.height)
            let ox = (size.width - side) / 2
            let oy = (size.height - side) / 2
            func at(_ p: CGPoint) -> CGPoint {
                CGPoint(x: ox + p.x * side, y: oy + p.y * side)
            }
            func segment(_ a: CGPoint, _ b: CGPoint, _ color: Color, _ width: CGFloat) {
                var path = Path()
                path.move(to: at(a))
                path.addLine(to: at(b))
                context.stroke(
                    path, with: .color(color), style: .init(lineWidth: width, lineCap: .round))
            }

            // Body: neck→head + torso (neck→hip) + shoulder line + a stroked head.
            segment(pts.neck, pts.head, Self.bodyColor, 3)
            segment(pts.neck, pts.hip, Self.bodyColor, 3)
            segment(pts.leftShoulder, pts.rightShoulder, Self.bodyColor, 3)
            let headCenter = at(pts.head)
            let headRadius = side * 0.06
            context.stroke(
                Path(
                    ellipseIn: CGRect(
                        x: headCenter.x - headRadius, y: headCenter.y - headRadius,
                        width: 2 * headRadius, height: 2 * headRadius)),
                with: .color(Self.bodyColor), lineWidth: 3)

            // Arms, colour-coded, with a dot at each wrist (the flag end).
            segment(pts.leftShoulder, pts.leftWrist, Self.leftColor, 5)
            segment(pts.rightShoulder, pts.rightWrist, Self.rightColor, 5)
            for (point, color) in [(pts.leftWrist, Self.leftColor), (pts.rightWrist, Self.rightColor)] {
                let center = at(point)
                let dotRadius: CGFloat = 5
                context.fill(
                    Path(
                        ellipseIn: CGRect(
                            x: center.x - dotRadius, y: center.y - dotRadius,
                            width: 2 * dotRadius, height: 2 * dotRadius)),
                    with: .color(color))
            }
        }
        // Decorative: the card already announces the target letter; the full
        // Dynamic Type / VoiceOver pass is #92.
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }
}

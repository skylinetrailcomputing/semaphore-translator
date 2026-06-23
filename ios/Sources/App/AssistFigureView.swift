import SwiftUI

/// The contract-derived assist figure (#73 / 6a-5): a compact stick figure whose
/// two arms are drawn at the *exact* `semaphore_alphabet.json` angles for the
/// current drill target (`AssistGeometry.endpoints`), so the teaching aid is
/// perspective-correct by construction and can't drift from the contract. Embedded
/// in the drill target card; the caller gates it to Learn-drill + front lens +
/// the "Show assist figure" setting (see `ContentView`). Left arm cyan / right arm
/// orange — the same legend as `SkeletonOverlay`, drawn in the same mirrored-front
/// convention so the user mirrors the pose directly. The Android twin is
/// `SemaphoreScreen.AssistFigure`.
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

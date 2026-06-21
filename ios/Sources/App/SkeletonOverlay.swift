import SwiftUI

/// Draws the 6 post-adapter `Keypoints` over the camera preview ([3.5], #23) —
/// the human-visible check that the L/R labels and "up" are right. Left arm and
/// right arm are colored distinctly and labelled so the mirror is legible at a
/// glance: the signer's RIGHT wrist should appear on the correct side.
///
/// **Signer-frame → display-frame mapping** (same convention as Android's
/// `SkeletonOverlay`, verified there on a Pixel 9a — #23). `Keypoints` are
/// normalized `[0,1]`, **y-up**, in the **signer's** perspective (`+x` = signer's
/// right). The x-map follows the *preview*, selected by the lens-derived
/// `mirrored` flag (#57) — the same flag `CameraPreviewView` receives, so overlay
/// and preview stay in lockstep:
///   - front, **mirrored** preview (selfie): `+x` already matches screen-right →
///     `screen_x = kp.x · W`
///   - rear, **un-mirrored** preview: undo the signer→observer flip →
///     `screen_x = (1 − kp.x) · W`
///   - undo y-up (both), for the top-left display origin →
///     `screen_y = (1 − kp.y) · H`
/// This is purely a *display* transform — it never touches the adapter mirror.
/// If the overlay lands flipped/rotated on a real device, this function, the
/// preview mirroring/rotation, and the Vision request orientation are the knobs
/// (tuned in lockstep); a second adapter flip is never the fix (it would desync
/// the platforms and the parity harness). The front mirror + portrait orientation
/// were smoke-confirmed on a Pixel 9a and an iPhone 16 (#23); the rear x-flip on a
/// throwaway scratch branch (#57).
///
/// **Registration is approximate.** The preview uses `.resizeAspectFill`, which
/// crops the buffer to fill the view, so this linear full-view mapping can drift
/// a few percent near the edges. That is fine for the orientation/mirror smoke
/// (a point on the signer's right still renders on the correct side); exact joint
/// registration would use `AVCaptureVideoPreviewLayer` coordinate conversion.
struct SkeletonOverlay: View {
    let keypoints: Keypoints?
    /// Whether the preview is mirrored (lens-derived, #57): `true` front / `false`
    /// rear. Must match the flag `CameraPreviewView` receives so the overlay
    /// registers against what's shown.
    let mirrored: Bool

    private static let leftColor = Color.cyan
    private static let rightColor = Color.orange
    private static let torsoColor = Color.white.opacity(0.7)

    /// `nonisolated` so the `Canvas` render closure — which runs in a nonisolated
    /// context — can call it without hopping to the main actor (the `View` type
    /// is otherwise inferred `@MainActor`). `mirrored` flips x for the un-mirrored
    /// rear preview (#57); y-up is undone the same way for both lenses.
    nonisolated static func displayPoint(_ kp: Keypoint, in size: CGSize, mirrored: Bool)
        -> CGPoint
    {
        let x = mirrored ? kp.x : (1 - kp.x)
        return CGPoint(x: x * size.width, y: (1 - kp.y) * size.height)
    }

    var body: some View {
        // Bind to locals so the @Sendable Canvas closure captures value types
        // rather than `self` (the View is main-actor isolated).
        let snapshot = keypoints
        let mirror = mirrored
        return Canvas { context, size in
            guard let k = snapshot else { return }
            func at(_ kp: Keypoint) -> CGPoint { Self.displayPoint(kp, in: size, mirrored: mirror) }

            func limb(_ a: Keypoint, _ b: Keypoint, _ c: Keypoint, _ color: Color) {
                var path = Path()
                path.move(to: at(a))
                path.addLine(to: at(b))
                path.addLine(to: at(c))
                context.stroke(path, with: .color(color), style: .init(lineWidth: 4, lineCap: .round))
            }

            // Torso (shoulder line), then each arm shoulder→elbow→wrist.
            var torso = Path()
            torso.move(to: at(k.leftShoulder))
            torso.addLine(to: at(k.rightShoulder))
            context.stroke(torso, with: .color(Self.torsoColor), lineWidth: 3)
            limb(k.leftShoulder, k.leftElbow, k.leftWrist, Self.leftColor)
            limb(k.rightShoulder, k.rightElbow, k.rightWrist, Self.rightColor)

            // Joints.
            for (kp, color) in [
                (k.leftShoulder, Self.leftColor), (k.leftElbow, Self.leftColor),
                (k.leftWrist, Self.leftColor),
                (k.rightShoulder, Self.rightColor), (k.rightElbow, Self.rightColor),
                (k.rightWrist, Self.rightColor),
            ] {
                let pt = at(kp)
                let r: CGFloat = 6
                let dot = Path(ellipseIn: CGRect(x: pt.x - r, y: pt.y - r, width: 2 * r, height: 2 * r))
                context.fill(dot, with: .color(color))
            }

            // L / R labels at the wrists — the at-a-glance mirror check.
            context.draw(
                Text("L").font(.caption.bold()).foregroundStyle(Self.leftColor),
                at: at(k.leftWrist).applying(.init(translationX: 0, y: -14)))
            context.draw(
                Text("R").font(.caption.bold()).foregroundStyle(Self.rightColor),
                at: at(k.rightWrist).applying(.init(translationX: 0, y: -14)))
        }
        .allowsHitTesting(false)
    }
}

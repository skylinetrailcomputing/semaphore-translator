import AVFoundation
import SwiftUI

/// SwiftUI host for the live camera feed: an `AVCaptureVideoPreviewLayer` bound
/// to the capture actor's `session` ([3.5], #23).
///
/// **Frame discipline (read alongside `SkeletonOverlay`).** The preview is
/// **mirrored** (`isVideoMirrored = true`) — the natural selfie view, which is
/// the signer's perspective, so the overlay maps `Keypoints` straight
/// (`screen_x = x·W`). This convention is smoke-verified on Android and iOS (#23). It is
/// **independent of the analysis path**: the `AVCaptureVideoDataOutput` in
/// `PoseCaptureSession` stays NON-mirrored (`isVideoMirrored = false`) so the
/// adapter still sees the observer-perspective buffer it is calibrated against
/// (the fixtures). The single adapter mirror lives in `VisionPoseAdapter` and is
/// **never** touched here — mirroring the *preview* only changes display.
///
/// **Orientation is the on-device smoke** (guardrail-d; there is no camera or
/// body-pose in the simulator). `previewRotationAngle` (portrait = 90°) and the
/// Vision request orientation in `PoseCaptureSession` (#21's `.up` placeholder)
/// must agree, and the `SkeletonOverlay` mapping must match both. If the live
/// overlay looks rotated relative to the preview, tune *those* knobs in lockstep
/// — not a second adapter flip, which would desync the platforms and the parity
/// harness.
struct CameraPreviewView: UIViewRepresentable {
    /// The capture *actor* (Sendable), not its `AVCaptureSession` (not Sendable):
    /// passing the non-Sendable session through a ViewBuilder trips Swift 6's
    /// region-isolation checker. The session is read on the main thread inside
    /// `makeUIView`, where the `nonisolated(unsafe)` access is well-defined.
    let capture: PoseCaptureSession

    /// Portrait. Kept here as the single preview-orientation knob for the smoke.
    static let previewRotationAngle: CGFloat = 90

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        let preview = view.previewLayer
        preview.session = capture.session
        preview.videoGravity = .resizeAspectFill
        if let connection = preview.connection {
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = true
            }
            if connection.isVideoRotationAngleSupported(Self.previewRotationAngle) {
                connection.videoRotationAngle = Self.previewRotationAngle
            }
        }
        return view
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {}
}

/// A `UIView` whose backing layer *is* the preview layer, so it resizes with the
/// view automatically (no manual frame bookkeeping).
final class PreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

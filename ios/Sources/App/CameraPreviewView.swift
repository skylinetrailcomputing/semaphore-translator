import AVFoundation
import SwiftUI

/// SwiftUI host for the live camera feed: an `AVCaptureVideoPreviewLayer` bound
/// to the capture actor's `session` ([3.5], #23).
///
/// **Frame discipline (read alongside `SkeletonOverlay`).** The preview is
/// **mirrored for the front lens** (`isVideoMirrored = true`) — the natural
/// selfie view, which is the signer's perspective, so the overlay maps
/// `Keypoints` straight (`screen_x = x·W`). For the **rear lens** (Interpret,
/// #56/#57) the preview must **not** be mirrored — you're watching someone else
/// — so `mirrored` is `false` and the overlay flips to `screen_x = (1 − x)·W`.
/// `mirrored` is lens-derived by `PreviewViewModel.isPreviewMirrored` and
/// threaded into both knobs together so they stay in lockstep. This convention
/// is smoke-verified on Android and iOS (#23; rear via #57). It is
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

    /// Whether to mirror the preview for display (lens-derived, #57): `true` for
    /// the front selfie view, `false` for the rear lens. The matching
    /// `SkeletonOverlay` must receive the same flag so its x-map stays in lockstep.
    let mirrored: Bool

    /// Whether to attach pinch-to-zoom — Interpret (rear lens) only. Learn is
    /// front-lens self-signing at arm's length, where a tight crop would only push
    /// the signer's own arms out of frame, so the gesture isn't installed there.
    /// Lens-derived by `PreviewViewModel.isZoomEnabled` (the twin of `mirrored`).
    let zoomEnabled: Bool

    /// Portrait. Kept here as the single preview-orientation knob for the smoke.
    static let previewRotationAngle: CGFloat = 90

    func makeCoordinator() -> Coordinator { Coordinator(capture: capture) }

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        let preview = view.previewLayer
        preview.session = capture.session
        preview.videoGravity = .resizeAspectFill
        if let connection = preview.connection {
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = mirrored
            }
            if connection.isVideoRotationAngleSupported(Self.previewRotationAngle) {
                connection.videoRotationAngle = Self.previewRotationAngle
            }
        }
        if zoomEnabled {
            let pinch = UIPinchGestureRecognizer(
                target: context.coordinator,
                action: #selector(Coordinator.handlePinch(_:)))
            view.addGestureRecognizer(pinch)
        }
        return view
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {}

    /// Translates the preview's pinch gesture into `videoZoomFactor` updates on the
    /// capture actor (Interpret only). Tracks the zoom locally on the main thread
    /// because `UIPinchGestureRecognizer.scale` is cumulative from the gesture's
    /// start: each gesture multiplies the factor it began at. Clamped to the same
    /// `1.0…maxZoomFactor` band the actor enforces, so the local mirror matches what
    /// the device applies.
    ///
    /// `@MainActor` because UIKit delivers gesture-recognizer callbacks on the main
    /// thread and `UIPinchGestureRecognizer`'s `state`/`scale` are themselves
    /// main-actor isolated under Swift 6 — matching that isolation here is what keeps
    /// the `Task { await capture.setZoom(...) }` hand-off to the actor race-free.
    @MainActor
    final class Coordinator: NSObject {
        private let capture: PoseCaptureSession
        private var baseZoom: CGFloat = 1.0
        private var currentZoom: CGFloat = 1.0

        init(capture: PoseCaptureSession) { self.capture = capture }

        @objc func handlePinch(_ gesture: UIPinchGestureRecognizer) {
            switch gesture.state {
            case .began:
                baseZoom = currentZoom
            case .changed:
                let desired = baseZoom * gesture.scale
                let clamped = min(max(desired, 1.0), PoseCaptureSession.maxZoomFactor)
                currentZoom = clamped
                Task { await capture.setZoom(factor: clamped) }
            default:
                break
            }
        }
    }
}

/// A `UIView` whose backing layer *is* the preview layer, so it resizes with the
/// view automatically (no manual frame bookkeeping).
final class PreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

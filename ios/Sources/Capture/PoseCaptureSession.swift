import AVFoundation
import Vision

/// One capture frame surfaced by `PoseCaptureSession`: the adapted `Keypoints`
/// plus the frame's **monotonic** capture time in milliseconds (Issue #34, spec
/// §4.4). `tMs` is taken from the sample buffer's presentation timestamp at
/// capture, not read at consumption — so the temporal committer (#4.5) is timed
/// by the frame, not by whenever a view model happens to process it. Only the
/// deltas between successive `tMs` matter to the committer, so the clock's epoch
/// is irrelevant; it just has to advance monotonically with the frames.
struct PoseFrame: Sendable {
    let keypoints: Keypoints
    let tMs: Int
}

/// Front-camera capture → Apple Vision body pose → adapter → `PoseFrame`,
/// surfaced as an `AsyncStream` (Issue #21, spec §3.1/§3.2, FR1/FR2; the
/// per-frame timestamp is #34).
///
/// **Swift-6 isolation.** `AVFoundation` is not `Sendable`-clean and the
/// `AVCaptureVideoDataOutput` delegate fires on a background queue, not on this
/// actor. So: all session mutation is confined to the actor; the per-frame work
/// lives on a `@unchecked Sendable` delegate that owns the stream continuation;
/// the only thing crossing the isolation boundary is an immutable, `Sendable`
/// `PoseFrame`. The blocking `startRunning()`/`stopRunning()` calls hop to a
/// dedicated queue via `nonisolated(unsafe)` (the idiomatic escape for this
/// AVFoundation gotcha) rather than block the actor's executor.
///
/// **Not unit-tested.** There is no camera in the simulator/CI, so this path is
/// compiled and reviewed but its live orientation + mirror behaviour is an
/// on-device smoke item. The *math* it feeds is pinned by `VisionCalibrationTests`.
/// Keeping the data output **non-mirrored** (`isVideoMirrored = false`) makes the
/// live buffer match the observer-perspective fixture the adapter is calibrated
/// against, so the single mirror in `VisionPoseAdapter` is correct for both.
actor PoseCaptureSession {
    enum CaptureError: Error { case noFrontCamera, cannotAddInput, cannotAddOutput }

    /// The capture session, exposed so the SwiftUI preview layer ([3.5], #23)
    /// can attach to the *same* session this actor configures and runs. Sharing
    /// the session between this actor (configuration) and a main-thread
    /// `AVCaptureVideoPreviewLayer` (display) is the documented AVFoundation
    /// pattern; since AVFoundation is not `Sendable`-clean, it crosses the
    /// boundary as `nonisolated(unsafe)` — the same escape the start/stop hops
    /// below use. The preview is the *display* surface; the decode path still
    /// runs off the non-mirrored `AVCaptureVideoDataOutput` configured here.
    nonisolated(unsafe) let session = AVCaptureSession()
    private let videoQueue = DispatchQueue(label: "com.skylinetrailcomputing.semaphore.capture")
    private var handler: PoseSampleHandler?

    /// Portrait. The analysis connection is rotated by this so the buffer Vision
    /// receives is upright, matching `CameraPreviewView.previewRotationAngle`.
    static let portraitRotationAngle: CGFloat = 90

    /// Configure the front-camera pipeline and start streaming adapted poses.
    /// The stream finishes when its consuming task is cancelled or `stop()` runs.
    func start() throws -> AsyncStream<PoseFrame> {
        let (stream, continuation) = AsyncStream<PoseFrame>.makeStream()
        let handler = PoseSampleHandler(continuation: continuation)
        self.handler = handler

        session.beginConfiguration()
        session.sessionPreset = .high

        guard
            let camera = AVCaptureDevice.default(
                .builtInWideAngleCamera, for: .video, position: .front)
        else { throw CaptureError.noFrontCamera }

        let input = try AVCaptureDeviceInput(device: camera)
        guard session.canAddInput(input) else { throw CaptureError.cannotAddInput }
        session.addInput(input)

        let output = AVCaptureVideoDataOutput()
        output.setSampleBufferDelegate(handler, queue: videoQueue)
        guard session.canAddOutput(output) else { throw CaptureError.cannotAddOutput }
        session.addOutput(output)

        if let connection = output.connection(with: .video) {
            // Rotate the analysis buffer to portrait-upright so Vision (fed `.up`)
            // sees an upright signer. Without this the sensor-native landscape
            // buffer makes the skeleton come out rotated 90° — the [3.5] on-device
            // smoke. Matches the preview's rotation so both paths share one frame.
            if connection.isVideoRotationAngleSupported(Self.portraitRotationAngle) {
                connection.videoRotationAngle = Self.portraitRotationAngle
            }
            // Quarantine the mirror in the adapter, not the buffer: keep the
            // analysis stream non-mirrored (observer perspective) so it matches the
            // fixtures the adapter is calibrated against.
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = false
            }
        }
        session.commitConfiguration()

        continuation.onTermination = { [weak self] _ in
            Task { await self?.stop() }
        }

        nonisolated(unsafe) let captureSession = session
        videoQueue.async { captureSession.startRunning() }
        return stream
    }

    func stop() {
        nonisolated(unsafe) let captureSession = session
        videoQueue.async { if captureSession.isRunning { captureSession.stopRunning() } }
        handler?.finish()
        handler = nil
    }
}

/// Per-frame worker: runs Vision on each sample buffer (on the capture queue)
/// and yields adapted `Keypoints`. `@unchecked Sendable` because it is only ever
/// touched on its single serial delegate queue, and the continuation it holds is
/// itself `Sendable`.
private final class PoseSampleHandler: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate,
    @unchecked Sendable
{
    private let continuation: AsyncStream<PoseFrame>.Continuation
    private let request = VNDetectHumanBodyPoseRequest()
    private let adapter = VisionPoseAdapter()

    init(continuation: AsyncStream<PoseFrame>.Continuation) {
        self.continuation = continuation
    }

    func finish() { continuation.finish() }

    func captureOutput(
        _ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        // The frame's monotonic capture time (#34): the presentation timestamp,
        // taken here at capture, not when a view model later consumes the frame.
        // For `AVCaptureVideoDataOutput` the PTS rides the host time clock, so it
        // advances monotonically across the stream; the committer only uses deltas.
        // Guard on `isFinite`, not `pts.isValid`: an indefinite/infinite CMTime is
        // still "valid" (CMTIME_IS_INDEFINITE = valid + the indefinite flag), and
        // `CMTimeGetSeconds` maps it to NaN/Inf — which would *trap* in `Int(...)`.
        let secs = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
        guard secs.isFinite else { return }
        let tMs = Int((secs * 1000).rounded())
        // The capture connection rotates the buffer to portrait-upright, so `.up`
        // is correct here; the front-camera mirror is handled once in the adapter.
        let requestHandler = VNImageRequestHandler(
            cvPixelBuffer: pixelBuffer, orientation: .up, options: [:])
        do {
            try requestHandler.perform([request])
        } catch {
            return
        }
        guard let observation = request.results?.first,
            let keypoints = try? adapter.adapt(observation)
        else { return }
        continuation.yield(PoseFrame(keypoints: keypoints, tMs: tMs))
    }
}

import AVFoundation
import Vision

/// Front-camera capture → Apple Vision body pose → adapter → `Keypoints`,
/// surfaced as an `AsyncStream` (Issue #21, spec §3.1/§3.2, FR1/FR2).
///
/// **Swift-6 isolation.** `AVFoundation` is not `Sendable`-clean and the
/// `AVCaptureVideoDataOutput` delegate fires on a background queue, not on this
/// actor. So: all session mutation is confined to the actor; the per-frame work
/// lives on a `@unchecked Sendable` delegate that owns the stream continuation;
/// the only thing crossing the isolation boundary is an immutable, `Sendable`
/// `Keypoints`. The blocking `startRunning()`/`stopRunning()` calls hop to a
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

    /// Configure the front-camera pipeline and start streaming adapted poses.
    /// The stream finishes when its consuming task is cancelled or `stop()` runs.
    func start() throws -> AsyncStream<Keypoints> {
        let (stream, continuation) = AsyncStream<Keypoints>.makeStream()
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

        // Quarantine the mirror in the adapter, not the buffer.
        if let connection = output.connection(with: .video), connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = false
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
    private let continuation: AsyncStream<Keypoints>.Continuation
    private let request = VNDetectHumanBodyPoseRequest()
    private let adapter = VisionPoseAdapter()

    init(continuation: AsyncStream<Keypoints>.Continuation) {
        self.continuation = continuation
    }

    func finish() { continuation.finish() }

    func captureOutput(
        _ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        // `.up` is a portrait-front placeholder; the correct device orientation
        // is an on-device smoke item (see the actor's doc comment).
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
        continuation.yield(keypoints)
    }
}

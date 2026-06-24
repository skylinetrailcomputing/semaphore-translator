import AVFoundation
import Vision
import os

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

/// Camera capture → Apple Vision body pose → adapter → `PoseFrame`, surfaced as
/// an `AsyncStream` (Issue #21, spec §3.1/§3.2, FR1/FR2; the per-frame timestamp
/// is #34). The lens is a `start(cameraPosition:)` parameter (front by default;
/// Interpret requests the rear lens, #56) — lens *selection* only, the mirror
/// stays quarantined in `VisionPoseAdapter` (spec §3.2, NFR3).
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
    enum CaptureError: Error { case noCamera, cannotAddInput, cannotAddOutput }

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
    /// The active capture device, retained so Interpret's pinch-to-zoom can drive
    /// `videoZoomFactor` after `start()`. `nil` until a session is configured.
    private var device: AVCaptureDevice?

    /// Portrait. The analysis connection is rotated by this so the buffer Vision
    /// receives is upright, matching `CameraPreviewView.previewRotationAngle`.
    static let portraitRotationAngle: CGFloat = 90

    /// Hard cap on Interpret pinch-zoom (the rear-lens read of a distant signer).
    /// This is *digital* zoom on the wide-angle lens, so past a modest factor the
    /// crop just softens; more to the point, a tight crop pushes the signer's wide
    /// semaphore wingspan out of frame — the exact #101 friction the pose model
    /// already fights — so zoom is kept deliberately small. In lockstep with
    /// Android's `MAX_ZOOM_RATIO`.
    static let maxZoomFactor: CGFloat = 3.0

    /// Configure the capture pipeline for `cameraPosition` (front by default) and
    /// start streaming adapted poses. The stream finishes when its consuming task
    /// is cancelled or `stop()` runs.
    func start(cameraPosition: AVCaptureDevice.Position = .front) throws
        -> AsyncStream<PoseFrame>
    {
        let (stream, continuation) = AsyncStream<PoseFrame>.makeStream()
        let handler = PoseSampleHandler(continuation: continuation)
        self.handler = handler

        session.beginConfiguration()
        // Fallback only: `.high` resolves to a 16:9 format on modern iPhones. We
        // override it with an explicit 4:3 `activeFormat` below (which flips the
        // session to `.inputPriority`); this preset applies only if no 4:3 format
        // is available (#110, 6a-16, lever A).
        session.sessionPreset = .high

        guard
            let camera = AVCaptureDevice.default(
                .builtInWideAngleCamera, for: .video, position: cameraPosition)
        else { throw CaptureError.noCamera }
        self.device = camera

        let input = try AVCaptureDeviceInput(device: camera)
        guard session.canAddInput(input) else { throw CaptureError.cannotAddInput }
        session.addInput(input)

        // #110 (6a-16, lever A): widen the analysis FOV to 4:3. In portrait the
        // horizontal FOV is the *narrow* dimension, so the 16:9 format that `.high`
        // resolves to crops wingspan — a fully-extended arm clips the frame side and
        // the wrist gates out (the "iOS worse on arms-out" field report; iOS's 16:9
        // clipped the wrist sooner than Android's ~4:3 in the #101 diagnostic).
        // Selecting a 4:3 `activeFormat` keeps the full horizontal FOV at a given
        // distance. The adapter normalizes keypoints to [0,1] regardless of buffer
        // aspect and the native fixtures are static images, so the frozen contract
        // and `VisionCalibrationTests` are unaffected. Best-effort: on the rare
        // device with no 4:3 format, or if the lock fails, we keep the `.high`
        // fallback above.
        if let format = Self.fourByThreeFormat(for: camera),
            (try? camera.lockForConfiguration()) != nil
        {
            camera.activeFormat = format
            // Setting an explicit `activeFormat` resets the frame duration to the
            // format's default, which is its MAX rate (60 fps on a modern iPhone) —
            // so #110 inadvertently doubled the capture rate vs the `.high` preset's
            // ~30. Pin the analysis stream back to 30 fps: ample for semaphore, it
            // halves the per-frame `VNDetectHumanBodyPoseRequest` cost (the 60-fps
            // regression made iOS markedly heavier than Android's CameraX path), and
            // it keeps the frame-counted `SMOOTHING_WINDOW` at its intended ~30-fps
            // wall-clock span (at 60 fps a 5-frame window is half the time, twitchier).
            // `COMMIT_HOLD_MS` is wall-clock, so commit latency is unchanged either way.
            // Guarded: only pin when the chosen format actually covers 30 fps (it does
            // on every current iPhone; the selector already requires a range ≥ 30).
            let targetFps = 30.0
            let supports30 = format.videoSupportedFrameRateRanges.contains {
                $0.minFrameRate <= targetFps && targetFps <= $0.maxFrameRate
            }
            if supports30 {
                let duration = CMTime(value: 1, timescale: Int32(targetFps))
                camera.activeVideoMinFrameDuration = duration
                camera.activeVideoMaxFrameDuration = duration
            }
            camera.unlockForConfiguration()
        }

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

        // Start every session un-zoomed so the preview's pinch handler — which tracks
        // zoom from a 1.0 base — stays in sync with the device across a teardown and
        // restart (the device is the shared singleton, whose `videoZoomFactor`
        // persists). A no-op on the front lens, which never zooms.
        setZoom(factor: 1.0)

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

    /// #110 (6a-16, lever A). Pick a 4:3 capture format so portrait analysis keeps
    /// the full horizontal FOV (16:9 crops the sides → an arms-out wingspan clips
    /// and the wrist gates). Formats report landscape dimensions, so 4:3 means
    /// `width * 3 == height * 4`. Among the 4:3 formats that stream at ≥30 fps,
    /// prefer the highest resolution at or below 1920px wide — enough to localize a
    /// wrist at distance without paying full-sensor Vision cost — and, if every 4:3
    /// format is larger than that, fall back to the smallest. `nil` when the device
    /// exposes no 4:3 format at all (caller then keeps the default preset).
    private static func fourByThreeFormat(for device: AVCaptureDevice)
        -> AVCaptureDevice.Format?
    {
        func width(_ format: AVCaptureDevice.Format) -> Int32 {
            CMVideoFormatDescriptionGetDimensions(format.formatDescription).width
        }
        let fourByThree = device.formats.filter { format in
            let d = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            let isFourByThree = d.width * 3 == d.height * 4
            let streams30 = format.videoSupportedFrameRateRanges
                .contains { $0.maxFrameRate >= 30 }
            return isFourByThree && streams30
        }
        let capped = fourByThree.filter { width($0) <= 1920 }
        return capped.max { width($0) < width($1) }
            ?? fourByThree.min { width($0) < width($1) }
    }

    /// Set the live zoom for Interpret's pinch-to-zoom (rear lens). Clamped to
    /// `1.0…maxZoomFactor` and to the device's own ceiling. Zoom is a uniform
    /// center-crop, so it changes only the field of view: the analysis buffer the
    /// adapter sees is still normalized, and the decode rests on joint *angles*,
    /// which a uniform crop+scale preserves — so nothing downstream of capture is
    /// affected (the mirror stays quarantined in `VisionPoseAdapter`). A no-op until
    /// `start()` has acquired a device.
    func setZoom(factor: CGFloat) {
        guard let device else { return }
        let ceiling = min(Self.maxZoomFactor, device.maxAvailableVideoZoomFactor)
        let clamped = max(1.0, min(factor, ceiling))
        do {
            try device.lockForConfiguration()
            device.videoZoomFactor = clamped
            device.unlockForConfiguration()
        } catch {
            // A transient configuration-lock failure just skips this zoom step; the
            // next pinch delta will try again.
        }
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
        // is correct here; the capture mirror is handled once in the adapter.
        let requestHandler = VNImageRequestHandler(
            cvPixelBuffer: pixelBuffer, orientation: .up, options: [:])
        do {
            try requestHandler.perform([request])
        } catch {
            return
        }
        guard let observation = request.results?.first else { return }
        guard let keypoints = try? adapter.adapt(observation) else {
            // #101 (6a-14) diagnostic: a body was detected but the adapter dropped
            // the frame because Vision omitted ≥1 of the six joints. Log which, so
            // the on-device write-up can confirm the iOS-vs-Android friction gap.
            logDroppedFrame(observation)
            return
        }
        continuation.yield(PoseFrame(keypoints: keypoints, tMs: tMs))
    }

    /// #101 (6a-14) diagnostic, dev-mode only. The whole-frame-drop branch above is
    /// the suspected iOS-specific failure on the hard / out-of-frame poses: Apple
    /// Vision drops a body joint that has left the frame, and `adapt(observation)`
    /// returns `nil` for the entire frame (no skeleton, no feedback) — where Android
    /// ML Kit instead keeps the joint at a low `inFrameLikelihood` and only gates
    /// that one arm. This logs each of the six joints' presence + confidence on a
    /// dropped frame so the write-up can tell those two regimes apart (the open
    /// question behind the elbow-fallback and graceful-degradation levers). No
    /// behaviour change; nothing logs when developer mode is off. Reads the
    /// (quarantined) capture path deliberately — the signal is *which joints Vision
    /// omitted*, which is invisible post-adapter.
    private func logDroppedFrame(_ observation: VNHumanBodyPoseObservation) {
        guard UserDefaults.standard.bool(forKey: AppSettingsKeys.developerMode) else { return }
        let joints: [(String, VNHumanBodyPoseObservation.JointName)] = [
            ("LSh", .leftShoulder), ("LEl", .leftElbow), ("LWr", .leftWrist),
            ("RSh", .rightShoulder), ("REl", .rightElbow), ("RWr", .rightWrist),
        ]
        let points = (try? observation.recognizedPoints(.all)) ?? [:]
        let parts = joints.map { label, name -> String in
            guard let p = points[name] else { return "\(label)=absent" }
            return "\(label)=\(String(format: "%.2f", Double(p.confidence)))"
        }
        Self.dropLog.debug("DROP \(parts.joined(separator: " "), privacy: .public)")
    }

    /// Same `geometry-probe` category as `PreviewViewModel`'s consumer-side probe,
    /// so a single `log` filter captures both the dropped-frame lines and the
    /// post-adapter geometry lines (#101 / 6a-14).
    private static let dropLog = Logger(
        subsystem: "com.skylinetrailcomputing.semaphore", category: "geometry-probe")
}

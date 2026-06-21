import AVFoundation
import SwiftUI

/// Drives the live debug screen ([3.5], #23): owns the capture session and the
/// decoder, consumes the `AsyncStream<PoseFrame>`, runs the **rules** decoder
/// **per frame** (no `COMMIT_HOLD_MS` / `SMOOTHING_WINDOW` smoothing — that is
/// Epic 4), and publishes the raw readout the overlay + panel render.
///
/// Mode (LETTERS ↔ NUMERIC) is threaded across frames because that is the
/// decoder's own state machine (spec §4.5), not temporal smoothing: holding the
/// NUMERALS pose flips to numeric and the J pose flips back, exactly as the
/// parity sequence vectors exercise it.
@MainActor
final class PreviewViewModel: ObservableObject {
    enum Status: Equatable {
        case starting
        case denied
        case noSigner
        case tracking
        case failed(String)
    }

    @Published private(set) var status: Status = .starting
    @Published private(set) var keypoints: Keypoints?
    @Published private(set) var leftId: Int?
    @Published private(set) var rightId: Int?
    @Published private(set) var character: String = ""
    @Published private(set) var mode: Mode = .letters

    let capture = PoseCaptureSession()
    private var decoder: SemaphoreDecoder?
    private var streamTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var lastFrameAt = Date.distantPast

    /// If no full skeleton arrives for this long, declare "no signer detected"
    /// (NFR4). The capture stream simply stops yielding when a signer leaves the
    /// frame (the adapter returns `nil` without a full upper body), so absence
    /// is detected by a freshness watchdog rather than an explicit event.
    private let signerTimeout: TimeInterval = 0.5

    func start() async {
        if decoder == nil {
            do {
                decoder = try ContractLoader.makeDecoder()
            } catch {
                status = .failed("Couldn’t load the semaphore contract: \(error)")
                return
            }
        }
        guard await ensureCameraAccess() else {
            status = .denied
            return
        }
        do {
            let stream = try await capture.start()
            status = .noSigner
            lastFrameAt = .distantPast
            startWatchdog()
            streamTask = Task { [weak self] in
                for await frame in stream {
                    self?.handle(frame)
                }
            }
        } catch {
            status = .failed("Camera unavailable: \(error)")
        }
    }

    func stop() async {
        streamTask?.cancel()
        streamTask = nil
        watchdogTask?.cancel()
        watchdogTask = nil
        await capture.stop()
    }

    private func handle(_ frame: PoseFrame) {
        guard let decoder else { return }
        // `lastFrameAt` is the watchdog's freshness clock (real-time liveness),
        // distinct from `frame.tMs` (the frame-aligned committer clock, #34): the
        // committer consumes `frame.tMs` when it is wired in at the live layer (#35).
        lastFrameAt = Date()
        let kp = frame.keypoints
        let result = decoder.decodeFrame(kp, mode: mode)
        mode = result.mode
        keypoints = kp
        leftId = result.ids[0]
        rightId = result.ids[1]
        character = result.emit
        status = .tracking
    }

    private func startWatchdog() {
        watchdogTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self else { return }
                if Date().timeIntervalSince(self.lastFrameAt) > self.signerTimeout,
                    self.status == .tracking || self.status == .noSigner
                {
                    self.status = .noSigner
                    self.keypoints = nil
                    self.leftId = nil
                    self.rightId = nil
                    self.character = ""
                }
            }
        }
    }

    private func ensureCameraAccess() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
        default: return false
        }
    }
}

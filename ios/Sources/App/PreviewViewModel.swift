import AVFoundation
import SwiftUI

/// Drives the live debug screen ([3.5], #23): owns the capture session, the
/// decoder, and the temporal `Committer` (#4.5). It consumes the
/// `AsyncStream<PoseFrame>` and, per frame, runs the mode-independent `classify`
/// and feeds the resulting symbol + `frame.tMs` (#4.4) to the committer. The
/// committer's emitted text accumulates into `committedText` (the user-visible
/// output); the raw per-frame ids + character remain published for the white-box
/// debug readout.
///
/// Mode (LETTERS ↔ NUMERIC) now lives in the committer and flips only on a
/// *committed* control pose (NUMERALS / the J letters-shift) — no longer threaded
/// here per frame, which is what killed the #23 single-frame mode flicker. The
/// badge reads `committer.currentMode`.
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
    /// The committed output the user reads — the accumulation of every non-empty
    /// emit the committer returns. Persists across a no-signer `reset()` (that
    /// clears the committer's *internal* state, not the text already signed).
    @Published private(set) var committedText: String = ""

    let capture = PoseCaptureSession()
    private var decoder: SemaphoreDecoder?
    private var committer: Committer?
    private var streamTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var lastFrameAt = Date.distantPast

    /// If no full skeleton arrives for this long, declare "no signer detected"
    /// (NFR4). The capture stream simply stops yielding when a signer leaves the
    /// frame (the adapter returns `nil` without a full upper body), so absence
    /// is detected by a freshness watchdog rather than an explicit event.
    private let signerTimeout: TimeInterval = 0.5

    func start() async {
        if committer == nil {
            do {
                let decoder = try ContractLoader.makeDecoder()
                let timing = try ContractLoader.makeCommitTiming()
                self.decoder = decoder
                self.committer = Committer(decoder: decoder, timing: timing)
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

    /// Clears the committed readout (debug affordance, #35). Independent of the
    /// committer's internal state — just empties the displayed accumulation.
    func clearCommitted() {
        committedText = ""
    }

    func stop() async {
        streamTask?.cancel()
        streamTask = nil
        watchdogTask?.cancel()
        watchdogTask = nil
        // Tearing down cancels the watchdog, so on a `.task` re-fire after
        // `.onDisappear` `start()` skips the `committer == nil` rebuild and would
        // resume against a stale window + `candidateSince`. The next frame's `tMs`
        // has jumped seconds ahead, instantly clearing the hold gate → a ghost
        // commit. Reset here so every session starts clean. (Android needs no
        // equivalent: its watchdog keeps running while backgrounded and resets
        // before the 600ms hold, since SIGNER_TIMEOUT 500ms < COMMIT_HOLD_MS.)
        committer?.reset()
        await capture.stop()
    }

    private func handle(_ frame: PoseFrame) {
        guard let decoder, let committer else { return }
        // `lastFrameAt` is the watchdog's freshness clock (real-time liveness),
        // distinct from `frame.tMs` (the frame-aligned committer clock, #34) the
        // committer consumes below.
        lastFrameAt = Date()
        let kp = frame.keypoints

        // Temporal path: classify the mode-independent pose, then let the committer
        // smooth/hold/debounce it. A non-empty return is a committed letter/digit/space.
        let symbol = decoder.classify(kp)
        let emitted = committer.process(symbol, at: frame.tMs)
        if !emitted.isEmpty { committedText += emitted }

        // Raw white-box readout: ids are mode-independent; only the per-frame
        // character is interpreted, in the committer's (possibly just-flipped) mode.
        let raw = decoder.decodeFrame(kp, mode: committer.currentMode)
        keypoints = kp
        leftId = raw.ids[0]
        rightId = raw.ids[1]
        character = raw.emit
        mode = committer.currentMode
        status = .tracking
    }

    private func startWatchdog() {
        watchdogTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self else { return }
                if Date().timeIntervalSince(self.lastFrameAt) > self.signerTimeout,
                    self.status == .tracking
                {
                    // Fire once on the tracking → no-signer transition (mirrors
                    // Android's `signerPresent` self-disarm); `status` then stays
                    // `.noSigner` until a frame arrives, so reset isn't re-fired
                    // every tick. True signer-loss: hard-reset the committer (clear
                    // window, mode → LETTERS). `committedText` is deliberately
                    // preserved so the word just signed stays readable after the
                    // arms drop.
                    self.status = .noSigner
                    self.committer?.reset()
                    self.keypoints = nil
                    self.leftId = nil
                    self.rightId = nil
                    self.character = ""
                    self.mode = self.committer?.currentMode ?? .letters
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

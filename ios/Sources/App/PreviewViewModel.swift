import AVFoundation
import SwiftUI
import os

/// A snapshot of the drill HUD (6a-2, #70), republished on every commit while a
/// drill is active. `nil` on the free-form Learn/Interpret screens. Mirrors the
/// `DrillSession` getters so the view never touches the engine directly.
struct DrillHUD: Equatable {
    /// The character to sign next (`DrillSession.currentTarget`); `nil` once complete.
    let target: Character?
    /// Targets matched so far (the resulting index after the last commit).
    let index: Int
    /// Total targets in the passage.
    let count: Int
    /// Reached the end of the passage — the celebrate signal.
    let complete: Bool
}

/// The transient success/miss highlight for the drill target card. Cleared a beat
/// after each commit so the card settles back to neutral.
enum DrillFlash: Equatable { case hit, miss }

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
    /// Live drill HUD state (6a-2, #70), or `nil` when this screen isn't a drill.
    /// Republished on every commit the drill observes; the view reads only this.
    @Published private(set) var drillHUD: DrillHUD?
    /// The transient highlight for the last drill commit; auto-clears after a beat.
    @Published private(set) var drillFlash: DrillFlash?
    /// Seconds left on the post-completion auto-reset countdown (#97, 6a-12), or
    /// `nil` when no countdown is running (the setting is off, the user tapped
    /// "Stay", or the drill already reset). The celebrate card renders this as
    /// "Resetting in N…". Armed by the view (`startAutoReset()`) when the drill
    /// completes and the setting is on; the view owns the setting read so the ON
    /// default lives in `@AppStorage`, not in a raw `UserDefaults` lookup.
    @Published private(set) var autoResetRemaining: Int?

    let capture = PoseCaptureSession()
    /// Which lens to drive. Front for the Learn screen (the default keeps that
    /// path unchanged); Interpret passes `.back` for the rear lens (#56). Lens
    /// selection only — the mirror stays quarantined in the adapter (spec §3.2).
    private let cameraPosition: AVCaptureDevice.Position
    /// Whether the preview is mirrored for display, derived from the lens (#57).
    /// Front uses the selfie mirror (the signer sees themselves naturally); the
    /// rear lens must not (you're watching someone else). Drives the preview's
    /// `isVideoMirrored` and the `SkeletonOverlay` x-map together so the skeleton
    /// registers against what's shown. Display-only — distinct from the adapter
    /// mirror, which stays quarantined regardless of lens (spec §3.2, NFR3).
    var isPreviewMirrored: Bool { cameraPosition == .front }
    private var decoder: SemaphoreDecoder?
    private var committer: Committer?
    /// Contract-derived assist-figure geometry (#73 / 6a-5, transition cues #100),
    /// loaded only for drill screens. `nil` on free-form Learn/Interpret and if the
    /// (frozen, already decode-critical) contract somehow fails to parse — the figure
    /// just doesn't draw. Read-only over the alphabet, strictly beside the decode core.
    private var assistGeometry: AssistGeometry?
    /// The drill passage (#100), kept so the assist can walk the whole target
    /// sequence to compute the transition cues for the current index. `nil` off a
    /// drill screen. The `DrillSession` owns the live index; this owns the text.
    private let drillTargets: String?
    /// The drill engine (6a-1, ADR 0007) for a passage-drill screen, or `nil` for
    /// free-form Learn/Interpret. Strictly downstream of the committer: it observes
    /// only committed characters and never the decode/adapter/commit core.
    private let drill: DrillSession?
    private var streamTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    /// Clears `drillFlash` a beat after the last commit; cancelled if another lands.
    private var flashTask: Task<Void, Never>?
    /// Drives the post-completion auto-reset countdown (#97, 6a-12); cancelled by
    /// "Stay", by "Practice again", and on teardown. The Android twin is the
    /// `LaunchedEffect(complete)` countdown in `SemaphoreScreen`.
    private var autoResetTask: Task<Void, Never>?
    private var lastFrameAt = Date.distantPast

    /// The post-completion auto-reset countdown length (#97, 6a-12). Kept in lockstep
    /// with Android's `AUTO_RESET_COUNTDOWN_SECONDS` (no parity vector needed — it's a
    /// view affordance, like `drillFlash`'s ~450 ms clear).
    private static let autoResetCountdownSeconds = 3

    /// Dev-only coordinate probe (#58 / ADR 0006). The geometry-seam smoke for the
    /// rear lens: a flipped analysis buffer would mirror-twin every asymmetric
    /// letter *silently*, so this logs the post-adapter geometry the decode rests
    /// on so a maintainer can read it numerically off-device.
    private static let probeLog = Logger(
        subsystem: "com.skylinetrailcomputing.semaphore", category: "geometry-probe")
    /// Throttle the probe: one line per distinct (ids + coarse arm-angle) signature,
    /// so a hard letter that sits at `·` because its wrist is gated still logs once
    /// per distinct attempt instead of being swallowed by an unchanged-ids throttle
    /// (#101, 6a-14 diagnostic).
    private var lastProbeSig: String?

    /// If no full skeleton arrives for this long, declare "no signer detected"
    /// (NFR4). The capture stream simply stops yielding when a signer leaves the
    /// frame (the adapter returns `nil` without a full upper body), so absence
    /// is detected by a freshness watchdog rather than an explicit event.
    private let signerTimeout: TimeInterval = 0.5

    /// `drillTargets` is the passage to drill (6a-2, #70); `nil` is free-form
    /// Learn/Interpret (unchanged). Each platform builds its own `DrillSession`
    /// from the same hardcoded starter passage — the custom source lands in 6a-3.
    init(cameraPosition: AVCaptureDevice.Position = .front, drillTargets: String? = nil) {
        self.cameraPosition = cameraPosition
        self.drillTargets = drillTargets
        if let drillTargets {
            let session = DrillSession(targets: drillTargets)
            self.drill = session
            self.drillHUD = DrillHUD(
                target: session.currentTarget,
                index: session.index,
                count: session.count,
                complete: session.isComplete)
        } else {
            self.drill = nil
        }
    }

    func start() async {
        if committer == nil {
            do {
                let decoder = try ContractLoader.makeDecoder()
                // The committer timing forks by lens (ADR 0009): rear → Interpret
                // (faster commit), front → Learn. Selected here, the single
                // construction site (the view model is re-created per lens, so the
                // committer's profile is fixed for its lifetime). Passed explicitly
                // so a missed default can't silently run the rear lens at Learn speed.
                let profile = TimingProfile(cameraPosition: cameraPosition)
                let timing = try ContractLoader.makeCommitTiming(profile: profile)
                self.decoder = decoder
                self.committer = Committer(decoder: decoder, timing: timing)
                // Only drill screens render the assist figure (#73); skip the parse
                // otherwise. A failure here is non-fatal — the figure just won't draw.
                if drill != nil {
                    self.assistGeometry = try? ContractLoader.makeAssistGeometry()
                }
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
            let stream = try await capture.start(cameraPosition: cameraPosition)
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

    /// The ordered assist filmstrip for the drill target at `index` (#73 / 6a-5 +
    /// transition cues #100): an optional transition pre-cue, then the target pose.
    /// Empty when there's nothing to draw. This is the **structural front-lens
    /// gate**: the figure only makes sense over the mirrored selfie preview, so a
    /// non-front lens (a hypothetical future rear drill) returns `[]` rather than an
    /// un-mirrored, wrong-handed pose. Drill-only by construction — `assistGeometry`
    /// and `drillTargets` are both `nil` off a drill screen.
    func assistCues(at index: Int) -> [AssistCue] {
        guard isPreviewMirrored, let targets = drillTargets, let geometry = assistGeometry
        else { return [] }
        return geometry.cues(targets: targets, index: index)
    }

    /// Feed one committed character to the drill (6a-2, #70). Stay-until-success is
    /// the engine's own policy — a hit advances, a miss is a no-op — so the view
    /// only renders the resulting HUD + a brief success/miss flash. No-op once the
    /// passage is complete (so post-celebrate commits don't flash).
    private func observeDrill(_ emitted: String) {
        guard let drill, !drill.isComplete else { return }
        // A rest between letters commits a SPACE; don't penalise that as a miss
        // unless a space is actually the current target (6a-2 smoke nit). A rest is
        // natural signing rhythm, not a wrong attempt — only a wrong *letter* misses.
        // A space still advances when the target IS a space (multi-word 6a-3 sources).
        if emitted == " ", drill.currentTarget != " " { return }
        let step = drill.observe(emitted)
        drillHUD = DrillHUD(
            target: drill.currentTarget,
            index: step.index,
            count: drill.count,
            complete: step.complete)
        flash(step.matched ? .hit : .miss)
    }

    private func flash(_ kind: DrillFlash) {
        drillFlash = kind
        flashTask?.cancel()
        flashTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(450))
            guard let self, !Task.isCancelled else { return }
            self.drillFlash = nil
        }
    }

    /// Replay the passage from the first target (the celebrate-screen "Practice
    /// again", 6a-2). Caller-driven `reset()` — same discipline as the committer's.
    /// Clears the committed readout and the committer window so the rerun is clean.
    /// Also cancels any running auto-reset countdown (#97), so an in-flight countdown
    /// doesn't fire a second reset after a manual "Practice again".
    func resetDrill() {
        guard let drill else { return }
        cancelAutoReset()
        drill.reset()
        committer?.reset()
        committedText = ""
        drillFlash = nil
        flashTask?.cancel()
        drillHUD = DrillHUD(
            target: drill.currentTarget,
            index: drill.index,
            count: drill.count,
            complete: drill.isComplete)
    }

    /// Arm the post-completion auto-reset countdown (#97, 6a-12). Called by the view
    /// on the drill's COMPLETE signal when the `autoResetOnComplete` setting is on.
    /// Counts `autoResetCountdownSeconds` → 0 (publishing each second to the celebrate
    /// card as "Resetting in N…"), then runs the existing `resetDrill()` for a
    /// hands-free replay of the same passage. Cancelable: "Stay" calls
    /// `cancelAutoReset()`; "Practice again" resets immediately (which cancels too).
    func startAutoReset() {
        autoResetTask?.cancel()
        autoResetRemaining = Self.autoResetCountdownSeconds
        autoResetTask = Task { [weak self] in
            while let current = self?.autoResetRemaining, current > 0 {
                try? await Task.sleep(for: .seconds(1))
                guard let self, !Task.isCancelled else { return }
                let next = current - 1
                if next <= 0 {
                    self.resetDrill()  // clears `autoResetRemaining`, ending the loop
                } else {
                    self.autoResetRemaining = next
                }
            }
        }
    }

    /// Cancel a running auto-reset countdown (#97) without resetting the drill — the
    /// "Stay" affordance. Leaves the celebrate card up (the leave-as-is behaviour for
    /// this run); a no-op when no countdown is active.
    func cancelAutoReset() {
        autoResetTask?.cancel()
        autoResetTask = nil
        autoResetRemaining = nil
    }

    func stop() async {
        streamTask?.cancel()
        streamTask = nil
        watchdogTask?.cancel()
        watchdogTask = nil
        // Drop any in-flight auto-reset countdown (#97) so it can't fire `resetDrill()`
        // against a torn-down screen after the view disappears.
        cancelAutoReset()
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
        if !emitted.isEmpty {
            committedText += emitted
            observeDrill(emitted)
        }

        // Raw white-box readout: ids are mode-independent; only the per-frame
        // character is interpreted, in the committer's (possibly just-flipped) mode.
        let raw = decoder.decodeFrame(kp, mode: committer.currentMode)
        keypoints = kp
        leftId = raw.ids[0]
        rightId = raw.ids[1]
        character = raw.emit
        mode = committer.currentMode
        status = .tracking

        logProbe(kp: kp, leftId: raw.ids[0], rightId: raw.ids[1], char: raw.emit)
    }

    /// Dev-only coordinate probe (#58 / ADR 0006; extended for #101 / 6a-14): when
    /// developer mode is on, log the post-adapter, signer's-perspective geometry the
    /// decode rests on. It serves two diagnostics:
    ///
    /// 1. **Mirror seam (#58):** for a correctly-oriented read the signer's right
    ///    shoulder sits at greater x than the left (`R.shC`/`wrX` columns), and an
    ///    arm extended to the signer's right has `wrX > shoulder x`; a flipped rear
    ///    buffer inverts both.
    /// 2. **Pose-friction buckets (#101):** per arm it adds wrist/elbow/shoulder
    ///    confidence, BOTH the `shoulder→wrist` (`aWr`) and `shoulder→elbow` (`aEl`)
    ///    angles, and the raw wrist x/y. This lets the on-device write-up bucket each
    ///    hard letter — gated wrist (`wrC` below `MIN_KEYPOINT_CONFIDENCE`),
    ///    mislocalized/crossed wrist (`aWr` vs `aEl` disagree on a straight arm),
    ///    edge-clip (`wrX`/`wrY` at the 0/1 frame edge — the portrait-wingspan case)
    ///    — and shows whether the elbow stays reliable where the wrist fails (the
    ///    elbow-fallback prior). The whole-frame-drop case (Vision omits a joint
    ///    entirely) never reaches here; `PoseCaptureSession` logs that separately.
    ///
    /// Reads *post-adapter* coords deliberately — the adapter transform is
    /// byte-pinned by the native fixtures, so any chirality/geometry fault surfaces
    /// here, in the consumer, with no need to instrument the quarantined capture path
    /// (spec §3.2). No behaviour change; nothing logs when developer mode is off.
    private func logProbe(kp: Keypoints, leftId: Int?, rightId: Int?, char: String) {
        guard UserDefaults.standard.bool(forKey: AppSettingsKeys.developerMode) else { return }
        func angle(_ sh: Keypoint, _ tip: Keypoint) -> Double {
            atan2(tip.y - sh.y, tip.x - sh.x) * 180 / .pi
        }
        let lAWr = angle(kp.leftShoulder, kp.leftWrist)
        let lAEl = angle(kp.leftShoulder, kp.leftElbow)
        let rAWr = angle(kp.rightShoulder, kp.rightWrist)
        let rAEl = angle(kp.rightShoulder, kp.rightElbow)
        // Throttle on ids + coarse (~15°) wrist-angle buckets, so a distinct pose
        // attempt logs once even while it sits at `·` (the gated-wrist hard letter).
        func bucket(_ a: Double) -> Int { Int((a / 15).rounded()) }
        let sig =
            "\(leftId.map(String.init) ?? "-"),\(rightId.map(String.init) ?? "-"),"
            + "\(bucket(lAWr)),\(bucket(rAWr))"
        if sig == lastProbeSig { return }
        lastProbeSig = sig
        let lens = cameraPosition == .back ? "rear" : "front"
        func f(_ v: Double) -> String { String(format: "%.2f", v) }
        func a(_ v: Double) -> String { String(format: "%.1f", v) }
        let l = leftId.map(String.init) ?? "—"
        let r = rightId.map(String.init) ?? "—"
        let c = char.isEmpty ? "·" : char
        let msg =
            "lens=\(lens) ids=[\(l),\(r)] char=\(c) | "
            + "L shC=\(f(kp.leftShoulder.confidence)) wrC=\(f(kp.leftWrist.confidence)) "
            + "elC=\(f(kp.leftElbow.confidence)) aWr=\(a(lAWr)) aEl=\(a(lAEl)) "
            + "wrX=\(f(kp.leftWrist.x)) wrY=\(f(kp.leftWrist.y)) | "
            + "R shC=\(f(kp.rightShoulder.confidence)) wrC=\(f(kp.rightWrist.confidence)) "
            + "elC=\(f(kp.rightElbow.confidence)) aWr=\(a(rAWr)) aEl=\(a(rAEl)) "
            + "wrX=\(f(kp.rightWrist.x)) wrY=\(f(kp.rightWrist.y))"
        Self.probeLog.debug("\(msg, privacy: .public)")
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

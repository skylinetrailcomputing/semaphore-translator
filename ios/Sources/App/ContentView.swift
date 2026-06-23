import AVFoundation
import SwiftUI

/// The shared camera+decode screen — driven by **Learn** (front lens, #48) and
/// **Interpret** (rear lens, #46/#60), which differ only by `cameraPosition` +
/// display mirror (lens-derived in `PreviewViewModel`) and the empty-state hint.
/// The committed text is the visual hero; the "no signer detected" state (NFR4)
/// and clear/reset (FR5) are kept.
///
/// The Epic-4 debug chrome — the 6-keypoint skeleton overlay and the raw
/// per-frame readout (L/R position ids, mode badge, indeterminate `·`) — is
/// hidden in this clean view and gated behind `developerMode`, surfaced by the
/// Developer-mode toggle (#5d). That overlay is still the only visual
/// confirmation of the adapter flips (autonomy guardrail-d), so it is gated, not
/// deleted. `developerMode` reads the same `@AppStorage` flag the Settings
/// toggle writes (#50), so the overlay reflects the persisted setting and
/// updates live when it is flipped. The Android twin is `SemaphoreScreen`.
struct ContentView: View {
    @StateObject private var model: PreviewViewModel
    @AppStorage(AppSettingsKeys.developerMode) private var developerMode = false
    /// Whether to draw the contract-derived assist figure in the drill card (#73,
    /// 6a-5). Defaults ON — it's the primary teaching aid for Learn; the Settings
    /// toggle writes the same `@AppStorage` key. Only consulted on a drill screen,
    /// and `model.assistPose` adds the front-lens gate, so this is purely the
    /// user's on/off.
    @AppStorage(AppSettingsKeys.showAssistFigure) private var showAssistFigure = true
    /// The empty-state prompt — mode-specific copy ("Sign a letter…" for Learn,
    /// "Point at someone signing" for Interpret). The only behavioral difference
    /// between the two modes beyond lens + display mirror.
    private let emptyHint: String
    /// Whether this screen is a passage drill (6a-2, #70) — derived from a non-nil
    /// `drillTargets`. Drives the drill HUD; the free-form path is otherwise intact.
    private let isDrill: Bool

    /// `cameraPosition` is injected into the `@StateObject` via
    /// `StateObject(wrappedValue:)` (the autoclosure is evaluated once, so each
    /// mode's NavigationLink destination gets its own view model). Defaults keep
    /// the Learn call site (`ContentView()`) front-facing and unchanged.
    /// `drillTargets` (6a-2, #70) turns the same screen into a passage drill.
    init(
        cameraPosition: AVCaptureDevice.Position = .front,
        emptyHint: String = "Sign a letter to begin",
        drillTargets: String? = nil
    ) {
        _model = StateObject(
            wrappedValue: PreviewViewModel(
                cameraPosition: cameraPosition, drillTargets: drillTargets))
        self.emptyHint = emptyHint
        self.isDrill = drillTargets != nil
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            switch model.status {
            case .starting:
                ProgressView("Starting camera…")
                    .tint(.white)
                    .foregroundStyle(.white)
            case .denied:
                message(
                    title: "Camera access needed",
                    detail:
                        "Semaphore Translator reads flag positions from the camera. "
                        + "Enable camera access in Settings to use the live preview.")
            case .failed(let detail):
                message(title: "Camera unavailable", detail: detail)
            case .noSigner, .tracking:
                cameraStack
            }
        }
        .task { await model.start() }
        .onDisappear { Task { await model.stop() } }
    }

    private var cameraStack: some View {
        ZStack {
            CameraPreviewView(capture: model.capture, mirrored: model.isPreviewMirrored)
                .ignoresSafeArea()
            if developerMode {
                SkeletonOverlay(keypoints: model.keypoints, mirrored: model.isPreviewMirrored)
                    .ignoresSafeArea()
            }
            if model.status == .noSigner, !(isDrill && (model.drillHUD?.complete ?? false)) {
                Text("No signer detected")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.black.opacity(0.55), in: Capsule())
            }
            VStack(spacing: 12) {
                if isDrill, let hud = model.drillHUD, !hud.complete {
                    drillTargetCard(hud)
                }
                Spacer()
                committedHero
                if developerMode { readout }
            }
            .padding()
            if isDrill, let hud = model.drillHUD, hud.complete {
                celebrationOverlay
            }
        }
    }

    /// The drill HUD hero (6a-2, #70): the letter to sign next, a progress bar
    /// through the passage, and an `index / count` tally. The card flashes green on
    /// a matched commit and red on a miss (stay-until-success — a miss never
    /// advances), then settles back to neutral.
    private func drillTargetCard(_ hud: DrillHUD) -> some View {
        VStack(spacing: 10) {
            Text("Sign this letter")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.75))
            Text(targetGlyph(hud.target))
                .font(.system(size: 72, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
            // The contract-derived assist figure (#73, 6a-5): the pose to make for
            // this target, drawn at the exact alphabet angles. `assistPose` is the
            // structural gate (nil off the front lens / off a drill screen);
            // `showAssistFigure` is the user's on/off.
            if showAssistFigure, let pose = model.assistPose(for: hud.target) {
                AssistFigureView(pose: pose)
                    .frame(height: 132)
                    .padding(.vertical, 2)
            }
            ProgressView(value: Double(hud.index), total: Double(max(hud.count, 1)))
                .tint(.white)
            Text("\(hud.index) / \(hud.count)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white.opacity(0.7))
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(drillCardColor, in: RoundedRectangle(cornerRadius: 20))
        .animation(.easeOut(duration: 0.2), value: model.drillFlash)
        .animation(.easeOut(duration: 0.2), value: hud.index)
    }

    /// Neutral by default; tinted briefly by the last commit's success/miss flash.
    private var drillCardColor: Color {
        switch model.drillFlash {
        case .hit: return Color.green.opacity(0.55)
        case .miss: return Color.red.opacity(0.5)
        case .none: return Color.black.opacity(0.55)
        }
    }

    /// The current target as a glyph: a visible `␣` for a SPACE target, a checkmark
    /// once the passage is complete (no current target).
    private func targetGlyph(_ target: Character?) -> String {
        guard let target else { return "✓" }
        return target == " " ? "␣" : String(target)
    }

    /// The small celebration shown on COMPLETE (6a-2): a centered card with a
    /// replay affordance that calls `resetDrill()` to run the passage again.
    private var celebrationOverlay: some View {
        VStack(spacing: 16) {
            Text("🎉").font(.system(size: 64))
            Text("Passage complete!")
                .font(.title2.bold())
                .foregroundStyle(.white)
            Button(action: { model.resetDrill() }) {
                Label("Practice again", systemImage: "arrow.counterclockwise")
                    .font(.headline)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(.white.opacity(0.2), in: Capsule())
                    .foregroundStyle(.white)
            }
        }
        .padding(32)
        .background(.black.opacity(0.78), in: RoundedRectangle(cornerRadius: 24))
        .transition(.scale.combined(with: .opacity))
    }

    /// The committed output (#4.5) as the Learn screen's hero — the debounced
    /// text the committer emits, large and centered. Empty shows a gentle hint;
    /// non-empty shows the text plus Clear (FR5). Head truncation keeps the
    /// most-recent characters visible as the string grows.
    private var committedHero: some View {
        VStack(spacing: 12) {
            if model.committedText.isEmpty {
                Text(emptyHint)
                    .font(.system(.title3, design: .rounded))
                    .foregroundStyle(.white.opacity(0.6))
            } else {
                Text(model.committedText)
                    .font(.system(size: 40, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .minimumScaleFactor(0.5)
                    .truncationMode(.head)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                // Clear is the free-form reset; in a drill, "Practice again" on the
                // celebrate card is the reset path, so Clear is hidden to avoid
                // desyncing the visible text from the drill's target index.
                if !isDrill {
                    Button(action: { model.clearCommitted() }) {
                        Label("Clear", systemImage: "xmark.circle.fill")
                            .font(.subheadline)
                            .foregroundStyle(.white.opacity(0.8))
                    }
                }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 20))
    }

    /// Per-frame readout: the position ids per arm, the emitted character, and
    /// the decoder mode. Raw, un-smoothed — a debug surface, not committed text.
    private var readout: some View {
        HStack(alignment: .center, spacing: 20) {
            VStack(alignment: .leading, spacing: 4) {
                Label("L \(idText(model.leftId))   R \(idText(model.rightId))", systemImage: "figure.wave")
                    .font(.system(.subheadline, design: .monospaced))
                Text(model.mode == .numeric ? "NUMERIC" : "LETTERS")
                    .font(.caption2.bold())
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(.white.opacity(0.2), in: Capsule())
            }
            Spacer()
            Text(displayChar)
                .font(.system(size: 48, weight: .bold, design: .rounded))
                .frame(minWidth: 56)
        }
        .foregroundStyle(.white)
        .padding()
        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 16))
    }

    /// `" "` (REST/space) and `""` (indeterminate) need visible glyphs.
    private var displayChar: String {
        if model.character == " " { return "␣" }
        if model.character.isEmpty { return "·" }
        return model.character
    }

    private func idText(_ id: Int?) -> String { id.map(String.init) ?? "—" }

    private func message(title: String, detail: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "flag.2.crossed.fill").font(.largeTitle)
            Text(title).font(.headline)
            Text(detail)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .foregroundStyle(.white)
        .padding(32)
    }
}

#Preview {
    ContentView()
}

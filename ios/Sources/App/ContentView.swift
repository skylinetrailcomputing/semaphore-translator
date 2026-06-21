import SwiftUI

/// The **Learn** screen ([5b], #48) — the front-camera view a signer uses to
/// sign into the camera and read their decode. The committed text is the visual
/// hero; the "no signer detected" state (NFR4) and clear/reset (FR5) are kept.
///
/// The Epic-4 debug chrome — the 6-keypoint skeleton overlay and the raw
/// per-frame readout (L/R position ids, mode badge, indeterminate `·`) — is
/// hidden in this clean view and gated behind `developerMode`, surfaced by the
/// Developer-mode toggle (#5d). That overlay is still the only visual
/// confirmation of the adapter flips (autonomy guardrail-d), so it is gated, not
/// deleted. `developerMode` is a plain stored flag for now; #5d binds it to a
/// persisted setting. The Android twin is `SemaphoreScreen`.
struct ContentView: View {
    @StateObject private var model = PreviewViewModel()
    var developerMode = false

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
            CameraPreviewView(capture: model.capture)
                .ignoresSafeArea()
            if developerMode {
                SkeletonOverlay(keypoints: model.keypoints)
                    .ignoresSafeArea()
            }
            if model.status == .noSigner {
                Text("No signer detected")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.black.opacity(0.55), in: Capsule())
            }
            VStack(spacing: 12) {
                Spacer()
                committedHero
                if developerMode { readout }
            }
            .padding()
        }
    }

    /// The committed output (#4.5) as the Learn screen's hero — the debounced
    /// text the committer emits, large and centered. Empty shows a gentle hint;
    /// non-empty shows the text plus Clear (FR5). Head truncation keeps the
    /// most-recent characters visible as the string grows.
    private var committedHero: some View {
        VStack(spacing: 12) {
            if model.committedText.isEmpty {
                Text("Sign a letter to begin")
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
                Button(action: { model.clearCommitted() }) {
                    Label("Clear", systemImage: "xmark.circle.fill")
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.8))
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

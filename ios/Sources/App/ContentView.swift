import SwiftUI

/// The live debug screen ([3.5], #23): camera preview + 6-keypoint skeleton
/// overlay + per-frame decoded readout, plus the "no signer detected" state
/// (NFR4). This is the human smoke surface for "is the mirror actually right in
/// the live app" — there is no PR-preview deploy, so the on-device overlay is
/// the only visual confirmation of the adapter flips (autonomy guardrail-d).
struct ContentView: View {
    @StateObject private var model = PreviewViewModel()

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
            SkeletonOverlay(keypoints: model.keypoints)
                .ignoresSafeArea()
            if model.status == .noSigner {
                Text("No signer detected")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.black.opacity(0.55), in: Capsule())
            }
            VStack {
                Spacer()
                readout
            }
            .padding()
        }
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

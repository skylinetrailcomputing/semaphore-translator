import SwiftUI

/// The passive **About** surface ([6b-4], #82) — the always-reachable counterpart
/// to the first-launch disclaimer gate ([6b-9], #87). Reached from the Settings
/// surface (today's `SettingsView`; the 6a-4 regular-user surface, #72, will
/// re-home the entry row). Shows the app version/build, a one-line AS-IS beta
/// notice, and links to the *hosted* EULA + Privacy Policy. The links reuse the
/// URLs from the bundled `disclaimer.json` (via `DisclaimerDocument`) — the same
/// single source the gate uses, so About and the gate can never point at
/// different hosts. The Android twin is `AboutScreen`.
struct AboutView: View {
    // Reload the bundled doc for its hosted URLs. By the time About is reachable
    // the gate has already loaded this successfully (you can't pass the gate
    // otherwise), so it succeeds in practice; `try?` degrades to hidden links
    // rather than failing the screen if it somehow can't load.
    private let document = try? DisclaimerDocument.loadBundled()

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Semaphore Translator")
                        .font(.headline)
                    Text("Version \(Self.versionLine)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            } footer: {
                Text(
                    "Pre-release beta software, provided “as is”, without "
                    + "warranty of any kind.")
            }

            if let document {
                Section {
                    Link("End User License Agreement", destination: document.eula)
                    Link("Privacy Policy", destination: document.privacy)
                } footer: {
                    Text(
                        "Camera frames are processed on your device and are never "
                        + "stored or transmitted.")
                }
                // Explicit accent: the app root tints to white for nav chevrons,
                // which would otherwise paint these links the same as body text —
                // the same reason the disclaimer gate tints its links cyan.
                .tint(.cyan)
            }
        }
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
    }

    /// "0.1.0 (1)" — the marketing version + build from the bundle's generated
    /// Info.plist (`MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` in project.yml),
    /// so it always reflects the installed build with no second source to keep in
    /// sync. The Android twin reads the same pair from `PackageManager`.
    private static var versionLine: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "—"
        let build = info?["CFBundleVersion"] as? String ?? "—"
        return "\(short) (\(build))"
    }
}

#Preview {
    NavigationStack { AboutView() }
}

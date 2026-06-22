import SwiftUI

/// Persisted app-settings keys. Centralized so the toggle that writes them and the
/// Learn screen (`ContentView`) that reads them bind to the *same* `UserDefaults`
/// key — no stringly drift between writer and reader. The Android twin is
/// `AppSettings`.
enum AppSettingsKeys {
    static let developerMode = "developerMode"
}

/// The **Developer** surface ([5d], #50) — the dev-ish settings split out of the
/// regular-user `SettingsView` by 6a-4 (#72). Hosts the Developer-mode toggle,
/// which gates the Learn screen's skeleton overlay + raw per-frame readout (the
/// maintainer smoke surface). It sits one level below the regular-user Settings so
/// a beta tester meets assist/About config first, not dev internals. The toggle is
/// `@AppStorage`, so flipping it here updates the same `UserDefaults` flag
/// `ContentView` reads — persisted across launches, no manual save. Contract knobs
/// (angle-tolerance, commit-hold) are deliberately out of scope (FR7); if ever
/// surfaced they'd be dev-only runtime overrides, never mutating the frozen JSON.
/// The Android twin is `DeveloperSettingsScreen`.
struct DeveloperSettingsView: View {
    @AppStorage(AppSettingsKeys.developerMode) private var developerMode = false

    var body: some View {
        Form {
            Section {
                // Restore the conventional green on-state track: the app root
                // sets `.tint(.white)` for the back chevrons, which otherwise
                // leaks in and paints the toggle's on-track white.
                Toggle("Developer mode", isOn: $developerMode)
                    .tint(.green)
            } footer: {
                Text(
                    "Shows the skeleton overlay and raw per-frame readout in "
                    + "Learn mode — the maintainer smoke surface.")
            }
        }
        .navigationTitle("Developer")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    NavigationStack { DeveloperSettingsView() }
}

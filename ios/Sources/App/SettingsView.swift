import SwiftUI

/// Persisted app-settings keys. Centralized so the Settings toggle and the Learn
/// screen (`ContentView`) bind to the *same* `UserDefaults` key — no stringly
/// drift between the writer and the reader. The Android twin is `AppSettings`.
enum AppSettingsKeys {
    static let developerMode = "developerMode"
}

/// The lean **Settings** surface ([5d], #50). Its only content for now is the
/// Developer-mode toggle, which gates the Learn screen's skeleton overlay + raw
/// per-frame readout (the maintainer smoke surface). The toggle is `@AppStorage`,
/// so flipping it here updates the same `UserDefaults` flag `ContentView` reads —
/// persisted across launches, no manual save. Contract knobs (angle-tolerance,
/// commit-hold) are deliberately out of scope (FR7); if ever surfaced they'd be
/// dev-only runtime overrides, never mutating the frozen JSON. The Android twin
/// is `SettingsScreen`.
struct SettingsView: View {
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
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    NavigationStack { SettingsView() }
}

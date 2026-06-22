import SwiftUI

/// The regular-user **Settings** surface ([6a-4], #72) — the gear destination from
/// Home. Distinct from the dev-ish `DeveloperSettingsView` it was split from: this
/// is the surface a beta tester sees, and the home for the assist/practice-mode
/// toggles (6a-5 #73, 6a-6 #74) that land in their own section above About. It
/// hosts the always-reachable **About** entry ([6b-4], #82) and a **Developer**
/// row that pushes the maintainer smoke surface one level deeper, off this
/// user-facing screen. The Android twin is `SettingsScreen`.
struct SettingsView: View {
    var body: some View {
        Form {
            // Assist & practice-mode toggles (6a-5 #73, 6a-6 #74) land here as
            // their own section — the primary regular-user content. Until then,
            // About + Developer are the only rows in the closed-beta cut.

            Section {
                // The always-reachable About/privacy surface ([6b-4], #82): app
                // version, AS-IS beta line, and the hosted EULA + Privacy links.
                NavigationLink("About") {
                    AboutView()
                }
                // Developer mode lives one level deeper ([5d], #50): it's a
                // maintainer smoke surface, not regular-user config, so it gets its
                // own screen rather than a toggle on this surface.
                NavigationLink("Developer") {
                    DeveloperSettingsView()
                }
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    NavigationStack { SettingsView() }
}

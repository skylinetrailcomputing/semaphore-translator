import SwiftUI

/// App entry. Mounts `RootView`, which shows the first-launch disclaimer gate
/// ([6b-9], #87) until accepted and then the no-camera **Home** screen ([5a],
/// #47) — the camera (and its permission prompt) only starts once the user taps
/// into a mode, so neither fires on first launch.
@main
struct SemaphoreTranslatorApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                // Every screen hardcodes a black background + white text, so the
                // app is dark by design. Pin the color scheme so the first
                // system-styled surface — the Settings `Form` (#50) — renders dark
                // too, which keeps the white back chevron legible (a light Form
                // would hide it).
                .preferredColorScheme(.dark)
        }
    }
}

/// The composition root. On launch it shows the first-launch disclaimer gate
/// ([6b-9], #87) until the user accepts the *current* disclaimer doc, then the
/// Home → mode fork. Consent is keyed on the doc's content hash (persisted in
/// `UserDefaults` via `@AppStorage`), so the gate reappears only when the bundled
/// `shared/disclaimer.json` changes. The Android twin is `SemaphoreApp`, which
/// gates its `NavHost` the same way.
private struct RootView: View {
    @AppStorage(DisclaimerKeys.acceptedHash) private var acceptedHash = ""
    @AppStorage(DisclaimerKeys.acceptedAt) private var acceptedAt = 0.0

    var body: some View {
        switch Self.disclaimer {
        case .success(let document)
        where !DisclaimerDocument.needsConsent(
            acceptedHash: acceptedHash, documentHash: document.contentHash):
            home
        case .success(let document):
            DisclaimerGateView(document: document) {
                acceptedAt = Date().timeIntervalSince1970
                // Set the hash last: it's what `needsConsent` checks, so writing it
                // flips the gate away once the timestamp is already recorded.
                acceptedHash = document.contentHash
            }
        case .failure:
            DisclaimerUnavailableView()
        }
    }

    private var home: some View {
        // The `NavigationStack` owns the Home → mode fork (Android's twin is
        // `SemaphoreApp`'s `NavHost`). White back chevrons over the dark mode
        // screens (the camera bar is transparent; see HomeView's Learn link).
        NavigationStack {
            HomeView()
        }
        .tint(.white)
    }

    /// Loaded once (a `static let` is lazy + run-once) so re-evaluating `body`
    /// doesn't re-read the bundle and re-hash on every render. A packaging failure
    /// is captured as `.failure` and rendered fail-closed by `body`.
    private static let disclaimer = Result { try DisclaimerDocument.loadBundled() }
}

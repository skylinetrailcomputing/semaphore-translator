import SwiftUI

/// App entry. Opens on the no-camera **Home** screen ([5a], #47); the camera —
/// and therefore the camera-permission prompt — only starts once the user taps
/// into a mode, so neither fires on first launch. The `NavigationStack` owns the
/// Home → mode fork (Android's twin is `SemaphoreApp`'s `NavHost`).
@main
struct SemaphoreTranslatorApp: App {
    var body: some Scene {
        WindowGroup {
            NavigationStack {
                HomeView()
            }
            // White back chevrons over the dark mode screens (the camera bar is
            // transparent; see HomeView's Learn link).
            .tint(.white)
        }
    }
}

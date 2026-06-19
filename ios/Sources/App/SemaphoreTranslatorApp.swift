import SwiftUI

/// Minimal app shell. The camera/Vision/Core ML capture path lands in a later
/// epic; for now the app exists so the parity harness has a host module to
/// `@testable import` (Issue #14, Epic 2).
@main
struct SemaphoreTranslatorApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

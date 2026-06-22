import SwiftUI

/// The Learn fork ([6a], #70). The "Sign / Learn" pill lands here and offers two
/// front-camera surfaces: **free practice** (sign anything, watch the decode) and
/// a **passage drill** (stay-until-success over a fixed passage, with a HUD +
/// celebrate). Both push the same `ContentView`; the drill differs only by an
/// injected passage. No camera or permission prompt fires here — that's deferred
/// to the pushed screen, exactly as on Home. The Android twin is `SemaphoreApp`'s
/// `LEARN_HUB` chooser.
struct LearnChooserView: View {
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 16) {
                Spacer()
                NavigationLink {
                    // Free-form Learn — the original surface, unchanged. Transparent,
                    // title-less nav bar so the camera fills the screen under just
                    // the back chevron.
                    ContentView()
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbarBackground(.hidden, for: .navigationBar)
                } label: {
                    ModePill(
                        title: "Free practice",
                        subtitle: "Sign anything — see the letters",
                        systemImage: "hand.wave")
                }
                NavigationLink {
                    // The same front-camera screen, driven as a drill by an injected
                    // passage (6a-2). The custom / sight-read sources land in 6a-3.
                    ContentView(
                        emptyHint: "Sign the letter shown above",
                        drillTargets: Self.starterPassage)
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbarBackground(.hidden, for: .navigationBar)
                } label: {
                    ModePill(
                        title: "Drill a passage",
                        subtitle: "Sign “\(Self.starterPassage)”, letter by letter",
                        systemImage: "list.bullet.rectangle")
                }
                Spacer()
            }
            .padding(24)
        }
        .navigationTitle("Learn")
        .navigationBarTitleDisplayMode(.inline)
    }

    /// The hardcoded starter passage for the 6a-2 spine; custom / sight-read
    /// sources are 6a-3 (#71). Kept in lockstep with the Android `STARTER_PASSAGE`.
    static let starterPassage = "HELLO"
}

#Preview {
    NavigationStack { LearnChooserView() }
}

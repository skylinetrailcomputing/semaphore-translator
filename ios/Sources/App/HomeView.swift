import SwiftUI

/// The no-camera landing screen ([5a], #47) — the app's entry point. Forks to
/// the two camera modes via two pills; no camera or permission prompt fires here
/// (that's deferred until a mode is entered). The Android twin is `HomeScreen`.
struct HomeView: View {
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            // Scroll + min-height keeps the hero vertically centered at normal text
            // sizes but lets it grow and scroll — instead of truncating the title and
            // pill labels — at the largest Dynamic Type sizes (#92, caught at AX5).
            GeometryReader { proxy in
                ScrollView {
                    VStack(spacing: 32) {
                        Spacer(minLength: 0)
                        header
                        Spacer(minLength: 0)
                        VStack(spacing: 16) {
                            NavigationLink {
                                // The Learn fork ([6a], #70): a no-camera chooser between
                                // free-form practice and a guided passage drill. Both push
                                // the same front-camera screen; the camera only mounts once
                                // one of those is entered, so nothing fires here. Keeps its
                                // default nav bar (back chevron + "Learn" title) — the camera
                                // screens it pushes bring their own transparent bar.
                                LearnChooserView()
                            } label: {
                                ModePill(
                                    title: "Sign / Learn semaphore",
                                    subtitle: "Practice or drill — front camera",
                                    systemImage: "figure.wave")
                            }
                            NavigationLink {
                                // The same shared camera+decode screen as Learn, only
                                // rear-facing (#46/#60). Same transparent, title-less nav
                                // bar so the rear camera fills the screen under just the
                                // back chevron. Lens + display mirror are handled inside
                                // by `PreviewViewModel`; the decode path is identical
                                // (ADR 0006 — rear needs no adapter change).
                                ContentView(
                                    cameraPosition: .back,
                                    emptyHint: "Point at someone signing")
                                    .navigationBarTitleDisplayMode(.inline)
                                    .toolbarBackground(.hidden, for: .navigationBar)
                            } label: {
                                ModePill(
                                    title: "Interpret semaphore",
                                    subtitle: "Read someone else’s flags",
                                    systemImage: "binoculars")
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(24)
                    .frame(minHeight: proxy.size.height)
                }
            }
        }
        // A single gear in the top-trailing corner is the Settings entry point
        // ([5d], #50) — kept minimal per the grooming decision. Placed as an
        // overlay (not a nav-bar item) because Home hides its nav bar; the
        // overlay aligns within the safe area, so it sits below the status bar.
        .overlay(alignment: .topTrailing) { settingsGear }
        // Home is the landing surface — no nav bar here; the pushed mode screens
        // bring their own (back chevron + optional title).
        .toolbar(.hidden, for: .navigationBar)
    }

    private var settingsGear: some View {
        NavigationLink {
            SettingsView()
        } label: {
            Image(systemName: "gearshape")
                .font(.title2)
                .foregroundStyle(.white.opacity(0.8))
                .padding(16)
                .contentShape(Rectangle())
        }
        // The gear is icon-only; without this VoiceOver announces "gearshape" (or
        // nothing). The Android twin's IconButton already has contentDescription
        // "Settings" (#92).
        .accessibilityLabel("Settings")
    }

    private var header: some View {
        VStack(spacing: 12) {
            Image(systemName: "flag.2.crossed.fill")
                .font(.system(size: 48))
                .accessibilityHidden(true)
            Text("Semaphore Translator")
                .font(.title.bold())
            Text("Signal with flags or hands, read with the camera.")
                .font(.callout)
                .foregroundStyle(.white.opacity(0.7))
                .multilineTextAlignment(.center)
        }
        .foregroundStyle(.white)
    }
}

/// One tappable mode option on Home — leading glyph, title + subtitle, trailing
/// chevron, in a rounded card. Reused by the Learn chooser ([6a], #70). The
/// Android twin is `HomeScreen`'s `ModePill`.
struct ModePill: View {
    let title: String
    let subtitle: String
    let systemImage: String

    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: systemImage)
                .font(.title2)
                .frame(width: 32)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.7))
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.footnote)
                .foregroundStyle(.white.opacity(0.4))
                .accessibilityHidden(true)
        }
        .foregroundStyle(.white)
        .padding()
        .frame(maxWidth: .infinity)
        .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
        .contentShape(Rectangle())
    }
}

#Preview {
    NavigationStack { HomeView() }
}

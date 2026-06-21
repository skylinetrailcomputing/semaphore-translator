import SwiftUI

/// The no-camera landing screen ([5a], #47) — the app's entry point. Forks to
/// the two camera modes via two pills; no camera or permission prompt fires here
/// (that's deferred until a mode is entered). The Android twin is `HomeScreen`.
struct HomeView: View {
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 32) {
                Spacer()
                header
                Spacer()
                VStack(spacing: 16) {
                    NavigationLink {
                        // Today's live screen ([3.5]) is the Learn surface (#5b
                        // fleshes it out). It starts the front camera on appear and
                        // stops it on disappear, so entering/leaving here is what
                        // mounts and tears down capture. A transparent, title-less
                        // nav bar lets the camera fill the screen with just the back
                        // chevron over it (a title would sit low-contrast on the feed).
                        ContentView()
                            .navigationBarTitleDisplayMode(.inline)
                            .toolbarBackground(.hidden, for: .navigationBar)
                    } label: {
                        ModePill(
                            title: "Sign / Learn semaphore",
                            subtitle: "Practice signing — front camera",
                            systemImage: "figure.wave")
                    }
                    NavigationLink {
                        InterpretStubView()
                    } label: {
                        ModePill(
                            title: "Interpret semaphore",
                            subtitle: "Read someone else’s flags",
                            systemImage: "binoculars")
                    }
                }
                Spacer()
            }
            .padding(24)
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
    }

    private var header: some View {
        VStack(spacing: 12) {
            Image(systemName: "flag.2.crossed.fill")
                .font(.system(size: 48))
            Text("Semaphore Translator")
                .font(.title.bold())
            Text("Signal with flags, read with the camera.")
                .font(.callout)
                .foregroundStyle(.white.opacity(0.7))
                .multilineTextAlignment(.center)
        }
        .foregroundStyle(.white)
    }
}

/// One tappable mode option on Home — leading glyph, title + subtitle, trailing
/// chevron, in a rounded card. The Android twin is `HomeScreen`'s `ModePill`.
private struct ModePill: View {
    let title: String
    let subtitle: String
    let systemImage: String

    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: systemImage)
                .font(.title2)
                .frame(width: 32)
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

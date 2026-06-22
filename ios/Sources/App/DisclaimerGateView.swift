import SwiftUI

/// The first-launch disclaimer gate ([6b-9], #87) — the **active** click-through
/// shown before the Home fork, the counterpart to the passive About surface
/// (6b-4). Maintainer-elective belt-and-suspenders for the Tier-A beta: the text
/// is distilled from the EULA (the full docs are one tap away). `RootView` shows
/// this until the user accepts; on accept it persists the doc's content hash, so
/// the gate stays dismissed until the doc is edited. The Android twin is
/// `DisclaimerGateScreen`.
struct DisclaimerGateView: View {
    let document: DisclaimerDocument
    /// Called when the user accepts — `RootView` persists the consent hash here.
    let onAccept: () -> Void

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        header
                        ForEach(document.body, id: \.self) { paragraph in
                            Text(paragraph)
                                .font(.callout)
                                .foregroundStyle(.white.opacity(0.85))
                        }
                        links
                        Text(document.agreement)
                            .font(.footnote)
                            .foregroundStyle(.white.opacity(0.7))
                        Text("Version \(document.version)")
                            .font(.caption2)
                            .foregroundStyle(.white.opacity(0.4))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(24)
                }
                acceptBar
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: "flag.2.crossed.fill")
                .font(.system(size: 40))
            Text(document.title)
                .font(.title.bold())
        }
        .foregroundStyle(.white)
        .padding(.bottom, 4)
    }

    private var links: some View {
        VStack(alignment: .leading, spacing: 8) {
            Link("End User License Agreement", destination: document.eula)
            Link("Privacy Policy", destination: document.privacy)
        }
        .font(.callout.weight(.medium))
        // Explicit accent: the app root tints to white for nav chevrons, which
        // would otherwise paint these links the same as body text.
        .tint(.cyan)
        .padding(.vertical, 4)
    }

    // The accept affordance is pinned outside the ScrollView so it's always
    // reachable even when the body scrolls on a small screen — the gate blocks
    // entry, so the button must never be scrolled off.
    private var acceptBar: some View {
        Button(action: onAccept) {
            Text(document.acceptLabel)
                .font(.headline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
        }
        .buttonStyle(.borderedProminent)
        .tint(.white)
        .foregroundStyle(.black)
        .padding(.horizontal, 24)
        .padding(.top, 8)
        .padding(.bottom, 16)
        .background(.black)
    }
}

/// Fail-closed screen shown when the bundled disclaimer can't be loaded ([6b-9],
/// #87). A missing/corrupt `disclaimer.json` is a build-packaging bug; the legal
/// gate blocks entry rather than silently passing, surfacing the bug instead of
/// granting unconsented access. The Android twin is `DisclaimerUnavailableScreen`.
struct DisclaimerUnavailableView: View {
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 40))
                Text("Couldn’t load the required notice.")
                    .font(.headline)
                Text("This is a packaging error. Please reinstall the app.")
                    .font(.callout)
                    .foregroundStyle(.white.opacity(0.7))
            }
            .multilineTextAlignment(.center)
            .foregroundStyle(.white)
            .padding(24)
        }
    }
}

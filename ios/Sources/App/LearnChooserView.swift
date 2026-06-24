import SwiftUI
import UIKit

/// The Learn source picker ([6a], #71). The "Sign / Learn" pill lands here and
/// offers three front-camera surfaces: **free practice** (sign anything, watch the
/// decode) and two passage-drill sources — **type a passage** (custom text,
/// sanitised) and **sight-read a stock passage** (a bundled passage you haven't
/// seen). All push the same `ContentView`; the drills differ only by an injected,
/// sanitised passage. No camera or permission prompt fires here — that's deferred
/// to the pushed screen, exactly as on Home. The Android twin is
/// `SemaphoreApp`'s `LEARN_HUB` chooser + the custom/stock screens.
struct LearnChooserView: View {
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 16) {
                Spacer()
                NavigationLink {
                    // Free-form Learn — the original surface, unchanged.
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
                    CustomPassageView()
                } label: {
                    ModePill(
                        title: "Type a passage",
                        subtitle: "Drill your own words, letter by letter",
                        systemImage: "keyboard")
                }
                NavigationLink {
                    StockPassageView()
                } label: {
                    ModePill(
                        title: "Sight-read a passage",
                        subtitle: "Drill a surprise passage you haven’t seen",
                        systemImage: "book")
                }
                Spacer()
            }
            .padding(24)
        }
        .navigationTitle("Learn")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The **custom passage** source ([6a-3], #71): a text field whose live, sanitised
/// preview is exactly what the drill will use (`PassageSource.sanitize`). Start is
/// disabled until the sanitised result is a non-empty passage within
/// `PassageSource.maxTargets` — so the drill never gets an empty target sequence
/// (drill_contract `empty_targets`). Prefilled with a friendly starter. The Android
/// twin is `CustomPassageScreen`.
struct CustomPassageView: View {
    @State private var text = "HELLO"
    /// Owned keyboard focus (#104, 6a-15). The drill screen pushes from the
    /// "Start drill" CTA below; on the pop back, iOS otherwise auto-refocused this
    /// field — raising the keyboard unbidden — and keyboard-avoidance failed to
    /// lift the docked CTA above it, so the button was present but occluded. Taking
    /// explicit ownership and clearing focus on leave/return keeps the keyboard
    /// down on return (Android's `CustomPassageScreen` already behaves this way:
    /// keyboard only on an active tap).
    @FocusState private var fieldFocused: Bool

    var body: some View {
        // Sanitise once per render (the Kotlin twin computes it once per
        // recomposition too); the sanitiser is the single source of truth, so the
        // preview can't disagree with what's drilled. No raw-length cap: the
        // sanitiser is O(n)-cheap and the *visible* sanitised counter (below) is the
        // only bound, so input is never silently truncated.
        let sanitized = PassageSource.sanitize(text)
        let withinCap = sanitized.count <= PassageSource.maxTargets
        let canStart = !sanitized.isEmpty && withinCap
        return ZStack {
            Color.black.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 20) {
                Text("Type anything — letters, digits, and spaces. Other characters "
                    + "are ignored.")
                    .font(.callout)
                    .foregroundStyle(.white.opacity(0.7))

                TextField("Your passage", text: $text, axis: .vertical)
                    .focused($fieldFocused)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .lineLimit(2...4)
                    .padding()
                    .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    .foregroundStyle(.white)

                previewCard(sanitized: sanitized, withinCap: withinCap)

                Spacer()

                NavigationLink {
                    ContentView(
                        emptyHint: "Sign the letter shown above",
                        drillTargets: sanitized)
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbarBackground(.hidden, for: .navigationBar)
                } label: {
                    Text("Start drill")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(
                            canStart ? Color.white.opacity(0.2) : Color.white.opacity(0.06),
                            in: RoundedRectangle(cornerRadius: 14))
                        .foregroundStyle(canStart ? .white : .white.opacity(0.4))
                }
                .disabled(!canStart)
                .accessibilityIdentifier("startDrillButton")
            }
            .padding(24)
        }
        .navigationTitle("Type a passage")
        .navigationBarTitleDisplayMode(.inline)
        // Drop focus when leaving (so the keyboard is down as the drill pushes), and
        // force the keyboard down again on the return. On the push→pop return UIKit
        // restores the field as first responder *after* `onAppear` fires, re-raising
        // the keyboard over the docked "Start drill" CTA — and because SwiftUI's
        // `@FocusState` is already `false`, re-clearing it is a no-op that never
        // resigns the UIKit-restored responder. So resign at the UIKit level
        // directly, deferred to the next runloop tick to land after restoration. The
        // keyboard's safe-area inset is stale on this path (avoidance won't lift the
        // button), so keeping the keyboard *down* is what makes the CTA reachable —
        // matching Android's "keyboard only on an active tap" (#104).
        .onDisappear { fieldFocused = false }
        .onAppear {
            DispatchQueue.main.async {
                fieldFocused = false
                UIApplication.shared.sendAction(
                    #selector(UIResponder.resignFirstResponder),
                    to: nil, from: nil, for: nil)
            }
        }
    }

    /// The live preview = the sanitised target string, shown verbatim (spaces as `␣`
    /// so trims/collapses are legible), with a target counter. Empty-state copy names
    /// the supported set so a field of only-dropped characters doesn't read as a
    /// broken Start button.
    private func previewCard(sanitized: String, withinCap: Bool) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Preview")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.6))
            if sanitized.isEmpty {
                Text("No supported characters yet (A–Z, 0–9, space).")
                    .font(.callout)
                    .foregroundStyle(.white.opacity(0.5))
            } else {
                Text(sanitized.replacingOccurrences(of: " ", with: "␣"))
                    .font(.system(.title3, design: .monospaced))
                    .foregroundStyle(.white)
                    .lineLimit(3)
                    .truncationMode(.tail)
                // `count` == target count: the sanitiser's output is pure ASCII, so
                // one Character is exactly one drill target.
                Text("\(sanitized.count) / \(PassageSource.maxTargets)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(withinCap ? .white.opacity(0.6) : .red)
                if !withinCap {
                    Text("Too long — shorten to \(PassageSource.maxTargets) characters.")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.black.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// The **sight-read** source ([6a-3], #71): a list of bundled stock passages shown
/// by their content-neutral hint only — the text is never displayed here, so the
/// signer sight-reads it one HUD target at a time. Loads `shared/stock_passages.json`
/// from the bundle; fails **soft** (an "unavailable" message, not a crash) if the
/// resource is missing/corrupt, leaving the custom source usable. The Android twin
/// is `StockPassageScreen`.
struct StockPassageView: View {
    private let passages: [StockPassage]

    init() {
        passages = (try? StockPassages.loadBundled())?.passages ?? []
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if passages.isEmpty {
                unavailable
            } else {
                ScrollView {
                    VStack(spacing: 16) {
                        Text("Pick one and sign it as it’s revealed, letter by letter.")
                            .font(.callout)
                            .foregroundStyle(.white.opacity(0.7))
                            .frame(maxWidth: .infinity, alignment: .leading)
                        ForEach(passages) { passage in
                            NavigationLink {
                                ContentView(
                                    emptyHint: "Sign the letter shown above",
                                    drillTargets: PassageSource.sanitize(passage.text))
                                    .navigationBarTitleDisplayMode(.inline)
                                    .toolbarBackground(.hidden, for: .navigationBar)
                            } label: {
                                ModePill(
                                    title: passage.hint,
                                    subtitle: "\(PassageSource.sanitize(passage.text).count) steps",
                                    systemImage: "list.bullet.rectangle")
                            }
                        }
                    }
                    .padding(24)
                }
            }
        }
        .navigationTitle("Sight-read")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var unavailable: some View {
        VStack(spacing: 12) {
            Image(systemName: "book.closed").font(.largeTitle).accessibilityHidden(true)
            Text("Sight-read passages are unavailable.")
                .font(.headline)
            Text("Try “Type a passage” instead.")
                .font(.callout)
                .foregroundStyle(.white.opacity(0.6))
        }
        .foregroundStyle(.white)
        .padding(32)
    }
}

#Preview {
    NavigationStack { LearnChooserView() }
}

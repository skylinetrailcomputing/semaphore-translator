import SwiftUI

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

    /// The exact target string the drill will run — the sanitiser is the single
    /// source of truth, so the preview can't disagree with what's drilled.
    private var sanitized: String { PassageSource.sanitize(text) }
    private var withinCap: Bool { sanitized.count <= PassageSource.maxTargets }
    private var canStart: Bool { !sanitized.isEmpty && withinCap }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 20) {
                Text("Type anything — letters, digits, and spaces. Other characters "
                    + "are ignored.")
                    .font(.callout)
                    .foregroundStyle(.white.opacity(0.7))

                TextField("Your passage", text: $text, axis: .vertical)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .lineLimit(2...4)
                    .padding()
                    .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    .foregroundStyle(.white)
                    // Bound a pathological paste so sanitising stays cheap; the
                    // sanitised cap (below) is the user-facing limit.
                    .onChange(of: text) { _, new in
                        if new.count > PassageSource.maxTargets * 2 {
                            text = String(new.prefix(PassageSource.maxTargets * 2))
                        }
                    }

                previewCard

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
            }
            .padding(24)
        }
        .navigationTitle("Type a passage")
        .navigationBarTitleDisplayMode(.inline)
    }

    /// The live preview = the sanitised target string, shown verbatim (spaces as `␣`
    /// so trims/collapses are legible), with a target counter. Empty-state copy names
    /// the supported set so a field of only-dropped characters doesn't read as a
    /// broken Start button.
    private var previewCard: some View {
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
            Image(systemName: "book.closed").font(.largeTitle)
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

import SwiftUI

/// The regular-user **Settings** surface ([6a-4], #72) — the gear destination from
/// Home. Distinct from the dev-ish `DeveloperSettingsView` it was split from: this
/// is the surface a beta tester sees, and the home for the assist/practice-mode
/// toggles (6a-5 #73, 6a-6 #74) that land in their own section above About. It
/// hosts the always-reachable **About** entry ([6b-4], #82) and a **Developer**
/// row that pushes the maintainer smoke surface one level deeper, off this
/// user-facing screen. The Android twin is `SettingsScreen`.
struct SettingsView: View {
    /// The assist-figure on/off (6a-5, #73). Default ON; `ContentView`'s drill card
    /// reads the same `@AppStorage` key, so flipping it here shows/hides the figure
    /// on the next return to the drill screen.
    @AppStorage(AppSettingsKeys.showAssistFigure) private var showAssistFigure = true
    /// The Learn numerals on/off (#127). Default ON; `PreviewViewModel` seeds its
    /// front-lens decode gate from the same `@AppStorage` key, and the Learn passage
    /// surfaces read it to disable/​warn on number passages. The next drill/practice
    /// entry reflects a flip here.
    @AppStorage(AppSettingsKeys.allowNumerals) private var allowNumerals = true
    /// The numerals-indicator on/off (6a-14, #103). Default ON; `ContentView` reads
    /// the same `@AppStorage` key, so flipping it here shows/hides the live pill on
    /// the camera screen the next time it appears.
    @AppStorage(AppSettingsKeys.showNumeralsIndicator) private var showNumeralsIndicator = true
    /// The framing-hint banner on/off (6a-18 #112, polish 6a-20 #125). Default ON;
    /// `ContentView` reads the same `@AppStorage` key and gates the Learn banner on it.
    @AppStorage(AppSettingsKeys.showFramingHint) private var showFramingHint = true
    /// The forgiving "easy mode" drill readout on/off (6a-10, #95). Default ON;
    /// `ContentView` reads the same `@AppStorage` key, so flipping it here switches
    /// the drill readout between matched-only and verbatim on the next drill entry.
    @AppStorage(AppSettingsKeys.drillMatchedOnlyReadout) private var drillMatchedOnlyReadout = true
    /// The drill auto-reset on/off (6a-12, #97). Default ON; `ContentView` reads the
    /// same `@AppStorage` key and arms the countdown on a drill's COMPLETE signal.
    @AppStorage(AppSettingsKeys.autoResetOnComplete) private var autoResetOnComplete = true

    var body: some View {
        Form {
            // Assist & practice-mode toggles (6a-5 #73, 6a-6 #74) land here as
            // their own section — the primary regular-user content.
            Section {
                Toggle("Show assist figure", isOn: $showAssistFigure)
                    .tint(.green)
            } footer: {
                Text(
                    "Shows a stick figure of the arm positions to copy for each "
                    + "letter while practising a passage in Learn.")
            }

            // The Learn numerals on/off (#127) — a decode-behaviour toggle for the
            // self-signing path; sits just above the display-only indicator toggle so
            // the two numerals controls read together.
            Section {
                Toggle("Enable numerals", isOn: $allowNumerals)
                    .tint(.green)
            } footer: {
                Text(
                    "Lets signing switch into number mode (digits 0–9) in Learn. Turn "
                    + "off to practise letters only — the numbers switch is ignored, "
                    + "sight-read passages with numbers are hidden, and typing a number "
                    + "into a passage warns you. Interpret still reads numbers normally.")
            }

            // The live NUMERALS mode indicator (6a-14, #103) — a display toggle for
            // the camera screen, so it sits with the other regular-user toggles.
            Section {
                Toggle("Show numerals indicator", isOn: $showNumeralsIndicator)
                    .tint(.green)
            } footer: {
                Text(
                    "Shows a badge over the camera while the decoder is reading "
                    + "digits, and hides it when it returns to letters.")
            }

            // The Learn framing-hint banner (6a-18 #112, polish 6a-20 #125) — a
            // camera display toggle, so it sits with the other display toggles.
            Section {
                Toggle("Show framing hints", isOn: $showFramingHint)
                    .tint(.green)
            } footer: {
                Text(
                    "In Learn, shows a hint over the camera when an arm isn't fully "
                    + "visible — so you can step back or add light to fix it.")
            }

            // The forgiving "easy mode" drill readout (6a-10, #95) — a Learn-drill
            // behaviour toggle, so it sits with the other regular-user toggles.
            Section {
                Toggle("Forgiving drill readout", isOn: $drillMatchedOnlyReadout)
                    .tint(.green)
            } footer: {
                Text(
                    "While drilling a passage, the readout fills in only the letters "
                    + "you've matched — wrong letters and pauses are ignored. Turn off "
                    + "to show everything you sign.")
            }

            // Drill auto-reset (6a-12, #97) — a Learn drill-flow affordance, so it
            // sits with the other regular-user toggles.
            Section {
                Toggle("Auto-reset after a passage", isOn: $autoResetOnComplete)
                    .tint(.green)
            } footer: {
                Text(
                    "When you finish a drill, a short countdown clears it and starts "
                    + "the same passage again — so you can keep practising hands-free. "
                    + "Tap “Stay” on the celebration to keep it up instead.")
            }

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

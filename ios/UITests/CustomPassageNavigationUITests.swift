import XCTest

/// UI regression for **#104 (6a-15)**: in Learn → "Type a passage"
/// (`CustomPassageView`), after **Start drill** → **back**, the "Start drill" CTA
/// must stay visible and tappable. The bug was that iOS auto-refocused the
/// `TextField` on the push→pop return, raising the keyboard unbidden, and
/// keyboard-avoidance failed to lift the bottom button above it — so the CTA was
/// present but occluded (not hittable). This test drives the real navigation and
/// asserts the button is hittable on return, with no manual keyboard dismissal.
///
/// iOS-only: Android's `CustomPassageScreen` does not share the bug (#104 smoke),
/// so there is no Android twin for this guard.
///
/// This reproduces the bug deterministically in the simulator: the return-path
/// assertion fails before the fix (keyboard auto-restored over the docked CTA)
/// and passes after — so it is a genuine CI regression guard, not a device-only
/// check. It does require the simulator's **software** keyboard (Hardware →
/// Keyboard → Connect Hardware Keyboard *off*, i.e. `defaults write
/// com.apple.iphonesimulator ConnectHardwareKeyboard -bool false`); without it no
/// keyboard is presented and the occlusion can't be exercised. The test asserts
/// the keyboard is up after focusing the field so a misconfigured runner fails
/// loudly rather than passing vacuously. On-device smoke is still worth doing as
/// belt-and-suspenders for real-device first-responder timing.
///
/// `@MainActor`-isolated: the XCUI element APIs are main-actor-bound under Swift 6,
/// so the class runs on the main actor to call them in-context (Apple's current
/// XCUITest posture).
@MainActor
final class CustomPassageNavigationUITests: XCTestCase {
    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        // The drill screen starts the camera; on a sim/device without a prior
        // grant iOS presents the camera-permission alert, which would block the
        // nav bar and break `tapBack`. Dismiss it if it appears. (CI should also
        // pre-grant via `simctl privacy grant camera <bundleid>` for determinism.)
        // The handler closure is a nonisolated `@Sendable`, so the `@MainActor`
        // class annotation doesn't reach it; interruption handlers are invoked on
        // the main thread, so assume that isolation to call the main-actor XCUI APIs.
        addUIInterruptionMonitor(withDescription: "Camera permission") { alert in
            MainActor.assumeIsolated {
                for label in ["Allow", "OK", "Allow Once", "While Using the App"] {
                    let button = alert.buttons[label]
                    if button.exists {
                        button.tap()
                        return true
                    }
                }
                return false
            }
        }
    }

    func testStartDrillButtonHittableAfterReturningFromDrill() {
        let app = XCUIApplication()
        app.launch()

        acceptDisclaimerIfPresent(app)

        // Home → Learn chooser → Type a passage. The pills are `ModePill`s whose
        // title + subtitle SwiftUI folds into one combined accessibility label, so
        // match on the title substring rather than the exact label.
        let learn = pill(app, labeled: "Sign / Learn")
        XCTAssertTrue(learn.waitForExistence(timeout: 5), "Home Learn pill should exist")
        learn.tap()

        let typePassage = pill(app, labeled: "Type a passage")
        XCTAssertTrue(typePassage.waitForExistence(timeout: 5),
                      "Learn chooser 'Type a passage' should exist")
        typePassage.tap()

        let startDrill = app.buttons["startDrillButton"]
        XCTAssertTrue(startDrill.waitForExistence(timeout: 5),
                      "Start drill button should exist on first entry")

        // Focus the text field so the keyboard is up before the push — the state
        // under which iOS auto-refocused on the return path (#104).
        let field = firstTextInput(app)
        XCTAssertTrue(field.waitForExistence(timeout: 5), "Passage text field should exist")
        field.tap()

        // Test-validity guard: the regression is keyboard occlusion, so the
        // software keyboard must actually be up. If it isn't (hardware keyboard
        // connected on the runner), fail loudly instead of passing vacuously.
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5),
                      "Software keyboard must be up to exercise the regression — "
                      + "disable the simulator's Connect Hardware Keyboard if this fails")

        // First-pass contract: with the keyboard up, avoidance keeps the CTA
        // hittable (this is the behaviour the return path was breaking). Poll
        // rather than read `isHittable` once, so a mid-animation frame doesn't flake.
        XCTAssertTrue(waitUntilHittable(startDrill, timeout: 3),
                      "Start drill should be hittable on first pass with the keyboard up")

        // Push the drill screen, then pop back.
        startDrill.tap()
        XCTAssertTrue(waitForDisappearance(startDrill, timeout: 5),
                      "Drill screen should have pushed (Start drill no longer present)")
        tapBack(app)

        // The regression assertion: on return the button must be reachable with no
        // manual keyboard dismissal. Poll for hittability so the keyboard-dismiss
        // animation triggered by the fix can settle (avoids real-device flake).
        XCTAssertTrue(startDrill.waitForExistence(timeout: 5),
                      "Start drill button should reappear after tapping back")
        XCTAssertTrue(waitUntilHittable(startDrill, timeout: 3),
                      "Start drill must be hittable after returning from the drill (#104)")
    }

    // MARK: - Helpers

    /// The first-launch disclaimer gate (#87) blocks Home until accepted. Accept it
    /// if present; on a sim whose state already recorded consent it won't appear.
    private func acceptDisclaimerIfPresent(_ app: XCUIApplication) {
        let accept = app.buttons["Agree & Continue"]
        if accept.waitForExistence(timeout: 3) {
            accept.tap()
        }
    }

    /// A `ModePill` NavigationLink, matched by a substring of its combined
    /// (title + subtitle) accessibility label.
    private func pill(_ app: XCUIApplication, labeled substring: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", substring)).firstMatch
    }

    /// The vertical-axis `TextField` surfaces as a text view or text field depending
    /// on platform version; take whichever the runtime exposes.
    private func firstTextInput(_ app: XCUIApplication) -> XCUIElement {
        app.textViews.count > 0 ? app.textViews.firstMatch : app.textFields.firstMatch
    }

    /// Tap the nav back button. iOS labels it with the previous screen's title
    /// ("Type a passage"); fall back to the first nav-bar button if that label
    /// isn't surfaced.
    private func tapBack(_ app: XCUIApplication) {
        let named = app.navigationBars.buttons["Type a passage"]
        if named.waitForExistence(timeout: 2) {
            named.tap()
            return
        }
        let first = app.navigationBars.buttons.element(boundBy: 0)
        XCTAssertTrue(first.waitForExistence(timeout: 3), "A back button should exist")
        first.tap()
    }

    private func waitForDisappearance(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let gone = NSPredicate(format: "exists == false")
        let exp = XCTNSPredicateExpectation(predicate: gone, object: element)
        return XCTWaiter().wait(for: [exp], timeout: timeout) == .completed
    }

    /// Poll until `element` becomes hittable (or the timeout expires). `isHittable`
    /// is a point-in-time read; polling lets a keyboard/safe-area animation settle
    /// so the assertion doesn't flake on a mid-transition frame.
    private func waitUntilHittable(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let hittable = NSPredicate(format: "isHittable == true")
        let exp = XCTNSPredicateExpectation(predicate: hittable, object: element)
        return XCTWaiter().wait(for: [exp], timeout: timeout) == .completed
    }
}

import Foundation

/// The Learn passage SOURCE sanitiser (Epic 6a, #71 / 6a-3) — turns arbitrary
/// user/stock text into the already-validated target string the `DrillSession`
/// (ADR 0007) consumes. A faithful Swift twin of the reference `sanitize()` in
/// `shared/tools/gen_sanitize_vectors.py` and of the Kotlin `PassageSource`. The
/// cross-platform enforcement is `shared/sanitize_vectors.json` (the
/// `SanitizeParityTests` harness); the frozen rule is `shared/source_contract.json`
/// + `docs/adr/0008-learn-source-sanitization.md`. It is strictly upstream of the
/// drill engine and never touches the decode/adapter/commit core.
///
/// Two parity-critical choices (see ADR 0008), both pinned by the vectors:
///   * **Iterate `unicodeScalars`, NOT `Character`.** A decomposed base letter +
///     combining mark is one `Character` (grapheme) but two scalars; iterating
///     `Character`s would fold them together and drop the base letter too, diverging
///     from Python code points / Kotlin UTF-16 `Char`s.
///   * **ASCII-only uppercase by scalar value** (`a–z` → `A–Z`); never
///     `String.uppercased()`, which is locale-aware (expands `ß` → `"SS"`, folds the
///     Turkish dotless-i) and would keep characters the rule means to drop.
enum PassageSource {
    /// Soft UI cap on a drill passage's sanitised length — a beta safety bound, not
    /// a decode constant. Kept in lockstep with Android's `PassageSource.MAX_TARGETS`
    /// and with `shared/source_contract.json`'s `max_targets` (the `SanitizeParityTests`
    /// assert this equals the contract value, so the three can't drift).
    static let maxTargets = 200

    /// Map arbitrary text to the frozen ASCII target alphabet (A–Z / 0–9 / SPACE),
    /// in signing order, with whitespace runs collapsed to one SPACE and leading /
    /// trailing whitespace trimmed. Everything else (punctuation, accents, emoji,
    /// NBSP, control chars) is dropped — no transliteration.
    static func sanitize(_ input: String) -> String {
        var out = String.UnicodeScalarView()
        var hasKept = false
        var pendingSpace = false
        for scalar in input.unicodeScalars {
            let v = scalar.value
            let c = (v >= 0x61 && v <= 0x7A) ? v - 0x20 : v  // ASCII-only a–z → A–Z
            switch c {
            case 0x20, 0x09, 0x0A, 0x0D:  // SPACE, TAB, LF, CR — word separators
                // Flush as a single SPACE only before the next kept char, and only
                // after a kept char has already been emitted → leading/trailing
                // trimmed, internal runs collapsed, all in one pass.
                if hasKept { pendingSpace = true }
            case 0x41...0x5A, 0x30...0x39:  // A–Z, 0–9 — kept
                if pendingSpace { out.append(Unicode.Scalar(0x20)!) }
                out.append(Unicode.Scalar(c)!)
                hasKept = true
                pendingSpace = false
            default:
                break  // drop silently
            }
        }
        return String(out)
    }
}

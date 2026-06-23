import Foundation

/// Loads the frozen shared contract (`semaphore_alphabet.json` +
/// `semaphore_config.json`) bundled into the app and assembles the
/// `SemaphoreDecoder`. This is the app-side counterpart to the test harness's
/// `ReferenceDecoder.make()` / `SharedFiles` (Issue #14, ADR 0001): the test
/// target reaches `shared/` on the host via `#filePath`, but a shipped app
/// cannot, so the two JSON files are packaged as bundle resources (declared in
/// `ios/project.yml`, referencing `../shared/*.json` directly — no committed
/// copy, so `shared/` stays the single source of truth) and decoded here with
/// `Codable`. Per the `semaphore_config.json` README, both platforms *load* the
/// contract; the constants are never hardcoded in Swift/Kotlin.
///
/// The DTOs are deliberately `private` nested types: ADR 0001 anticipated the
/// app growing "its own bundled-asset loader (Epic 3+)", and the app cannot
/// `import` the test target's `SharedContract.swift`. Nesting them also keeps
/// these names out of the module namespace, so the test target's identically
/// named top-level DTOs don't collide under `@testable import`.
enum ContractLoader {
    enum LoadError: Error {
        case missingResource(String)
        /// A non-Learn `TimingProfile` was requested but `timing_profiles.<name>`
        /// is absent from the contract. Fatal by design (ADR 0009) — the loader
        /// never silently falls back to Learn timing, so a packaging/contract bug
        /// surfaces instead of running the rear lens at the wrong speed.
        case missingTimingProfile(String)
    }

    /// Build the decoder from the bundled contract, mirroring the test harness's
    /// `ReferenceDecoder.make()` exactly so the live app and the parity fixtures
    /// share one construction. Throws if a resource is missing or malformed —
    /// that is a build-packaging bug, surfaced to the caller as an error state.
    static func makeDecoder(bundle: Bundle = .main) throws -> SemaphoreDecoder {
        let alphabet = try load(Alphabet.self, "semaphore_alphabet", bundle)
        let config = try load(Config.self, "semaphore_config", bundle)

        var octantAngles: [Int: Double] = [:]
        for position in alphabet.positionModel.positions.values {
            octantAngles[position.id] = position.angleDeg
        }

        var symbolPairs: [String: (left: Int, right: Int)] = [:]
        for (symbol, ids) in alphabet.letters {
            symbolPairs[symbol] = (ids.left, ids.right)
        }
        symbolPairs["NUMERALS"] = (
            alphabet.controlSignals.numerals.left, alphabet.controlSignals.numerals.right
        )
        symbolPairs["REST"] = (
            alphabet.controlSignals.rest.left, alphabet.controlSignals.rest.right
        )

        return SemaphoreDecoder(
            octantAngles: octantAngles,
            symbolPairs: symbolPairs,
            digitMap: alphabet.numericMode.digitMap,
            angleToleranceDeg: config.angleToleranceDeg,
            minKeypointConfidence: config.minKeypointConfidence)
    }

    /// Build the assist-figure geometry (#73 / 6a-5, extended for transition cues in
    /// #100) from the bundled alphabet. Deliberately a *second* parse of
    /// `semaphore_alphabet.json` rather than a reach into the `SemaphoreDecoder`: the
    /// decoder stores the pairs in an order-*insensitive* lookup that discards which
    /// id is the left vs right arm, but the figure draws each arm from its own
    /// shoulder and needs the ordered `(left, right)`. It also carries `NUMERALS`
    /// (the numeric-shift pre-pose) and the reversed digit map (digit → its letter
    /// pose), which the decoder doesn't expose. The extra parse is one ~8 KB file,
    /// only on a drill screen.
    static func makeAssistGeometry(bundle: Bundle = .main) throws -> AssistGeometry {
        let alphabet = try load(Alphabet.self, "semaphore_alphabet", bundle)

        var octantAngles: [Int: Double] = [:]
        for position in alphabet.positionModel.positions.values {
            octantAngles[position.id] = position.angleDeg
        }

        var symbolPairs: [String: (left: Int, right: Int)] = [:]
        for (symbol, ids) in alphabet.letters {
            symbolPairs[symbol] = (ids.left, ids.right)
        }
        symbolPairs["REST"] = (
            alphabet.controlSignals.rest.left, alphabet.controlSignals.rest.right
        )
        symbolPairs["NUMERALS"] = (
            alphabet.controlSignals.numerals.left, alphabet.controlSignals.numerals.right
        )

        var digitToLetter: [Character: String] = [:]
        for (letter, digit) in alphabet.numericMode.digitMap {
            if let d = digit.first { digitToLetter[d] = letter }
        }

        return AssistGeometry(
            octantAngles: octantAngles, symbolPairs: symbolPairs, digitToLetter: digitToLetter)
    }

    /// Parse the temporal-commit timing (spec §4.4) for one fork profile from the
    /// bundled `semaphore_config.json`. No committer is built here — this only
    /// surfaces the constants the live layer injects (selected by lens, ADR 0009).
    ///
    /// `.learn` reads the frozen flat constants; any other profile reads its
    /// fully-specified `timing_profiles.<name>` block and **throws**
    /// `missingTimingProfile` if absent — never a silent fallback to Learn. The
    /// default keeps test/legacy callers on Learn; the production call site
    /// (`PreviewViewModel`) passes the lens-derived profile explicitly.
    static func makeCommitTiming(bundle: Bundle = .main, profile: TimingProfile = .learn) throws
        -> CommitTiming
    {
        // Learn reads only the flat keys via `Config`, which does NOT model
        // `timing_profiles` -- so (like Android's flat-key org.json path) a malformed
        // `timing_profiles` block can't break the Learn timing or the decoder. The
        // interpret block is decoded ONLY on the non-Learn path, via `ProfilesConfig`,
        // so a fault there is fatal exactly when that profile is selected (ADR 0009).
        if profile == .learn {
            let config = try load(Config.self, "semaphore_config", bundle)
            return CommitTiming(
                smoothingWindow: config.smoothingWindow,
                commitHoldMs: config.commitHoldMs,
                interCharGapMs: config.interCharGapMs)
        }
        let config = try load(ProfilesConfig.self, "semaphore_config", bundle)
        guard let t = config.timingProfiles?[profile.rawValue] else {
            throw LoadError.missingTimingProfile(profile.rawValue)
        }
        return CommitTiming(
            smoothingWindow: t.smoothingWindow,
            commitHoldMs: t.commitHoldMs,
            interCharGapMs: t.interCharGapMs)
    }

    private static func load<T: Decodable>(_ type: T.Type, _ name: String, _ bundle: Bundle) throws
        -> T
    {
        guard let url = bundle.url(forResource: name, withExtension: "json") else {
            throw LoadError.missingResource("\(name).json")
        }
        return try JSONDecoder().decode(T.self, from: Data(contentsOf: url))
    }

    // MARK: - DTOs (app-side mirror of the test target's `SharedContract.swift`)

    private struct ArmPair: Decodable {
        let left: Int
        let right: Int
    }

    private struct Position: Decodable {
        let id: Int
        let angleDeg: Double
        enum CodingKeys: String, CodingKey {
            case id
            case angleDeg = "angle_deg"
        }
    }

    private struct PositionModel: Decodable {
        let positions: [String: Position]
    }

    private struct ControlSignals: Decodable {
        let numerals: ArmPair
        let rest: ArmPair
        enum CodingKeys: String, CodingKey {
            case numerals = "NUMERALS"
            case rest = "REST"
        }
    }

    private struct NumericMode: Decodable {
        let digitMap: [String: String]
        enum CodingKeys: String, CodingKey {
            case digitMap = "digit_map"
        }
    }

    private struct Alphabet: Decodable {
        let positionModel: PositionModel
        let letters: [String: ArmPair]
        let controlSignals: ControlSignals
        let numericMode: NumericMode
        enum CodingKeys: String, CodingKey {
            case positionModel = "_position_model"
            case letters
            case controlSignals = "control_signals"
            case numericMode = "numeric_mode"
        }
    }

    /// One fully-specified per-fork timing override (ADR 0009). All three keys are
    /// required — a missing key fails decoding (no merge/delta against the flat
    /// Learn keys), matching the contract's "profiles are fully specified" rule.
    private struct ProfileTiming: Decodable {
        let commitHoldMs: Double
        let smoothingWindow: Int
        let interCharGapMs: Double
        enum CodingKeys: String, CodingKey {
            case commitHoldMs = "COMMIT_HOLD_MS"
            case smoothingWindow = "SMOOTHING_WINDOW"
            case interCharGapMs = "INTER_CHAR_GAP_MS"
        }
    }

    /// The flat config: the geometry constants + the Learn timing keys. It does
    /// **not** model `timing_profiles`, so decoding it (here and in `makeDecoder`)
    /// can never throw on a malformed fork block — the Learn path and the decoder
    /// stay robust to it, the same way Android's flat-key org.json reads do. The
    /// fork overrides are decoded separately (`ProfilesConfig`), only when selected.
    private struct Config: Decodable {
        let angleToleranceDeg: Double
        let minKeypointConfidence: Double
        let commitHoldMs: Double
        let smoothingWindow: Int
        let interCharGapMs: Double
        enum CodingKeys: String, CodingKey {
            case angleToleranceDeg = "ANGLE_TOLERANCE_DEG"
            case minKeypointConfidence = "MIN_KEYPOINT_CONFIDENCE"
            case commitHoldMs = "COMMIT_HOLD_MS"
            case smoothingWindow = "SMOOTHING_WINDOW"
            case interCharGapMs = "INTER_CHAR_GAP_MS"
        }
    }

    /// Decodes only the `timing_profiles` fork overrides (ADR 0009), used solely on
    /// the non-Learn `makeCommitTiming` path. A selected profile that is malformed
    /// (a present block missing a required key) throws here — fatal, and exactly
    /// when that profile is requested; the Learn/decoder paths (which decode `Config`)
    /// never touch this, so a fork-block fault can't break them.
    private struct ProfilesConfig: Decodable {
        let timingProfiles: [String: ProfileTiming]?
        enum CodingKeys: String, CodingKey {
            case timingProfiles = "timing_profiles"
        }
    }
}

/// The frozen temporal-commit constants (spec §4.4, ADR 0004), surfaced from the
/// shared contract so the live layer (#4.5) can build the committer. Kept a plain
/// value type — like `SemaphoreDecoder`'s plain-value constructor, it carries no
/// JSON/loader code; `ContractLoader.makeCommitTiming` parses the contract into it.
struct CommitTiming: Equatable {
    /// Frames of majority-vote smoothing on the votable pose symbol.
    let smoothingWindow: Int
    /// How long a candidate symbol must hold (wall-clock ms) before it commits.
    let commitHoldMs: Double
    /// Minimum *brief-`REST`* dwell (ms) that re-arms the *same* symbol for
    /// re-commit — the double-letter separator; a `REST` held `≥ commitHoldMs`
    /// commits a space instead. A distinct symbol commits on its hold alone
    /// (ADR 0005, superseding ADR 0004 Decision 3).
    let interCharGapMs: Double
}

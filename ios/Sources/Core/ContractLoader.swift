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
    enum LoadError: Error { case missingResource(String) }

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

    /// Parse the frozen temporal-commit constants (spec §4.4) from the bundled
    /// `semaphore_config.json`. No committer is built here — that is #4.5; this
    /// only surfaces the constants the live layer will inject.
    static func makeCommitTiming(bundle: Bundle = .main) throws -> CommitTiming {
        let config = try load(Config.self, "semaphore_config", bundle)
        return CommitTiming(
            smoothingWindow: config.smoothingWindow,
            commitHoldMs: config.commitHoldMs,
            interCharGapMs: config.interCharGapMs)
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
    /// Minimum intervening *indeterminate* gap (ms) before the *same* symbol may
    /// re-commit. A distinct symbol commits on its hold alone (ADR 0004).
    let interCharGapMs: Double
}

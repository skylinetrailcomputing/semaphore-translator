import Foundation

// Test-only mirrors of the language-neutral `shared/*.json` contract plus the
// loader that reaches them. These live in the test target (not the app) so the
// shipping app carries no JSON-loader code or build-machine `#filePath`. Only
// the fields the harness needs are modeled; Codable ignores unknown keys (the
// `_`-prefixed documentation, control signals other than NUMERALS/REST, etc.).
// When the app needs the contract at runtime (Epic 3+), it gets its own
// bundled-asset loader; this stays test-side.

// MARK: - semaphore_config.json

struct SemaphoreConfig: Decodable {
    let angleToleranceDeg: Double
    let minKeypointConfidence: Double
    // Temporal-commit constants (spec §4.4, ADR 0004). Parsed here so the
    // committer port (#4.2) can build its `CommitTiming` from the same contract
    // the decoder is built from; the per-frame parity harness ignores them.
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

// MARK: - semaphore_alphabet.json

struct ArmPair: Decodable {
    let left: Int
    let right: Int
}

struct Position: Decodable {
    let id: Int
    let angleDeg: Double

    enum CodingKeys: String, CodingKey {
        case id
        case angleDeg = "angle_deg"
    }
}

struct PositionModel: Decodable {
    let positions: [String: Position]
}

struct ControlSignals: Decodable {
    let numerals: ArmPair
    let rest: ArmPair

    enum CodingKeys: String, CodingKey {
        case numerals = "NUMERALS"
        case rest = "REST"
    }
}

struct NumericMode: Decodable {
    let digitMap: [String: String]

    enum CodingKeys: String, CodingKey {
        case digitMap = "digit_map"
    }
}

struct Alphabet: Decodable {
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

// MARK: - keypoint_contract.json

struct ModelInputOrder: Decodable {
    let floats: [String]
    let length: Int
}

struct KeypointContract: Decodable {
    let modelInputOrder: ModelInputOrder

    enum CodingKeys: String, CodingKey {
        case modelInputOrder = "model_input_order"
    }
}

// MARK: - native_fixtures/invariants.json

struct NativeInvariant: Decodable {
    let kind: String
    let lhs: String
    let rhs: String
    let value: Double?  // present only for `abs_diff_lt`
}

struct NativePose: Decodable {
    let name: String
    let image: String
    let expectedPositionIds: ArmPair
    let referencePostAdapter: [String: [Double]]
    let invariants: [NativeInvariant]

    enum CodingKeys: String, CodingKey {
        case name, image, invariants
        case expectedPositionIds = "expected_position_ids"
        case referencePostAdapter = "reference_post_adapter"
    }
}

struct NativeFixtures: Decodable {
    let poses: [NativePose]
}

// MARK: - test_vectors.json

struct SinglePoseVector: Decodable {
    let name: String
    let keypoints: [String: [Double]]
    let modeBefore: String
    let expected: String
    let expectedPositionIds: [Int?]

    enum CodingKeys: String, CodingKey {
        case name, keypoints, expected
        case modeBefore = "mode_before"
        case expectedPositionIds = "expected_position_ids"
    }
}

struct SequenceFrame: Decodable {
    let name: String
    let keypoints: [String: [Double]]
    let expected: String
    let expectedPositionIds: [Int?]

    enum CodingKeys: String, CodingKey {
        case name, keypoints, expected
        case expectedPositionIds = "expected_position_ids"
    }
}

struct SequenceVector: Decodable {
    let name: String
    let modeStart: String
    let frames: [SequenceFrame]

    enum CodingKeys: String, CodingKey {
        case name, frames
        case modeStart = "mode_start"
    }
}

struct TestVectors: Decodable {
    let singlePoseVectors: [SinglePoseVector]
    let sequenceVectors: [SequenceVector]

    enum CodingKeys: String, CodingKey {
        case singlePoseVectors = "single_pose_vectors"
        case sequenceVectors = "sequence_vectors"
    }
}

// MARK: - temporal_vectors.json (ADR 0004; the committer parity fixtures)

/// One timed frame. A pose frame carries `keypoints` + `expectedSymbol`; a reset
/// frame carries `reset == true` and no keypoints (`reset`/`keypoints`/
/// `expectedSymbol` are absent there, hence optional). `expectedSymbol` is also
/// `nil` for an indeterminate pose — but those frames are told apart from reset
/// frames by `reset`, so the harness only reads `expectedSymbol` on pose frames.
struct TemporalFrame: Decodable {
    let reset: Bool?
    let keypoints: [String: [Double]]?
    let tMs: Int
    let expectedSymbol: String?
    let expectedEmit: String

    enum CodingKeys: String, CodingKey {
        case reset, keypoints
        case tMs = "t_ms"
        case expectedSymbol = "expected_symbol"
        case expectedEmit = "expected_emit"
    }
}

struct TemporalSequence: Decodable {
    let name: String
    let modeStart: String
    let frames: [TemporalFrame]
    let expectedCommitted: String

    enum CodingKeys: String, CodingKey {
        case name, frames
        case modeStart = "mode_start"
        case expectedCommitted = "expected_committed"
    }
}

struct TemporalVectors: Decodable {
    let sequenceVectors: [TemporalSequence]

    enum CodingKeys: String, CodingKey {
        case sequenceVectors = "sequence_vectors"
    }
}

// MARK: - locating the shared contract

/// Resolves the repo's `shared/` directory and loads the JSON contract files.
///
/// Per the #14 decision, the tests read the `shared/` JSON files directly rather
/// than copying or symlinking them, so both platforms parse the exact bytes the
/// Python generator wrote. The anchor is this source file's compile-time path
/// (`#filePath`); we walk up until we find the directory containing
/// `shared/test_vectors.json`. This works from a simulator-hosted XCTest the
/// same way swift-snapshot-testing reaches its reference images.
enum SharedFiles {
    /// Absolute URL of the repo's `shared/` directory.
    static let directory: URL = {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let fm = FileManager.default
        while dir.path(percentEncoded: false) != "/" {
            let marker = dir.appendingPathComponent("shared/test_vectors.json")
            if fm.fileExists(atPath: marker.path(percentEncoded: false)) {
                return dir.appendingPathComponent("shared")
            }
            dir = dir.deletingLastPathComponent()
        }
        fatalError("Could not locate repo root (shared/test_vectors.json) above \(#filePath)")
    }()

    static func load<T: Decodable>(_ type: T.Type, _ fileName: String) throws -> T {
        let url = directory.appendingPathComponent(fileName)
        let data = try Data(contentsOf: url)
        return try JSONDecoder().decode(T.self, from: data)
    }
}

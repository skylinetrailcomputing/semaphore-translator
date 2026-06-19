import Foundation

// Decodable mirrors of the language-neutral `shared/*.json` contract. Only the
// fields the decoder consumes are modeled; unknown keys (the `_`-prefixed
// documentation, control signals other than NUMERALS/REST, etc.) are ignored
// by Codable. These three files are the single source of truth — see
// `SharedFiles` below for how the tests reach them without copying.

// MARK: - semaphore_config.json

struct SemaphoreConfig: Decodable {
    let angleToleranceDeg: Double
    let minKeypointConfidence: Double

    enum CodingKeys: String, CodingKey {
        case angleToleranceDeg = "ANGLE_TOLERANCE_DEG"
        case minKeypointConfidence = "MIN_KEYPOINT_CONFIDENCE"
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

// MARK: - locating the shared contract

/// Resolves the repo's `shared/` directory and loads the JSON contract files.
///
/// Per the #14 decision, the tests read `shared/*.json` directly rather than
/// copying or symlinking them, so both platforms parse the exact bytes the
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

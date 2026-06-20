import XCTest

@testable import SemaphoreTranslator

// Shared test-only helpers for the fixture suites (parity + native fixtures):
// the single decoder construction, the map -> typed `Keypoints` bridge, and the
// native-fixture invariant evaluator. None of this ships in the app.

/// Builds `SemaphoreDecoder` from the frozen shared contract. The single
/// construction shared by `ParityTests` and `NativeFixtureTests`.
enum ReferenceDecoder {
    static func make() throws -> SemaphoreDecoder {
        let alphabet = try SharedFiles.load(Alphabet.self, "semaphore_alphabet.json")
        let config = try SharedFiles.load(SemaphoreConfig.self, "semaphore_config.json")

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
            minKeypointConfidence: config.minKeypointConfidence
        )
    }
}

/// Build the typed `Keypoints` the decoder consumes from a fixture's
/// `[name: [x, y, confidence]]` map (the shape used by both `test_vectors.json`
/// and `invariants.json`). The map is a fixture artifact; the shipping adapter
/// builds `Keypoints` directly from native pose output (Epic 3). A missing name
/// is a malformed fixture and fails the test loudly.
func makeKeypoints(from map: [String: [Double]], _ context: String) throws -> Keypoints {
    func point(_ name: String) throws -> Keypoint {
        let v = try XCTUnwrap(map[name], "\(context): missing keypoint \(name)")
        return Keypoint(x: v[0], y: v[1], confidence: v[2])
    }
    return Keypoints(
        leftShoulder: try point("left_shoulder"),
        leftElbow: try point("left_elbow"),
        leftWrist: try point("left_wrist"),
        rightShoulder: try point("right_shoulder"),
        rightElbow: try point("right_elbow"),
        rightWrist: try point("right_wrist")
    )
}

// MARK: - native-fixture invariant evaluation

/// Resolve a `<keypoint>.<x|y|confidence>` operand against a `Keypoints`, or nil
/// if the operand is malformed.
private func value(of operand: String, in kp: Keypoints) -> Double? {
    let parts = operand.split(separator: ".")
    guard parts.count == 2 else { return nil }
    let byName: [String: Keypoint] = [
        "left_shoulder": kp.leftShoulder, "left_elbow": kp.leftElbow, "left_wrist": kp.leftWrist,
        "right_shoulder": kp.rightShoulder, "right_elbow": kp.rightElbow,
        "right_wrist": kp.rightWrist,
    ]
    guard let point = byName[String(parts[0])] else { return nil }
    switch parts[1] {
    case "x": return point.x
    case "y": return point.y
    case "confidence": return point.confidence
    default: return nil
    }
}

/// Evaluate one invariant against a `Keypoints`, or nil if it is malformed
/// (unknown operand/kind, or `abs_diff_lt` missing its `value`).
func evaluate(_ inv: NativeInvariant, on kp: Keypoints) -> Bool? {
    guard let lhs = value(of: inv.lhs, in: kp), let rhs = value(of: inv.rhs, in: kp) else {
        return nil
    }
    switch inv.kind {
    case "gt": return lhs > rhs
    case "lt": return lhs < rhs
    case "abs_diff_lt":
        guard let v = inv.value else { return nil }
        return abs(lhs - rhs) < v
    default: return nil
    }
}

private func mapPoints(_ kp: Keypoints, _ transform: (Keypoint) -> Keypoint) -> Keypoints {
    Keypoints(
        leftShoulder: transform(kp.leftShoulder),
        leftElbow: transform(kp.leftElbow),
        leftWrist: transform(kp.leftWrist),
        rightShoulder: transform(kp.rightShoulder),
        rightElbow: transform(kp.rightElbow),
        rightWrist: transform(kp.rightWrist)
    )
}

/// A correct output with the horizontal mirror wrong (x not flipped to the
/// signer's perspective): every x becomes `1 - x`.
func mirrorBroken(_ kp: Keypoints) -> Keypoints {
    mapPoints(kp) { Keypoint(x: 1 - $0.x, y: $0.y, confidence: $0.confidence) }
}

/// A correct output with the y-flip wrong (still y-down): every y becomes `1 - y`.
func yFlipBroken(_ kp: Keypoints) -> Keypoints {
    mapPoints(kp) { Keypoint(x: $0.x, y: 1 - $0.y, confidence: $0.confidence) }
}

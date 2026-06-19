import Foundation

/// Decoder mode (spec §4.5). Raw values match the strings used in
/// `test_vectors.json` (`mode_before` / `mode_start`).
enum Mode: String {
    case letters = "LETTERS"
    case numeric = "NUMERIC"
}

/// Faithful Swift port of the reference decoder in
/// `shared/tools/gen_test_vectors.py` (the fixtures were generated against it).
/// Pure logic, downstream of the per-platform adapter: it consumes the frozen
/// 6-keypoint representation and produces the same emitted string + position
/// ids as the Kotlin port. The parity harness proves they agree byte-for-byte.
///
/// The decoder is intentionally decoupled from the wire format: it takes the
/// already-parsed contract as plain values, so the app target carries no JSON /
/// loader code. The test harness parses `shared/*.json` and constructs it.
struct SemaphoreDecoder {
    /// Order-insensitive lookup key: an unordered pair of position ids.
    private struct Pair: Hashable {
        let a: Int
        let b: Int
        init(_ x: Int, _ y: Int) {
            if x <= y { a = x; b = y } else { a = y; b = x }
        }
    }

    private let octants: [(id: Int, angle: Double)]  // sorted by id, like the generator
    private let lookup: [Pair: String]
    private let digitMap: [String: String]
    private let toleranceDeg: Double
    private let minConfidence: Double

    /// - Parameters:
    ///   - octantAngles: position id → canonical angle in degrees.
    ///   - symbolPairs: symbol name → (left id, right id); the 26 letters plus
    ///     the `NUMERALS` and `REST` control signals.
    ///   - digitMap: letter symbol → digit string, in numeric mode (A–I → 1–9, K → 0).
    init(
        octantAngles: [Int: Double],
        symbolPairs: [String: (left: Int, right: Int)],
        digitMap: [String: String],
        angleToleranceDeg: Double,
        minKeypointConfidence: Double
    ) {
        self.toleranceDeg = angleToleranceDeg
        self.minConfidence = minKeypointConfidence
        self.digitMap = digitMap
        self.octants = octantAngles
            .map { (id: $0.key, angle: $0.value) }
            .sorted { $0.id < $1.id }

        var table: [Pair: String] = [:]
        for (symbol, ids) in symbolPairs {
            let key = Pair(ids.left, ids.right)
            if let existing = table[key] {
                fatalError(
                    "alphabet collision under order-insensitive match: "
                        + "\(symbol) and \(existing) both map to \(key)")
            }
            table[key] = symbol
        }
        self.lookup = table
    }

    /// Circular angular distance in degrees. Octant id 7 is stored as -135°
    /// (= 225°); a naive linear diff misclassifies near the wrap, so this
    /// mirrors the generator's `abs(((a - b + 180) % 360) - 180)` with Python
    /// floor-modulo semantics.
    private func circDiff(_ a: Double, _ b: Double) -> Double {
        let r = (a - b + 180).truncatingRemainder(dividingBy: 360)
        let m = r < 0 ? r + 360 : r
        return abs(m - 180)
    }

    /// Snap an arm angle to the nearest octant id, or nil if farther than the
    /// tolerance from every octant.
    private func quantize(_ angle: Double) -> Int? {
        var bestId: Int?
        var best = Double.greatestFiniteMagnitude
        for octant in octants {
            let d = circDiff(angle, octant.angle)
            if d < best {
                best = d
                bestId = octant.id
            }
        }
        return best <= toleranceDeg ? bestId : nil
    }

    /// Position id for one arm, or nil if indeterminate (angle out of tolerance
    /// or a defining keypoint below the confidence floor). Only shoulder and
    /// wrist define the arm vector; the elbow does not gate.
    private func armId(shoulder: [Double], wrist: [Double]) -> Int? {
        if shoulder[2] < minConfidence || wrist[2] < minConfidence { return nil }
        let angle = atan2(wrist[1] - shoulder[1], wrist[0] - shoulder[0]) * 180 / .pi
        return quantize(angle)
    }

    private func classify(_ kp: [String: [Double]]) -> (left: Int?, right: Int?, symbol: String?) {
        let left = armId(shoulder: kp["left_shoulder"]!, wrist: kp["left_wrist"]!)
        let right = armId(shoulder: kp["right_shoulder"]!, wrist: kp["right_wrist"]!)
        var symbol: String?
        if let left, let right { symbol = lookup[Pair(left, right)] }
        return (left, right, symbol)
    }

    /// Map a classified symbol to (emitted string, new mode) per spec §4.5.
    private func interpret(_ symbol: String?, _ mode: Mode) -> (emit: String, mode: Mode) {
        guard let symbol else { return ("", mode) }            // indeterminate: emit nothing
        if symbol == "NUMERALS" { return ("", .numeric) }      // numerals sign
        if symbol == "J", mode == .numeric { return ("", .letters) }  // letters-shift, numeric only
        if symbol == "REST" { return (" ", mode) }             // space; mode persists
        if mode == .numeric, let digit = digitMap[symbol] { return (digit, mode) }
        return (symbol, mode)                                  // letter (incl. the J pose in LETTERS)
    }

    /// Decode a single post-adapter frame.
    /// - Returns: the emitted string, the resulting mode, and the white-box
    ///   `[left_id, right_id]` (nil where an arm is indeterminate).
    func decodeFrame(_ kp: [String: [Double]], mode: Mode) -> (emit: String, mode: Mode, ids: [Int?]) {
        let (left, right, symbol) = classify(kp)
        let (emit, newMode) = interpret(symbol, mode)
        return (emit, newMode, [left, right])
    }
}

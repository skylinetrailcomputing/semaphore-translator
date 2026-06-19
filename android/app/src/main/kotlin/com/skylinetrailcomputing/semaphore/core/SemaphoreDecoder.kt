package com.skylinetrailcomputing.semaphore.core

import kotlin.math.abs
import kotlin.math.atan2

/**
 * Decoder mode (spec §4.5). Enum names match the strings used in
 * `test_vectors.json` (`mode_before` / `mode_start`), so `Mode.valueOf` parses
 * them directly.
 */
enum class Mode { LETTERS, NUMERIC }

/** Result of decoding one post-adapter frame. */
data class FrameResult(val emit: String, val mode: Mode, val ids: List<Int?>)

/**
 * Faithful Kotlin port of the reference decoder in
 * `shared/tools/gen_test_vectors.py` (the fixtures were generated against it).
 * Pure logic, downstream of the per-platform adapter: it consumes the frozen
 * 6-keypoint representation and produces the same emitted string + position
 * ids as the Swift port. The parity harness proves they agree byte-for-byte.
 */
class SemaphoreDecoder(alphabet: Alphabet, config: SemaphoreConfig) {
    /** Order-insensitive lookup key: an unordered pair of position ids. */
    private data class Pair(val a: Int, val b: Int)

    private fun pairOf(x: Int, y: Int): Pair = if (x <= y) Pair(x, y) else Pair(y, x)

    // (id, angle) sorted by id, like the generator.
    private val octants: List<kotlin.Pair<Int, Double>> =
        alphabet.positionModel.positions.values
            .map { it.id to it.angleDeg }
            .sortedBy { it.first }
    private val lookup: Map<Pair, String>
    private val digitMap: Map<String, String> = alphabet.numericMode.digitMap
    private val toleranceDeg: Double = config.angleToleranceDeg
    private val minConfidence: Double = config.minKeypointConfidence

    init {
        val table = HashMap<Pair, String>()
        fun add(symbol: String, left: Int, right: Int) {
            val key = pairOf(left, right)
            val existing = table[key]
            check(existing == null) {
                "alphabet collision under order-insensitive match: " +
                    "$symbol and $existing both map to $key"
            }
            table[key] = symbol
        }
        alphabet.letters.forEach { (symbol, ids) -> add(symbol, ids.left, ids.right) }
        add("NUMERALS", alphabet.controlSignals.numerals.left, alphabet.controlSignals.numerals.right)
        add("REST", alphabet.controlSignals.rest.left, alphabet.controlSignals.rest.right)
        lookup = table
    }

    /**
     * Circular angular distance in degrees. Octant id 7 is stored as -135°
     * (= 225°); a naive linear diff misclassifies near the wrap, so this
     * mirrors the generator's `abs(((a - b + 180) % 360) - 180)`. Kotlin's
     * `Double.mod` is floor-modulo (non-negative for a positive divisor),
     * matching Python's `%`.
     */
    private fun circDiff(a: Double, b: Double): Double = abs((a - b + 180).mod(360.0) - 180)

    /** Snap an arm angle to the nearest octant id, or null if farther than the tolerance. */
    private fun quantize(angle: Double): Int? {
        var bestId: Int? = null
        var best = Double.MAX_VALUE
        for ((id, octAngle) in octants) {
            val d = circDiff(angle, octAngle)
            if (d < best) {
                best = d
                bestId = id
            }
        }
        return if (best <= toleranceDeg) bestId else null
    }

    /**
     * Position id for one arm, or null if indeterminate (angle out of tolerance
     * or a defining keypoint below the confidence floor). Only shoulder and
     * wrist define the arm vector; the elbow does not gate.
     */
    private fun armId(shoulder: List<Double>, wrist: List<Double>): Int? {
        if (shoulder[2] < minConfidence || wrist[2] < minConfidence) return null
        val angle = Math.toDegrees(atan2(wrist[1] - shoulder[1], wrist[0] - shoulder[0]))
        return quantize(angle)
    }

    private fun classify(kp: Map<String, List<Double>>): Triple<Int?, Int?, String?> {
        val left = armId(kp.getValue("left_shoulder"), kp.getValue("left_wrist"))
        val right = armId(kp.getValue("right_shoulder"), kp.getValue("right_wrist"))
        val symbol = if (left != null && right != null) lookup[pairOf(left, right)] else null
        return Triple(left, right, symbol)
    }

    /** Map a classified symbol to (emitted string, new mode) per spec §4.5. */
    private fun interpret(symbol: String?, mode: Mode): kotlin.Pair<String, Mode> {
        if (symbol == null) return "" to mode // indeterminate: emit nothing
        if (symbol == "NUMERALS") return "" to Mode.NUMERIC // numerals sign
        if (symbol == "J" && mode == Mode.NUMERIC) return "" to Mode.LETTERS // letters-shift, numeric only
        if (symbol == "REST") return " " to mode // space; mode persists
        if (mode == Mode.NUMERIC) {
            val digit = digitMap[symbol]
            if (digit != null) return digit to mode
        }
        return symbol to mode // letter (incl. the J pose in LETTERS)
    }

    /**
     * Decode a single post-adapter frame, returning the emitted string, the
     * resulting mode, and the white-box `[left_id, right_id]` (null where an
     * arm is indeterminate).
     */
    fun decodeFrame(kp: Map<String, List<Double>>, mode: Mode): FrameResult {
        val (left, right, symbol) = classify(kp)
        val (emit, newMode) = interpret(symbol, mode)
        return FrameResult(emit, newMode, listOf(left, right))
    }
}

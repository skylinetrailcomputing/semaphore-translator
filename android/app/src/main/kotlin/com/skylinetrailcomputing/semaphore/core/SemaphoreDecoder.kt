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
 *
 * The decoder is intentionally decoupled from the wire format: it takes the
 * already-parsed contract as plain values, so the app module carries no JSON /
 * Gson / loader code. The test harness parses the shared JSON and constructs it.
 *
 * @param octantAngles position id -> canonical angle in degrees.
 * @param symbolPairs symbol name -> (left id, right id); the 26 letters plus
 *   the NUMERALS and REST control signals.
 * @param digitMap letter symbol -> digit string, in numeric mode (A-I -> 1-9, K -> 0).
 */
class SemaphoreDecoder(
    octantAngles: Map<Int, Double>,
    symbolPairs: Map<String, Pair<Int, Int>>,
    digitMap: Map<String, String>,
    angleToleranceDeg: Double,
    minKeypointConfidence: Double,
) {
    /** Order-insensitive lookup key: an unordered pair of position ids. */
    private data class IdPair(val a: Int, val b: Int)

    private fun pairOf(x: Int, y: Int): IdPair = if (x <= y) IdPair(x, y) else IdPair(y, x)

    // (id, angle) sorted by id, like the generator.
    private val octants: List<Pair<Int, Double>> =
        octantAngles.map { it.key to it.value }.sortedBy { it.first }
    private val lookup: Map<IdPair, String>
    private val digitMap: Map<String, String> = digitMap
    private val toleranceDeg: Double = angleToleranceDeg
    private val minConfidence: Double = minKeypointConfidence

    init {
        val table = HashMap<IdPair, String>()
        symbolPairs.forEach { (symbol, ids) ->
            val key = pairOf(ids.first, ids.second)
            val existing = table[key]
            check(existing == null) {
                "alphabet collision under order-insensitive match: " +
                    "$symbol and $existing both map to $key"
            }
            table[key] = symbol
        }
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
     * Position id for one arm, or null if indeterminate (angle out of tolerance,
     * or no usable keypoint above the confidence floor).
     *
     * The primary arm vector is shoulder->wrist. When the wrist is below the
     * floor but the elbow is not, fall back to the collinear shoulder->elbow
     * vector (#111, lever C from the #101 diagnostic): semaphore arms are held
     * straight, so the two share an angle, and the elbow is the more proximal,
     * lower-variance joint that survives the frame-clipping / body-crossing that
     * gates the wrist. The shoulder is the common origin for both vectors, so a
     * low-confidence shoulder is unrecoverable -> null, as is a low wrist with no
     * in-frame elbow proxy. Mirrors `arm_id` in the Python reference.
     */
    private fun armId(shoulder: Keypoint, elbow: Keypoint, wrist: Keypoint): Int? {
        if (shoulder.confidence < minConfidence) return null
        val tip =
            when {
                wrist.confidence >= minConfidence -> wrist
                elbow.confidence >= minConfidence -> elbow
                else -> return null
            }
        val angle = Math.toDegrees(atan2(tip.y - shoulder.y, tip.x - shoulder.x))
        return quantize(angle)
    }

    private fun classifyArms(kp: Keypoints): Triple<Int?, Int?, String?> {
        val left = armId(kp.leftShoulder, kp.leftElbow, kp.leftWrist)
        val right = armId(kp.rightShoulder, kp.rightElbow, kp.rightWrist)
        val symbol = if (left != null && right != null) lookup[pairOf(left, right)] else null
        return Triple(left, right, symbol)
    }

    /**
     * Stage 1 of the decode: the **mode-independent pose symbol** -- a letter
     * pose, NUMERALS, REST, or null (indeterminate). This is the temporal
     * committer's votable unit (ADR 0004, #31): mode is exactly what is unstable
     * frame-to-frame, so smoothing votes on the pre-mode pose, never the emitted
     * character. [decodeFrame] composes this with [interpret].
     *
     * `internal`: the committer (#4.3) lives in this module; nothing outside it
     * should reach the pre-mode stages directly (matches Swift's default access).
     */
    internal fun classify(kp: Keypoints): String? = classifyArms(kp).third

    /**
     * Stage 2 of the decode: map a classified symbol to (emitted string, new
     * mode) per spec §4.5. Exposed so the temporal committer (#4.2/#4.3) can run
     * it on commit; mode therefore flips only on a *committed* control pose.
     *
     * `internal`: exposed for the in-module committer (#4.3), not the app at large.
     */
    internal fun interpret(symbol: String?, mode: Mode): Pair<String, Mode> {
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
    fun decodeFrame(kp: Keypoints, mode: Mode): FrameResult {
        val (left, right, symbol) = classifyArms(kp)
        val (emit, newMode) = interpret(symbol, mode)
        return FrameResult(emit, newMode, listOf(left, right))
    }
}

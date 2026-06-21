package com.skylinetrailcomputing.semaphore.core

import android.content.Context
import org.json.JSONObject

/**
 * The frozen temporal-commit constants (spec §4.4, ADR 0004), surfaced from the
 * shared contract so the live layer (#4.5) can build the committer. A plain value
 * type -- like [SemaphoreDecoder]'s plain-value constructor, it carries no JSON
 * code; [ContractLoader.makeCommitTiming] parses the contract into it.
 *
 * @param smoothingWindow frames of majority-vote smoothing on the votable pose symbol.
 * @param commitHoldMs how long a candidate symbol must hold (wall-clock ms) before it commits.
 * @param interCharGapMs minimum *brief-REST* dwell (ms) that re-arms the *same*
 *   symbol for re-commit (the double-letter separator); a REST held >=
 *   commitHoldMs commits a space instead, and a distinct symbol commits on its
 *   hold alone (ADR 0005, superseding ADR 0004 Decision 3).
 */
data class CommitTiming(
    val smoothingWindow: Int,
    val commitHoldMs: Double,
    val interCharGapMs: Double,
)

/**
 * Loads the frozen shared contract (`semaphore_alphabet.json` +
 * `semaphore_config.json`) bundled into the app and assembles the
 * [SemaphoreDecoder]. App-side counterpart to the test harness's
 * `referenceDecoder()` / `SharedFiles` (Issue #14, ADR 0001): the tests reach
 * `shared/` on the host via the JVM working directory, but a shipped app
 * cannot, so a Gradle `Copy` task (see `app/build.gradle.kts`) stages the two
 * files into the app's assets straight from `shared/` — no committed copy, so
 * `shared/` stays the single source of truth.
 *
 * Parsed with the framework's `org.json` rather than Gson: Gson stays a
 * test-only dependency (ADR 0001 keeps the shipping app free of it), and
 * `org.json` needs no dependency at all — symmetric with iOS's `ContractLoader`
 * using the built-in `Codable`. Per the `semaphore_config.json` README, both
 * platforms *load* the contract; the constants are never hardcoded in
 * Swift/Kotlin.
 */
object ContractLoader {
    /**
     * Build the decoder from the bundled contract, mirroring the test harness's
     * `referenceDecoder()` exactly so the live app and the parity fixtures share
     * one construction. Throws if an asset is missing or malformed — that is a
     * build-packaging bug, surfaced to the caller as an error state.
     */
    fun makeDecoder(context: Context): SemaphoreDecoder {
        val alphabet = JSONObject(readAsset(context, "semaphore_alphabet.json"))
        val config = JSONObject(readAsset(context, "semaphore_config.json"))

        val positions = alphabet.getJSONObject("_position_model").getJSONObject("positions")
        val octantAngles =
            buildMap {
                for (key in positions.keys()) {
                    val pos = positions.getJSONObject(key)
                    put(pos.getInt("id"), pos.getDouble("angle_deg"))
                }
            }

        val letters = alphabet.getJSONObject("letters")
        val controls = alphabet.getJSONObject("control_signals")
        val symbolPairs =
            buildMap {
                for (symbol in letters.keys()) {
                    val p = letters.getJSONObject(symbol)
                    put(symbol, p.getInt("left") to p.getInt("right"))
                }
                val numerals = controls.getJSONObject("NUMERALS")
                put("NUMERALS", numerals.getInt("left") to numerals.getInt("right"))
                val rest = controls.getJSONObject("REST")
                put("REST", rest.getInt("left") to rest.getInt("right"))
            }

        val digitMapJson = alphabet.getJSONObject("numeric_mode").getJSONObject("digit_map")
        val digitMap = buildMap { for (k in digitMapJson.keys()) put(k, digitMapJson.getString(k)) }

        return SemaphoreDecoder(
            octantAngles = octantAngles,
            symbolPairs = symbolPairs,
            digitMap = digitMap,
            angleToleranceDeg = config.getDouble("ANGLE_TOLERANCE_DEG"),
            minKeypointConfidence = config.getDouble("MIN_KEYPOINT_CONFIDENCE"),
        )
    }

    /**
     * Parse the frozen temporal-commit constants (spec §4.4) from the bundled
     * `semaphore_config.json`. No committer is built here -- that is #4.5; this
     * only surfaces the constants the live layer will inject.
     */
    fun makeCommitTiming(context: Context): CommitTiming {
        val config = JSONObject(readAsset(context, "semaphore_config.json"))
        return CommitTiming(
            smoothingWindow = config.getInt("SMOOTHING_WINDOW"),
            commitHoldMs = config.getDouble("COMMIT_HOLD_MS"),
            interCharGapMs = config.getDouble("INTER_CHAR_GAP_MS"),
        )
    }

    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }
}

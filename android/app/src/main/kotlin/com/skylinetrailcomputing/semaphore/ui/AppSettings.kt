package com.skylinetrailcomputing.semaphore.ui

import android.content.Context

/**
 * Lean app-settings persistence ([5d], #50). Backed by
 * [android.content.SharedPreferences] — synchronous and zero-dependency, the
 * natural parity match for iOS's `@AppStorage` (`UserDefaults`); DataStore's
 * async Flow surface would be overkill here. Holds the developer-mode flag that
 * gates the Learn screen's skeleton overlay + raw per-frame readout, plus the
 * first-launch disclaimer consent ([6b-9], #87). Contract knobs (angle-tolerance,
 * commit-hold) are deliberately out of scope (FR7). The iOS twin is `@AppStorage`
 * (`developerMode` + the `DisclaimerKeys`).
 */
object AppSettings {
    private const val PREFS = "semaphore.settings"
    private const val KEY_DEVELOPER_MODE = "developer_mode"
    private const val KEY_SHOW_ASSIST = "show_assist_figure"
    private const val KEY_SHOW_NUMERALS_INDICATOR = "show_numerals_indicator"
    private const val KEY_MATCHED_ONLY_READOUT = "drill_matched_only_readout"
    private const val KEY_DISCLAIMER_HASH = "disclaimer_accepted_hash"
    private const val KEY_DISCLAIMER_AT = "disclaimer_accepted_at"

    fun developerMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEVELOPER_MODE, false)

    fun setDeveloperMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEVELOPER_MODE, enabled).apply()
    }

    /**
     * Whether the Learn drill draws the contract-derived assist figure (#73, 6a-5).
     * Default ON — it's the primary teaching aid for Learn. The iOS twin is
     * `@AppStorage("showAssistFigure")`.
     */
    fun showAssist(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_ASSIST, true)

    fun setShowAssist(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_ASSIST, enabled).apply()
    }

    /**
     * Whether the live camera screen shows the user-facing NUMERALS mode pill
     * (#103, 6a-14). Default ON — informative and unobtrusive. The iOS twin is
     * `@AppStorage("showNumeralsIndicator")`.
     */
    fun showNumeralsIndicator(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_NUMERALS_INDICATOR, true)

    fun setShowNumeralsIndicator(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_NUMERALS_INDICATOR, enabled).apply()
    }

    /**
     * Whether a passage drill's committed readout grows only by *matched* targets —
     * the forgiving "easy mode" (#95, 6a-10) — rather than echoing every committed
     * character verbatim. Default ON; drill-only (free-form Learn/Interpret is always
     * verbatim). The iOS twin is `@AppStorage("drillMatchedOnlyReadout")`.
     */
    fun matchedOnlyReadout(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MATCHED_ONLY_READOUT, true)

    fun setMatchedOnlyReadout(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MATCHED_ONLY_READOUT, enabled).apply()
    }

    /**
     * SHA-256 hex of the disclaimer doc the user last accepted ([6b-9], #87), or
     * empty if never accepted. Compared against the bundled doc's hash to decide
     * whether to re-show the gate. The iOS twin is `@AppStorage("disclaimerAcceptedHash")`.
     */
    fun acceptedDisclaimerHash(context: Context): String =
        prefs(context).getString(KEY_DISCLAIMER_HASH, "") ?: ""

    /** Persist disclaimer consent: the accepted hash + a local epoch-millis timestamp. */
    fun recordDisclaimerAccepted(context: Context, hash: String, atEpochMillis: Long) {
        prefs(context)
            .edit()
            .putString(KEY_DISCLAIMER_HASH, hash)
            .putLong(KEY_DISCLAIMER_AT, atEpochMillis)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

package com.skylinetrailcomputing.semaphore.ui

import android.content.Context

/**
 * Lean app-settings persistence ([5d], #50). The only setting today is a
 * developer-mode flag that gates the Learn screen's skeleton overlay + raw
 * per-frame readout. Backed by [android.content.SharedPreferences] — synchronous
 * and zero-dependency, the natural parity match for iOS's `@AppStorage`
 * (`UserDefaults`); DataStore's async Flow surface would be overkill for one
 * boolean. Contract knobs (angle-tolerance, commit-hold) are deliberately out of
 * scope (FR7). The iOS twin is `@AppStorage("developerMode")`.
 */
object AppSettings {
    private const val PREFS = "semaphore.settings"
    private const val KEY_DEVELOPER_MODE = "developer_mode"

    fun developerMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEVELOPER_MODE, false)

    fun setDeveloperMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEVELOPER_MODE, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

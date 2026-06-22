package com.skylinetrailcomputing.semaphore.ui

import android.content.Context
import java.security.MessageDigest
import org.json.JSONObject

/**
 * The first-launch disclaimer, loaded from the bundled `shared/disclaimer.json`
 * ([6b-9], #87). The displayed fields are parsed from JSON; [contentHash] is
 * computed from the asset's **raw bytes**, so it is identical to iOS's hash over
 * the same `shared/` file and changes whenever the doc is edited — that hash is
 * the consent key ([SemaphoreApp] re-shows the gate when it stops matching the
 * stored one). Mirrors `ContractLoader`'s bundled-asset posture (`org.json`, no
 * Gson in the shipping app). The iOS twin is `DisclaimerDocument` in
 * `App/DisclaimerGate.swift`.
 */
data class DisclaimerDocument(
    val version: String,
    val title: String,
    val body: List<String>,
    val agreement: String,
    val acceptLabel: String,
    val eulaUrl: String,
    val privacyUrl: String,
    /** SHA-256 hex over the raw doc bytes — the consent key. */
    val contentHash: String,
) {
    companion object {
        /**
         * Load + parse the disclaimer bundled into the app's assets (staged from
         * `shared/` by the `copySharedContract` Gradle task). Throws if the asset
         * is missing or malformed — a build-packaging bug, which [SemaphoreApp]
         * surfaces as a fail-closed blocking screen rather than skipping the gate.
         */
        fun loadFromAssets(context: Context): DisclaimerDocument {
            val bytes = context.assets.open("disclaimer.json").use { it.readBytes() }
            return decode(bytes)
        }

        /**
         * Parse a disclaimer doc from its raw JSON bytes and stamp it with the
         * SHA-256 of those exact bytes. Factored out from [loadFromAssets] so the
         * hashing matches what iOS computes on-device over the same `shared/` file.
         */
        fun decode(bytes: ByteArray): DisclaimerDocument {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val bodyJson = json.getJSONArray("body")
            val body = buildList { for (i in 0 until bodyJson.length()) add(bodyJson.getString(i)) }
            val links = json.getJSONObject("links")
            return DisclaimerDocument(
                version = json.getString("version"),
                title = json.getString("title"),
                body = body,
                agreement = json.getString("agreement"),
                acceptLabel = json.getString("accept_label"),
                eulaUrl = links.getString("eula"),
                privacyUrl = links.getString("privacy"),
                contentHash = sha256Hex(bytes),
            )
        }

        /**
         * The gate decision: re-show whenever the stored accepted hash doesn't
         * match the bundled doc's hash. Empty stored hash (never accepted) → show;
         * a doc edit (new hash) → show; otherwise → skip. Pure, so it's unit-tested
         * without a device. The iOS twin is `DisclaimerDocument.needsConsent`.
         */
        fun needsConsent(acceptedHash: String, documentHash: String): Boolean =
            acceptedHash != documentHash

        /**
         * Lowercase hex SHA-256 of [bytes]. Matches iOS's CryptoKit `SHA256` hex
         * over the same bytes, so both platforms derive the same consent key.
         */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

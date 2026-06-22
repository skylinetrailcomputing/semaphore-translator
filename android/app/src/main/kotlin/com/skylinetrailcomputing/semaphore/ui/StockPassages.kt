package com.skylinetrailcomputing.semaphore.ui

import android.content.Context
import com.skylinetrailcomputing.semaphore.core.PassageSource
import org.json.JSONObject

/** One bundled sight-read stock passage (Epic 6a, #71 / 6a-3). iOS twin: `StockPassage`. */
data class StockPassage(
    val id: String,
    /**
     * A content-neutral teaser shown in the picker -- never the passage text. The
     * drill HUD reveals one target at a time; that reveal is what "sight-read" means.
     */
    val hint: String,
    /**
     * The drill passage, authored already-clean (`sanitize(text) == text`). Still run
     * through [com.skylinetrailcomputing.semaphore.core.PassageSource.sanitize] on use
     * so there is a single code path.
     */
    val text: String,
)

/**
 * The bundled sight-read stock passages, loaded verbatim from
 * `shared/stock_passages.json` (staged into assets by `copySharedContract`) so the
 * list is identical to iOS's. Mirrors `DisclaimerDocument`'s bundled-asset posture
 * (`org.json`, no Gson in the shipping app). The iOS twin is `StockPassages` in
 * `App/StockPassages.swift`.
 */
data class StockPassages(val passages: List<StockPassage>) {
    companion object {
        /**
         * Load + parse the stock passages from the app's assets. Throws if the asset
         * is missing or malformed -- a build-packaging bug. Unlike the disclaimer gate
         * (which fails closed), the sight-read source fails **soft**: the picker shows
         * an "unavailable" state and the custom-passage source stays usable.
         */
        fun loadFromAssets(context: Context): StockPassages {
            val bytes = context.assets.open("stock_passages.json").use { it.readBytes() }
            return decode(bytes)
        }

        /** Parse stock passages from raw JSON bytes. Factored out so tests can drive the shipping parser. */
        fun decode(bytes: ByteArray): StockPassages {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val arr = json.getJSONArray("passages")
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(StockPassage(o.getString("id"), o.getString("hint"), o.getString("text")))
                }
            }
            // Defence-in-depth: drop any entry whose text isn't a valid drill passage
            // (empty, not already-clean, or over the cap) so a malformed bundled asset
            // fails soft per-entry rather than starting an empty/garbled drill. The
            // checked-in file is asserted well-formed by SanitizeParityTest; this
            // guards a future edit that ships without the tests.
            return StockPassages(list.filter(::isValid))
        }

        /**
         * A stock entry is usable iff its text sanitises to itself (already-clean),
         * is non-empty, and is within the target cap — the same invariants the
         * generator + parity tests assert on the checked-in file.
         */
        private fun isValid(passage: StockPassage): Boolean {
            val sanitized = PassageSource.sanitize(passage.text)
            return sanitized.isNotEmpty() && sanitized == passage.text &&
                sanitized.length <= PassageSource.MAX_TARGETS
        }
    }
}

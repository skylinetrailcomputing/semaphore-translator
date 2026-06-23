package com.skylinetrailcomputing.semaphore.core

import androidx.camera.core.CameraSelector

/**
 * Which committer-timing profile a camera lens selects (ADR 0009).
 *
 * The temporal committer's timing forks by camera lens: front-camera **Learn**
 * (self-signing) uses the frozen flat constants in `semaphore_config.json`;
 * rear-camera **Interpret** (reading another — possibly fast — signer) uses the
 * `timing_profiles.interpret` override (a faster `COMMIT_HOLD_MS`). This timing
 * fork is the *only* behavioural difference the lens makes — the classify /
 * adapter geometry stays lens-agnostic (ADR 0006). [jsonKey] is the
 * `timing_profiles` key [ContractLoader.makeCommitTiming] looks the override up by.
 */
enum class TimingProfile(val jsonKey: String) {
    LEARN("learn"),
    INTERPRET("interpret"),
    ;

    companion object {
        /**
         * The lens → profile mapping, applied at the committer-construction site:
         * the rear lens drives Interpret, every other lens (front — the Learn/drill
         * default) Learn. Keyed on the CameraX [CameraSelector] lensFacing int (a
         * `static final` compile-time constant that the compiler inlines) rather
         * than a [CameraSelector] instance, so it is a pure function unit-tested on
         * the JVM without constructing/loading CameraX (whose builders aren't mocked
         * in local unit tests). The temporal parity tests prove the vectors pass
         * *given* a profile; this proves the app *selects* the right one. If the
         * lens↔mode coupling ever breaks (e.g. a rear-camera Learn mode), this
         * becomes an explicit caller-supplied argument instead.
         */
        fun forLensFacing(lensFacing: Int?): TimingProfile =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) INTERPRET else LEARN
    }
}

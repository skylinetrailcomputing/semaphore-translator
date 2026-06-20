package com.skylinetrailcomputing.semaphore.core

import com.google.gson.annotations.SerializedName

// Gson-mapped mirrors of the native-fixture contract (`shared/native_fixtures/
// invariants.json`) plus the shared `(left, right)` arm pair. Lives in the
// `sharedTest` source set so BOTH the local-JVM Layer-1 regression test and the
// instrumented Layer-2 calibration parse the same file with the same types —
// the same single-source discipline the rest of the contract follows. Gson
// ignores unknown keys, so the `_`-prefixed documentation is skipped.

/** An ordered `(left, right)` position-id pair (`semaphore_alphabet.json`, fixtures). */
data class ArmPair(val left: Int, val right: Int)

data class NativeInvariant(
    val kind: String,
    val lhs: String,
    val rhs: String,
    val value: Double? = null, // present only for `abs_diff_lt`
)

data class NativePose(
    val name: String,
    val image: String,
    @SerializedName("expected_position_ids") val expectedPositionIds: ArmPair,
    @SerializedName("reference_post_adapter") val referencePostAdapter: Map<String, List<Double>>,
    val invariants: List<NativeInvariant>,
)

data class NativeFixtures(val poses: List<NativePose>)

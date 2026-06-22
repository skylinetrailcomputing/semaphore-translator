package com.skylinetrailcomputing.semaphore.core

import com.google.gson.annotations.SerializedName

// Gson-mapped mirrors of the language-neutral `shared/*.json` contract. Only the
// fields the decoder consumes are modeled; Gson ignores unknown keys, so the
// `_`-prefixed documentation and the control signals other than NUMERALS/REST
// are skipped. These three files are the single source of truth — see
// SharedFiles for how the tests reach them. @SerializedName maps the JSON keys
// whose names differ from the Kotlin property names.

// --- semaphore_config.json ---

data class SemaphoreConfig(
    @SerializedName("ANGLE_TOLERANCE_DEG") val angleToleranceDeg: Double,
    @SerializedName("MIN_KEYPOINT_CONFIDENCE") val minKeypointConfidence: Double,
    // Temporal-commit constants (spec §4.4, ADR 0004). Parsed here so the
    // committer port (#4.3) can build its CommitTiming from the same contract the
    // decoder is built from; the per-frame parity harness ignores them.
    @SerializedName("COMMIT_HOLD_MS") val commitHoldMs: Double,
    @SerializedName("SMOOTHING_WINDOW") val smoothingWindow: Int,
    @SerializedName("INTER_CHAR_GAP_MS") val interCharGapMs: Double,
)

// --- semaphore_alphabet.json ---

// `ArmPair` (the shared `(left, right)` pair) lives in the `sharedTest` source
// set alongside the native-fixture DTOs (`core/NativeFixtureContract.kt`), so it
// is visible to both this `test` set and the instrumented `androidTest` set.

data class Position(val id: Int, @SerializedName("angle_deg") val angleDeg: Double)

data class PositionModel(val positions: Map<String, Position>)

data class ControlSignals(
    @SerializedName("NUMERALS") val numerals: ArmPair,
    @SerializedName("REST") val rest: ArmPair,
)

data class NumericMode(@SerializedName("digit_map") val digitMap: Map<String, String>)

data class Alphabet(
    @SerializedName("_position_model") val positionModel: PositionModel,
    val letters: Map<String, ArmPair>,
    @SerializedName("control_signals") val controlSignals: ControlSignals,
    @SerializedName("numeric_mode") val numericMode: NumericMode,
)

// --- keypoint_contract.json ---

data class ModelInputOrder(val floats: List<String>, val length: Int)

data class KeypointContract(
    @SerializedName("model_input_order") val modelInputOrder: ModelInputOrder,
)

// --- native_fixtures/invariants.json ---
// `NativeInvariant` / `NativePose` / `NativeFixtures` live in the `sharedTest`
// source set (`core/NativeFixtureContract.kt`) so the instrumented Layer-2
// calibration can parse the same file with the same types.

// --- test_vectors.json ---

data class SinglePoseVector(
    val name: String,
    val keypoints: Map<String, List<Double>>,
    @SerializedName("mode_before") val modeBefore: String,
    val expected: String,
    @SerializedName("expected_position_ids") val expectedPositionIds: List<Int?>,
)

data class SequenceFrame(
    val name: String,
    val keypoints: Map<String, List<Double>>,
    val expected: String,
    @SerializedName("expected_position_ids") val expectedPositionIds: List<Int?>,
)

data class SequenceVector(
    val name: String,
    @SerializedName("mode_start") val modeStart: String,
    val frames: List<SequenceFrame>,
)

data class TestVectors(
    @SerializedName("single_pose_vectors") val singlePoseVectors: List<SinglePoseVector>,
    @SerializedName("sequence_vectors") val sequenceVectors: List<SequenceVector>,
)

// --- temporal_vectors.json (ADR 0004; the committer parity fixtures) ---

// A pose frame carries `keypoints` + `expectedSymbol`; a reset frame carries
// `reset == true` and no keypoints (those keys are absent there, so Gson leaves
// them at false/null). `expectedSymbol` is also null for an indeterminate pose --
// but those are told apart from reset frames by `reset`, so the harness only
// reads `expectedSymbol` on pose frames.
data class TemporalFrame(
    val reset: Boolean = false,
    val keypoints: Map<String, List<Double>>? = null,
    @SerializedName("t_ms") val tMs: Long,
    @SerializedName("expected_symbol") val expectedSymbol: String? = null,
    @SerializedName("expected_emit") val expectedEmit: String,
)

data class TemporalSequence(
    val name: String,
    @SerializedName("mode_start") val modeStart: String,
    val frames: List<TemporalFrame>,
    @SerializedName("expected_committed") val expectedCommitted: String,
)

data class TemporalVectors(
    @SerializedName("sequence_vectors") val sequenceVectors: List<TemporalSequence>,
)

// --- drill_contract.json (Epic 6a #69; descriptive frozen contract) ---

// The light, machine-checkable slice of the drill contract. The full file is a
// human-readable spec (like keypoint_contract.json's prose sections); only
// `version` + `states` are asserted, to anchor the doc to the code so it can't
// silently rot. The behavioural enforcement is drill_vectors.json.
data class DrillContract(
    val version: String,
    val states: List<String>,
)

// --- drill_vectors.json (Epic 6a #69; the drill-engine parity fixtures) ---

// An emit step carries `emit` + `expectedMatched`; a reset step carries
// `reset == true` and neither (the harness calls reset() instead of observe), so
// those two are nullable -- like TemporalFrame's reset split. The drill engine is
// downstream of the committer, so a step carries only an emitted character -- no
// keypoints, no timing.
data class DrillStepVector(
    val reset: Boolean = false,
    val emit: String? = null,
    @SerializedName("expected_matched") val expectedMatched: Boolean? = null,
    @SerializedName("expected_index") val expectedIndex: Int,
    @SerializedName("expected_complete") val expectedComplete: Boolean,
)

data class DrillSequence(
    val name: String,
    val targets: String,
    val steps: List<DrillStepVector>,
    @SerializedName("expected_final_index") val expectedFinalIndex: Int,
    @SerializedName("expected_final_complete") val expectedFinalComplete: Boolean,
)

data class DrillVectors(
    @SerializedName("sequence_vectors") val sequenceVectors: List<DrillSequence>,
)

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

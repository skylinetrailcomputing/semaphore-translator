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
)

// --- semaphore_alphabet.json ---

data class ArmPair(val left: Int, val right: Int)

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

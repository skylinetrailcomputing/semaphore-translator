package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.Alphabet
import com.skylinetrailcomputing.semaphore.core.AssistCue
import com.skylinetrailcomputing.semaphore.core.AssistCueKind
import com.skylinetrailcomputing.semaphore.core.AssistCueVectors
import com.skylinetrailcomputing.semaphore.core.AssistGeometry
import com.skylinetrailcomputing.semaphore.core.AssistPose
import com.skylinetrailcomputing.semaphore.core.Mode
import com.skylinetrailcomputing.semaphore.core.SharedFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Cross-platform parity harness for the Learn assist TRANSITION cues (#100 / 6a-13).
 *
 * Drives every sequence in `shared/assist_cue_vectors.json` through [AssistGeometry]
 * and asserts, per target index, the `implied_mode_before` and the full ordered
 * `cues` (kind + arm angles) the reference (`shared/tools/gen_assist_cue_vectors.py`)
 * recorded. The Swift `AssistCueParityTests` asserts the SAME file, so green on both
 * means the two cue engines are byte-for-byte twins of each other and of the Python
 * reference — and the reference itself was cross-checked against the committer's
 * `interpret` at generation time, which is what binds the implied-mode walk to the
 * committer (the #100 correctness risk).
 *
 * The geometry is built from a `SharedFiles`-parsed alphabet (the same Gson path as
 * [AssistGeometryTest]/[DrillParityTest]) so it runs in the fast JVM `src/test` set
 * with no `Context`; the `org.json` asset wrapper shares `makeDecoder`'s
 * instrumented-only coverage gap (#37). The cues are strictly downstream of the
 * alphabet and beside the decode/commit core (ADR 0007), so these vectors carry no
 * keypoints and no timing — only target characters and contract angles.
 */
class AssistCueParityTest {
    private fun geometry(): AssistGeometry {
        val alphabet = SharedFiles.load<Alphabet>("semaphore_alphabet.json")
        val octantAngles = alphabet.positionModel.positions.values.associate { it.id to it.angleDeg }
        val symbolPairs =
            buildMap {
                alphabet.letters.forEach { (symbol, ids) -> put(symbol, ids.left to ids.right) }
                put("REST", alphabet.controlSignals.rest.left to alphabet.controlSignals.rest.right)
                put(
                    "NUMERALS",
                    alphabet.controlSignals.numerals.left to alphabet.controlSignals.numerals.right,
                )
            }
        val digitToLetter =
            alphabet.numericMode.digitMap.entries.associate { (letter, digit) -> digit.first() to letter }
        return AssistGeometry(octantAngles, symbolPairs, digitToLetter)
    }

    @Test
    fun assistCueVectors() {
        val g = geometry()
        val vectors = SharedFiles.load<AssistCueVectors>("assist_cue_vectors.json")
        assertFalse("no assist cue vectors loaded", vectors.sequenceVectors.isEmpty())

        for (sequence in vectors.sequenceVectors) {
            for (step in sequence.steps) {
                val label = "${sequence.name}#${step.index}('${step.target}')"

                assertEquals(
                    "implied mode mismatch for $label",
                    Mode.valueOf(step.impliedModeBefore),
                    g.impliedMode(sequence.targets, step.index),
                )

                val expected =
                    step.cues.map { cue ->
                        val kind = AssistCueKind.entries.first { it.json == cue.kind }
                        AssistCue(kind, AssistPose(cue.leftAngleDeg, cue.rightAngleDeg))
                    }
                assertEquals(
                    "cue mismatch for $label",
                    expected,
                    g.cues(sequence.targets, step.index),
                )
            }
        }
    }
}

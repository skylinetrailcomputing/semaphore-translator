package com.skylinetrailcomputing.semaphore

import androidx.camera.core.CameraSelector
import com.skylinetrailcomputing.semaphore.core.Committer
import com.skylinetrailcomputing.semaphore.core.SharedFiles
import com.skylinetrailcomputing.semaphore.core.TemporalVectors
import com.skylinetrailcomputing.semaphore.core.TimingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interpret-fork temporal parity + profile-selection harness (Issue #75 / 6a-7,
 * ADR 0009). The sister of [TemporalParityTest] (the Learn profile): it drives
 * `shared/temporal_vectors_interpret.json` through `classify` -> [Committer] built
 * from the **interpret** timing, and pins three things the Learn harness can't:
 *
 *   1. the Interpret vectors decode identically on both platforms (the Swift
 *      `InterpretTemporalParityTests` asserts the same file);
 *   2. the lens -> profile mapping the app uses to *select* that timing; and
 *   3. that the profile actually changes committer behaviour (the discriminating
 *      `[400,600)` sequence commits a space under Interpret where Learn doubles).
 */
class InterpretTemporalParityTest {
    /**
     * 1. Replay every Interpret sequence through the interpret-profile committer and
     * assert per-frame `expected_emit` + the rollup. Includes
     * `interpret_rest5_spaces_not_doubles`, the discriminator that asserts `"L L"`
     * (a port ignoring the interpret COMMIT_HOLD_MS would emit `"LL"` and fail here).
     */
    @Test
    fun interpretTemporalVectors() {
        val decoder = referenceDecoder()
        val timing = referenceTiming(TimingProfile.INTERPRET)
        val vectors = SharedFiles.load<TemporalVectors>("temporal_vectors_interpret.json")
        assertFalse("no interpret temporal vectors loaded", vectors.sequenceVectors.isEmpty())

        for (sequence in vectors.sequenceVectors) {
            assertEquals(
                "the committer always starts in LETTERS; ${sequence.name} starts elsewhere",
                "LETTERS",
                sequence.modeStart,
            )
            val committer = Committer(decoder, timing)
            val committed = StringBuilder()

            sequence.frames.forEachIndexed { i, frame ->
                val label = "${sequence.name}#$i@${frame.tMs}ms"
                if (frame.reset) {
                    committer.reset()
                    assertEquals("reset frame must emit '' ($label)", "", frame.expectedEmit)
                } else {
                    val map = requireNotNull(frame.keypoints) { "pose frame missing keypoints ($label)" }
                    val symbol = decoder.classify(keypointsFrom(map))
                    assertEquals("classify mismatch for $label", frame.expectedSymbol, symbol)
                    val emit = committer.process(symbol, frame.tMs)
                    assertEquals("emit mismatch for $label", frame.expectedEmit, emit)
                    committed.append(emit)
                }
            }

            assertEquals(
                "committed string mismatch for ${sequence.name}",
                sequence.expectedCommitted,
                committed.toString(),
            )
        }
    }

    /**
     * 2. The lens -> profile mapping the app selects timing with (ADR 0009): rear ->
     * Interpret, every other lens -> Learn. The parity test above proves the vectors
     * pass *given* interpret timing; this proves the app *picks* it from the lens.
     */
    @Test
    fun lensSelectsProfile() {
        assertEquals(
            TimingProfile.INTERPRET,
            TimingProfile.forLensFacing(CameraSelector.LENS_FACING_BACK),
        )
        assertEquals(
            TimingProfile.LEARN,
            TimingProfile.forLensFacing(CameraSelector.LENS_FACING_FRONT),
        )
        assertEquals("a null lens facing falls back to Learn", TimingProfile.LEARN, TimingProfile.forLensFacing(null))
    }

    /**
     * 3a. The interpret profile is present + fully specified, and is genuinely a
     * *faster* commit than Learn (the point of the fork). A missing block would
     * throw in [referenceTiming] (fatal-not-fallback, ADR 0009).
     */
    @Test
    fun interpretProfileIsFasterThanLearn() {
        val learn = referenceTiming(TimingProfile.LEARN)
        val interpret = referenceTiming(TimingProfile.INTERPRET)
        assertTrue(
            "the interpret fork must commit faster than Learn",
            interpret.commitHoldMs < learn.commitHoldMs,
        )
        assertTrue(interpret.smoothingWindow > 0)
        assertTrue(interpret.interCharGapMs > 0)
    }

    /**
     * 3b. Profile selection actually changes behaviour, not just self-consistent
     * vectors: replay the discriminating sequence's frames through the **Learn**
     * committer and confirm it commits `"LL"` -- the opposite of the file's `"L L"`.
     * So the two profiles provably diverge on the same input.
     */
    @Test
    fun discriminatorDivergesUnderLearnTiming() {
        val decoder = referenceDecoder()
        val learnTiming = referenceTiming(TimingProfile.LEARN)
        val vectors = SharedFiles.load<TemporalVectors>("temporal_vectors_interpret.json")
        val discriminator =
            requireNotNull(
                vectors.sequenceVectors.firstOrNull {
                    it.name == "interpret_rest5_spaces_not_doubles"
                }
            ) { "the interpret fixtures must carry the discriminating sequence" }
        assertEquals(
            "under interpret timing the discriminator commits a space",
            "L L",
            discriminator.expectedCommitted,
        )

        val committer = Committer(decoder, learnTiming)
        val committed = StringBuilder()
        for (frame in discriminator.frames) {
            if (frame.reset) {
                committer.reset()
            } else {
                val map = requireNotNull(frame.keypoints)
                committed.append(committer.process(decoder.classify(keypointsFrom(map)), frame.tMs))
            }
        }
        assertEquals(
            "the SAME frames commit 'LL' under Learn timing -- the fork changes behaviour",
            "LL",
            committed.toString(),
        )
    }
}

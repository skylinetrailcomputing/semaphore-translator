import AVFoundation
import XCTest

@testable import SemaphoreTranslator

/// Interpret-fork temporal parity + profile-selection harness (Issue #75 / 6a-7,
/// ADR 0009). The sister of `TemporalParityTests` (the Learn profile): it drives
/// `shared/temporal_vectors_interpret.json` through `classify` → `Committer` built
/// from the **interpret** timing, and pins three things the Learn harness can't:
///
///   1. the Interpret vectors decode identically on both platforms (the Kotlin
///      `InterpretTemporalParityTest` asserts the same file);
///   2. the lens → profile mapping the app uses to *select* that timing; and
///   3. that the profile actually changes committer behaviour (the discriminating
///      `[350,600)` sequence commits a space under Interpret where Learn doubles).
final class InterpretTemporalParityTests: XCTestCase {
    /// 1. Replay every Interpret sequence through the interpret-profile committer and
    /// assert per-frame `expected_emit` + the rollup — the temporal-parity guarantee
    /// for the rear-camera fork. Includes `interpret_rest5_spaces_not_doubles`, the
    /// discriminator that asserts `"L L"` (a port ignoring the interpret
    /// `COMMIT_HOLD_MS` would emit `"LL"` and fail here).
    func testInterpretTemporalVectors() throws {
        let decoder = try ReferenceDecoder.make()
        let timing = try referenceTiming(profile: .interpret)
        let vectors = try SharedFiles.load(
            TemporalVectors.self, "temporal_vectors_interpret.json")
        XCTAssertFalse(vectors.sequenceVectors.isEmpty, "no interpret temporal vectors loaded")

        for sequence in vectors.sequenceVectors {
            XCTAssertEqual(
                sequence.modeStart, "LETTERS",
                "the committer always starts in LETTERS; \(sequence.name) starts elsewhere")
            let committer = Committer(decoder: decoder, timing: timing)
            var committed = ""

            for (i, frame) in sequence.frames.enumerated() {
                let label = "\(sequence.name)#\(i)@\(frame.tMs)ms"
                if frame.reset == true {
                    committer.reset()
                    XCTAssertEqual(frame.expectedEmit, "", "reset frame must emit '' (\(label))")
                    continue
                }
                let map = try XCTUnwrap(frame.keypoints, "pose frame missing keypoints (\(label))")
                let kp = try makeKeypoints(from: map, label)
                let symbol = decoder.classify(kp)
                XCTAssertEqual(symbol, frame.expectedSymbol, "classify mismatch for \(label)")
                let emit = committer.process(symbol, at: frame.tMs)
                XCTAssertEqual(emit, frame.expectedEmit, "emit mismatch for \(label)")
                committed += emit
            }

            XCTAssertEqual(
                committed, sequence.expectedCommitted,
                "committed string mismatch for \(sequence.name)")
        }
    }

    /// 2. The lens → profile mapping the app selects timing with (ADR 0009): rear →
    /// Interpret, every other lens → Learn. The parity test above proves the vectors
    /// pass *given* interpret timing; this proves the app *picks* it from the lens.
    func testLensSelectsProfile() {
        XCTAssertEqual(TimingProfile(cameraPosition: .back), .interpret)
        XCTAssertEqual(TimingProfile(cameraPosition: .front), .learn)
        XCTAssertEqual(TimingProfile(cameraPosition: .unspecified), .learn)
    }

    /// 3a. The interpret profile is present + fully specified, and is genuinely a
    /// *faster* commit than Learn (the point of the fork). A missing block would
    /// throw in `referenceTiming` (fatal-not-fallback, ADR 0009).
    func testInterpretProfileIsFasterThanLearn() throws {
        let learn = try referenceTiming(profile: .learn)
        let interpret = try referenceTiming(profile: .interpret)
        XCTAssertLessThan(
            interpret.commitHoldMs, learn.commitHoldMs,
            "the interpret fork must commit faster than Learn")
        XCTAssertGreaterThan(interpret.smoothingWindow, 0)
        XCTAssertGreaterThan(interpret.interCharGapMs, 0)
    }

    /// 3b. Profile selection actually changes behaviour, not just self-consistent
    /// vectors: replay the discriminating sequence's frames through the **Learn**
    /// committer and confirm it commits `"LL"` — the opposite of the file's `"L L"`.
    /// So the two profiles provably diverge on the same input.
    func testDiscriminatorDivergesUnderLearnTiming() throws {
        let decoder = try ReferenceDecoder.make()
        let learnTiming = try referenceTiming(profile: .learn)
        let vectors = try SharedFiles.load(
            TemporalVectors.self, "temporal_vectors_interpret.json")
        let discriminator = try XCTUnwrap(
            vectors.sequenceVectors.first { $0.name == "interpret_rest5_spaces_not_doubles" },
            "the interpret fixtures must carry the discriminating sequence")
        XCTAssertEqual(
            discriminator.expectedCommitted, "L L",
            "under interpret timing the discriminator commits a space")

        let committer = Committer(decoder: decoder, timing: learnTiming)
        var committed = ""
        for frame in discriminator.frames {
            if frame.reset == true {
                committer.reset()
                continue
            }
            let map = try XCTUnwrap(frame.keypoints)
            let kp = try makeKeypoints(from: map, discriminator.name)
            committed += committer.process(decoder.classify(kp), at: frame.tMs)
        }
        XCTAssertEqual(
            committed, "LL",
            "the SAME frames commit 'LL' under Learn timing — the fork changes behaviour")
    }
}

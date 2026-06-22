import Foundation

/// One `DrillSession.observe(_:)` decision: did this emit match the current
/// target, the resulting target index, and whether the session is now complete.
struct DrillStep: Equatable {
    /// Did this emit match the current target?
    let matched: Bool
    /// The current target index AFTER this emit (advances by one on a hit).
    let index: Int
    /// Has the session reached the end of its targets (the celebrate signal)?
    let complete: Bool
}

/// The Learn drill engine (Epic 6a, #69) — a faithful Swift twin of the reference
/// `DrillSession` in `shared/tools/gen_drill_vectors.py` and of the Kotlin
/// `DrillSession`. It sits strictly DOWNSTREAM of the `Committer` (ADR 0004/0005):
/// it observes only the characters the committer emits and never reads keypoints,
/// timing, or any decode state, so it cannot perturb the decode/adapter/commit
/// core (parity risk ~0). See `shared/drill_contract.json` +
/// `docs/adr/0007-drill-engine-contract.md`; the cross-platform enforcement is
/// `shared/drill_vectors.json` (the `DrillParityTests` harness).
///
/// Pure logic: `observe(_:)` is a function of the emitted character and the
/// current target only. Stay-until-success (the 6a-1 lifecycle, iv-a) is the
/// advance policy — a matching emit advances, a wrong one is a no-op. The session
/// HUD + celebrate (6a-2) and later modes (6a-6) layer over this same target/index
/// core; they do not change the match rule.
final class DrillSession {
    private let targets: [Character]
    /// Index of the current (not-yet-matched) target; `== count` once complete.
    private(set) var index = 0

    /// `targets` is the drill passage; it is split into single-character targets
    /// in signing order. The source (6a-3) sanitises a passage to the frozen ASCII
    /// alphabet (A-Z / 0-9 / SPACE) before it reaches here.
    init(targets: String) {
        self.targets = Array(targets)
    }

    var count: Int { targets.count }
    var isComplete: Bool { index >= targets.count }

    /// The character the signer must produce next, or `nil` once complete — for
    /// the 6a-2 HUD.
    var currentTarget: Character? { isComplete ? nil : targets[index] }

    /// Feed one NON-EMPTY committed character (the `Committer.process` return).
    /// Returns this step's match decision and the resulting session state. After
    /// COMPLETE, further emits are no-ops (contract: `after_complete`).
    @discardableResult
    func observe(_ emit: String) -> DrillStep {
        guard let target = currentTarget else {
            return DrillStep(matched: false, index: index, complete: true)
        }
        let matched = emit.uppercased() == String(target).uppercased()
        if matched { index += 1 }
        return DrillStep(matched: matched, index: index, complete: isComplete)
    }

    /// Restart the session at the first target (e.g. replay). Caller-driven, like
    /// `Committer.reset()` — never self-driven.
    func reset() { index = 0 }
}

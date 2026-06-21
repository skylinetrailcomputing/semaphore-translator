import Foundation

/// The temporal committer (ADR 0004, #4.2) — a faithful Swift twin of the
/// reference `Committer` in `shared/tools/gen_temporal_vectors.py` and of the
/// Kotlin `Committer` (#4.3). It turns the per-frame decoder into a temporal one:
/// a held pose produces exactly one committed character (FR4), and the
/// numeric-mode switch stops flipping on a single fleeting frame (the #23
/// symptom). The temporal parity harness proves the two platform committers, and
/// the Python reference the fixtures were generated against, agree byte-for-byte.
///
/// Pure + injected-time: `process(_:at:)` and `reset()` never read the clock —
/// time is the caller's `tMs` argument — so `temporal_vectors.json` is
/// deterministic (the same discipline as `SemaphoreDecoder` taking plain values,
/// not JSON). The live layer (#4.5) feeds it the per-frame timestamp (#4.4) and
/// drives `reset()` from the no-signer watchdog.
///
/// It wraps `SemaphoreDecoder` because it owns `mode` and must run `interpret`
/// *at commit* (so mode flips only on a committed control pose). The caller runs
/// the mode-independent `classify` per frame — the votable unit — and feeds the
/// resulting symbol here; `classify` is pre-vote, `interpret` is at-commit, which
/// is exactly the reference's split.
final class Committer {
    private let decoder: SemaphoreDecoder
    private let smoothingWindow: Int
    private let commitHoldMs: Double
    private let interCharGapMs: Double

    /// Ring buffer of the last `smoothingWindow` votable symbols (`nil` =
    /// indeterminate is a legitimate vote value).
    private var window: [String?] = []
    /// Decoder mode (§4.5); flipped only by `interpret` at commit.
    private var mode: Mode = .letters
    /// The voted candidate currently being timed. `candidateSet` distinguishes
    /// "no candidate yet" from a candidate of `nil` — the reference's `_UNSET`
    /// sentinel — so the very first frame still starts the hold timer.
    private var candidate: String?
    private var candidateSet = false
    /// `tMs` at which `candidate` last changed value (the hold timer's origin).
    private var candidateSince = 0
    /// The last committed symbol (the same-symbol lock key).
    private var lastCommitted: String?
    /// Has a re-arming brief REST been seen since the last commit (same-symbol gate)?
    private var gapOk = true
    /// `tMs` the current continuous-REST run began, else `nil`.
    private var restSince: Int?

    /// The committer's current decoder mode (§4.5), for the live mode badge.
    /// Read-only — mode flips solely inside `process`, at commit.
    var currentMode: Mode { mode }

    init(decoder: SemaphoreDecoder, timing: CommitTiming) {
        self.decoder = decoder
        self.smoothingWindow = timing.smoothingWindow
        self.commitHoldMs = timing.commitHoldMs
        self.interCharGapMs = timing.interCharGapMs
        reset()
    }

    /// Hard reset (true signer-loss, §4.5): clear the window, mode → LETTERS.
    /// Driven by the live no-signer watchdog (#4.5), never by the committer
    /// itself — a short indeterminate gap is handled inside `process` and
    /// deliberately preserves mode and the window.
    func reset() {
        window.removeAll(keepingCapacity: true)
        mode = .letters
        candidate = nil
        candidateSet = false
        candidateSince = 0
        lastCommitted = nil
        gapOk = true
        restSince = nil
    }

    /// Feed one classified pose symbol (`nil` = indeterminate) at wall-clock
    /// `tMs`. Returns the string committed on *this* frame: `""` on most frames,
    /// the committed letter/digit/space when a commit fires. A control-pose
    /// commit (NUMERALS, or the J letters-shift) flips mode but also returns `""`
    /// — so the return is the emitted text, never a "did something commit" flag.
    func process(_ symbol: String?, at tMs: Int) -> String {
        window.append(symbol)
        if window.count > smoothingWindow { window.removeFirst() }

        let candidate = vote()
        if !candidateSet || candidate != self.candidate {  // changed → restart hold timer
            self.candidate = candidate
            candidateSet = true
            candidateSince = tMs
        }

        // A brief REST re-arms the same-symbol lock — the conventional double-letter
        // separator (ADR 0005, superseding ADR 0004 Decision 3). The voted candidate
        // being REST, with its dwell in [interCharGapMs, commitHoldMs), re-arms
        // WITHOUT committing a space; at >= commitHoldMs the REST commits a space
        // instead (the commit step below), so the window is half-open at the top —
        // which also stops a sustained REST re-arming after its space commits and
        // streaming spaces. An indeterminate gap no longer re-arms, so incidental
        // off-octant hold jitter can't double a held letter (FR4). Like the hold
        // timer, this keys off the VOTED candidate, not the raw incoming symbol
        // (ADR 0004 Decision 2) — mirror that from the reference. While the
        // candidate is REST, `restSince` == `candidateSince` (both set when the
        // candidate became REST), so the dwell here is the dwell the commit step
        // checks against `commitHoldMs` — which is what lets the half-open top
        // (`< commitHoldMs`) hand off to the space commit at `>= commitHoldMs`.
        if candidate == "REST" {
            let since = restSince ?? tMs
            restSince = since
            let dwell = Double(tMs - since)
            if dwell >= interCharGapMs && dwell < commitHoldMs { gapOk = true }
        } else {
            restSince = nil
        }

        // Commit: a determinate candidate, held long enough, past the same-symbol gate.
        if let candidate, Double(tMs - candidateSince) >= commitHoldMs {
            if candidate != lastCommitted || gapOk {
                let (emit, newMode) = decoder.interpret(candidate, mode)
                mode = newMode
                lastCommitted = candidate
                gapOk = false
                return emit
            }
        }
        return ""
    }

    /// Plurality over the window; ties break to the most-recent occurrence. `nil`
    /// (indeterminate) is a legitimate vote value and can win. Votes over the
    /// partial window before it fills (sequence start / post-`reset`) so the first
    /// character is not committed `smoothingWindow − 1` frames late (ADR 0004
    /// Decision 2).
    private func vote() -> String? {
        var counts: [String?: Int] = [:]
        for value in window { counts[value, default: 0] += 1 }
        let best = counts.values.max() ?? 0
        var winner: String?
        for value in window where counts[value] == best { winner = value }
        return winner
    }
}

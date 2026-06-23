import AVFoundation

/// Which committer-timing profile a camera lens selects (ADR 0009).
///
/// The temporal committer's timing forks by camera lens: front-camera **Learn**
/// (self-signing) uses the frozen flat constants in `semaphore_config.json`;
/// rear-camera **Interpret** (reading another — possibly fast — signer) uses the
/// `timing_profiles.interpret` override (a faster `COMMIT_HOLD_MS`). This timing
/// fork is the *only* behavioural difference the lens makes — the classify /
/// adapter geometry stays lens-agnostic (ADR 0006). The raw value is the JSON
/// `timing_profiles` key (`ContractLoader.makeCommitTiming(profile:)` keys on it).
enum TimingProfile: String {
    case learn
    case interpret

    /// The lens → profile mapping, applied at the committer-construction site:
    /// the rear lens drives Interpret, every other lens (front — the Learn/drill
    /// default) drives Learn. A pure function so it is unit-tested directly — the
    /// temporal parity tests prove the vectors pass *given* a profile; this proves
    /// the app *selects* the right one from the lens (the DoD's "Interpret uses the
    /// new profile"). If the lens↔mode coupling ever breaks (e.g. a rear-camera
    /// Learn mode), this becomes an explicit caller-supplied argument instead.
    init(cameraPosition: AVCaptureDevice.Position) {
        self = cameraPosition == .back ? .interpret : .learn
    }
}

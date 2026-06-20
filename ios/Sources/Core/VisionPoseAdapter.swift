import Vision

/// One joint as reported by Apple Vision, in Vision's **native** normalized
/// frame: origin lower-left (y already up), `x` toward the image's right,
/// labels anatomical. This is the adapter's *input* frame — pre-mirror.
struct VisionJoint {
    let x: Double
    let y: Double
    let confidence: Double
}

/// The six Vision joints the adapter consumes. Both the live capture path and
/// the recorded Layer-1 fixtures produce this; it is the per-platform native
/// skeleton (`VNHumanBodyPoseObservation.JointName`) reduced to the six the
/// shared contract needs (`shared/ADAPTER-CONTRACT.md` §5).
struct VisionSkeleton {
    let leftShoulder: VisionJoint
    let leftElbow: VisionJoint
    let leftWrist: VisionJoint
    let rightShoulder: VisionJoint
    let rightElbow: VisionJoint
    let rightWrist: VisionJoint
}

/// The iOS half of the per-platform adapter (`shared/ADAPTER-CONTRACT.md` §3,
/// spec §3.2): converts an Apple Vision skeleton into the frozen post-adapter
/// `Keypoints` frame — normalized `[0,1]`, **y-up**, **signer's perspective**
/// (`+x` = signer's right). The two flips, each applied **exactly once here and
/// nowhere else**, are empirically pinned by the Epic-3 native fixtures
/// (`VisionCalibrationTests` runs real Vision on `shared/native_fixtures` and
/// asserts `invariants.json`):
///
/// - **y-flip — NO-OP.** Vision reports normalized coordinates with a
///   lower-left origin, i.e. already y-up. Confirmed by the calibration fixture
///   (`arms_down` → `wrist.y < shoulder.y` straight out of Vision).
/// - **horizontal mirror — `x_out = 1 − x`.** The front camera (and the
///   observer-perspective fixture image) mirror the signer; this flip lands the
///   output in the signer's perspective, so the signer's right arm has
///   `right_shoulder.x > left_shoulder.x`.
/// - **labels — trusted as-is.** Vision assigns anatomical left/right correctly
///   from the figure it sees, so `.leftShoulder`/`.rightShoulder` map straight
///   to `left_*`/`right_*`. (A *featureless* figure can make Vision flip these;
///   real signers and the textured fixture do not — the native fixture is what
///   guards the assumption.)
///
/// Per-keypoint confidence is passed through **unmodified**; the
/// `MIN_KEYPOINT_CONFIDENCE` floor is applied downstream, not here
/// (`ADAPTER-CONTRACT.md` §4).
struct VisionPoseAdapter {
    /// The frozen transform. Pure (no Vision dependency on the call), so Layer-1
    /// can replay a recorded skeleton through the exact path live capture uses.
    func adapt(_ s: VisionSkeleton) -> Keypoints {
        Keypoints(
            leftShoulder: mirror(s.leftShoulder),
            leftElbow: mirror(s.leftElbow),
            leftWrist: mirror(s.leftWrist),
            rightShoulder: mirror(s.rightShoulder),
            rightElbow: mirror(s.rightElbow),
            rightWrist: mirror(s.rightWrist)
        )
    }

    /// Live path: pull the six joints from a Vision observation and adapt them.
    /// Returns `nil` if any of the six is absent from the observation (a frame
    /// without a full upper-body skeleton — no decode this frame).
    func adapt(_ observation: VNHumanBodyPoseObservation) throws -> Keypoints? {
        let points = try observation.recognizedPoints(.all)
        func joint(_ name: VNHumanBodyPoseObservation.JointName) -> VisionJoint? {
            guard let p = points[name] else { return nil }
            return VisionJoint(
                x: Double(p.location.x), y: Double(p.location.y), confidence: Double(p.confidence))
        }
        guard let ls = joint(.leftShoulder), let le = joint(.leftElbow), let lw = joint(.leftWrist),
            let rs = joint(.rightShoulder), let re = joint(.rightElbow), let rw = joint(.rightWrist)
        else { return nil }
        return adapt(
            VisionSkeleton(
                leftShoulder: ls, leftElbow: le, leftWrist: lw,
                rightShoulder: rs, rightElbow: re, rightWrist: rw))
    }

    /// The single mirror: `x_out = 1 − x`, `y` unchanged (Vision is y-up),
    /// confidence unchanged.
    private func mirror(_ j: VisionJoint) -> Keypoint {
        Keypoint(x: 1.0 - j.x, y: j.y, confidence: j.confidence)
    }
}

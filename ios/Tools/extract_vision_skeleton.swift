// Host-side Apple Vision body-pose extractor — the Epic-3 Layer-2 capture tool.
//
// Runs `VNDetectHumanBodyPoseRequest` on a PNG and prints the six upper-body
// joints in Vision's NATIVE normalized frame (lower-left origin, y-up, x toward
// the image's right, anatomical L/R labels) — i.e. exactly the
// `VisionPoseAdapter` INPUT recorded in `ios/Tests/Fixtures/vision_skeletons.json`.
//
// Why a host tool: Apple Vision body pose does NOT run on the iOS Simulator
// ("Unable to setup request"), so the per-platform Layer-1 fixture is captured
// once on the macOS host (identical Vision framework + lower-left-origin
// convention) and frozen. See `shared/native_fixtures/NATIVE-FIXTURES.md` §6 and
// `ios/Tests/VisionCalibrationTests.swift` (the live, device-only Layer-2 pass).
//
// Usage (re-run after regenerating the fixtures, then paste into
// vision_skeletons.json — values come out 4-decimal, the JSON's precision):
//
//     swift ios/Tools/extract_vision_skeleton.swift shared/native_fixtures/pose_arms_down.png
//     swift ios/Tools/extract_vision_skeleton.swift shared/native_fixtures/pose_right_arm_out.png

import Foundation
import ImageIO
import Vision

func fail(_ message: String, code: Int32) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(code)
}

guard CommandLine.arguments.count == 2 else {
    fail("usage: extract_vision_skeleton.swift <image.png>", code: 2)
}
let path = CommandLine.arguments[1]

guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
    let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
else {
    fail("could not load image \(path)", code: 1)
}

let request = VNDetectHumanBodyPoseRequest()
try VNImageRequestHandler(cgImage: image, orientation: .up, options: [:]).perform([request])

guard let observation = request.results?.first else {
    fail("Vision detected no body pose in \(path) (see NATIVE-FIXTURES.md §6)", code: 3)
}
let points = try observation.recognizedPoints(.all)

// Names + Vision joints in the exact order/labels vision_skeletons.json records.
let joints: [(name: String, joint: VNHumanBodyPoseObservation.JointName)] = [
    ("left_shoulder", .leftShoulder), ("left_elbow", .leftElbow), ("left_wrist", .leftWrist),
    ("right_shoulder", .rightShoulder), ("right_elbow", .rightElbow), ("right_wrist", .rightWrist),
]

func f(_ v: Double) -> String { String(format: "%.4f", v) }

var rows: [String] = []
for (name, joint) in joints {
    guard let p = points[joint] else { fail("missing joint \(name) in \(path)", code: 4) }
    let pad = String(repeating: " ", count: max(1, 15 - name.count))
    rows.append("  \"\(name)\":\(pad)[\(f(Double(p.location.x))), \(f(Double(p.location.y))), \(f(Double(p.confidence)))]")
}
print("{\n" + rows.joined(separator: ",\n") + "\n}")

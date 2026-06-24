// Host-side Apple Vision body-pose extractor over a *directory of frames* — the
// batch sibling of `extract_vision_skeleton.swift`, used by the #76 (6a-8)
// Interpret-timing tuning harness (`shared/tools/tune_interpret_timing.py`).
//
// Runs `VNDetectHumanBodyPoseRequest` on every `*.jpg` in a directory (sorted by
// name) and prints one JSON object per line (JSONL) to stdout — the six
// upper-body joints in Vision's NATIVE normalized frame (lower-left origin, y-up,
// x toward the image's right, anatomical L/R labels), i.e. exactly the
// `VisionPoseAdapter` INPUT. A frame whose full upper-body skeleton is not
// detected emits `{"frame": N, "detected": false}` — the live adapter's
// "any of the six joints absent -> no decode this frame" case.
//
// Why a host tool: Apple Vision body pose does NOT run on the iOS Simulator, so
// the iOS-side pose path is reproduced on the macOS host (identical Vision
// framework + lower-left-origin convention), the same way the frozen Layer-1
// fixtures were captured. See `ios/Tools/extract_vision_skeleton.swift` and
// `shared/native_fixtures/NATIVE-FIXTURES.md` §6.
//
// Usage (frames are extracted upright by ffmpeg, so Vision sees `.up`):
//
//     ffmpeg -i clip.mov -qscale:v 2 /tmp/frames/%05d.jpg
//     swift ios/Tools/extract_vision_video.swift /tmp/frames > /tmp/keypoints.jsonl
//
// Frame numbers are parsed from the filename stem (00001.jpg -> 1); the Python
// harness maps frame index -> t_ms via the known capture frame rate.

import Foundation
import ImageIO
import Vision

func fail(_ message: String, code: Int32) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(code)
}

guard CommandLine.arguments.count == 2 else {
    fail("usage: extract_vision_video.swift <frames-dir>", code: 2)
}
let dir = CommandLine.arguments[1]

let fm = FileManager.default
guard let entries = try? fm.contentsOfDirectory(atPath: dir) else {
    fail("could not read directory \(dir)", code: 1)
}
let frames = entries.filter { $0.lowercased().hasSuffix(".jpg") }.sorted()
guard !frames.isEmpty else { fail("no .jpg frames in \(dir)", code: 1) }

// Names + Vision joints in the exact order/labels vision_skeletons.json records.
let joints: [(name: String, joint: VNHumanBodyPoseObservation.JointName)] = [
    ("left_shoulder", .leftShoulder), ("left_elbow", .leftElbow), ("left_wrist", .leftWrist),
    ("right_shoulder", .rightShoulder), ("right_elbow", .rightElbow), ("right_wrist", .rightWrist),
]

func f(_ v: Double) -> String { String(format: "%.4f", v) }

func frameNumber(_ filename: String) -> Int {
    let stem = (filename as NSString).deletingPathExtension
    return Int(stem.drop { !$0.isNumber }.prefix { $0.isNumber }) ?? Int(stem) ?? 0
}

let out = FileHandle.standardOutput

for filename in frames {
    let n = frameNumber(filename)
    let path = (dir as NSString).appendingPathComponent(filename)

    func emit(_ line: String) { out.write((line + "\n").data(using: .utf8)!) }

    guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
        let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
    else {
        emit("{\"frame\": \(n), \"detected\": false, \"reason\": \"load_failed\"}")
        continue
    }

    let request = VNDetectHumanBodyPoseRequest()
    do {
        try VNImageRequestHandler(cgImage: image, orientation: .up, options: [:]).perform([request])
    } catch {
        emit("{\"frame\": \(n), \"detected\": false, \"reason\": \"request_failed\"}")
        continue
    }

    guard let observation = request.results?.first,
        let points = try? observation.recognizedPoints(.all)
    else {
        emit("{\"frame\": \(n), \"detected\": false}")
        continue
    }

    var pairs: [String] = ["\"frame\": \(n)", "\"detected\": true"]
    var missing = false
    for (name, joint) in joints {
        guard let p = points[joint] else { missing = true; break }
        pairs.append(
            "\"\(name)\": [\(f(Double(p.location.x))), \(f(Double(p.location.y))), \(f(Double(p.confidence)))]"
        )
    }
    if missing {
        emit("{\"frame\": \(n), \"detected\": false}")
    } else {
        emit("{" + pairs.joined(separator: ", ") + "}")
    }
}

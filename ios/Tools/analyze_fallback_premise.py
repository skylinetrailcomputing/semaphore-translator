# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Measure the #111 / ADR-0012 elbow-fallback premise + the bent-arm risk from
host-Apple-Vision keypoint JSONL.

Consumes the per-frame `[x, y, confidence]` JSONL that
`ios/Tools/extract_vision_video.swift` emits over a directory of video frames
(see ADR 0012 "How to run"). It answers two questions the synthetic parity
vectors can't, off-device:

  [B] Is the straight-arm prior real? On frames where BOTH the wrist and elbow
      are reliable, how far apart are the shoulder->wrist and shoulder->elbow
      angles? Small deltas (within ANGLE_TOLERANCE_DEG) mean the elbow is a sound
      proxy for the wrist's octant.
  [C] Does the premise hold? How often does Vision keep the elbow above the
      confidence floor while the wrist drops below it (the frames the fallback
      newly resolves, where the old code returned indeterminate)?
  [D] How often do BOTH drop together (the fallback correctly declines -- the
      ML-Kit-like case where #111 cannot help)?

All metrics are frame-convention-invariant: confidence is intrinsic, and a
circular angle DELTA between two vectors sharing an origin is preserved under the
adapter's mirror / y-flip. So we work directly in Vision's native frame without
porting the adapter. Host Vision != live on-device Vision, so this is a sanity
check that de-risks the on-device smoke, not a substitute for it.

    swift ios/Tools/extract_vision_video.swift /tmp/frames > /tmp/kp.jsonl
    uv run ios/Tools/analyze_fallback_premise.py <clip-label> /tmp/kp.jsonl
"""

import json
import math
import sys

MIN_CONF = 0.5  # MIN_KEYPOINT_CONFIDENCE
TOL = 20.0  # ANGLE_TOLERANCE_DEG


def circ_diff(a, b):
    return abs(((a - b + 180) % 360) - 180)


def ang(sh, tip):
    return math.degrees(math.atan2(tip[1] - sh[1], tip[0] - sh[0]))


def summ(xs):
    if not xs:
        return "n/a"
    s = sorted(xs)
    p90 = s[min(len(s) - 1, int(0.9 * len(s)))]
    return f"median={s[len(s)//2]:.1f}° p90={p90:.1f}° max={max(s):.1f}°"


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: analyze_fallback_premise.py <clip-label> <keypoints.jsonl>")
    label, path = sys.argv[1], sys.argv[2]
    frames = [json.loads(line) for line in open(path) if line.strip()]
    detected = [f for f in frames if f.get("detected")]

    collinear_deltas = []  # [B] both joints reliable -> how straight is the arm
    fallback_fires = []  # [C] premise: shoulder+elbow ok, wrist sub-floor
    both_low = 0  # [D] wrist AND elbow sub-floor -> fallback declines
    arm_obs = 0

    for fr in detected:
        for side in ("left", "right"):
            sh, el, wr = fr.get(f"{side}_shoulder"), fr.get(f"{side}_elbow"), fr.get(f"{side}_wrist")
            if not (sh and el and wr):
                continue
            arm_obs += 1
            if sh[2] < MIN_CONF:
                continue  # shoulder gate: indeterminate in both old and new code
            delta = circ_diff(ang(sh, wr), ang(sh, el))
            if wr[2] >= MIN_CONF and el[2] >= MIN_CONF:
                collinear_deltas.append(delta)
            elif wr[2] < MIN_CONF and el[2] >= MIN_CONF:
                fallback_fires.append((fr["frame"], side, round(wr[2], 2), round(el[2], 2)))
            elif wr[2] < MIN_CONF and el[2] < MIN_CONF:
                both_low += 1

    within = (
        f"{100 * sum(d <= TOL for d in collinear_deltas) // max(1, len(collinear_deltas))}%"
        if collinear_deltas
        else "n/a"
    )
    print(f"### {label}")
    print(f"frames={len(frames)} detected={len(detected)} arm-observations={arm_obs}")
    print(
        f"[B] collinearity on RELIABLE arms (both wrist+elbow >= {MIN_CONF}): "
        f"n={len(collinear_deltas)}  Δ(sh→wrist vs sh→elbow): {summ(collinear_deltas)}  "
        f"within TOL({TOL:.0f}°): {within}"
    )
    print(
        f"[C] PREMISE — fallback fires (shoulder+elbow ok, wrist<{MIN_CONF}): "
        f"n={len(fallback_fires)}  (old code: indeterminate; new code: resolves via elbow)"
    )
    print(f"[D] both wrist+elbow sub-floor (fallback declines, stays indeterminate): n={both_low}")
    for s in fallback_fires[:8]:
        print(f"    fallback-fire (frame, side, wristConf, elbowConf): {s}")


if __name__ == "__main__":
    main()

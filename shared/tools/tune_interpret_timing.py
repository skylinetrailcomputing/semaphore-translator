# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Interpret timing-profile tuning harness (#76 / 6a-8; ADR 0009).

Replays a real fast-signer clip through the **canonical** decode + commit
reference and sweeps the three committer-timing constants to find the profile
that best decodes the clip — the empirical input ADR 0009 left to #76.

Pipeline, faithful to the live iOS path (PreviewViewModel.handle):

    video frames (ffmpeg, upright)
      -> Apple Vision body pose      (ios/Tools/extract_vision_video.swift -> JSONL)
      -> VisionPoseAdapter           (x_out = 1 - x; here, mirror toggle)
      -> SemaphoreDecoder.classify   (_semaphore_ref.classify, the canonical ref)
      -> Committer.process(sym, t_ms)(gen_temporal_vectors.Committer, the canonical ref)

Faithfulness notes:
  * A frame with no full skeleton is DROPPED, never fed as indeterminate -- on
    device the adapter returns nil and no PoseFrame reaches the committer (a long
    absence is the watchdog's job, not modelled here; this clip has no long gap).
  * t_ms comes from the frame index and the capture fps; the committer only uses
    deltas, so COMMIT_HOLD_MS / INTER_CHAR_GAP_MS are frame-rate-robust.
    SMOOTHING_WINDOW is a frame COUNT, so its time span scales with the rate --
    flagged in the report; the clip's 30 fps is the camera rate (an upper bound on
    on-device Vision throughput).

Not a fixture generator -- it reads a (gitignored) keypoint trace and prints a
report. It mutates no contract file; applying a tuned profile is a separate,
deliberate edit to semaphore_config.json + a gen_temporal_vectors.py rerun.

Usage:
    swift ios/Tools/extract_vision_video.swift /tmp/frames > /tmp/keypoints.jsonl
    uv run shared/tools/tune_interpret_timing.py --keypoints /tmp/keypoints.jsonl
"""

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from _semaphore_ref import classify  # canonical reference decoder (stage 1)
from gen_temporal_vectors import Committer  # canonical reference committer (ADR 0004)

GROUND_TRUTH = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
JOINTS = (
    "left_shoulder",
    "left_elbow",
    "left_wrist",
    "right_shoulder",
    "right_elbow",
    "right_wrist",
)


# --- input -------------------------------------------------------------------


def load_frames(path, fps):
    """[(t_ms, kp|None)] in capture order; None == no full skeleton this frame."""
    frames = []
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        o = json.loads(line)
        t_ms = round((o["frame"] - 1) * 1000.0 / fps)
        if o.get("detected"):
            frames.append((t_ms, {j: o[j] for j in JOINTS}))
        else:
            frames.append((t_ms, None))
    return frames


def adapt(kp, mirror):
    """The iOS VisionPoseAdapter: x_out = 1 - x when mirrored; y/conf unchanged;
    anatomical labels trusted as-is (VisionPoseAdapter.swift)."""
    return {
        name: [(1.0 - x) if mirror else x, y, c] for name, (x, y, c) in kp.items()
    }


def symbol_stream(frames, mirror):
    """[(t_ms, symbol)] for the frames the live pipeline would actually commit on
    (detected frames only); symbol is a letter / NUMERALS / REST / None."""
    stream = []
    for t_ms, kp in frames:
        if kp is None:
            continue
        _, _, sym = classify(adapt(kp, mirror))
        stream.append((t_ms, sym))
    return stream


# --- decode + scoring --------------------------------------------------------


def decode(stream, *, window, hold, gap):
    c = Committer(smoothing_window=window, commit_hold_ms=hold, inter_char_gap_ms=gap)
    return "".join(c.process(sym, t_ms) for t_ms, sym in stream)


def levenshtein(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(
                prev[j - 1] if ca == cb else 1 + min(prev[j], cur[j - 1], prev[j - 1])
            )
        prev = cur
    return prev[-1]


def lcs_len(a, b):
    prev = [0] * (len(b) + 1)
    for ca in a:
        cur = [0]
        for j, cb in enumerate(b, 1):
            cur.append(prev[j - 1] + 1 if ca == cb else max(prev[j], cur[j - 1]))
        prev = cur
    return prev[-1]


def score(decoded, truth=GROUND_TRUTH):
    letters = decoded.replace(" ", "")
    dist = levenshtein(letters, truth)
    return {
        "raw": decoded,
        "letters": letters,
        "n_letters": len(letters),
        "n_spaces": decoded.count(" "),
        "lcs": lcs_len(letters, truth),  # correct letters in order
        "edit": dist,
        "accuracy": max(0.0, 1.0 - dist / len(truth)),  # 1 - normalized edit distance
    }


# --- cadence (how fast is this signer?) --------------------------------------


def cadence(stream, window):
    """Durations (ms) each DETERMINATE non-REST pose stays the smoothed plurality
    candidate -- the dwell COMMIT_HOLD_MS is compared against. Mirrors the
    committer's vote (plurality over the last `window`, ties to most recent)."""

    def vote(buf):
        counts = {}
        for v in buf:
            counts[v] = counts.get(v, 0) + 1
        best = max(counts.values())
        winner = None
        for v in buf:
            if counts[v] == best:
                winner = v
        return winner

    buf, cands = [], []
    for t_ms, sym in stream:
        buf.append(sym)
        if len(buf) > window:
            buf.pop(0)
        cands.append((t_ms, vote(buf)))

    runs = []  # (symbol, duration_ms) for determinate, non-REST candidate runs
    i = 0
    while i < len(cands):
        t0, c = cands[i]
        j = i
        while j + 1 < len(cands) and cands[j + 1][1] == c:
            j += 1
        if c is not None and c != "REST":
            # duration = span to the next candidate change (or last-frame delta)
            end = cands[j + 1][0] if j + 1 < len(cands) else cands[j][0] + _dt(cands, j)
            runs.append((c, end - t0))
        i = j + 1
    return runs


def _dt(cands, j):
    return cands[j][0] - cands[j - 1][0] if j > 0 else 33


def pct(xs, p):
    if not xs:
        return 0
    s = sorted(xs)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]


# --- report ------------------------------------------------------------------


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--keypoints", required=True, help="JSONL from extract_vision_video.swift")
    ap.add_argument("--fps", type=float, default=30.0)
    ap.add_argument("--truth", default=GROUND_TRUTH)
    ap.add_argument("--mirror", choices=["auto", "on", "off"], default="auto")
    ap.add_argument("--holds", default="600,550,500,450,400,350,300,250")
    ap.add_argument("--gaps", default="100,150,200,250,300")
    ap.add_argument("--windows", default="3,5,7,9")
    args = ap.parse_args()

    frames = load_frames(args.keypoints, args.fps)
    n_det = sum(1 for _, kp in frames if kp is not None)
    print(f"# Interpret timing tuning (#76 / 6a-8) — {Path(args.keypoints).name}")
    print(
        f"\nframes: {len(frames)} total, {n_det} detected "
        f"({100*n_det/len(frames):.1f}%), {args.fps:g} fps, "
        f"{frames[-1][0]/1000:.2f}s, truth={args.truth!r} ({len(args.truth)} letters)"
    )

    # Mirror selection (geometry only; auto = whichever decodes A–Z better).
    base = dict(window=5, hold=400, gap=200)
    if args.mirror == "auto":
        on = score(decode(symbol_stream(frames, True), **base), args.truth)
        off = score(decode(symbol_stream(frames, False), **base), args.truth)
        mirror = on["edit"] <= off["edit"]
        print(
            f"\nmirror auto-detect @ {base}: "
            f"on→edit {on['edit']} {on['letters']!r} | off→edit {off['edit']} {off['letters']!r} "
            f"⇒ mirror={'ON' if mirror else 'OFF'}"
        )
    else:
        mirror = args.mirror == "on"
        print(f"\nmirror={'ON' if mirror else 'OFF'} (forced)")

    stream = symbol_stream(frames, mirror)

    # Cadence: the signer's actual per-letter dwell, at window=5.
    runs = cadence(stream, 5)
    durs = [d for _, d in runs]
    print(
        f"\n## Signer cadence (smoothed letter-pose dwell, window=5): "
        f"{len(runs)} determinate letter runs"
    )
    print(
        f"   dwell ms  min={min(durs)}  p10={pct(durs,10)}  median={pct(durs,50)}  "
        f"p90={pct(durs,90)}  max={max(durs)}"
    )
    print(
        "   → COMMIT_HOLD_MS must be ≤ a letter's dwell to capture it; "
        f"{sum(1 for d in durs if d < 600)}/{len(durs)} runs dwell <600ms (Learn), "
        f"{sum(1 for d in durs if d < 400)}/{len(durs)} <400ms (current Interpret)."
    )

    # Primary sweep: COMMIT_HOLD_MS at gap=200, window=5.
    holds = [int(x) for x in args.holds.split(",")]
    print("\n## COMMIT_HOLD_MS sweep (gap=200, window=5)")
    print(f"   {'hold':>5} {'acc':>6} {'edit':>5} {'lcs':>4} {'len':>4} {'sp':>3}  decoded")
    best = None
    for h in holds:
        s = score(decode(stream, window=5, hold=h, gap=200), args.truth)
        flag = " ←current" if h == 400 else (" ←Learn" if h == 600 else "")
        print(
            f"   {h:>5} {s['accuracy']:>6.2f} {s['edit']:>5} {s['lcs']:>4} "
            f"{s['n_letters']:>4} {s['n_spaces']:>3}  {s['letters']}{flag}"
        )
        if best is None or s["edit"] < best[1]["edit"]:
            best = (h, s)
    best_hold = best[0]
    print(
        f"\n   min-edit hold on THIS clip @ gap=200,win=5: {best_hold} (edit {best[1]['edit']}) "
        "— a single-clip optimum, NOT the recommendation: lower holds narrow the\n"
        "   brief-REST double-letter window, which an all-distinct clip can't measure "
        "(see docs/interpret-timing-tuning.md)."
    )

    # Secondary sweeps around the best hold.
    gaps = [int(x) for x in args.gaps.split(",")]
    print(f"\n## INTER_CHAR_GAP_MS sweep (hold={best_hold}, window=5)")
    print(f"   {'gap':>5} {'acc':>6} {'edit':>5} {'len':>4} {'sp':>3}  decoded")
    for g in gaps:
        s = score(decode(stream, window=5, hold=best_hold, gap=g), args.truth)
        print(
            f"   {g:>5} {s['accuracy']:>6.2f} {s['edit']:>5} "
            f"{s['n_letters']:>4} {s['n_spaces']:>3}  {s['letters']}"
        )

    windows = [int(x) for x in args.windows.split(",")]
    print(
        f"\n## SMOOTHING_WINDOW sweep (hold={best_hold}, gap=200) "
        f"— frame-COUNT, so time span scales with fps (≈{1000/args.fps:.0f}ms/frame here)"
    )
    print(f"   {'win':>5} {'acc':>6} {'edit':>5} {'len':>4} {'sp':>3}  decoded")
    for w in windows:
        s = score(decode(stream, window=w, hold=best_hold, gap=200), args.truth)
        print(
            f"   {w:>5} {s['accuracy']:>6.2f} {s['edit']:>5} "
            f"{s['n_letters']:>4} {s['n_spaces']:>3}  {s['letters']}"
        )

    # Before/after headline (the DoD), against the SHIPPED contract profiles so the
    # tool can't go stale as the Interpret values are tuned.
    from _semaphore_ref import timing_for

    def shipped(profile):
        t = timing_for(profile)
        s = score(
            decode(
                stream,
                window=t["smoothing_window"],
                hold=t["commit_hold_ms"],
                gap=t["inter_char_gap_ms"],
            ),
            args.truth,
        )
        return t["commit_hold_ms"], s

    learn_hold, learn = shipped("learn")
    interp_hold, interp = shipped("interpret")
    print("\n## Before/after (DoD) — shipped contract profiles + this clip's min-edit")
    rows = (
        (f"Learn      {learn_hold}", learn),
        (f"Interpret  {interp_hold}", interp),
        (f"min-edit   {best_hold}", best[1]),
    )
    for label, s in rows:
        print(
            f"   {label:>14}: acc {s['accuracy']:.2f}  edit {s['edit']:>2}  "
            f"{s['n_letters']}/{len(args.truth)} letters  {s['letters']!r}"
        )


if __name__ == "__main__":
    main()

# Interpret timing-profile tuning (#76 / 6a-8)

Tunes the **Interpret** committer-timing profile (ADR 0009) against real
fast-signer footage, and documents the before/after decode results that
issue [#76](https://github.com/skylinetrailcomputing/semaphore-translator/issues/76)
(6a-8) asks for. Depends on #75 (6a-7), which shipped the per-fork timing
mechanism with a *provisional* `COMMIT_HOLD_MS = 400`.

**Outcome:** `COMMIT_HOLD_MS` **400 → 350** (values-only; `INTER_CHAR_GAP_MS`
and `SMOOTHING_WINDOW` unchanged). The dominant residual decode error is
**geometry, not timing** — so the real next lever for Interpret quality is the
pose-friction work (#110–112), not faster commit.

## The footage

A single ~A–Z clip (`IMG_1366.mov`, **not** committed — it's personal footage):
1080×1920 portrait, 30 fps, 21.7 s, one signer running the alphabet "a little
fast" — representative of (and a touch slower than) the Ocean City lifeguards
the Interpret mode targets. Ground truth: `ABCDEFGHIJKLMNOPQRSTUVWXYZ`.

## Method — host replay of the live iOS pipeline

The decode-quality-vs-speed tradeoff is fully determined by the committer state
machine, which is the **canonical Python reference** both native ports mirror
(`shared/tools/_semaphore_ref.py` + the `Committer` in
`shared/tools/gen_temporal_vectors.py`). So we replay the real clip through that
reference, faithfully reproducing the live iOS path
(`PreviewViewModel.handle`):

```
video → ffmpeg (upright frames) → Apple Vision body pose → VisionPoseAdapter
      → SemaphoreDecoder.classify → Committer.process(symbol, t_ms)
```

Two host tools (both reusable for any future clip):

- `ios/Tools/extract_vision_video.swift` — runs `VNDetectHumanBodyPoseRequest`
  on each frame (Vision doesn't run on the iOS simulator; the macOS host uses
  the identical framework — the same reason the Layer-1 fixtures are
  host-captured), emitting per-frame keypoints as JSONL in Vision's native
  frame (the adapter's input).
- `shared/tools/tune_interpret_timing.py` — applies the adapter (`x → 1−x`),
  runs `classify` + the reference `Committer` across a grid of the three timing
  constants, and scores the decode against ground truth.

Faithfulness notes:
- A frame with **no full skeleton is dropped**, never fed as indeterminate — on
  device the adapter returns `nil` and no `PoseFrame` reaches the committer.
  (Here 652/653 frames detected, so this is moot; it's correct anyway.)
- `t_ms` comes from the frame index and the capture fps. The committer uses
  **wall-clock deltas**, so `COMMIT_HOLD_MS` / `INTER_CHAR_GAP_MS` are
  frame-rate-robust. `SMOOTHING_WINDOW` is a frame **count**, so its time span
  scales with the rate — flagged below.
- The adapter mirror is **auto-detected** by which orientation decodes A–Z
  better (`on → edit 12` vs `off → edit 22` ⇒ mirror **ON**, matching the
  lens-agnostic `x → 1−x` adapter the app ships, ADR 0006).

### Reproduce

```bash
ffmpeg -i IMG_1366.mov -qscale:v 2 /tmp/frames/%05d.jpg
swift ios/Tools/extract_vision_video.swift /tmp/frames > /tmp/keypoints.jsonl
uv run shared/tools/tune_interpret_timing.py --keypoints /tmp/keypoints.jsonl
```

## Results

### The fork works — and the bottleneck is geometry, not timing

| profile | `COMMIT_HOLD_MS` | letters captured | decoded |
|---|---|---|---|
| **Learn** (front, self-signing) | 600 | **9 / 26** | `BCDEFHRUG` |
| **Interpret** (shipped) | **350** | **16 / 26** | `BCDEFHIJPRUWXYZG` |
| this clip's min-edit | 300 | 18 / 26 | `BCDEFHIJPQRTUWXYZG` |

The captured letters are a correct **in-order subsequence** of A–Z — strong
evidence the replay is faithful. But mapping the smoothed pose timeline to the
alphabet shows the misses are structural:

```
expected:   A  B  C  D  E  F  G  H  I  J  K  L  M  N  O  P  Q  R  S  T  U  V  W  X  Y  Z
classified:    B  C  D  E  F     H  I  J              (O) P  Q  R (S) T  U     W  X  Y  Z   + spurious G
```

- **A → misread as B** (adjacent octant); **G missing mid-alphabet**;
  **K / L / M / N never classify** (long indeterminate runs — the wide /
  across-body / portrait-wingspan poses); **V missing**; **S** only a 234 ms
  flicker. These go indeterminate at the **classify** layer and stay missing at
  *every* hold (down to 250 ms) — pose-detection / geometry friction
  (spike-101; #110 / #111 / #112), which ADR 0009 deliberately keeps **out** of
  the timing fork.
- The **spurious trailing G** is the signer's arms-down end pose classifying as
  G instead of REST — also geometry.

So timing tuning has a **geometry ceiling** of ~18–19/26 on this clip; within
that ceiling, faster commit is what the Interpret fork buys.

### `COMMIT_HOLD_MS` sweep (gap = 200, window = 5)

| hold | accuracy | edit | letters | decoded |
|---|---|---|---|---|
| 600 (Learn) | 0.31 | 18 | 9 | `BCDEFHRUG` |
| 500 | 0.46 | 14 | 13 | `BCDEFHIJRUWYG` |
| 450 | 0.46 | 14 | 14 | `BCDEFHIJRUWYZG` |
| **400** | 0.54 | 12 | 16 | `BCDEFHIJPRUWXYZG` |
| **350** ✅ | 0.54 | 12 | 16 | `BCDEFHIJPRUWXYZG` |
| 300 | 0.62 | 10 | 18 | `BCDEFHIJPQRTUWXYZG` |
| 250 | 0.62 | 10 | 19 | `BCDEFHIJPQRTU**J**WXYZG` (spurious J) |

This is a genuinely fast signer: median letter-pose dwell **233 ms**, and
**30 / 46** held poses dwell under 400 ms.

### Why 350, not lower — the double-letter window

`COMMIT_HOLD_MS` is also the **upper bound of the brief-REST double-letter
re-arm window `[INTER_CHAR_GAP_MS, COMMIT_HOLD_MS)`** (ADR 0005/0009). Lowering
the hold narrows it. Measured against the reference committer (frame counts `k`
of a brief REST between two held `L`s that yield `"LL"`):

| hold | double-letter window (k → `"LL"`) |
|---|---|
| 600 (Learn) | k ∈ {3, 4, 5, 6} |
| 400 | k ∈ {3, 4} |
| **350** | k ∈ {3, 4} — **same as 400** |
| 300 | k ∈ {3} — **halved** |

An all-distinct A–Z clip **cannot** measure double-letter reliability, so we
won't trade it away on this evidence:

- **350 dominates 400 here:** identical decode (16/26) *and* identical
  double-letter window, with headroom for signers slightly faster than this clip
  (the stated Ocean City target) — letters held in `[350, 400)` would drop at
  400 but commit at 350.
- **300** captures +2 single letters (Q, T) but **halves** the double-letter
  window — deferred until a doubles-containing clip can validate it (and ideally
  until #110–112 lifts the geometry ceiling, so faster holds have more
  correctly-classified letters to catch).

`INTER_CHAR_GAP_MS` had **no effect** on this clip (no doubles); `SMOOTHING_WINDOW`
is left at 5 — raising it helped marginally (window 9 → 19/26) but it is a frame
**count**, so its time span depends on on-device Vision throughput (unknown;
30 fps is the camera's rate, an upper bound), making it the riskiest knob to
move from the frozen value.

## Caveats / what footage couldn't settle

- **One clip, one signer, A–Z (no doubles, no digits).** The hold curve and the
  geometry ceiling are robust; the double-letter floor and the digit/NUMERALS
  path are unmeasured.
- **`SMOOTHING_WINDOW`** is frame-rate-sensitive; kept at the frozen 5.
- **On-device guardrail-(d) smoke** for the 350 value is still owed (the fork
  mechanism was already smoked under 6a-7; 350 vs 400 is a sub-perceptual
  tweak — a re-confirm, not a re-prove). The contract change is values-only and
  parity-pinned: at `DT_MS=100` not a single fixture frame changed, only the
  embedded hold metadata.

## Next levers (not timing)

1. **#110 / #111 / #112 (geometry / pose friction)** — the 8 never-classified
   letters are the real Interpret-quality bottleneck.
2. A **doubles-containing** fast clip — to safely explore hold < 350.
3. The **trained classifier (#7)** — would lift the geometry ceiling wholesale.

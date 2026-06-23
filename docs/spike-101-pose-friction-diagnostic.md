# Spike #101 / 6a-14 — pose-reading friction diagnostic

> **Record of the #101 spike.** The dev-only probe instrumentation described here
> ships behind Developer mode (this PR); this doc captures its purpose, the
> on-device measurement protocol (re-runnable for #7 and the #111 implementation),
> and the cross-platform findings + lever dispositions the spike produced. The
> decode path, frozen contracts, and parity vectors are **byte-unchanged** — the
> probe is consumer-side logging only, nothing logs when Developer mode is off.
> Follow-ups: #110 (FOV), #111 (elbow fallback), #112 (framing/assist UX),
> #113 (boundary-flicker hysteresis/tolerance).

## What this spike is measuring

`SemaphoreDecoder.armId` classifies each arm from **`shoulder→wrist` only**,
gated on shoulder+wrist confidence; the elbow is carried in the frozen 12-float
input but never used by the rules classifier
(`SemaphoreDecoder.swift:95-99` / `SemaphoreDecoder.kt:94-98`). Field reports
say some pose classes are higher-friction than they should be for non-expert
users. The hypothesis (issue #101): **the wrist is the weak link**, and every
hard class puts it somewhere unreliable:

- **Diagonals / propellers** (e.g. **L** = `5` + `1`) — the wrist clips a frame
  **corner** (up-diagonal off the top, down-diagonal sunk toward the hip).
- **Across-the-body sextet** (**H, I, O, W, X, Z**) — the crossing wrist lands
  over the torso/other arm → low confidence or an L/R mislocalization.
- **Portrait wingspan** (added scope) — a fully-extended *cardinal* arm (the
  "easy" out-horizontal poses `2`/`3`) clips the frame **side** in portrait,
  because horizontal FOV is the narrow dimension; you must stand far back, which
  shrinks the figure and degrades detection.

All three are the same root cause — *the wrist left the usable frame* — which is
why the elbow fallback (lever C) is expected to help all three at once: the
elbow is proximal, stays in frame, and on a straight semaphore arm
`shoulder→elbow` is collinear with `shoulder→wrist`.

### The three failure buckets (issue Step 1), plus a fourth for the portrait case

| # | Bucket | Numeric signature in the probe line |
|---|--------|-------------------------------------|
| 1 | **gated-out** — a keypoint (usually wrist) below `MIN_KEYPOINT_CONFIDENCE` (0.5) → `nil` → `·` | `wrC < 0.50` (or `shC < 0.50`), id shows `—` |
| 2 | **confident-but-mislocalized** — wrist conf ≥ floor but position wrong (the across-body L/R swap) | `wrC ≥ 0.50` **and** `aWr` disagrees with `aEl` by ≳ a half-octant (~22°) on a straight arm |
| 3 | **near-boundary flicker** — good keypoints, arm at ~22.5°, id flickers between octants | `wrC` & `aWr`≈`aEl` both fine, id flips between two adjacent octants on a held pose |
| 4 | **edge-clipped** (portrait/diagonal out-of-frame) — wrist at/over the frame edge | `wrX`/`wrY` ≈ `0.00`/`1.00` (iOS, clamped) or **outside [0,1]** (Android, ML Kit extrapolates) — or, iOS only, the whole frame drops (`DROP` line) |

The **bucket distribution decides the lever.** Prior: 1 + 4 dominate (and #2 on
the sextet). If so → elbow fallback (C) + capture-FOV (A) + UX (D). If #3
dominates → that's a config-tolerance story (lever E, last resort).

## Enabling the probe

1. Build this branch to the device. Turn **Developer mode** ON
   (Settings → Developer). Front lens = Learn (self-signing); rear = Interpret.
2. The probe is throttled to one line per distinct *(ids + ~15° arm-angle)*
   signature, so a pose that sits at `·` still logs once per attempt (it is no
   longer swallowed by the old unchanged-ids throttle).

### Reading a probe line

```
lens=front ids=[5,1] char=L | L shC=0.97 wrC=0.41 elC=0.93 aWr=46.1 aEl=44.2 wrX=0.21 wrY=0.88 | R shC=0.96 wrC=0.88 elC=0.94 aWr=-44.3 aEl=-46.0 wrX=0.79 wrY=0.12
```

- `ids=[L,R]` quantized octant per arm (`—` = indeterminate); `char` = emitted (`·` = nothing).
- per arm: `shC/wrC/elC` = shoulder/wrist/elbow confidence; `aWr` = `shoulder→wrist`
  angle, `aEl` = `shoulder→elbow` angle (degrees, signer's frame); `wrX/wrY` = raw
  wrist position. In the example, the **left** arm is bucket 1+4: `wrC=0.41`
  (gated) with `wrY=0.88` (clipping the top) — yet `elC=0.93` and `aEl=44.2` ≈
  the true 45°, i.e. **the elbow would have classified it correctly.** That is
  the elbow-fallback evidence, per arm.

### Capturing the lines

- **Android (primary, quantitative).** USB-only adb (Wi-Fi client isolation):
  ```
  ~/Library/Android/sdk/platform-tools/adb logcat -s SemaphoreProbe
  ```
- **iOS (confirmatory).** Per ADR 0006, iOS 26.5 blocks the usual device-log
  relays and the `log` CLI can't stream a connected device. Try **Console.app**
  (device selected; filter subsystem `com.skylinetrailcomputing.semaphore`,
  category `geometry-probe` — this also surfaces the capture-side `DROP …` lines).
  If Console is blocked, fall back to the **behavioral test** below, which needs
  no logs, and take the numbers from Android (the geometry is physics, not
  platform — only the *handling* of an out-of-frame joint differs by platform).

## Protocol A — per-letter friction pass (both platforms)

Front lens, portrait, top-up. Hold each pose past `COMMIT_HOLD_MS` (600 ms),
return to **REST** (arms down) between letters. For each, record the bucket and
the key numbers.

**Hard set (sign each, record the worst-arm bucket):**
`L, F, B, P, R, V` (diagonals/propellers) and `H, I, O, W, X, Z` (across-body).
**Easy controls (should be clean):** `D, N, U` (cardinal) and `A, E, K`.

For each, note: did it commit? if `·`, which arm was indeterminate, and was it
bucket 1 (`wrC<0.5`), 2 (`aWr`≠`aEl`), 3 (flicker), or 4 (`wrX/wrY` at edge)? And
critically: **was `elC` high and `aEl` ≈ the correct octant angle** while the
wrist failed? (the per-arm elbow-fallback verdict).

## Protocol B — portrait wingspan / FOV pass

Front lens, portrait. Stand at a normal arm's-length-plus distance and sign a
**full out-horizontal spread** (both arms straight out = ids `[2,...]`/`[...,3]`
region, e.g. **R** = `3` + `5` or just hold a clean horizontal).

1. **How far back must you stand** for both wrists to register (`wrC ≥ 0.5`,
   `wrX/wrY` inside [0,1])? Record the distance on each platform — the gap is
   the iOS-vs-Android FOV gap (iOS analysis is 16:9 `.high`; Android ~4:3).
2. **iOS skeleton-vanish test (no logs needed).** Extend one arm until the wrist
   leaves the frame. Does the **whole skeleton overlay vanish** (whole-frame
   drop — confirms Vision omits the joint and `adapt` returns `nil`), or does it
   **stay with one arm reading `·`** (Vision kept the joint at low confidence)?
   This single observation resolves the iOS-specific failure regime — and tells
   us whether lever B (graceful partial-skeleton degradation) is needed on iOS.
   Cross-check against the `DROP …` Console lines if available.
3. **Android edge behavior.** Confirm `wrX`/`wrY` go **outside [0,1]** as the arm
   leaves frame (ML Kit extrapolates) and whether `wrC` stays usable or collapses.

## Results template — fill in on-device, then summarize into #101

### Protocol A (per-letter)

| Letter | ids | Android bucket | iOS bucket | worst-arm `wrC` | worst-arm `elC` | `aEl` ≈ correct? | notes |
|--------|-----|----------------|------------|-----------------|-----------------|------------------|-------|
| L (5,1) | | | | | | | |
| H | | | | | | | |
| I | | | | | | | |
| O | | | | | | | |
| W | | | | | | | |
| X | | | | | | | |
| Z | | | | | | | |
| F | | | | | | | |
| P | | | | | | | |
| R | | | | | | | |
| V | | | | | | | |
| D/N/U (controls) | | | | | | | should be clean |

### Protocol B (wingspan / FOV)

| Platform | min distance for full spread | iOS: skeleton vanish or 1-arm `·`? | edge `wrX/wrY` behavior | `wrC` at edge |
|----------|------------------------------|------------------------------------|-------------------------|---------------|
| Android (Pixel 9a) | | n/a | | |
| iOS (iPhone 16) | | | | |

### Verdict → lever selection

- Bucket 1+4 dominate, with `elC`/`aEl` reliable where the wrist fails →
  **lever C (elbow fallback)** earns its keep + **lever A (capture FOV)** for the
  wingspan distance gap. (Expected outcome.)
- iOS whole-frame-drop confirmed → add **lever B (graceful degradation)**.
- Bucket 2 (mislocalized/crossed wrist) appears on the sextet → elbow-fallback's
  *disagreement* variant (trust elbow when `aWr`≠`aEl`) is worth the extra risk;
  note it for 6a-17.
- Bucket 3 (flicker) dominates → defer to **lever E (config tolerance)** only,
  with regenerate-and-eyeball of the parity vectors.
- If the rules pipeline clearly can't get there cheaply → document and defer to
  the trained classifier (#7, post-beta), don't over-invest.

---

## Session 1 results — Android (Pixel 9a), 2026-06-23

Free-practice (front lens), Developer mode on, agent-driven paced captures
(`adb logcat -s SemaphoreProbe`). The signer is the maintainer; arm-angle
precision is a confound (noted per pose). **The probe works on both gating
regimes.** Headline: **the measurement flipped the issue's lever prior.**

### Per-pose captures

| Pose | Framing/light | Frames/12s | Result | Read |
|------|---------------|-----------|--------|------|
| REST | good | 18 | `[0,0]` stable, all six joints `conf=1.00`, elbow≈wrist (aWr −100/aEl −105 L; −83/−73 R) | clean baseline |
| L (5,1) | good | 2 | `[5,1]=L`, conf ~1.0, aWr≈aEl both arms (142/146; −32/−31) | **clean — diagonal friction is framing-driven, not geometric** |
| D (0,4) | **low light** | 142 | `[0,4]=D` ~24%, `[0,—]=·` ~36%, **`[0,3]=C` ~16%**; conf ~1.0; R-arm aWr 64–82° on the 67.5° id3/id4 boundary; elbow ~50° (worse) | **bucket 3** — boundary flicker at full confidence |
| D (0,4) | **lights on** | 14 | **`[0,4]=D` 14/14 clean**; R-arm aWr steady 76° | lighting fixed the flicker |
| Wingspan (T) | in-frame | 158 | `[2,6]/[6,2]=R`, wrists `wrX≈0.93–0.96` at high conf, some `·` flicker | did **not** clip; ML Kit holds confidence near the edge |
| Wingspan (T) | **stepped in to clip** | **0** | no probe lines at all | **total skeleton dropout** — body exceeds frame → ML Kit returns no pose → frame dropped (no skeleton, no decode, no feedback) |
| X (7,5) | in-frame | 61 | `[5,7]=X` ~33%, **`[5,—]=·` ~41%**, +G/F/E; crossing R-arm at **conf 1.00**, aWr −156…−159° (just past id-7's −155° tol edge → dead zone), elbow −149…−153° (inside tol, marginally better) | **bucket 3, sextet-density-amplified** — committed O/Z/H/W before X; overlay tracked both arms solidly (no occlusion) |

(First X attempt mis-signed — read O/H/A; X = left `7` down-left + right `5`
up-left, both flags to the signer's left, right arm crossing the chest.)

### Findings

1. **Bucket 3 (near-boundary flicker) dominates at any usable framing** — at
   **full keypoint confidence**, the arm sits within ~20° of an octant boundary
   and hold-imprecision or sensor noise tips it across. This is the opposite of
   the issue's prior (which expected gated/clipped buckets 1+2 to dominate).
2. **Lighting is a primary driver of bucket 3.** D went 142 churning frames →
   14/14 clean purely from turning a light on (steadier angle estimate, less
   noise across the boundary).
3. **The across-body sextet (H/I/O/W/X/Z) are angular *neighbors*** (45° apart,
   ±20° tol), so small hold-imprecision on the crossing arm flickers *among the
   sextet* (X→O/Z/H/W) — not occlusion (the overlay tracked the crossing arm at
   conf 1.0). Sextet density + imprecision, not a weak keypoint.
4. **Clipping/gating (buckets 1/4) appear only at bad framing:** face-only →
   everything gated; **too-close + wide → ML Kit returns no skeleton at all**
   (hard dropout, distinct from a single low-confidence joint).
5. **Elbow fallback (lever C / #111) is NOT supported by Android evidence.** The
   conservative "wrist conf < floor → use elbow" rule **would not have fired
   once**: in-frame wrists were ~1.0 conf; out-of-frame, the *whole* skeleton
   dropped (no elbow either). ML Kit degrades wrist+elbow *together*. The elbow's
   angle was also inconsistent vs the wrist (better in X, worse in D — depends on
   arm bend). **Its value now hinges on the iOS smoke** (does Vision drop the
   wrist *alone*?).

### Updated lever ranking (Android evidence; revisit after iOS)

1. **Lever E — angle tolerance / temporal hysteresis** (was ranked *last*):
   bucket-3 flicker is the dominant friction; a boundary-hysteresis / stickier
   commit is the direct fix. NB the committer's 5-frame plurality vote already
   absorbs *some* flicker, but the X commits (O/Z/H/W/X) show it's not enough.
   Parity-coupled → regenerate + eyeball if `ANGLE_TOLERANCE_DEG` changes.
2. **Lever D — assist + "more light" / "hold steadier" cue** (#112): lighting
   alone took D 24%→100%; a brightness/steadiness nudge is cheap and high-impact.
3. **Lever A — FOV + "too close / step back" cue** (#110): the total-dropout
   regime is real; a framing nudge + the 4:3 FOV widening address it.
4. **Lever C — elbow fallback** (#111): **deprioritized on Android**; decision
   deferred to the iOS smoke.

### Caveats
- One session, one signer; arm-angle imprecision is a confound (it *is* the
  point for bucket 3, but means "% correct" numbers aren't device-quality).
- The probe shows **raw per-frame** classification; the shipped committer applies
  5-frame plurality smoothing before commit, so the user sees less flicker than
  the raw lines — though the X commit trail shows it's not fully absorbed.

## Session 2 results — iOS (iPhone 16), 2026-06-23

Method given iOS-26.5 constraints (no agent screencap/logcat/UI-drive): app run
from Xcode on-device, Developer mode on, Free practice. Maintainer signs ~8 ft
back and triggers **Siri screenshots**, AirDropped to the Mac for the agent to
`Read`. Screenshots show the camera + skeleton overlay + on-screen `L# R#`/char
readout — i.e. **structural/visual** signals (overlay present?, quantized ids).
They do **not** show confidence/angle numbers (console-only) or temporal flicker.

### Captures
| # | Pose / framing | Overlay | Readout |
|---|---|---|---|
| 1 | right wrist off the side, body+left arm in | **stays** | `L 0 R —` (right gated) |
| 2 | right arm off top-right corner | **stays** | `L 0 R —` |
| 3 | wingspan, both wrists clip the edges | **stays** | `L — R —` (both gated) |
| 4 | clean L (5,1), all in frame, lit | stays | **`L 5 R 1` = L** ✓ |

### Findings
1. **Vision does NOT whole-frame-drop on an out-of-frame wrist** — overturns the
   code-based hypothesis (`adapt` returns nil only if a joint is *absent*; Vision
   instead returns the off-frame wrist extrapolated/low-conf, so `adapt` succeeds,
   the skeleton persists, and only that arm gates per-arm). The `DROP …`
   instrumentation will therefore rarely fire except on true no-body cases.
2. **iOS is *more* graceful than Android on the clip** — Android total-dropped
   (no skeleton) on the stepped-in wingspan; iOS kept the skeleton and gated both
   arms (`L — R —`). So **#110-B (iOS graceful degradation) is less urgent** than
   feared.
3. **The elbow stays in frame while the clipped wrist gates** (every clip shot:
   elbow bend mid-frame, only forearm/wrist off). This is the
   **elbow-fallback-favorable structure — the *opposite* of Android**, where
   wrist+elbow degraded together.
4. **L reads cleanly at good framing** (`[5,1]=L`) — friction is framing-driven on
   iOS too, consistent with Android.
5. **Reconciles the field report ("iOS worse on arms-out").** iOS's narrower
   **16:9** analysis FOV clips the wrist *sooner* than Android's ~**4:3**, so at
   the same distance iOS gates the arm (`—`, letter won't read) while Android
   keeps the wrist in frame and reads it. **Lever A (iOS → 4:3 FOV, #110) directly
   targets the reported problem.**

### Cross-platform conclusion on the levers
- **Elbow fallback (#111):** placed in the shared decoder it **helps iOS**
  (recovers a gated arm when the elbow is still good — favorable structure
  confirmed visually), is a **no-op on Android** (elbow degrades with the wrist),
  and is **parity-safe by construction** → **worth doing.** The one unconfirmed
  number — is iOS elbow `conf ≥ 0.5` while wrist `conf < 0.5` in a clipped pose?
  (screenshots can't show it) — is **deferred to #111's implementation** as its
  first gating check (maintainer decision, 2026-06-23).
- **FOV (#110, lever A):** top fix for the arms-out problem; the graceful-degradation
  half (B) is downgraded per finding 2.
- **Bucket-3 flicker / lighting (levers E, D):** Android-confirmed as the dominant
  good-framing friction; iOS likely similar (screenshots can't show flicker).
  Lever E (tolerance / temporal hysteresis) is currently **un-ticketed**.

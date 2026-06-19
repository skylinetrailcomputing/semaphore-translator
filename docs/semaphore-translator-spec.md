# Semaphore Translator — Shared Specification & Requirements

**Status:** Draft v0.2
**Purpose:** Single source of truth for a native iOS (Swift) + native Android (Kotlin) app that recognizes flag-semaphore signals from the device camera and decodes them to text. This document defines the platform-agnostic contract both implementations must conform to, so the two native codebases reimplement the same *logic* while consuming the same *model artifacts and constants*.

### Resolved decisions (v0.2)

These were open in v0.1 and are now frozen:

1. **Model input → raw keypoints.** The classifier consumes the 6 keypoints (12 floats), not pre-derived angles. More ML-honest; gives the trained model a real chance to beat the rules baseline on noisy input.
2. **Pose estimation → native per platform.** Apple **Vision** (`VNDetectHumanBodyPoseRequest`) on iOS; **ML Kit Pose Detection** on Android. This requires a per-platform **adapter layer** to map each native skeleton schema into the shared 6-keypoint format (see §3.2).
3. **Alphabet scope → letters + digits.** Digits use semaphore's stateful numeric-mode convention, requiring a **mode state machine** in the decoder (see §4.5).
4. **Frozen angle convention** → defined in §4.2, signer's-perspective, y-up. Mirror handling quarantined to the per-platform adapter (§3.2).

---

## 1. Project Intent

This is primarily a **learning vehicle**. The goal is to learn the full on-device ML pipeline — data → training → export → native inference → UX — on a problem small enough to finish, while deliberately building parallel native implementations on both platforms rather than a cross-platform abstraction.

Semaphore is chosen because it is a tractable, near-closed-form pose-classification problem: two arms, each at one of a small number of discrete angles. It is solvable with pose estimation plus trigonometry alone, which lets us build a rules-based baseline and then measure what, if anything, a trained model adds.

**Non-goals (v1):**
- Sign language (ASL/BSL) recognition — explicitly out of scope; different, much harder problem.
- Text → semaphore rendering — a separate, easier pathway, deferred.
- Multi-person detection. One signer, one frame.
- Cloud inference. Everything runs on-device.

---

## 2. The Semaphore Alphabet (Ground Truth)

Flag semaphore encodes characters as the positions of two flags held by the arms. Each arm points to one of **8 positions** spaced 45° apart, like compass octants relative to the body. A character is defined by the **(left arm position, right arm position)** pair.

### 2.1 Position model

Each arm points to one of **8 canonical directions** spaced 45° apart (octants), defined from the signer's perspective. The authoritative ID↔angle assignment lives in §4.2 (the frozen angle convention); it is not duplicated here to avoid drift between two tables. Real letters occupy only a subset of the 64 possible (left, right) pairs; the valid pairs come from the authoritative chart encoded in `semaphore_alphabet.json`.

> **The per-letter (left,right) mapping must be finalized and frozen as `semaphore_alphabet.json` before either platform implements classification** (see §6 and §9-A).

### 2.2 Why this matters for the contract

The alphabet definition is the *most shared* artifact in the entire project. Both platforms classify an observed pose against this exact mapping. Any drift here causes silent cross-platform disagreement, so it lives in a single versioned JSON file, not in Swift or Kotlin source.

---

## 3. System Architecture

The pipeline is identical in shape on both platforms; only the language and platform SDKs differ.

```
Camera frame
   │
   ▼
Pose estimation  (pretrained, on-device)
   │  → body keypoints (shoulders, elbows, wrists)
   ▼
Feature extraction  (compute 2 arm-angle features from keypoints)
   │  → (left_angle, right_angle)  [+ optional confidences]
   ▼
Classifier  (rules-based baseline  OR  trained model)
   │  → predicted character + confidence
   ▼
Temporal smoothing / debounce  (hold-to-commit a character)
   │
   ▼
Decoded text output (UI)
```

**Key architectural principle:** We train only the final classifier box. The pose estimator is a pretrained, on-device feature extractor we do not train. This mirrors the broader "lean on a pretrained extractor, train a small task head" pattern.

### 3.1 Per-platform component choices

| Component | iOS (Swift) | Android (Kotlin) |
|-----------|-------------|------------------|
| Camera capture | AVFoundation / AVCaptureSession | CameraX |
| Pose estimation | **Vision** (`VNDetectHumanBodyPoseRequest`) | **ML Kit Pose Detection** |
| Trained model runtime | Core ML | LiteRT (TensorFlow Lite) |
| Accelerator | Neural Engine (via Core ML) | GPU / NNAPI delegate |

### 3.2 Per-platform adapter layer (required)

Because we chose native pose APIs over a single cross-platform engine, the two platforms emit **different skeleton schemas** — different joint enumerations, naming, ordering, and coordinate conventions. Apple Vision and ML Kit Pose do not agree out of the box.

Each platform therefore implements a thin **adapter** that converts its native skeleton into the shared 6-keypoint representation (§4.1) before any shared logic runs:

```
native skeleton  →  [adapter]  →  shared 6-keypoint struct (normalized, y-up, signer's perspective)
```

The adapter is the **only** place allowed to know about platform-specific joint schemas, coordinate origins, or the camera mirror. Everything downstream (feature extraction, classification, decoding) operates purely on the shared representation and must be byte-for-byte logically identical across platforms.

Adapter responsibilities:
- Map native joints → `left_shoulder, left_elbow, left_wrist, right_shoulder, right_elbow, right_wrist`.
- Normalize coordinates to [0,1].
- Apply the y-axis flip to y-up (§4.2).
- Apply the **mirror flip** so keypoints are expressed in the **signer's perspective** (front camera is mirrored relative to the signer; the signer's left arm appears on the image's right). This is the single most error-prone step and is deliberately confined here.
- Pass through per-keypoint visibility/confidence.

> This adapter requirement is the price of the native-per-platform decision. It is also where most of the genuine platform-specific learning lives, and it is the most likely source of cross-platform divergence — which is exactly what the parity test (§6) is designed to catch.

---

## 4. Shared Logic (Single Source of Truth)

These items define behavior and MUST be identical across platforms. They are specified here and, where data, checked into the repo as language-neutral files.

### 4.1 Keypoints required

From the pose estimator, the feature extractor consumes six 2D keypoints (normalized image coordinates, origin top-left, x and y in [0,1]):

- `left_shoulder`, `left_elbow`, `left_wrist`
- `right_shoulder`, `right_elbow`, `right_wrist`

Each keypoint has an associated visibility/confidence in [0,1].

### 4.2 Feature definition (frozen angle convention)

This convention is **frozen**. Both platforms must reproduce it exactly; the adapter (§3.2) is responsible for delivering keypoints already in this frame.

**Reference frame:**
- **Per-arm origin:** the shoulder. The arm vector is shoulder → wrist.
- **Coordinates:** image-normalized to [0,1], then **y flipped to point up** (screen y grows downward; we negate once at ingest so "up" is +y). This flip happens in the adapter.
- **Perspective:** **signer's perspective**, not camera's. The front camera mirrors the signer, so the adapter applies a horizontal mirror flip; downstream logic always sees the world as the signer experiences it (matching every printed semaphore chart).

**Arm vector and angle:**
```
v = (wrist.x - shoulder.x, wrist.y - shoulder.y)   // coords already normalized, y-up, signer-perspective
angle_deg = degrees( atan2(v.y, v.x) )             // CCW from +x axis
```
(Note: because the adapter already delivers y-up coordinates, the formula uses `+v.y` directly — there is no second negation here. The negation lives once, in the adapter.)

**Canonical positions:** +x points toward the signer's left. Angle is measured counterclockwise. Thus:

| Position ID | Angle (deg) | Direction (signer's perspective) |
|-------------|-------------|----------------------------------|
| 0 | -90 (= 270) | straight down |
| 1 | -45 (= 315) | down-and-right (low diagonal toward signer's right) |
| 2 | 0   | straight out to signer's left |
| 3 | 45  | up-and-left |
| 4 | 90  | straight up |
| 5 | 135 | up-and-right |
| 6 | 180 | straight out to signer's right |
| 7 | 225 | down-and-left |

> The exact ID↔angle assignment above is the contract the alphabet JSON is authored against. The single rule to remember: **the mirror and the y-flip happen once, in the adapter, and nowhere else.** Cross-platform divergence almost always traces to one platform's adapter getting one of those two flips wrong — which the parity test (§6) will surface immediately.

### 4.3 Quantization to position IDs

A continuous arm angle is snapped to the nearest 45° position with a tolerance band. If the angle is farther than `ANGLE_TOLERANCE_DEG` from any canonical position, the arm is "indeterminate" and no character is emitted.

### 4.4 Shared constants (frozen)

| Constant | Value (v1 default) | Meaning |
|----------|--------------------|---------|
| `ANGLE_TOLERANCE_DEG` | 20 | max deviation from a 45° position to accept |
| `MIN_KEYPOINT_CONFIDENCE` | 0.5 | below this, arm is indeterminate |
| `COMMIT_HOLD_MS` | 600 | how long a stable character must hold before being committed to output |
| `INTER_CHAR_GAP_MS` | 300 | min indeterminate gap before next character can commit |
| `SMOOTHING_WINDOW` | 5 | frames of majority-vote smoothing on predicted character |

These live in a shared `semaphore_config.json` checked into the repo; both platforms load/parse it rather than hardcoding.

### 4.5 Numeric mode state machine (digits)

Semaphore does not have distinct poses for digits. Instead it uses a **stateful mode switch**:

- A special **"numerals" sign** switches the decoder into numeric mode.
- While in numeric mode, the letter poses **A–K (excluding J)** are reinterpreted as digits **1, 2, 3, 4, 5, 6, 7, 8, 9, 0** respectively.
- A special **"letters" sign** (the "J" pose / alphabetic sign, per the standard chart) switches back to letter mode.

This makes the decoder **stateful**: the same observed pose maps to a different output character depending on current mode. The state must be modeled explicitly.

```
            numerals sign
   LETTERS ───────────────▶ NUMERIC
      ▲                        │
      └──────────────────────—┘
            letters sign

interpret(pose):
  if pose == NUMERALS_SIGN: mode = NUMERIC; emit nothing
  elif pose == LETTERS_SIGN: mode = LETTERS; emit nothing
  elif mode == NUMERIC and pose in DIGIT_MAP: emit DIGIT_MAP[pose]
  else: emit alphabet_lookup(pose)
```

Contract requirements:
- `mode` is decoder state, initialized to `LETTERS` on reset.
- The numerals-sign and letters-sign pose definitions, and the `DIGIT_MAP` (which letter pose → which digit), live in `semaphore_alphabet.json` so both platforms share them.
- The mode switch itself emits no character.
- The parity test set (§6) must include sequences that exercise both mode transitions, so cross-platform state handling is verified, not just stateless single-pose classification.

> This is the one place v1 steps beyond stateless classification. It is included deliberately: real decoders carry state, and a two-state machine is a gentle, bounded introduction to that.

---

## 5. Model & Training Contract

### 5.1 Two classifier implementations (intentional)

1. **Rules-based baseline** — pure trig: angle → position ID → alphabet lookup. No ML. Build first. Serves as both a working v1 and the eval baseline.
2. **Trained classifier** — a small model (MLP over the keypoint/angle features, or a tiny CNN over a cropped frame) that should *match or beat* the rules baseline on a held-out set, especially on noisy/real-world input.

### 5.2 Training pipeline (off-device, Python)

- Train once in Python (developer GPU is sufficient; this model is tiny — kilobytes to low-MB).
- Export the **same checkpoint** to **both** Core ML and LiteRT.
- Check both exported artifacts into the repo alongside a version tag.

### 5.3 Model I/O contract (must match on both platforms)

| Field | Spec |
|-------|------|
| Input | Fixed-length float vector of the **6 keypoints (12 values: x,y per point)**, in shared format (normalized, y-up, signer's perspective). Resolved v0.2: raw keypoints, not derived angles. |
| Input normalization | Coordinates normalized to [0,1]; document any additional mean/scale applied |
| Output | Probability vector over the alphabet's N pose classes + an "indeterminate" class. (Numeric-mode reinterpretation happens in the decoder, §4.5, not the model.) |
| Label order | Defined once in `semaphore_alphabet.json`; both exports and both platforms index identically |

> The single most common cross-platform ML bug is label-index or preprocessing mismatch between the two converted models. The mitigation is this shared contract plus a shared test vector set (§6).

### 5.4 Data

- v1: synthesized and/or self-recorded keypoint samples per character.
- The real cost here is data collection, labeling, and building an honest eval set — not GPU time. Budget effort accordingly.

---

## 6. Repo Layout & Shared Artifacts

```
/                              repo root
├─ docs/
│  └─ semaphore-translator-spec.md     (this file)
├─ shared/                              single source of truth, language-neutral
│  ├─ semaphore_alphabet.json           (left,right)→character mapping + label order
│  ├─ semaphore_config.json             frozen constants (§4.4)
│  └─ test_vectors.json                 input→expected-output pairs for parity tests
├─ model/
│  ├─ train.py                          training pipeline (Python)
│  ├─ export_coreml.py
│  ├─ export_tflite.py
│  └─ artifacts/
│     ├─ semaphore.mlmodel              checked-in export (iOS)
│     └─ semaphore.tflite               checked-in export (Android)
├─ ios/                                 Swift app
└─ android/                             Kotlin app
```

**Parity guarantee:** `shared/test_vectors.json` contains a set of input keypoint vectors with their expected decoded outputs. Both the Swift and Kotlin implementations run these vectors through their full feature-extraction + classification path in a unit test and must produce identical results. This is the mechanism that catches convention/preprocessing drift early.

---

## 7. Functional Requirements

- FR1: Capture live camera frames and run pose estimation on-device in real time (target ≥ 15 fps on mid-tier hardware).
- FR2: Convert the native skeleton to the shared 6-keypoint format via the platform adapter (§3.2), per frame.
- FR3: Classify the current pose to a character (or indeterminate) using the active classifier.
- FR4: Apply temporal smoothing and commit logic (§4.4) so a held pose produces exactly one committed character.
- FR5: Display the running decoded string with a clear/reset control.
- FR6: Allow switching between rules-based and trained classifier (developer/debug toggle) for comparison.
- FR7: Load all shared constants and the alphabet from the shared JSON files, never hardcoded.

## 8. Non-Functional Requirements

- NFR1: All inference on-device; no network dependency for core function.
- NFR2: Trained-model footprint negligible relative to the pose estimator; document measured sizes of each.
- NFR3: Deterministic parity between platforms on `test_vectors.json`.
- NFR4: Graceful degradation when no person / partial body is in frame (clear "no signer detected" state).

---

## 9. Decision Log & Remaining Items

**Resolved (v0.2):**
1. Model input → raw keypoints (12 floats). ✔
2. Pose estimation → native per platform (Vision / ML Kit) + adapter layer (§3.2). ✔
3. Alphabet scope → letters + digits, via numeric-mode state machine (§4.5). ✔
4. Angle convention → frozen in §4.2 (signer's perspective, y-up, mirror+flip in adapter). ✔

**Still to do before/while scaffolding:**
- A. Author `semaphore_alphabet.json` from an **authoritative** semaphore chart (pose→letter, the numerals/letters mode signs, and `DIGIT_MAP`). Must not be done from memory. **Next action.**
- B. Confirm the §4.2 ID↔angle table against that chart once authored (the two must agree).
- C. Decide control-signal scope beyond numerals/letters (e.g., space, error/annul sign) — defer unless trivial.
- D. Choose trained-model architecture (small MLP over the 12 floats is the default starting point).

---

## 10. Suggested Build Order

1. Freeze `semaphore_alphabet.json`, `semaphore_config.json`, and a first `test_vectors.json`.
2. Implement the rules-based classifier on **one** platform end-to-end (camera → pose → angle → letter), prove the pipeline.
3. Mirror it on the second platform; make `test_vectors.json` pass identically on both.
4. Build the Python training pipeline; export to both runtimes.
5. Wire the trained classifier behind the FR6 toggle; compare against the rules baseline on the held-out set.
6. Iterate on data quality and temporal/commit UX.

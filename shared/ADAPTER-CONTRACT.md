# Adapter contract — the shared 6-keypoint representation

**Status:** FROZEN (Issue #13, Epic 2 — the cross-platform spine).
**Machine-readable companion:** [`keypoint_contract.json`](keypoint_contract.json).
**Spec refs:** §3.2 (adapter layer), §4.1 (keypoints), §4.2 (frozen angle
convention), §4.3 (quantization), §5.3 (model I/O), §6 (parity guarantee).

This document defines the representation that **both** platform adapters must
emit. It backs `keypoint_contract.json` the same way
[`ALPHABET-VERIFICATION.md`](ALPHABET-VERIFICATION.md) backs
`semaphore_alphabet.json`: the JSON is the terse machine contract, this is the
human reasoning the adapters are written and reviewed against.

The whole point of the parallel-native approach is that exactly **one** thin
layer per platform knows about platform-specific skeleton schemas, coordinate
origins, and the camera mirror. Everything downstream — feature extraction,
classification, the numeric-mode decoder — operates on the representation below
and must be logically identical across iOS and Android. The parity harness
(#14) is what proves that; this contract is what it proves *against*.

---

## 1. The post-adapter representation (the frozen output)

The adapter converts a native skeleton into exactly six 2D keypoints:

```
left_shoulder   left_elbow   left_wrist
right_shoulder  right_elbow  right_wrist
```

Each keypoint is `[x, y, confidence]`:

| Field | Range | Meaning |
|-------|-------|---------|
| `x` | `[0,1]` | normalized horizontal; **increases toward the signer's right** (`+x` = signer's right) |
| `y` | `[0,1]` | normalized vertical; **increases up** (y-up) |
| `confidence` | `[0,1]` | per-keypoint visibility, passed through unmodified from the native pose estimator |

The six **names and their order** are frozen in `keypoint_contract.json`
(`keypoints.names`). The ordering matters because it also defines the model
input vector (§6 below).

`left_*` is the signer's **anatomical left** arm; `right_*` is the signer's
**anatomical right** arm. This is a property of the *world*, not of the image:
after the adapter's mirror, a signer facing the camera with arms relaxed has
`right_shoulder.x > left_shoulder.x`.

---

## 2. Coordinate frame

The frame is **normalized, y-up, signer's perspective**, identical to the
frozen angle convention in spec §4.2. Downstream, each arm vector is
`wrist − shoulder` and its angle is `atan2(wrist.y − shoulder.y,
wrist.x − shoulder.x)`, measured CCW from `+x`, with `+x` pointing to the
signer's right. Position ids 0..7 sit at −90, −45, 0, 45, 90, 135, 180, −135°
(see `semaphore_alphabet.json._position_model`).

Two consequences worth stating explicitly, because the test vectors assume them:

- **Arms down** (position id 0, −90°) ⇒ `wrist.y < shoulder.y` (the hand is
  below the shoulder in a y-up frame).
- **Signer's right arm straight out** (position id 2, 0°) ⇒
  `right_wrist.x > right_shoulder.x`.

---

## 3. The two transforms the adapter owns

Reaching the frame above from a native skeleton takes two flips, and **the
single rule to remember is that each happens exactly once, in the adapter, and
nowhere else.** Cross-platform divergence almost always traces to one
platform's adapter getting one of these two flips wrong.

### 3a. y-flip (to y-up)

Screen/image coordinate systems usually grow `y` downward. The adapter negates
once at ingest so the shared frame is y-up. For a coordinate already normalized
to `[0,1]`:

```
y_shared = 1 − y_native        # only when the native frame is y-down
```

This may be a **no-op on one platform and a real negation on the other** —
Apple Vision reports normalized coordinates with a **lower-left origin (already
y-up)**, while Android ML Kit reports image-pixel coordinates with a
**top-left origin (y-down)**. That asymmetry is precisely why the flip is
quarantined here and **validated against a native fixture (Epic 3)** rather than
assumed. Confirm the actual origin before freezing each adapter.

### 3b. horizontal mirror (to the signer's perspective)

The front camera mirrors the signer. The adapter applies a horizontal flip so
the output is in the **signer's** perspective (`+x` = signer's right), matching
every printed semaphore chart (which is drawn in the *observer's* perspective;
see `ALPHABET-VERIFICATION.md`). For a normalized coordinate:

```
x_shared = 1 − x_mirrored
```

The hazard is not just the `x` value — it is the **anatomical left/right
labels**. A pose estimator labels joints by the person it sees *in the image*.
On a mirrored buffer it sees a mirrored person, so its "left shoulder" may be
the signer's **right** shoulder. The adapter must land the signer's real left
arm in `left_*` and real right arm in `right_*`. Depending on whether a given
platform's capture buffer is mirrored, correct output may require flipping `x`,
**swapping the left/right labels, or both.** Do not assume; verify against a
native fixture (§7).

> Order-insensitive lookup (spec §4.3) tolerates a left/right **swap** for the
> six same-side letters (H, I, O, W, X, Z) only. It does **not** rescue a wrong
> mirror in general: the other 20 letters and both control signs are
> orientation-sensitive. The mirror must be correct on its own.

---

## 4. What the adapter must NOT do

The adapter is a coordinate translator, nothing more. It must not:

- compute arm angles or quantize to position ids (feature extraction /
  classification, §4.2–4.3);
- look up characters or run the numeric-mode state machine (§4.5);
- apply temporal smoothing or commit timing (§4.4, Epic 4);
- apply the mirror or the y-flip more than once, or anywhere downstream.

The confidence **floor** (`MIN_KEYPOINT_CONFIDENCE`) is *not* applied here
either: the adapter passes raw confidence through, and feature extraction marks
an arm indeterminate when its shoulder or wrist falls below the floor (§4.3),
identically on both platforms.

---

## 5. Per-platform mapping guidance

Joint-name mapping is high-confidence and listed below. Coordinate origin,
normalization, mirror direction, and left/right labeling under the front camera
are **platform- and configuration-dependent**; they are guidance to verify, not
frozen facts. Each adapter pins them against a known native fixture in Epic 3.

| Shared keypoint | Apple Vision (`VNHumanBodyPoseObservation.JointName`) | Android ML Kit (`PoseLandmark`) |
|-----------------|-------------------------------------------------------|---------------------------------|
| `left_shoulder`  | `.leftShoulder`  | `LEFT_SHOULDER`  |
| `left_elbow`     | `.leftElbow`     | `LEFT_ELBOW`     |
| `left_wrist`     | `.leftWrist`     | `LEFT_WRIST`     |
| `right_shoulder` | `.rightShoulder` | `RIGHT_SHOULDER` |
| `right_elbow`    | `.rightElbow`    | `RIGHT_ELBOW`    |
| `right_wrist`    | `.rightWrist`    | `RIGHT_WRIST`    |

Per-platform notes to validate (not assert):

- **iOS / Vision:** normalized coords, lower-left origin (likely y-up already →
  3a may be a no-op). Confidence via each joint's `confidence`. Mirror handling
  depends on the `AVCaptureConnection.isVideoMirrored` setting on the front
  camera. Watch the Swift 6 actor-isolation gotcha on the capture path.
- **Android / ML Kit:** `PoseLandmark.getPosition()` returns **image-pixel**
  coordinates → normalize by frame width/height to `[0,1]`, then apply 3a
  (top-left origin → y-down → real negation). Confidence via
  `getInFrameLikelihood()`. Mirror handling depends on the CameraX front-camera
  configuration.

Because the native label↔anatomy correspondence can invert under mirroring (§3b),
the joint-name table above is necessary but **not sufficient**: it tells you
which native joint *claims* to be which arm, not which arm it *is* once the
buffer is mirrored. The native fixture in §7 is what settles it.

---

## 6. Model input vector (forward ref, spec §5.3)

The trained classifier consumes the six keypoints flattened to a fixed
**12-float** vector. The flatten order is frozen in
`keypoint_contract.json.model_input_order.floats`:

```
[ left_shoulder.x,  left_shoulder.y,
  left_elbow.x,     left_elbow.y,
  left_wrist.x,     left_wrist.y,
  right_shoulder.x, right_shoulder.y,
  right_elbow.x,    right_elbow.y,
  right_wrist.x,    right_wrist.y ]
```

Confidence is **excluded** (12 = 6 keypoints × {x, y}), matching spec §5.3.
This is the input-side analogue of `semaphore_alphabet.json.label_order` (the
output class order): both the Core ML and LiteRT exports and both platform
adapters must index in this exact order. It is pinned now, with the
representation it derives from, so the model epic inherits a frozen contract
rather than inventing one — but it is only *exercised* once the trained
classifier lands.

---

## 7. Verification

This contract is verified, not asserted, by two mechanisms:

1. **Native fixtures (Epic 3):** a small set of known poses captured on each
   platform, asserting that the adapter's output satisfies §2's invariants
   (e.g. signer's right arm out ⇒ `right_wrist.x > right_shoulder.x`;
   arms down ⇒ `wrist.y < shoulder.y`). This is where the y-flip and mirror are
   pinned per platform.
2. **Parity harness (#14):** `test_vectors.json` is defined as the adapter's
   **output**, so it does not exercise the flips — it proves that everything
   *downstream* of the adapter (feature extraction → classification → decode)
   is byte-for-byte identical across Swift and Kotlin. The two mechanisms are
   complementary: native fixtures validate the adapter; the parity harness
   validates everything the adapter feeds.

---

## 8. Cross-references

- [`keypoint_contract.json`](keypoint_contract.json) — machine-readable form of
  this contract.
- [`semaphore_alphabet.json`](semaphore_alphabet.json) /
  [`ALPHABET-VERIFICATION.md`](ALPHABET-VERIFICATION.md) — the position model
  and `(left,right)` → character mapping this frame feeds.
- [`semaphore_config.json`](semaphore_config.json) — `MIN_KEYPOINT_CONFIDENCE`
  and the other frozen constants.
- [`test_vectors.json`](test_vectors.json) — post-adapter parity fixtures.
- `docs/semaphore-translator-spec.md` §3.2 / §4.1 / §4.2 / §5.3 / §6.

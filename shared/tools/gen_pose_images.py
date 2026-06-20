# /// script
# requires-python = ">=3.11"
# dependencies = ["pillow>=10,<12"]
# ///
"""Render the canonical native-fixture pose images (Issue #20, Epic 3).

Single source of geometry is ``shared/native_fixtures/invariants.json``: this
script READS each pose's ``reference_post_adapter`` keypoints (the post-adapter,
signer's-perspective frame) and draws the observer's-view figure by applying the
inverse of the two adapter transforms, so the rendered image can never disagree
with the frozen contract.

    image_x = 1 - signer_x   # undo the horizontal mirror (signer's right -> observer's left)
    image_y = 1 - signer_y   # undo the y-up flip (back to image y-down)

The figure is a filled, lightly shaded human form rather than a stick skeleton:
Vision / ML Kit are trained on real humans, so a recognizable body has a far
better chance of being detected when [3.3]/[3.4] run the real estimators on
these images (Layer-2 calibration). Detectability is still only *verified* there;
if an estimator can't read the render, swap in a real photo — the invariants are
relational and image-agnostic, so the contract is unaffected.

Run:  uv run shared/tools/gen_pose_images.py
"""

from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw

# Resolve shared/ relative to this file (shared/tools/ -> shared/).
SHARED = Path(__file__).resolve().parent.parent
FIXTURES = SHARED / "native_fixtures"
INVARIANTS = FIXTURES / "invariants.json"

W, H = 768, 1024  # output portrait size
SS = 3  # supersample factor; render large, downscale with LANCZOS for anti-aliasing

SKIN = (236, 200, 168)
SHIRT = (66, 110, 170)
SHIRT_SHADE = (52, 92, 146)
PANTS = (54, 64, 86)
BG_TOP = (242, 243, 245)
BG_BOTTOM = (222, 226, 232)
OUTLINE = (40, 44, 52)

# Body parts NOT in the 6-keypoint contract, fixed in normalized image space
# (x right, y down). Only the arms vary per pose; everything else is shared.
HEAD_C = (0.50, 0.275)
HEAD_R = 0.072
NECK_TOP = (0.50, 0.345)
SHOULDER_CENTER_Y = 0.40
HIP_L = (0.547, 0.625)  # signer's left hip -> observer's right
HIP_R = (0.453, 0.625)
KNEE_L, KNEE_R = (0.515, 0.785), (0.485, 0.785)
ANKLE_L, ANKLE_R = (0.515, 0.945), (0.485, 0.945)


def signer_to_img(pt: list[float]) -> tuple[float, float]:
    """Post-adapter (signer's frame, y-up) -> normalized observer image (y-down)."""
    x, y, _conf = pt
    return (1.0 - x, 1.0 - y)


def px(p: tuple[float, float]) -> tuple[float, float]:
    return (p[0] * W * SS, p[1] * H * SS)


def thick_polyline(draw: ImageDraw.ImageDraw, pts, width_n: float, fill) -> None:
    """A rounded thick polyline: curved joints + circular caps at every vertex."""
    w = max(1, int(width_n * W * SS))
    ipts = [px(p) for p in pts]
    draw.line(ipts, fill=fill, width=w, joint="curve")
    r = w / 2
    for x, y in ipts:
        draw.ellipse((x - r, y - r, x + r, y + r), fill=fill)


def draw_figure(arms: dict[str, tuple[float, float]]) -> Image.Image:
    img = Image.new("RGB", (W * SS, H * SS))
    # Vertical background gradient.
    grad = Image.new("RGB", (1, H * SS))
    for y in range(H * SS):
        t = y / (H * SS - 1)
        grad.putpixel(
            (0, y),
            tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3)),
        )
    img.paste(grad.resize((W * SS, H * SS)), (0, 0))
    draw = ImageDraw.Draw(img)

    ls, le, lw = arms["left_shoulder"], arms["left_elbow"], arms["left_wrist"]
    rs, re, rw = arms["right_shoulder"], arms["right_elbow"], arms["right_wrist"]
    shoulder_c = ((ls[0] + rs[0]) / 2, SHOULDER_CENTER_Y)

    # Legs (drawn first, behind torso).
    thick_polyline(draw, [HIP_R, KNEE_R, ANKLE_R], 0.058, PANTS)
    thick_polyline(draw, [HIP_L, KNEE_L, ANKLE_L], 0.058, PANTS)

    # Torso: shoulders -> hips, with a subtle shaded sliver down one side.
    draw.polygon([px(ls), px(rs), px(HIP_R), px(HIP_L)], fill=SHIRT)
    hip_mid = ((HIP_R[0] + HIP_L[0]) / 2, HIP_R[1])
    draw.polygon([px(rs), px(HIP_R), px(hip_mid)], fill=SHIRT_SHADE)

    # Neck.
    thick_polyline(draw, [shoulder_c, NECK_TOP], 0.05, SKIN)

    # Arms over the torso so the shoulders read cleanly.
    thick_polyline(draw, [ls, le, lw], 0.044, SKIN)
    thick_polyline(draw, [rs, re, rw], 0.044, SKIN)
    # Slightly larger hands.
    for hand in (lw, rw):
        hx, hy = px(hand)
        r = 0.030 * W * SS
        draw.ellipse((hx - r, hy - r, hx + r, hy + r), fill=SKIN)

    # Head + minimal face.
    hx, hy = px(HEAD_C)
    hr = HEAD_R * W * SS
    draw.ellipse((hx - hr, hy - hr, hx + hr, hy + hr), fill=SKIN)
    eye_r = 0.009 * W * SS
    for ex in (hx - hr * 0.38, hx + hr * 0.38):
        ey = hy - hr * 0.1
        draw.ellipse((ex - eye_r, ey - eye_r, ex + eye_r, ey + eye_r), fill=OUTLINE)

    return img.resize((W, H), Image.LANCZOS)


def main() -> None:
    contract = json.loads(INVARIANTS.read_text())
    for pose in contract["poses"]:
        ref = pose["reference_post_adapter"]
        arms = {name: signer_to_img(ref[name]) for name in ref}
        out = FIXTURES / pose["image"]
        draw_figure(arms).save(out)
        print(f"wrote {out.relative_to(SHARED.parent)}  ({pose['name']})")


if __name__ == "__main__":
    main()

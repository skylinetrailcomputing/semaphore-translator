"""Render the canonical native-fixture pose images (Issue #20/#21, Epic 3).

These images exist for **Layer-2 calibration**: the per-platform adapters run the
REAL native pose estimator (Apple Vision / ML Kit) on them and assert the frozen
`native_fixtures/invariants.json`. That only works if the estimator can actually
detect a human in the image -- and pose estimators are trained on real people, so
flat/synthetic figures are NOT detected (verified 2026-06-20: Apple Vision returns
zero faces/bodies/humans on a hand-drawn figure; see NATIVE-FIXTURES.md §6).

So each image is a **3D-rendered human** generated with MPFB
(https://static.makehumancommunity.org/mpfb.html), the MakeHuman-successor Blender
add-on. The human and its clothing are MakeHuman's **CC0** assets (license-clean
for an OSS repo) and are detected by Vision with all six keypoints above the 0.5
confidence floor, because the base mesh has real facial/limb geometry. Body-pose
detection keys on 3D *form*, not photo texture -- so even the untextured grey base
mesh is detected; the clothing (Issue #27) is a cosmetic upgrade for a nicer
public-repo asset and does not change the contract (verified: still all six
keypoints > 0.5 with correct L/R labels after dressing).

The figure is posed via the rig (not pixel-matched to `reference_post_adapter`):
the invariants are **relational** (NATIVE-FIXTURES.md §4), so any image that
*depicts* the pose in the observer's view satisfies the same contract. The figure
faces the camera; its anatomical-right arm therefore appears on the observer's
left, which a correct adapter mirrors to the signer's perspective.

Prerequisites (one-time):
    brew install --cask blender                 # Blender >= 4.2 (extension system)
    # 1. install the MPFB extension into Blender (id "mpfb"), e.g. from
    #    https://extensions.blender.org/add-ons/mpfb/ , then enable it once.
    # 2. install the two CC0 clothing packs MPFB looks for in its clothes dir
    #    (see CLOTHING below). Easiest: in Blender, MPFB tab -> Assets ->
    #    "Asset packs", or download + unzip into the MPFB user-data clothes dir:
    #      https://static.makehumancommunity.org/assets/assetpacks/shirts01.html
    #      https://static.makehumancommunity.org/assets/assetpacks/pants01.html
    #    (shirts01_cc0.zip + pants01_cc0.zip; both CC0). The unzipped layout is
    #    <mpfb-user-data>/clothes/<asset>/<asset>.mhclo, which is what CLOTHING
    #    resolves to via LocationService. If the assets are missing the script
    #    raises a FileNotFoundError naming the expected path.

Run:
    blender --background --python shared/tools/gen_native_fixtures.py
"""

import math
from pathlib import Path

import bpy
from mathutils import Vector, Matrix

SHARED = Path(__file__).resolve().parent.parent
FIXTURES = SHARED / "native_fixtures"
MPFB_MODULE = "bl_ext.user_default.mpfb"

POSES = ("arms_down", "right_arm_out")
DOWN = (0, 0, -1)

# CC0 clothing fitted onto the base mesh (Issue #27): a plain crew t-shirt + full
# cargo trousers -> a clothed, SFW public-repo asset. Paths are relative to the
# MPFB user-data dir (resolved via LocationService); install the shirts01 +
# pants01 CC0 packs as described in the module docstring.
CLOTHING = (
    "clothes/elvs_crude_t-shirt_male/elvs_crude_t-shirt_male.mhclo",
    "clothes/cortu_cargo_pants/cortu_cargo_pants.mhclo",
)


def _human_service():
    bpy.ops.preferences.addon_enable(module=MPFB_MODULE)
    return __import__(
        MPFB_MODULE + ".services.humanservice", fromlist=["HumanService"]
    ).HumanService


def _dress(human_service, basemesh):
    """Fit the CC0 CLOTHING assets onto `basemesh`, rigged to the same armature
    so they deform with the body when the arms are posed. MAKESKIN materials keep
    the asset textures; the clothes follow the rig via interpolated weights."""
    location_service = __import__(
        MPFB_MODULE + ".services.locationservice", fromlist=["LocationService"]
    ).LocationService
    for rel_path in CLOTHING:
        mhclo = location_service.get_user_data(rel_path)
        if not Path(mhclo).is_file():
            raise FileNotFoundError(
                f"clothing asset not found: {mhclo}\n"
                "Install the CC0 shirts01 + pants01 packs (see this file's docstring)."
            )
        human_service.add_mhclo_asset(
            mhclo,
            basemesh,
            asset_type="Clothes",
            material_type="MAKESKIN",
            set_up_rigging=True,
            interpolate_weights=True,
            import_subrig=False,
            import_weights=False,
        )


def _aim(arm, shoulder_bone, end_bone, target_dir):
    """Rotate `shoulder_bone` (in world space, about its head) so the straight
    line head(shoulder)->tail(end) points along `target_dir`. Children follow."""
    pbf = arm.pose.bones[shoulder_bone]
    mw = arm.matrix_world
    head = mw @ pbf.head
    end = mw @ arm.pose.bones[end_bone].tail
    cur = end - head
    if cur.length < 1e-6:
        return
    rot = (
        cur.normalized()
        .rotation_difference(Vector(target_dir).normalized())
        .to_matrix()
        .to_4x4()
    )
    pivot = Matrix.Translation(head) @ rot @ Matrix.Translation(-head)
    pbf.matrix = mw.inverted() @ (pivot @ (mw @ pbf.matrix))
    bpy.context.view_layer.update()


def build_and_render(pose_name, out_path):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    human_service = _human_service()

    basemesh = human_service.create_human()
    human_service.add_builtin_rig(basemesh, "default")
    arm = next(o for o in bpy.data.objects if o.type == "ARMATURE")
    bpy.context.view_layer.update()

    _dress(human_service, basemesh)
    bpy.context.view_layer.update()

    # Figure's anatomical right (horizontal) derived from the shoulder line, so
    # it is correct regardless of which way the generated human faces.
    r_sh = arm.matrix_world @ arm.pose.bones["upperarm01.R"].head
    l_sh = arm.matrix_world @ arm.pose.bones["upperarm01.L"].head
    fig_right = r_sh - l_sh
    fig_right.z = 0
    fig_right = fig_right.normalized()

    if pose_name == "arms_down":
        _aim(arm, "upperarm01.R", "wrist.R", DOWN)
        _aim(arm, "upperarm01.L", "wrist.L", DOWN)
    elif pose_name == "right_arm_out":
        _aim(arm, "upperarm01.R", "wrist.R", tuple(fig_right))
        _aim(arm, "upperarm01.L", "wrist.L", DOWN)
    else:
        raise ValueError(f"unknown pose {pose_name}")

    # Frame off the posed armature bones (reliable; the mesh bbox is noisy).
    pts = []
    for pose_bone in arm.pose.bones:
        pts += [arm.matrix_world @ pose_bone.head, arm.matrix_world @ pose_bone.tail]
    mins = Vector((min(p[i] for p in pts) for i in range(3)))
    maxs = Vector((max(p[i] for p in pts) for i in range(3)))
    maxs.z += (maxs.z - mins.z) * 0.06  # head reaches above the top neck joint
    center = (mins + maxs) / 2
    dims = maxs - mins

    cam_data = bpy.data.cameras.new("Cam")
    cam_data.type = "ORTHO"
    cam_data.ortho_scale = max(dims.z, dims.x) * 1.12
    cam_data.sensor_fit = "VERTICAL"
    cam = bpy.data.objects.new("Cam", cam_data)
    cam.location = (center.x, center.y - max(dims.y, 2.0) - 3.0, center.z)
    cam.rotation_euler = (math.radians(90), 0, 0)
    bpy.context.scene.collection.objects.link(cam)
    bpy.context.scene.camera = cam

    sun_data = bpy.data.lights.new("Sun", "SUN")
    sun_data.energy = 3.5
    sun = bpy.data.objects.new("Sun", sun_data)
    sun.rotation_euler = (math.radians(55), 0, math.radians(-25))
    bpy.context.scene.collection.objects.link(sun)
    fill_data = bpy.data.lights.new("Fill", "AREA")
    fill_data.energy = 400
    fill_data.size = 6
    fill = bpy.data.objects.new("Fill", fill_data)
    fill.location = (center.x + 3, center.y - 4, center.z + 0.5)
    bpy.context.scene.collection.objects.link(fill)

    world = bpy.data.worlds.new("W")
    bpy.context.scene.world = world
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (0.6, 0.62, 0.66, 1)
    world.node_tree.nodes["Background"].inputs[1].default_value = 0.85

    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = 768
    scene.render.resolution_y = 1024
    scene.render.image_settings.file_format = "PNG"
    scene.render.filepath = str(out_path)
    bpy.ops.render.render(write_still=True)
    print(f"wrote {out_path.relative_to(SHARED.parent)}  ({pose_name})")


def main():
    for pose_name in POSES:
        build_and_render(pose_name, FIXTURES / f"pose_{pose_name}.png")


if __name__ == "__main__":
    main()

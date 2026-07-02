from pathlib import Path

import numpy as np
import open3d as o3d
import trimesh

from rsmapper.config import Config
from rsmapper.dataset import load_dataset
from rsmapper.fragments import make_fragments
from rsmapper.integrate import export_mesh, integrate_scene
from rsmapper.registration import register_fragments


def test_integrate_and_export_redwood(redwood_dataset: Path, tmp_path: Path):
    ds = load_dataset(redwood_dataset)
    cfg = Config(frames_per_fragment=5, keyframe_gap=2,
                 decimate_target_triangles=20_000)
    frags_dir = tmp_path / "fragments"
    plys = make_fragments(ds, cfg, frags_dir)
    scene_pg = register_fragments(plys, cfg)

    mesh = integrate_scene(ds, scene_pg, frags_dir, cfg)
    assert len(mesh.vertices) > 1000
    assert mesh.has_vertex_colors()

    out = export_mesh(mesh, tmp_path / "export", cfg)
    for key in ("ply", "obj", "glb"):
        assert out[key].is_file(), f"faltou {key}"

    exported = o3d.io.read_triangle_mesh(str(out["ply"]))
    assert len(exported.triangles) <= cfg.decimate_target_triangles

    tm = trimesh.load(str(out["glb"]))
    geom = list(tm.geometry.values())[0] if isinstance(tm, trimesh.Scene) else tm
    assert geom.visual.vertex_colors.shape[0] == len(geom.vertices)

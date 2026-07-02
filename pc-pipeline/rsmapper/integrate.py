"""Fase 3: integração TSDF da cena completa e exportação da malha."""
from pathlib import Path

import numpy as np
import open3d as o3d
import trimesh

from rsmapper.config import Config
from rsmapper.dataset import Dataset, read_rgbd
from rsmapper.fragments import fragment_ranges


def integrate_scene(ds: Dataset, scene_pg, fragments_dir: Path,
                    cfg: Config) -> o3d.geometry.TriangleMesh:
    volume = o3d.pipelines.integration.ScalableTSDFVolume(
        voxel_length=cfg.voxel_size,
        sdf_trunc=cfg.voxel_size * 4,
        color_type=o3d.pipelines.integration.TSDFVolumeColorType.RGB8)

    ranges = fragment_ranges(ds.n_frames, cfg.frames_per_fragment)
    for frag_id, (start, end) in enumerate(ranges):
        frag_pg = o3d.io.read_pose_graph(
            str(Path(fragments_dir) / f"fragment_{frag_id:03d}.json"))
        frag_pose = scene_pg.nodes[frag_id].pose
        for k in range(end - start):
            pose = frag_pose @ frag_pg.nodes[k].pose
            rgbd = read_rgbd(ds, start + k, cfg, for_odometry=False)
            volume.integrate(rgbd, ds.intrinsic, np.linalg.inv(pose))

    mesh = volume.extract_triangle_mesh()
    mesh.compute_vertex_normals()
    return mesh


def export_mesh(mesh: o3d.geometry.TriangleMesh, out_dir: Path,
                cfg: Config) -> dict[str, Path]:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    if len(mesh.triangles) > cfg.decimate_target_triangles:
        mesh = mesh.simplify_quadric_decimation(cfg.decimate_target_triangles)
        mesh.compute_vertex_normals()

    paths = {"ply": out_dir / "scene.ply", "obj": out_dir / "scene.obj",
             "glb": out_dir / "scene.glb"}
    o3d.io.write_triangle_mesh(str(paths["ply"]), mesh)
    o3d.io.write_triangle_mesh(str(paths["obj"]), mesh)

    # .glb via trimesh: exportador do Open3D não grava cores de vértice em glTF
    colors = (np.asarray(mesh.vertex_colors) * 255).astype(np.uint8)
    tm = trimesh.Trimesh(vertices=np.asarray(mesh.vertices),
                         faces=np.asarray(mesh.triangles),
                         vertex_colors=colors)
    tm.export(str(paths["glb"]))
    return paths

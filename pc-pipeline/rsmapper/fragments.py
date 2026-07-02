"""Fase 1: fragmentos — odometria RGB-D + pose graph local + TSDF por bloco."""
from pathlib import Path

import numpy as np
import open3d as o3d

from rsmapper.config import Config
from rsmapper.dataset import Dataset, read_rgbd


def fragment_ranges(n_frames: int, per_fragment: int) -> list[tuple[int, int]]:
    """Intervalos [start, end) contíguos; o último pode ser menor."""
    return [(s, min(s + per_fragment, n_frames))
            for s in range(0, n_frames, per_fragment)]


def _rgbd_odometry(ds: Dataset, s: int, t: int, cfg: Config,
                   init: np.ndarray) -> tuple[bool, np.ndarray, np.ndarray]:
    source = read_rgbd(ds, s, cfg, for_odometry=True)
    target = read_rgbd(ds, t, cfg, for_odometry=True)
    option = o3d.pipelines.odometry.OdometryOption(depth_max=cfg.depth_max)
    return o3d.pipelines.odometry.compute_rgbd_odometry(
        source, target, ds.intrinsic, init,
        o3d.pipelines.odometry.RGBDOdometryJacobianFromHybridTerm(), option)


def _build_posegraph(ds: Dataset, start: int, end: int, cfg: Config
                     ) -> o3d.pipelines.registration.PoseGraph:
    pg = o3d.pipelines.registration.PoseGraph()
    odom = np.identity(4)
    pg.nodes.append(o3d.pipelines.registration.PoseGraphNode(odom))
    for s in range(start, end - 1):
        # aresta de odometria (frame consecutivo)
        ok, trans, info = _rgbd_odometry(ds, s, s + 1, cfg, np.identity(4))
        if ok:
            odom = odom @ np.linalg.inv(trans)
        pg.nodes.append(o3d.pipelines.registration.PoseGraphNode(odom.copy()))
        pg.edges.append(o3d.pipelines.registration.PoseGraphEdge(
            s - start, s - start + 1, trans, info, uncertain=False))
        # arestas de loop closure entre keyframes do fragmento
        if (s - start) % cfg.keyframe_gap == 0:
            for t in range(s + 2, min(s + cfg.keyframe_gap + 1, end)):
                ok, trans, info = _rgbd_odometry(ds, s, t, cfg, np.identity(4))
                if ok:
                    pg.edges.append(o3d.pipelines.registration.PoseGraphEdge(
                        s - start, t - start, trans, info, uncertain=True))
    _optimize(pg, cfg.voxel_size * 1.4)
    return pg


def _optimize(pg: o3d.pipelines.registration.PoseGraph, max_corr: float) -> None:
    option = o3d.pipelines.registration.GlobalOptimizationOption(
        max_correspondence_distance=max_corr,
        edge_prune_threshold=0.25,
        reference_node=0)
    o3d.pipelines.registration.global_optimization(
        pg,
        o3d.pipelines.registration.GlobalOptimizationLevenbergMarquardt(),
        o3d.pipelines.registration.GlobalOptimizationConvergenceCriteria(),
        option)


def make_fragment(ds: Dataset, frag_id: int, start: int, end: int,
                  cfg: Config, out_dir: Path) -> Path:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    pg = _build_posegraph(ds, start, end, cfg)
    o3d.io.write_pose_graph(str(out_dir / f"fragment_{frag_id:03d}.json"), pg)

    volume = o3d.pipelines.integration.ScalableTSDFVolume(
        voxel_length=cfg.voxel_size,
        sdf_trunc=cfg.voxel_size * 4,
        color_type=o3d.pipelines.integration.TSDFVolumeColorType.RGB8)
    for k, node in enumerate(pg.nodes):
        rgbd = read_rgbd(ds, start + k, cfg, for_odometry=False)
        volume.integrate(rgbd, ds.intrinsic, np.linalg.inv(node.pose))
    pcd = volume.extract_point_cloud()
    ply = out_dir / f"fragment_{frag_id:03d}.ply"
    o3d.io.write_point_cloud(str(ply), pcd)
    return ply


def make_fragments(ds: Dataset, cfg: Config, out_dir: Path) -> list[Path]:
    ranges = fragment_ranges(ds.n_frames, cfg.frames_per_fragment)
    plys = []
    for frag_id, (start, end) in enumerate(ranges):
        print(f"  fragmento {frag_id + 1}/{len(ranges)} (frames {start}–{end - 1})")
        plys.append(make_fragment(ds, frag_id, start, end, cfg, out_dir))
    return plys

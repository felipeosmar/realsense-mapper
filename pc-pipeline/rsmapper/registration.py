"""Fase 2: registro global entre fragmentos com loop closure."""
from pathlib import Path

import numpy as np
import open3d as o3d

from rsmapper.config import Config

reg = o3d.pipelines.registration


def preprocess(pcd: o3d.geometry.PointCloud, voxel: float):
    down = pcd.voxel_down_sample(voxel * 3)
    down.estimate_normals(
        o3d.geometry.KDTreeSearchParamHybrid(radius=voxel * 6, max_nn=30))
    fpfh = reg.compute_fpfh_feature(
        down, o3d.geometry.KDTreeSearchParamHybrid(radius=voxel * 15, max_nn=100))
    return down, fpfh


def pairwise_icp(src, tgt, voxel: float, init: np.ndarray):
    """ICP ponto-a-plano em duas escalas; retorna (transform, info_matrix)."""
    trans = init
    for scale in (voxel * 6, voxel * 1.5):
        s = src.voxel_down_sample(scale / 2)
        t = tgt.voxel_down_sample(scale / 2)
        s.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=scale * 2, max_nn=30))
        t.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=scale * 2, max_nn=30))
        result = reg.registration_icp(
            s, t, scale, trans, reg.TransformationEstimationPointToPlane())
        trans = result.transformation
    info = reg.get_information_matrix_from_point_clouds(src, tgt, voxel * 1.5, trans)
    return trans, info


def global_ransac(src_down, src_fpfh, tgt_down, tgt_fpfh, voxel: float):
    """Registro global FPFH+RANSAC; None se a sobreposição for insuficiente."""
    dist = voxel * 4.5
    result = reg.registration_ransac_based_on_feature_matching(
        src_down, tgt_down, src_fpfh, tgt_fpfh, mutual_filter=True,
        max_correspondence_distance=dist,
        estimation_method=reg.TransformationEstimationPointToPoint(False),
        ransac_n=3,
        checkers=[reg.CorrespondenceCheckerBasedOnEdgeLength(0.9),
                  reg.CorrespondenceCheckerBasedOnDistance(dist)],
        criteria=reg.RANSACConvergenceCriteria(1_000_000, 0.999))
    if result.fitness < 0.3:
        return None
    return result.transformation


def register_fragments(fragment_plys: list[Path], cfg: Config) -> reg.PoseGraph:
    pcds = [o3d.io.read_point_cloud(str(p)) for p in fragment_plys]
    pg = reg.PoseGraph()
    pg.nodes.append(reg.PoseGraphNode(np.identity(4)))
    if len(pcds) == 1:
        return pg

    downs, fpfhs = [], []
    for p in pcds:
        d, f = preprocess(p, cfg.voxel_size)
        downs.append(d)
        fpfhs.append(f)

    odom = np.identity(4)
    for s in range(len(pcds)):
        for t in range(s + 1, len(pcds)):
            adjacent = t == s + 1
            if adjacent:
                trans, info = pairwise_icp(pcds[s], pcds[t], cfg.voxel_size, np.identity(4))
                odom = np.linalg.inv(trans) @ odom
                pg.nodes.append(reg.PoseGraphNode(np.linalg.inv(odom)))
                pg.edges.append(reg.PoseGraphEdge(s, t, trans, info, uncertain=False))
            else:
                guess = global_ransac(downs[s], fpfhs[s], downs[t], fpfhs[t], cfg.voxel_size)
                if guess is None:
                    continue
                trans, info = pairwise_icp(pcds[s], pcds[t], cfg.voxel_size, guess)
                pg.edges.append(reg.PoseGraphEdge(s, t, trans, info, uncertain=True))

    option = reg.GlobalOptimizationOption(
        max_correspondence_distance=cfg.voxel_size * 1.5,
        edge_prune_threshold=0.25,
        reference_node=0)
    reg.global_optimization(pg, reg.GlobalOptimizationLevenbergMarquardt(),
                            reg.GlobalOptimizationConvergenceCriteria(), option)
    return pg

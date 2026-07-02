from pathlib import Path

import numpy as np
import open3d as o3d
import pytest

from rsmapper.config import Config
from rsmapper.registration import pairwise_icp, preprocess, register_fragments

VOXEL = 0.01


@pytest.fixture(scope="module")
def bunny() -> o3d.geometry.PointCloud:
    mesh = o3d.io.read_triangle_mesh(o3d.data.BunnyMesh().path)
    return mesh.sample_points_uniformly(8000)


def _small_transform() -> np.ndarray:
    t = np.identity(4)
    t[:3, :3] = o3d.geometry.get_rotation_matrix_from_xyz((0.05, 0.08, 0.03))
    t[:3, 3] = (0.01, -0.005, 0.008)
    return t


def test_pairwise_icp_recovers_transform(bunny):
    src = o3d.geometry.PointCloud(bunny)
    tgt = o3d.geometry.PointCloud(bunny)
    truth = _small_transform()
    tgt.transform(truth)
    trans, info = pairwise_icp(src, tgt, VOXEL, np.identity(4))
    err = np.linalg.norm(trans[:3, 3] - truth[:3, 3])
    assert err < 0.005  # translação recuperada com erro < 5 mm
    assert info.shape == (6, 6)


def test_register_two_fragments(bunny, tmp_path: Path):
    truth = _small_transform()
    a = o3d.geometry.PointCloud(bunny)
    b = o3d.geometry.PointCloud(bunny)
    b.transform(np.linalg.inv(truth))
    o3d.io.write_point_cloud(str(tmp_path / "fragment_000.ply"), a)
    o3d.io.write_point_cloud(str(tmp_path / "fragment_001.ply"), b)

    pg = register_fragments(
        [tmp_path / "fragment_000.ply", tmp_path / "fragment_001.ply"], Config())
    assert len(pg.nodes) == 2
    assert len(pg.edges) >= 1
    # pose do fragmento 1 deve aproximar a transformação verdadeira
    err = np.linalg.norm(pg.nodes[1].pose[:3, 3] - truth[:3, 3])
    assert err < 0.01


def test_register_single_fragment(bunny, tmp_path: Path):
    o3d.io.write_point_cloud(str(tmp_path / "fragment_000.ply"), bunny)
    pg = register_fragments([tmp_path / "fragment_000.ply"], Config())
    assert len(pg.nodes) == 1
    np.testing.assert_allclose(pg.nodes[0].pose, np.identity(4))

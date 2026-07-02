"""Tests para dataset layout: frames, intrínsecos, RGBD."""
from pathlib import Path

import numpy as np
import open3d as o3d
import pytest

from rsmapper.config import Config
from rsmapper.dataset import (
    Dataset,
    frame_name,
    load_dataset,
    read_rgbd,
    save_frame,
    write_intrinsics,
)


def test_frame_name():
    """frame_name formata índice como 6 dígitos zero-padded."""
    assert frame_name(0) == "000000"
    assert frame_name(42) == "000042"
    assert frame_name(999999) == "999999"


def test_save_frame_redwood(tmp_path: Path):
    """save_frame grava color e depth nos diretórios corretos."""
    import open3d as o3d

    sample = o3d.data.SampleRedwoodRGBDImages()
    color = o3d.io.read_image(sample.color_paths[0])
    depth = o3d.io.read_image(sample.depth_paths[0])

    save_frame(tmp_path, 0, color, depth)
    save_frame(tmp_path, 1, color, depth)

    assert (tmp_path / "color" / "000000.jpg").exists()
    assert (tmp_path / "color" / "000001.jpg").exists()
    assert (tmp_path / "depth" / "000000.png").exists()
    assert (tmp_path / "depth" / "000001.png").exists()

    # Verifica que depth é uint16
    depth_back = np.asarray(o3d.io.read_image(str(tmp_path / "depth" / "000000.png")))
    assert depth_back.dtype == np.uint16


def test_write_intrinsics(tmp_path: Path):
    """write_intrinsics salva intrinsics.json no formato correto."""
    write_intrinsics(tmp_path, 64, 48, 50.0, 50.0, 32.0, 24.0)
    assert (tmp_path / "intrinsics.json").exists()

    intr = o3d.io.read_pinhole_camera_intrinsic(str(tmp_path / "intrinsics.json"))
    assert intr.width == 64
    assert intr.height == 48


def test_load_dataset_redwood(redwood_dataset: Path):
    """load_dataset reconstrói Dataset a partir do layout rsmapper."""
    ds = load_dataset(redwood_dataset)
    assert ds.n_frames == 5
    assert ds.intrinsic.width > 0
    assert ds.intrinsic.height > 0
    assert len(ds.color_paths) == 5
    assert len(ds.depth_paths) == 5


def test_load_dataset_empty_raises(tmp_path: Path):
    """load_dataset levanta FileNotFoundError se diretório vazio."""
    with pytest.raises(FileNotFoundError):
        load_dataset(tmp_path)


def test_read_rgbd_redwood(redwood_dataset: Path):
    """read_rgbd converte para intensidade (odometria) ou RGB (integração)."""
    ds = load_dataset(redwood_dataset)
    assert ds.n_frames == 5

    # Odometria: intensidade (ndim == 2)
    rgbd = read_rgbd(ds, 0, Config(), for_odometry=True)
    assert np.asarray(rgbd.color).ndim == 2

    # Integração: RGB (ndim == 3)
    rgbd2 = read_rgbd(ds, 0, Config(), for_odometry=False)
    assert np.asarray(rgbd2.color).ndim == 3

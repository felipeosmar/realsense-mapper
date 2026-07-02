"""Fixtures: monta um dataset no layout do rsmapper a partir do sample Redwood."""
import shutil
from pathlib import Path

import open3d as o3d
import pytest


@pytest.fixture(scope="session")
def redwood_dataset(tmp_path_factory) -> Path:
    """5 frames RGB-D reais no layout color/ + depth/ + intrinsics.json."""
    sample = o3d.data.SampleRedwoodRGBDImages()
    root = tmp_path_factory.mktemp("redwood_ds")
    (root / "color").mkdir()
    (root / "depth").mkdir()
    for i, (c, d) in enumerate(zip(sample.color_paths, sample.depth_paths)):
        shutil.copy(c, root / "color" / f"{i:06d}.jpg")
        shutil.copy(d, root / "depth" / f"{i:06d}.png")
    intr = o3d.camera.PinholeCameraIntrinsic(
        o3d.camera.PinholeCameraIntrinsicParameters.PrimeSenseDefault
    )
    o3d.io.write_pinhole_camera_intrinsic(str(root / "intrinsics.json"), intr)
    return root

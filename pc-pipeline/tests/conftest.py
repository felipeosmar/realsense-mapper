"""Fixtures: monta dataset no layout do rsmapper a partir do sample Redwood."""
import shutil
from pathlib import Path

import open3d as o3d
import pytest

from rsmapper.dataset import frame_name, save_frame, write_intrinsics


@pytest.fixture(scope="session")
def redwood_dataset(tmp_path_factory) -> Path:
    """5 frames RGB-D reais no layout color/ + depth/ + intrinsics.json."""
    sample = o3d.data.SampleRedwoodRGBDImages()
    root = tmp_path_factory.mktemp("redwood_ds")

    # Carrega intrínsecos do sample (o3d 0.19 usa camera_intrinsic_path)
    intrinsic = o3d.io.read_pinhole_camera_intrinsic(sample.camera_intrinsic_path)

    # Salva frames no layout rsmapper
    for i in range(5):
        color = o3d.io.read_image(sample.color_paths[i])
        depth = o3d.io.read_image(sample.depth_paths[i])
        save_frame(root, i, color, depth)

    # Escreve intrínsecos
    write_intrinsics(
        root,
        intrinsic.width,
        intrinsic.height,
        intrinsic.get_focal_length()[0],
        intrinsic.get_focal_length()[1],
        intrinsic.get_principal_point()[0],
        intrinsic.get_principal_point()[1],
    )

    return root

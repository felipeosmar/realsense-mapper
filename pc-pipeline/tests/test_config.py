from pathlib import Path

import pytest

from rsmapper.config import Config, load_config


def test_defaults():
    cfg = load_config(None)
    assert cfg == Config()
    assert cfg.voxel_size == 0.01
    assert cfg.frames_per_fragment == 100
    assert cfg.depth_max == 3.0
    assert cfg.decimate_target_triangles == 500_000


def test_load_overrides(tmp_path: Path):
    f = tmp_path / "config.yaml"
    f.write_text("voxel_size: 0.02\nframes_per_fragment: 50\n")
    cfg = load_config(f)
    assert cfg.voxel_size == 0.02
    assert cfg.frames_per_fragment == 50
    assert cfg.depth_max == 3.0  # default preservado


def test_unknown_key_raises(tmp_path: Path):
    f = tmp_path / "config.yaml"
    f.write_text("voxel_sixe: 0.02\n")
    with pytest.raises(ValueError, match="voxel_sixe"):
        load_config(f)

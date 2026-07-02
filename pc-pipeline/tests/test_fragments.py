from pathlib import Path

import open3d as o3d

from rsmapper.config import Config
from rsmapper.dataset import load_dataset
from rsmapper.fragments import fragment_ranges, make_fragments


def test_fragment_ranges():
    assert fragment_ranges(10, 4) == [(0, 4), (4, 8), (8, 10)]
    assert fragment_ranges(4, 4) == [(0, 4)]
    assert fragment_ranges(3, 100) == [(0, 3)]


def test_make_fragments_redwood(redwood_dataset: Path, tmp_path: Path):
    ds = load_dataset(redwood_dataset)
    cfg = Config(frames_per_fragment=5, keyframe_gap=2)
    plys = make_fragments(ds, cfg, tmp_path)
    assert len(plys) == 1
    assert plys[0].is_file()

    pcd = o3d.io.read_point_cloud(str(plys[0]))
    assert len(pcd.points) > 10_000  # fragmento denso de uma cena real

    pg = o3d.io.read_pose_graph(str(tmp_path / "fragment_000.json"))
    assert len(pg.nodes) == 5

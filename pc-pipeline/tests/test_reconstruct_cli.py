from pathlib import Path

from rsmapper.config import Config
from reconstruct import run


def test_run_end_to_end(redwood_dataset: Path, tmp_path: Path):
    cfg = Config(frames_per_fragment=5, keyframe_gap=2,
                 decimate_target_triangles=20_000)
    out = run(redwood_dataset, cfg, tmp_path / "out")
    assert out["glb"].is_file()
    assert out["ply"].is_file()
    assert out["obj"].is_file()

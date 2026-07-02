import json
import os
from pathlib import Path

import pytest

from rsmapper.bag_extract import ExtractReport, count_drops


def test_count_drops_none():
    assert count_drops([1, 2, 3, 4]) == 0


def test_count_drops_gaps():
    # falta o 3 (1 frame) e faltam 6..9 (4 frames)
    assert count_drops([1, 2, 4, 5, 10]) == 5


def test_count_drops_short():
    assert count_drops([]) == 0
    assert count_drops([7]) == 0


def test_report_to_json(tmp_path: Path):
    rep = ExtractReport(frame_count=100, dropped_frames=2, duration_sec=3.5)
    rep.to_json(tmp_path / "report.json")
    data = json.loads((tmp_path / "report.json").read_text())
    assert data == {"frame_count": 100, "dropped_frames": 2, "duration_sec": 3.5}


@pytest.mark.skipif("RSM_TEST_BAG" not in os.environ,
                    reason="defina RSM_TEST_BAG=/caminho/scan.bag para rodar")
def test_extract_real_bag(tmp_path: Path):
    from rsmapper.bag_extract import extract_bag
    from rsmapper.dataset import load_dataset

    rep = extract_bag(Path(os.environ["RSM_TEST_BAG"]), tmp_path)
    assert rep.frame_count > 0
    ds = load_dataset(tmp_path)
    assert ds.n_frames == rep.frame_count

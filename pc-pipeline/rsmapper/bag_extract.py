"""Extração de frames alinhados (depth→color) de um .bag do RealSense."""
import json
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np

from rsmapper.dataset import save_frame, write_intrinsics


@dataclass
class ExtractReport:
    frame_count: int
    dropped_frames: int
    duration_sec: float

    def to_json(self, path: Path) -> None:
        Path(path).write_text(json.dumps(asdict(self), indent=2))


def count_drops(frame_numbers: list[int]) -> int:
    """Total de frames pulados, medido pelas lacunas na numeração."""
    if len(frame_numbers) < 2:
        return 0
    return sum(b - a - 1 for a, b in zip(frame_numbers, frame_numbers[1:]) if b > a + 1)


def extract_bag(bag_path: Path, out_dir: Path) -> ExtractReport:
    """Reproduz o bag (sem tempo real), alinha depth ao color e salva o dataset."""
    import pyrealsense2 as rs

    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    pipeline = rs.pipeline()
    config = rs.config()
    rs.config.enable_device_from_file(config, str(bag_path), repeat_playback=False)
    profile = pipeline.start(config)
    profile.get_device().as_playback().set_real_time(False)
    align = rs.align(rs.stream.color)

    color_profile = profile.get_stream(rs.stream.color).as_video_stream_profile()
    i = color_profile.get_intrinsics()
    write_intrinsics(out_dir, i.width, i.height, i.fx, i.fy, i.ppx, i.ppy)

    frame_numbers: list[int] = []
    timestamps: list[float] = []
    idx = 0
    try:
        while True:
            frames = pipeline.wait_for_frames(timeout_ms=5000)
            frames = align.process(frames)
            color = frames.get_color_frame()
            depth = frames.get_depth_frame()
            if not color or not depth:
                continue
            save_frame(out_dir, idx,
                       np.asanyarray(color.get_data()),
                       np.asanyarray(depth.get_data()))
            frame_numbers.append(color.get_frame_number())
            timestamps.append(color.get_timestamp())
            idx += 1
    except RuntimeError:
        pass  # fim do bag: wait_for_frames estoura timeout
    finally:
        pipeline.stop()

    duration = (timestamps[-1] - timestamps[0]) / 1000.0 if len(timestamps) > 1 else 0.0
    report = ExtractReport(frame_count=idx,
                           dropped_frames=count_drops(frame_numbers),
                           duration_sec=round(duration, 2))
    report.to_json(out_dir / "report.json")
    return report

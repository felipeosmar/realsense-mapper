"""Configuração do pipeline de reconstrução."""
import dataclasses
from dataclasses import dataclass
from pathlib import Path

import yaml


@dataclass
class Config:
    voxel_size: float = 0.01
    frames_per_fragment: int = 100
    keyframe_gap: int = 5
    depth_scale: float = 1000.0
    depth_max: float = 3.0
    decimate_target_triangles: int = 500_000


def load_config(path: Path | None) -> Config:
    """Carrega config.yaml; chaves ausentes usam os defaults da dataclass."""
    if path is None:
        return Config()
    data = yaml.safe_load(Path(path).read_text()) or {}
    known = {f.name for f in dataclasses.fields(Config)}
    unknown = set(data) - known
    if unknown:
        raise ValueError(f"Chaves desconhecidas no config: {sorted(unknown)}")
    return Config(**data)

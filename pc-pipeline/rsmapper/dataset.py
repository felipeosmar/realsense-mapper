"""Layout do dataset extraído: color/*.jpg, depth/*.png (uint16), intrinsics.json."""
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import open3d as o3d

from rsmapper.config import Config


def frame_name(i: int) -> str:
    """Formata índice de frame como 6 dígitos zero-padded."""
    return f"{i:06d}"


def save_frame(root: Path, i: int, color: o3d.geometry.Image, depth: o3d.geometry.Image) -> None:
    """Salva frame color como JPG e depth como PNG (uint16) no layout rsmapper."""
    root = Path(root)
    (root / "color").mkdir(parents=True, exist_ok=True)
    (root / "depth").mkdir(parents=True, exist_ok=True)

    name = frame_name(i)
    o3d.io.write_image(str(root / "color" / f"{name}.jpg"), color)
    o3d.io.write_image(str(root / "depth" / f"{name}.png"), depth)


def write_intrinsics(
    root: Path, width: int, height: int, fx: float, fy: float, ppx: float, ppy: float
) -> None:
    """Escreve intrínsecos da câmera em intrinsics.json."""
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)

    intrinsic = o3d.camera.PinholeCameraIntrinsic(width, height, fx, fy, ppx, ppy)
    o3d.io.write_pinhole_camera_intrinsic(str(root / "intrinsics.json"), intrinsic)


@dataclass
class Dataset:
    """Layout do dataset: frames RGB-D extraídos com intrínsecos."""

    root: Path
    color_paths: list[Path]
    depth_paths: list[Path]
    intrinsic: o3d.camera.PinholeCameraIntrinsic

    @property
    def n_frames(self) -> int:
        """Número de frames no dataset."""
        return len(self.color_paths)

    def __len__(self) -> int:
        """Suporte para len(ds)."""
        return self.n_frames


def load_dataset(root: Path) -> Dataset:
    """Carrega dataset do layout rsmapper.

    Args:
        root: Diretório com estrutura color/, depth/, intrinsics.json

    Returns:
        Dataset com paths dos frames e intrínsecos

    Raises:
        FileNotFoundError: se estrutura incompleta ou ausente
        ValueError: se número de frames não corresponde
    """
    root = Path(root)

    # Lista frames
    color_dir = root / "color"
    depth_dir = root / "depth"

    if not color_dir.is_dir() or not depth_dir.is_dir():
        raise FileNotFoundError(f"Dataset vazio ou incompleto em {root}")

    colors = sorted(color_dir.glob("*.jpg"))
    depths = sorted(depth_dir.glob("*.png"))

    if not colors or not depths:
        raise FileNotFoundError(f"Dataset vazio ou incompleto em {root}")

    if len(colors) != len(depths):
        raise ValueError(f"{len(colors)} frames color vs {len(depths)} depth em {root}")

    intr_path = root / "intrinsics.json"
    if not intr_path.is_file():
        raise FileNotFoundError(f"intrinsics.json não encontrado em {root}")

    intrinsic = o3d.io.read_pinhole_camera_intrinsic(str(intr_path))

    return Dataset(root, colors, depths, intrinsic)


def read_rgbd(
    ds: Dataset, i: int, cfg: Config, for_odometry: bool
) -> o3d.geometry.RGBDImage:
    """Lê frame RGB-D do dataset.

    Args:
        ds: Dataset carregado
        i: Índice do frame
        cfg: Configuração (depth_scale, depth_max)
        for_odometry: Se True, converte para intensidade (1 canal); se False, mantém RGB (3 canais)

    Returns:
        RGBDImage com depth e color alinhados
    """
    color = o3d.io.read_image(str(ds.color_paths[i]))
    depth = o3d.io.read_image(str(ds.depth_paths[i]))

    return o3d.geometry.RGBDImage.create_from_color_and_depth(
        color,
        depth,
        depth_scale=cfg.depth_scale,
        depth_trunc=cfg.depth_max,
        convert_rgb_to_intensity=for_odometry,
    )

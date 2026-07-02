#!/usr/bin/env python3
"""Reconstrói uma malha 3D a partir de um dataset extraído por extract.py.

Uso: python reconstruct.py out/scan_20260702_1030 [--config config.yaml] [-o saida/]
"""
import argparse
import time
from pathlib import Path

from rsmapper.config import Config, load_config
from rsmapper.dataset import load_dataset
from rsmapper.fragments import make_fragments
from rsmapper.integrate import export_mesh, integrate_scene
from rsmapper.registration import register_fragments


def run(dataset_dir: Path, cfg: Config, out_dir: Path) -> dict[str, Path]:
    out_dir = Path(out_dir)
    ds = load_dataset(dataset_dir)
    print(f"Dataset: {ds.n_frames} frames em {dataset_dir}")

    print("[1/3] Fragmentos (odometria RGB-D)...")
    plys = make_fragments(ds, cfg, out_dir / "fragments")

    print(f"[2/3] Registro global de {len(plys)} fragmento(s)...")
    scene_pg = register_fragments(plys, cfg)

    print("[3/3] Integração TSDF e exportação...")
    mesh = integrate_scene(ds, scene_pg, out_dir / "fragments", cfg)
    paths = export_mesh(mesh, out_dir, cfg)
    return paths


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("dataset", type=Path, help="pasta gerada por extract.py")
    ap.add_argument("--config", type=Path, default=None, help="config.yaml opcional")
    ap.add_argument("-o", "--out", type=Path, default=None,
                    help="pasta de saída (default: <dataset>/reconstruction)")
    args = ap.parse_args()

    cfg = load_config(args.config)
    out = args.out or args.dataset / "reconstruction"
    t0 = time.time()
    paths = run(args.dataset, cfg, out)
    print(f"\nConcluído em {time.time() - t0:.0f}s:")
    for kind, p in paths.items():
        print(f"  {kind}: {p}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Processa um .bag do RealSense de ponta a ponta: extrai os frames e reconstrói a malha.

Equivale a rodar, em sequência:
    python extract.py <bag>
    python reconstruct.py out/<nome-do-bag>

Uso:
    python process.py scan.bag                 # → out/<nome>/reconstruction/scene.glb
    python process.py scan.bag -o saida/       # pasta de saída customizada
    python process.py scan.bag --config c.yaml # config de reconstrução alternativa
"""
import argparse
import time
from pathlib import Path

from rsmapper.bag_extract import extract_bag
from rsmapper.config import load_config
from reconstruct import run as reconstruct_run


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bag", type=Path, help="arquivo .bag gravado pelo app")
    ap.add_argument("--config", type=Path, default=None, help="config.yaml opcional")
    ap.add_argument("-o", "--out", type=Path, default=None,
                    help="pasta de saída da malha (default: out/<nome>/reconstruction)")
    args = ap.parse_args()

    dataset_dir = Path("out") / args.bag.stem
    out = args.out or dataset_dir / "reconstruction"
    t0 = time.time()

    print(f"[1/2] Extraindo frames de {args.bag.name}...")
    rep = extract_bag(args.bag, dataset_dir)
    print(f"      {rep.frame_count} frames ({rep.dropped_frames} perdidos), "
          f"{rep.duration_sec}s de gravação → {dataset_dir}")

    print("[2/2] Reconstruindo a malha 3D...")
    cfg = load_config(args.config)
    paths = reconstruct_run(dataset_dir, cfg, out)

    print(f"\nConcluído em {time.time() - t0:.0f}s:")
    for kind, p in paths.items():
        print(f"  {kind}: {p}")


if __name__ == "__main__":
    main()

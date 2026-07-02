#!/usr/bin/env python3
"""Extrai frames RGB-D alinhados de um .bag do RealSense.

Uso: python extract.py scan.bag [-o out/scan]
"""
import argparse
from pathlib import Path

from rsmapper.bag_extract import extract_bag


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("bag", type=Path, help="arquivo .bag gravado pelo app")
    ap.add_argument("-o", "--out", type=Path, default=None,
                    help="pasta de saída (default: out/<nome-do-bag>)")
    args = ap.parse_args()
    out = args.out or Path("out") / args.bag.stem
    rep = extract_bag(args.bag, out)
    print(f"Extração concluída em {out}")
    print(f"  frames: {rep.frame_count}")
    print(f"  frames perdidos: {rep.dropped_frames}")
    print(f"  duração: {rep.duration_sec}s")


if __name__ == "__main__":
    main()

# PC Pipeline (Open3D) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pipeline Python que transforma um `.bag` do RealSense D435i em malha 3D colorida (`.glb`, `.ply`, `.obj`) pronta para VR/simulação.

**Architecture:** Dois CLIs em `pc-pipeline/`: `extract.py` (pyrealsense2: reproduz o bag, alinha depth→color, exporta frames + intrínsecos) e `reconstruct.py` (Open3D: fragmentos com odometria RGB-D → registro global com loop closure → integração TSDF → malha exportada). Lógica em pacote `rsmapper/`, CLIs finos.

**Tech Stack:** Python 3.10+, open3d ≥ 0.18, pyrealsense2, numpy, pyyaml, trimesh (export .glb com cores), pytest.

## Global Constraints

- Diretório do projeto: `/home/felipe/realsense-mapper/pc-pipeline/` (repo já tem git e a spec commitada).
- Python 3.10+; tudo roda dentro de venv em `pc-pipeline/.venv`.
- Formato do dataset extraído (contrato entre as duas etapas): `color/000000.jpg`, `depth/000000.png` (uint16, milímetros), `intrinsics.json` (formato `o3d.io.write_pinhole_camera_intrinsic`), `report.json`.
- Defaults do config (da spec): voxel 0.01 m, 100 frames por fragmento, depth máx 3.0 m, simplificação p/ 500 000 triângulos.
- Testes não exigem câmera: usam `o3d.data.SampleRedwoodRGBDImages` e `o3d.data.BunnyMesh` (download automático com cache em `~/open3d_data`). Teste de extração com bag real é opcional, ativado por env var `RSM_TEST_BAG`.
- Mensagens de CLI e docstrings em português; identificadores em inglês.
- Commits com mensagem `feat:`/`test:`/`chore:` curtas, co-autoria `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Scaffold do pc-pipeline

**Files:**
- Create: `.gitignore` (raiz do repo)
- Create: `pc-pipeline/requirements.txt`
- Create: `pc-pipeline/pytest.ini`
- Create: `pc-pipeline/rsmapper/__init__.py`
- Create: `pc-pipeline/tests/test_smoke.py`

**Interfaces:**
- Consumes: nada.
- Produces: venv funcional com dependências instaladas; pacote `rsmapper` importável; `pytest` rodando.

- [ ] **Step 1: Criar .gitignore na raiz do repo**

```gitignore
# Python
__pycache__/
*.pyc
.venv/
.pytest_cache/

# Saídas do pipeline
pc-pipeline/out/

# Android
android-app/.gradle/
android-app/build/
android-app/app/build/
android-app/local.properties
android-app/.idea/
*.apk

# Dados
*.bag
```

- [ ] **Step 2: Criar requirements.txt**

`pc-pipeline/requirements.txt`:

```
open3d>=0.18
pyrealsense2>=2.54
numpy
pyyaml
trimesh
pytest
```

- [ ] **Step 3: Criar pytest.ini e pacote**

`pc-pipeline/pytest.ini`:

```ini
[pytest]
testpaths = tests
```

`pc-pipeline/rsmapper/__init__.py`:

```python
"""Pipeline de reconstrução 3D a partir de gravações .bag do RealSense."""
```

`pc-pipeline/tests/test_smoke.py`:

```python
def test_import():
    import rsmapper  # noqa: F401
```

- [ ] **Step 4: Criar venv, instalar dependências e rodar o teste**

Run:
```bash
cd /home/felipe/realsense-mapper/pc-pipeline
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m pytest -v
```
Expected: `1 passed`. Se `pyrealsense2` não tiver wheel para o Python local, fixar versão de Python 3.11 no venv (`python3.11 -m venv .venv`).

- [ ] **Step 5: Commit**

```bash
cd /home/felipe/realsense-mapper
git add .gitignore pc-pipeline/
git commit -m "chore: scaffold do pc-pipeline (venv, pytest, rsmapper)"
```

---

### Task 2: Config (YAML → dataclass)

**Files:**
- Create: `pc-pipeline/rsmapper/config.py`
- Create: `pc-pipeline/config.yaml`
- Test: `pc-pipeline/tests/test_config.py`

**Interfaces:**
- Consumes: nada.
- Produces: `Config` (dataclass com `voxel_size: float`, `frames_per_fragment: int`, `keyframe_gap: int`, `depth_scale: float`, `depth_max: float`, `decimate_target_triangles: int`) e `load_config(path: Path | None) -> Config`.

- [ ] **Step 1: Escrever testes que falham**

`pc-pipeline/tests/test_config.py`:

```python
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
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `.venv/bin/python -m pytest tests/test_config.py -v`
Expected: FAIL com `ModuleNotFoundError: No module named 'rsmapper.config'`

- [ ] **Step 3: Implementar**

`pc-pipeline/rsmapper/config.py`:

```python
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
```

`pc-pipeline/config.yaml` (defaults documentados para o usuário):

```yaml
# Configuração do pipeline de reconstrução — defaults calibrados para salas.
voxel_size: 0.01              # tamanho do voxel TSDF em metros
frames_per_fragment: 100      # frames por fragmento na fase de odometria
keyframe_gap: 5               # intervalo de keyframes p/ loop closure interno
depth_scale: 1000.0           # unidades de depth por metro (D435i = 1000)
depth_max: 3.0                # profundidade máxima considerada (metros)
decimate_target_triangles: 500000  # alvo de simplificação da malha final
```

- [ ] **Step 4: Rodar e ver passar**

Run: `.venv/bin/python -m pytest tests/test_config.py -v`
Expected: `3 passed`

- [ ] **Step 5: Commit**

```bash
git add pc-pipeline/rsmapper/config.py pc-pipeline/config.yaml pc-pipeline/tests/test_config.py
git commit -m "feat: config do pipeline com defaults da spec"
```

---

### Task 3: Dataset (layout de frames extraídos + intrínsecos)

**Files:**
- Create: `pc-pipeline/rsmapper/dataset.py`
- Create: `pc-pipeline/tests/conftest.py`
- Test: `pc-pipeline/tests/test_dataset.py`

**Interfaces:**
- Consumes: `Config` (Task 2).
- Produces:
  - `frame_name(i: int) -> str` (ex.: `"000042"`)
  - `save_frame(root: Path, i: int, color: np.ndarray, depth: np.ndarray) -> None` (grava `color/000042.jpg` e `depth/000042.png` uint16)
  - `write_intrinsics(root: Path, width: int, height: int, fx: float, fy: float, ppx: float, ppy: float) -> None`
  - `Dataset` (dataclass: `root: Path`, `color_paths: list[Path]`, `depth_paths: list[Path]`, `intrinsic: o3d.camera.PinholeCameraIntrinsic`) com `len(ds)` via `n_frames`
  - `load_dataset(root: Path) -> Dataset`
  - `read_rgbd(ds: Dataset, i: int, cfg: Config, for_odometry: bool) -> o3d.geometry.RGBDImage`

- [ ] **Step 1: Escrever fixture compartilhada (dataset Redwood)**

`pc-pipeline/tests/conftest.py`:

```python
"""Fixtures: monta um dataset no layout do rsmapper a partir do sample Redwood."""
import shutil
from pathlib import Path

import open3d as o3d
import pytest


@pytest.fixture(scope="session")
def redwood_dataset(tmp_path_factory) -> Path:
    """5 frames RGB-D reais no layout color/ + depth/ + intrinsics.json."""
    sample = o3d.data.SampleRedwoodRGBDImages()
    root = tmp_path_factory.mktemp("redwood_ds")
    (root / "color").mkdir()
    (root / "depth").mkdir()
    for i, (c, d) in enumerate(zip(sample.color_paths, sample.depth_paths)):
        shutil.copy(c, root / "color" / f"{i:06d}.jpg")
        shutil.copy(d, root / "depth" / f"{i:06d}.png")
    intr = o3d.camera.PinholeCameraIntrinsic(
        o3d.camera.PinholeCameraIntrinsicParameters.PrimeSenseDefault
    )
    o3d.io.write_pinhole_camera_intrinsic(str(root / "intrinsics.json"), intr)
    return root
```

- [ ] **Step 2: Escrever testes que falham**

`pc-pipeline/tests/test_dataset.py`:

```python
from pathlib import Path

import numpy as np
import open3d as o3d
import pytest

from rsmapper.config import Config
from rsmapper.dataset import (
    Dataset, frame_name, load_dataset, read_rgbd, save_frame, write_intrinsics,
)


def test_frame_name():
    assert frame_name(0) == "000000"
    assert frame_name(42) == "000042"


def test_save_frame_and_intrinsics_roundtrip(tmp_path: Path):
    color = np.zeros((48, 64, 3), dtype=np.uint8)
    color[:, :, 0] = 200
    depth = np.full((48, 64), 1500, dtype=np.uint16)
    save_frame(tmp_path, 0, color, depth)
    save_frame(tmp_path, 1, color, depth)
    write_intrinsics(tmp_path, 64, 48, 50.0, 50.0, 32.0, 24.0)

    ds = load_dataset(tmp_path)
    assert ds.n_frames == 2
    assert ds.intrinsic.width == 64
    depth_back = np.asarray(o3d.io.read_image(str(ds.depth_paths[0])))
    assert depth_back.dtype == np.uint16
    assert int(depth_back[0, 0]) == 1500


def test_load_dataset_empty_raises(tmp_path: Path):
    with pytest.raises(FileNotFoundError):
        load_dataset(tmp_path)


def test_read_rgbd_redwood(redwood_dataset: Path):
    ds = load_dataset(redwood_dataset)
    assert ds.n_frames == 5
    rgbd = read_rgbd(ds, 0, Config(), for_odometry=True)
    assert np.asarray(rgbd.color).ndim == 2  # intensidade p/ odometria
    rgbd2 = read_rgbd(ds, 0, Config(), for_odometry=False)
    assert np.asarray(rgbd2.color).ndim == 3  # RGB p/ integração
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `.venv/bin/python -m pytest tests/test_dataset.py -v`
Expected: FAIL com `ModuleNotFoundError: No module named 'rsmapper.dataset'`

- [ ] **Step 4: Implementar**

`pc-pipeline/rsmapper/dataset.py`:

```python
"""Layout do dataset extraído: color/*.jpg, depth/*.png (uint16), intrinsics.json."""
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import open3d as o3d

from rsmapper.config import Config


def frame_name(i: int) -> str:
    return f"{i:06d}"


def save_frame(root: Path, i: int, color: np.ndarray, depth: np.ndarray) -> None:
    """Grava um par color (uint8 HxWx3) / depth (uint16 HxW, mm)."""
    (root / "color").mkdir(parents=True, exist_ok=True)
    (root / "depth").mkdir(parents=True, exist_ok=True)
    name = frame_name(i)
    o3d.io.write_image(str(root / "color" / f"{name}.jpg"),
                       o3d.geometry.Image(np.ascontiguousarray(color)))
    o3d.io.write_image(str(root / "depth" / f"{name}.png"),
                       o3d.geometry.Image(np.ascontiguousarray(depth)))


def write_intrinsics(root: Path, width: int, height: int,
                     fx: float, fy: float, ppx: float, ppy: float) -> None:
    intr = o3d.camera.PinholeCameraIntrinsic(width, height, fx, fy, ppx, ppy)
    o3d.io.write_pinhole_camera_intrinsic(str(root / "intrinsics.json"), intr)


@dataclass
class Dataset:
    root: Path
    color_paths: list[Path]
    depth_paths: list[Path]
    intrinsic: o3d.camera.PinholeCameraIntrinsic

    @property
    def n_frames(self) -> int:
        return len(self.color_paths)


def load_dataset(root: Path) -> Dataset:
    root = Path(root)
    colors = sorted((root / "color").glob("*.jpg")) if (root / "color").is_dir() else []
    depths = sorted((root / "depth").glob("*.png")) if (root / "depth").is_dir() else []
    if not colors or not depths:
        raise FileNotFoundError(f"Dataset vazio ou incompleto em {root}")
    if len(colors) != len(depths):
        raise ValueError(f"{len(colors)} frames color vs {len(depths)} depth em {root}")
    intr_path = root / "intrinsics.json"
    if not intr_path.is_file():
        raise FileNotFoundError(f"intrinsics.json não encontrado em {root}")
    intrinsic = o3d.io.read_pinhole_camera_intrinsic(str(intr_path))
    return Dataset(root, colors, depths, intrinsic)


def read_rgbd(ds: Dataset, i: int, cfg: Config, for_odometry: bool) -> o3d.geometry.RGBDImage:
    """RGBD do frame i. Odometria usa intensidade; integração usa RGB."""
    color = o3d.io.read_image(str(ds.color_paths[i]))
    depth = o3d.io.read_image(str(ds.depth_paths[i]))
    return o3d.geometry.RGBDImage.create_from_color_and_depth(
        color, depth,
        depth_scale=cfg.depth_scale,
        depth_trunc=cfg.depth_max,
        convert_rgb_to_intensity=for_odometry,
    )
```

- [ ] **Step 5: Rodar e ver passar**

Run: `.venv/bin/python -m pytest tests/test_dataset.py -v`
Expected: `4 passed` (primeira execução baixa o sample Redwood, ~5 MB)

- [ ] **Step 6: Commit**

```bash
git add pc-pipeline/rsmapper/dataset.py pc-pipeline/tests/conftest.py pc-pipeline/tests/test_dataset.py
git commit -m "feat: dataset extraído (frames, intrínsecos, RGBD)"
```

---

### Task 4: extract.py (bag → dataset)

**Files:**
- Create: `pc-pipeline/rsmapper/bag_extract.py`
- Create: `pc-pipeline/extract.py`
- Test: `pc-pipeline/tests/test_bag_extract.py`

**Interfaces:**
- Consumes: `save_frame`, `write_intrinsics`, `frame_name` (Task 3).
- Produces:
  - `ExtractReport` (dataclass: `frame_count: int`, `dropped_frames: int`, `duration_sec: float`) com método `to_json(path: Path)`
  - `count_drops(frame_numbers: list[int]) -> int` (lacunas na numeração de frames)
  - `extract_bag(bag_path: Path, out_dir: Path) -> ExtractReport` (requer pyrealsense2 e um bag)
  - CLI: `python extract.py scan.bag -o out/scan/`

- [ ] **Step 1: Escrever testes que falham (lógica pura + integração opcional)**

`pc-pipeline/tests/test_bag_extract.py`:

```python
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
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `.venv/bin/python -m pytest tests/test_bag_extract.py -v`
Expected: FAIL com `ModuleNotFoundError: No module named 'rsmapper.bag_extract'`

- [ ] **Step 3: Implementar**

`pc-pipeline/rsmapper/bag_extract.py`:

```python
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
```

`pc-pipeline/extract.py`:

```python
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
```

- [ ] **Step 4: Rodar e ver passar**

Run: `.venv/bin/python -m pytest tests/test_bag_extract.py -v`
Expected: `4 passed, 1 skipped` (o teste de integração fica para quando existir um bag real)

- [ ] **Step 5: Commit**

```bash
git add pc-pipeline/rsmapper/bag_extract.py pc-pipeline/extract.py pc-pipeline/tests/test_bag_extract.py
git commit -m "feat: extract.py — bag para dataset RGB-D alinhado"
```

---

### Task 5: Fragmentos (odometria RGB-D + pose graph local)

**Files:**
- Create: `pc-pipeline/rsmapper/fragments.py`
- Test: `pc-pipeline/tests/test_fragments.py`

**Interfaces:**
- Consumes: `Config` (Task 2); `Dataset`, `load_dataset`, `read_rgbd` (Task 3).
- Produces:
  - `fragment_ranges(n_frames: int, per_fragment: int) -> list[tuple[int, int]]` (intervalos `[start, end)`)
  - `make_fragment(ds: Dataset, frag_id: int, start: int, end: int, cfg: Config, out_dir: Path) -> Path` (grava `fragment_XXX.ply` e `fragment_XXX.json`, retorna caminho do .ply)
  - `make_fragments(ds: Dataset, cfg: Config, out_dir: Path) -> list[Path]`

- [ ] **Step 1: Escrever testes que falham**

`pc-pipeline/tests/test_fragments.py`:

```python
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
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `.venv/bin/python -m pytest tests/test_fragments.py -v`
Expected: FAIL com `ModuleNotFoundError: No module named 'rsmapper.fragments'`

- [ ] **Step 3: Implementar**

`pc-pipeline/rsmapper/fragments.py`:

```python
"""Fase 1: fragmentos — odometria RGB-D + pose graph local + TSDF por bloco."""
from pathlib import Path

import numpy as np
import open3d as o3d

from rsmapper.config import Config
from rsmapper.dataset import Dataset, read_rgbd


def fragment_ranges(n_frames: int, per_fragment: int) -> list[tuple[int, int]]:
    """Intervalos [start, end) contíguos; o último pode ser menor."""
    return [(s, min(s + per_fragment, n_frames))
            for s in range(0, n_frames, per_fragment)]


def _rgbd_odometry(ds: Dataset, s: int, t: int, cfg: Config,
                   init: np.ndarray) -> tuple[bool, np.ndarray, np.ndarray]:
    source = read_rgbd(ds, s, cfg, for_odometry=True)
    target = read_rgbd(ds, t, cfg, for_odometry=True)
    option = o3d.pipelines.odometry.OdometryOption(depth_max=cfg.depth_max)
    return o3d.pipelines.odometry.compute_rgbd_odometry(
        source, target, ds.intrinsic, init,
        o3d.pipelines.odometry.RGBDOdometryJacobianFromHybridTerm(), option)


def _build_posegraph(ds: Dataset, start: int, end: int, cfg: Config
                     ) -> o3d.pipelines.registration.PoseGraph:
    pg = o3d.pipelines.registration.PoseGraph()
    odom = np.identity(4)
    pg.nodes.append(o3d.pipelines.registration.PoseGraphNode(odom))
    for s in range(start, end - 1):
        # aresta de odometria (frame consecutivo)
        ok, trans, info = _rgbd_odometry(ds, s, s + 1, cfg, np.identity(4))
        if ok:
            odom = odom @ np.linalg.inv(trans)
        pg.nodes.append(o3d.pipelines.registration.PoseGraphNode(odom.copy()))
        pg.edges.append(o3d.pipelines.registration.PoseGraphEdge(
            s - start, s - start + 1, trans, info, uncertain=False))
        # arestas de loop closure entre keyframes do fragmento
        if (s - start) % cfg.keyframe_gap == 0:
            for t in range(s + 2, min(s + cfg.keyframe_gap + 1, end)):
                ok, trans, info = _rgbd_odometry(ds, s, t, cfg, np.identity(4))
                if ok:
                    pg.edges.append(o3d.pipelines.registration.PoseGraphEdge(
                        s - start, t - start, trans, info, uncertain=True))
    _optimize(pg, cfg.voxel_size * 1.4)
    return pg


def _optimize(pg: o3d.pipelines.registration.PoseGraph, max_corr: float) -> None:
    option = o3d.pipelines.registration.GlobalOptimizationOption(
        max_correspondence_distance=max_corr,
        edge_prune_threshold=0.25,
        reference_node=0)
    o3d.pipelines.registration.global_optimization(
        pg,
        o3d.pipelines.registration.GlobalOptimizationLevenbergMarquardt(),
        o3d.pipelines.registration.GlobalOptimizationConvergenceCriteria(),
        option)


def make_fragment(ds: Dataset, frag_id: int, start: int, end: int,
                  cfg: Config, out_dir: Path) -> Path:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    pg = _build_posegraph(ds, start, end, cfg)
    o3d.io.write_pose_graph(str(out_dir / f"fragment_{frag_id:03d}.json"), pg)

    volume = o3d.pipelines.integration.ScalableTSDFVolume(
        voxel_length=cfg.voxel_size,
        sdf_trunc=cfg.voxel_size * 4,
        color_type=o3d.pipelines.integration.TSDFVolumeColorType.RGB8)
    for k, node in enumerate(pg.nodes):
        rgbd = read_rgbd(ds, start + k, cfg, for_odometry=False)
        volume.integrate(rgbd, ds.intrinsic, np.linalg.inv(node.pose))
    pcd = volume.extract_point_cloud()
    ply = out_dir / f"fragment_{frag_id:03d}.ply"
    o3d.io.write_point_cloud(str(ply), pcd)
    return ply


def make_fragments(ds: Dataset, cfg: Config, out_dir: Path) -> list[Path]:
    ranges = fragment_ranges(ds.n_frames, cfg.frames_per_fragment)
    plys = []
    for frag_id, (start, end) in enumerate(ranges):
        print(f"  fragmento {frag_id + 1}/{len(ranges)} (frames {start}–{end - 1})")
        plys.append(make_fragment(ds, frag_id, start, end, cfg, out_dir))
    return plys
```

- [ ] **Step 4: Rodar e ver passar**

Run: `.venv/bin/python -m pytest tests/test_fragments.py -v`
Expected: `2 passed` (o teste Redwood leva ~10–30 s)

- [ ] **Step 5: Commit**

```bash
git add pc-pipeline/rsmapper/fragments.py pc-pipeline/tests/test_fragments.py
git commit -m "feat: fragmentos com odometria RGB-D e pose graph local"
```

---

### Task 6: Registro global entre fragmentos

**Files:**
- Create: `pc-pipeline/rsmapper/registration.py`
- Test: `pc-pipeline/tests/test_registration.py`

**Interfaces:**
- Consumes: `Config` (Task 2); arquivos `fragment_XXX.ply` (Task 5).
- Produces:
  - `preprocess(pcd, voxel: float) -> tuple[pcd_down, fpfh]`
  - `pairwise_icp(src, tgt, voxel: float, init: np.ndarray) -> tuple[np.ndarray, np.ndarray]` (transform 4×4, info matrix 6×6)
  - `global_ransac(src_down, src_fpfh, tgt_down, tgt_fpfh, voxel: float) -> np.ndarray | None`
  - `register_fragments(fragment_plys: list[Path], cfg: Config) -> o3d.pipelines.registration.PoseGraph` (1 nó por fragmento; caso trivial de 1 fragmento retorna pose graph com nó identidade)

- [ ] **Step 1: Escrever testes que falham**

`pc-pipeline/tests/test_registration.py`:

```python
from pathlib import Path

import numpy as np
import open3d as o3d
import pytest

from rsmapper.config import Config
from rsmapper.registration import pairwise_icp, preprocess, register_fragments

VOXEL = 0.01


@pytest.fixture(scope="module")
def bunny() -> o3d.geometry.PointCloud:
    mesh = o3d.io.read_triangle_mesh(o3d.data.BunnyMesh().path)
    return mesh.sample_points_uniformly(8000)


def _small_transform() -> np.ndarray:
    t = np.identity(4)
    t[:3, :3] = o3d.geometry.get_rotation_matrix_from_xyz((0.05, 0.08, 0.03))
    t[:3, 3] = (0.01, -0.005, 0.008)
    return t


def test_pairwise_icp_recovers_transform(bunny):
    src = o3d.geometry.PointCloud(bunny)
    tgt = o3d.geometry.PointCloud(bunny)
    truth = _small_transform()
    tgt.transform(truth)
    trans, info = pairwise_icp(src, tgt, VOXEL, np.identity(4))
    err = np.linalg.norm(trans[:3, 3] - truth[:3, 3])
    assert err < 0.005  # translação recuperada com erro < 5 mm
    assert info.shape == (6, 6)


def test_register_two_fragments(bunny, tmp_path: Path):
    truth = _small_transform()
    a = o3d.geometry.PointCloud(bunny)
    b = o3d.geometry.PointCloud(bunny)
    b.transform(np.linalg.inv(truth))
    o3d.io.write_point_cloud(str(tmp_path / "fragment_000.ply"), a)
    o3d.io.write_point_cloud(str(tmp_path / "fragment_001.ply"), b)

    pg = register_fragments(
        [tmp_path / "fragment_000.ply", tmp_path / "fragment_001.ply"], Config())
    assert len(pg.nodes) == 2
    assert len(pg.edges) >= 1
    # pose do fragmento 1 deve aproximar a transformação verdadeira
    err = np.linalg.norm(pg.nodes[1].pose[:3, 3] - truth[:3, 3])
    assert err < 0.01


def test_register_single_fragment(bunny, tmp_path: Path):
    o3d.io.write_point_cloud(str(tmp_path / "fragment_000.ply"), bunny)
    pg = register_fragments([tmp_path / "fragment_000.ply"], Config())
    assert len(pg.nodes) == 1
    np.testing.assert_allclose(pg.nodes[0].pose, np.identity(4))
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `.venv/bin/python -m pytest tests/test_registration.py -v`
Expected: FAIL com `ModuleNotFoundError: No module named 'rsmapper.registration'`

- [ ] **Step 3: Implementar**

`pc-pipeline/rsmapper/registration.py`:

```python
"""Fase 2: registro global entre fragmentos com loop closure."""
from pathlib import Path

import numpy as np
import open3d as o3d

from rsmapper.config import Config

reg = o3d.pipelines.registration


def preprocess(pcd: o3d.geometry.PointCloud, voxel: float):
    down = pcd.voxel_down_sample(voxel * 3)
    down.estimate_normals(
        o3d.geometry.KDTreeSearchParamHybrid(radius=voxel * 6, max_nn=30))
    fpfh = reg.compute_fpfh_feature(
        down, o3d.geometry.KDTreeSearchParamHybrid(radius=voxel * 15, max_nn=100))
    return down, fpfh


def pairwise_icp(src, tgt, voxel: float, init: np.ndarray):
    """ICP ponto-a-plano em duas escalas; retorna (transform, info_matrix)."""
    trans = init
    for scale in (voxel * 6, voxel * 1.5):
        s = src.voxel_down_sample(scale / 2)
        t = tgt.voxel_down_sample(scale / 2)
        s.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=scale * 2, max_nn=30))
        t.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=scale * 2, max_nn=30))
        result = reg.registration_icp(
            s, t, scale, trans, reg.TransformationEstimationPointToPlane())
        trans = result.transformation
    info = reg.get_information_matrix_from_point_clouds(src, tgt, voxel * 1.5, trans)
    return trans, info


def global_ransac(src_down, src_fpfh, tgt_down, tgt_fpfh, voxel: float):
    """Registro global FPFH+RANSAC; None se a sobreposição for insuficiente."""
    dist = voxel * 4.5
    result = reg.registration_ransac_based_on_feature_matching(
        src_down, tgt_down, src_fpfh, tgt_fpfh, mutual_filter=True,
        max_correspondence_distance=dist,
        estimation_method=reg.TransformationEstimationPointToPoint(False),
        ransac_n=3,
        checkers=[reg.CorrespondenceCheckerBasedOnEdgeLength(0.9),
                  reg.CorrespondenceCheckerBasedOnDistance(dist)],
        criteria=reg.RANSACConvergenceCriteria(1_000_000, 0.999))
    if result.fitness < 0.3:
        return None
    return result.transformation


def register_fragments(fragment_plys: list[Path], cfg: Config) -> reg.PoseGraph:
    pcds = [o3d.io.read_point_cloud(str(p)) for p in fragment_plys]
    pg = reg.PoseGraph()
    pg.nodes.append(reg.PoseGraphNode(np.identity(4)))
    if len(pcds) == 1:
        return pg

    downs, fpfhs = [], []
    for p in pcds:
        d, f = preprocess(p, cfg.voxel_size)
        downs.append(d)
        fpfhs.append(f)

    odom = np.identity(4)
    for s in range(len(pcds)):
        for t in range(s + 1, len(pcds)):
            adjacent = t == s + 1
            if adjacent:
                trans, info = pairwise_icp(pcds[s], pcds[t], cfg.voxel_size, np.identity(4))
                odom = np.linalg.inv(trans) @ odom
                pg.nodes.append(reg.PoseGraphNode(np.linalg.inv(odom)))
                pg.edges.append(reg.PoseGraphEdge(s, t, trans, info, uncertain=False))
            else:
                guess = global_ransac(downs[s], fpfhs[s], downs[t], fpfhs[t], cfg.voxel_size)
                if guess is None:
                    continue
                trans, info = pairwise_icp(pcds[s], pcds[t], cfg.voxel_size, guess)
                pg.edges.append(reg.PoseGraphEdge(s, t, trans, info, uncertain=True))

    option = reg.GlobalOptimizationOption(
        max_correspondence_distance=cfg.voxel_size * 1.5,
        edge_prune_threshold=0.25,
        reference_node=0)
    reg.global_optimization(pg, reg.GlobalOptimizationLevenbergMarquardt(),
                            reg.GlobalOptimizationConvergenceCriteria(), option)
    return pg
```

- [ ] **Step 4: Rodar e ver passar**

Run: `.venv/bin/python -m pytest tests/test_registration.py -v`
Expected: `3 passed` (primeira execução baixa o BunnyMesh)

- [ ] **Step 5: Commit**

```bash
git add pc-pipeline/rsmapper/registration.py pc-pipeline/tests/test_registration.py
git commit -m "feat: registro global de fragmentos com loop closure"
```

---

### Task 7: Integração TSDF e exportação da malha

**Files:**
- Create: `pc-pipeline/rsmapper/integrate.py`
- Test: `pc-pipeline/tests/test_integrate.py`

**Interfaces:**
- Consumes: `Config` (Task 2); `Dataset`, `read_rgbd` (Task 3); `fragment_ranges` (Task 5); pose graphs de fragmento (`fragment_XXX.json`, Task 5) e da cena (Task 6).
- Produces:
  - `integrate_scene(ds: Dataset, scene_pg, fragments_dir: Path, cfg: Config) -> o3d.geometry.TriangleMesh`
  - `export_mesh(mesh, out_dir: Path, cfg: Config) -> dict[str, Path]` (chaves `"ply"`, `"obj"`, `"glb"`; simplifica antes se exceder o alvo; `.glb` via trimesh para preservar cores de vértice)

- [ ] **Step 1: Escrever testes que falham**

`pc-pipeline/tests/test_integrate.py`:

```python
from pathlib import Path

import numpy as np
import open3d as o3d
import trimesh

from rsmapper.config import Config
from rsmapper.dataset import load_dataset
from rsmapper.fragments import make_fragments
from rsmapper.integrate import export_mesh, integrate_scene
from rsmapper.registration import register_fragments


def test_integrate_and_export_redwood(redwood_dataset: Path, tmp_path: Path):
    ds = load_dataset(redwood_dataset)
    cfg = Config(frames_per_fragment=5, keyframe_gap=2,
                 decimate_target_triangles=20_000)
    frags_dir = tmp_path / "fragments"
    plys = make_fragments(ds, cfg, frags_dir)
    scene_pg = register_fragments(plys, cfg)

    mesh = integrate_scene(ds, scene_pg, frags_dir, cfg)
    assert len(mesh.vertices) > 1000
    assert mesh.has_vertex_colors()

    out = export_mesh(mesh, tmp_path / "export", cfg)
    for key in ("ply", "obj", "glb"):
        assert out[key].is_file(), f"faltou {key}"

    exported = o3d.io.read_triangle_mesh(str(out["ply"]))
    assert len(exported.triangles) <= cfg.decimate_target_triangles

    tm = trimesh.load(str(out["glb"]))
    geom = list(tm.geometry.values())[0] if isinstance(tm, trimesh.Scene) else tm
    assert geom.visual.vertex_colors.shape[0] == len(geom.vertices)
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `.venv/bin/python -m pytest tests/test_integrate.py -v`
Expected: FAIL com `ModuleNotFoundError: No module named 'rsmapper.integrate'`

- [ ] **Step 3: Implementar**

`pc-pipeline/rsmapper/integrate.py`:

```python
"""Fase 3: integração TSDF da cena completa e exportação da malha."""
from pathlib import Path

import numpy as np
import open3d as o3d
import trimesh

from rsmapper.config import Config
from rsmapper.dataset import Dataset, read_rgbd
from rsmapper.fragments import fragment_ranges


def integrate_scene(ds: Dataset, scene_pg, fragments_dir: Path,
                    cfg: Config) -> o3d.geometry.TriangleMesh:
    volume = o3d.pipelines.integration.ScalableTSDFVolume(
        voxel_length=cfg.voxel_size,
        sdf_trunc=cfg.voxel_size * 4,
        color_type=o3d.pipelines.integration.TSDFVolumeColorType.RGB8)

    ranges = fragment_ranges(ds.n_frames, cfg.frames_per_fragment)
    for frag_id, (start, end) in enumerate(ranges):
        frag_pg = o3d.io.read_pose_graph(
            str(Path(fragments_dir) / f"fragment_{frag_id:03d}.json"))
        frag_pose = scene_pg.nodes[frag_id].pose
        for k in range(end - start):
            pose = frag_pose @ frag_pg.nodes[k].pose
            rgbd = read_rgbd(ds, start + k, cfg, for_odometry=False)
            volume.integrate(rgbd, ds.intrinsic, np.linalg.inv(pose))

    mesh = volume.extract_triangle_mesh()
    mesh.compute_vertex_normals()
    return mesh


def export_mesh(mesh: o3d.geometry.TriangleMesh, out_dir: Path,
                cfg: Config) -> dict[str, Path]:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    if len(mesh.triangles) > cfg.decimate_target_triangles:
        mesh = mesh.simplify_quadric_decimation(cfg.decimate_target_triangles)
        mesh.compute_vertex_normals()

    paths = {"ply": out_dir / "scene.ply", "obj": out_dir / "scene.obj",
             "glb": out_dir / "scene.glb"}
    o3d.io.write_triangle_mesh(str(paths["ply"]), mesh)
    o3d.io.write_triangle_mesh(str(paths["obj"]), mesh)

    # .glb via trimesh: exportador do Open3D não grava cores de vértice em glTF
    colors = (np.asarray(mesh.vertex_colors) * 255).astype(np.uint8)
    tm = trimesh.Trimesh(vertices=np.asarray(mesh.vertices),
                         faces=np.asarray(mesh.triangles),
                         vertex_colors=colors)
    tm.export(str(paths["glb"]))
    return paths
```

- [ ] **Step 4: Rodar e ver passar**

Run: `.venv/bin/python -m pytest tests/test_integrate.py -v`
Expected: `1 passed`

- [ ] **Step 5: Commit**

```bash
git add pc-pipeline/rsmapper/integrate.py pc-pipeline/tests/test_integrate.py
git commit -m "feat: integração TSDF e export ply/obj/glb com cores"
```

---

### Task 8: reconstruct.py (CLI ponta a ponta) + README

**Files:**
- Create: `pc-pipeline/reconstruct.py`
- Create: `pc-pipeline/README.md`
- Test: `pc-pipeline/tests/test_reconstruct_cli.py`

**Interfaces:**
- Consumes: tudo das Tasks 2–7.
- Produces: CLI `python reconstruct.py <pasta-dataset> [--config config.yaml] [-o out/]`; função `run(dataset_dir: Path, cfg: Config, out_dir: Path) -> dict[str, Path]`.

- [ ] **Step 1: Escrever teste ponta a ponta que falha**

`pc-pipeline/tests/test_reconstruct_cli.py`:

```python
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
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `.venv/bin/python -m pytest tests/test_reconstruct_cli.py -v`
Expected: FAIL com `ModuleNotFoundError: No module named 'reconstruct'`

- [ ] **Step 3: Implementar o CLI**

`pc-pipeline/reconstruct.py`:

```python
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
```

- [ ] **Step 4: Rodar todos os testes**

Run: `.venv/bin/python -m pytest -v`
Expected: todos passam (1 skipped: bag real)

- [ ] **Step 5: Escrever o README**

`pc-pipeline/README.md`:

```markdown
# pc-pipeline — reconstrução 3D de scans RealSense

Transforma um `.bag` gravado pelo app Android em malha 3D colorida.

## Instalação

    python3 -m venv .venv
    .venv/bin/pip install -r requirements.txt

## Uso

    # 1. Extrair frames do bag (inspecione color/ antes de reconstruir)
    .venv/bin/python extract.py scan_20260702_103000.bag

    # 2. Reconstruir
    .venv/bin/python reconstruct.py out/scan_20260702_103000

Saídas em `out/<scan>/reconstruction/`: `scene.glb` (Unity/VR, cores de
vértice), `scene.ply` (CloudCompare), `scene.obj`.

Parâmetros em `config.yaml` (voxel, fragmentos, simplificação).

## Plano B para scans grandes

Se a reconstrução divergir (salas muito grandes, poucos elementos
visuais), use o RTAB-Map desktop, que consome o mesmo `.bag` e usa o
IMU gravado: Source → RealSense2, apontando o arquivo do scan.

## Testes

    .venv/bin/python -m pytest -v
    # teste de extração com bag real:
    RSM_TEST_BAG=/caminho/scan.bag .venv/bin/python -m pytest -v
```

- [ ] **Step 6: Commit**

```bash
git add pc-pipeline/reconstruct.py pc-pipeline/README.md pc-pipeline/tests/test_reconstruct_cli.py
git commit -m "feat: reconstruct.py ponta a ponta + README do pipeline"
```

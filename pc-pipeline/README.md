# pc-pipeline — reconstrução 3D de scans RealSense

Transforma um `.bag` gravado pelo app Android em malha 3D colorida.

## Instalação

    python3.12 -m venv .venv
    (o open3d ainda não tem wheel para Python 3.13; use 3.10–3.12)
    .venv/bin/pip install -r requirements.txt

## Uso

    # 1. Extrair frames do bag (inspecione color/ antes de reconstruir)
    .venv/bin/python extract.py scan_20260702_103000.bag

    # 2. Reconstruir
    .venv/bin/python reconstruct.py out/scan_20260702_103000

Saídas em `out/<scan>/reconstruction/`: `scene.glb` (Unity/VR, cores de vértice), `scene.ply` (CloudCompare), `scene.obj`.

Parâmetros em `config.yaml` (voxel, fragmentos, simplificação).

## Plano B para scans grandes

Se a reconstrução divergir (salas muito grandes, poucos elementos visuais), use o RTAB-Map desktop, que consome o mesmo `.bag` e usa o IMU gravado: Source → RealSense2, apontando o arquivo do scan.

## Testes

    .venv/bin/python -m pytest -v
    # teste de extração com bag real:
    RSM_TEST_BAG=/caminho/scan.bag .venv/bin/python -m pytest -v

# RealSense Mapper

Captura RGB-D com a câmera **Intel RealSense D435i** direto no **Android** e reconstrução de um **ambiente 3D fiel** no PC.

O app grava a cena em um arquivo `.bag`; o pipeline do PC transforma esse `.bag` em uma **malha 3D colorida** (`.glb`/`.ply`/`.obj`) usando [Open3D](https://www.open3d.org/).

```
┌─────────────── Android (D435i via USB-C) ───────────────┐      ┌──────────── PC (Linux) ────────────┐
│  Preview 2D / nuvem de pontos 3D ao vivo  →  grava .bag  │  ──▶ │  process.py  →  malha 3D (scene.glb) │
│              compartilha sem fio ────────────────────────┼──────┘                                    │
└─────────────────────────────────────────────────────────┘      └────────────────────────────────────┘
```

## Como funciona (fluxo de ponta a ponta)

1. **Gravar** — conecte a D435i ao celular (USB-C), aponte para o ambiente e toque em gravar. Gera um `scan_<data>.bag`.
2. **Conferir ao vivo** — o app mostra o stream 2D e, no modo 3D, uma **nuvem de pontos colorida rotacionável** (arraste para girar) para validar o enquadramento na hora.
3. **Enviar ao PC** — ao parar a gravação, toque em **Compartilhar** para mandar o `.bag` sem fio (Drive, Quick Share, KDE Connect…).
4. **Reconstruir** — no PC, um comando converte o `.bag` em malha 3D.

## Estrutura do repositório

| Pasta | O quê |
|-------|-------|
| [`android-app/`](android-app/) | App Android (Kotlin) de captura e visualização ao vivo |
| [`pc-pipeline/`](pc-pipeline/) | Pipeline Python de reconstrução 3D (Open3D + pyrealsense2) |
| [`docs/`](docs/) | Planos e notas de projeto |

---

## App Android

Telas: **CaptureActivity** (preview ao vivo, gravação) e **ScansActivity** (lista de scans, compartilhar, apagar).

Recursos:
- Preview **2D** de um único stream, em tela cheia (retrato).
- Modo **3D** ao vivo: nuvem de pontos colorida, rotacionável pelo toque (renderização nativa do SDK).
- Gravação em `.bag` com aviso de porta **USB 2.0** e de espaço em disco.
- Atalho para **compartilhar** o `.bag` logo após gravar.

### Requisitos
- Câmera **Intel RealSense D435i** (ou outra da família; o filtro USB cobre `vendor-id 0x8086`).
- Cabo/adaptador **USB-C que transfira dados** (OTG).
- Android **8.0+** (`minSdk 26`). O app usa `targetSdk 30` de propósito — o AAR do librealsense 2.54.2 lança `SecurityException` na inicialização com `targetSdk ≥ 34` e trava ao conectar a câmera com `targetSdk ≥ 31`.

### Build e instalação
```bash
cd android-app
./gradlew assembleDebug
# instala no aparelho conectado (adb)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Ao plugar a câmera, autorize o acesso USB e a permissão de câmera quando solicitado.

> O binário `app/libs/librealsense.aar` (v2.54.2) acompanha o repositório — é a dependência nativa da câmera.

---

## Pipeline do PC

Transforma o `.bag` em malha 3D colorida. Requer Python **3.10–3.12** (o Open3D ainda não tem wheel para 3.13).

### Instalação
```bash
cd pc-pipeline
python3.12 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

### Uso — um comando (bag → malha)
```bash
source .venv/bin/activate
python process.py scan_20260705_113330.bag
#   → out/scan_20260705_113330/reconstruction/scene.glb
```

### Uso — em duas etapas (para inspecionar entre elas)
```bash
python extract.py scan_20260705_113330.bag       # → out/<nome>/ (frames RGB-D)
python reconstruct.py out/scan_20260705_113330/   # → .../reconstruction/scene.{glb,ply,obj}
```

Saídas em `out/<scan>/reconstruction/`:
- `scene.glb` — cores de vértice, ótimo para Unity/VR/visualizadores web.
- `scene.ply` — para CloudCompare/MeshLab.
- `scene.obj` — malha genérica.

Parâmetros de reconstrução (voxel TSDF, fragmentos, escala de profundidade) em [`pc-pipeline/config.yaml`](pc-pipeline/config.yaml).

### Testes
```bash
.venv/bin/python -m pytest -v
# com um bag real:
RSM_TEST_BAG=/caminho/scan.bag .venv/bin/python -m pytest -v
```

> **Plano B para ambientes grandes:** se a reconstrução divergir (salas amplas, poucos elementos visuais), o [RTAB-Map](https://introlab.github.io/rtabmap/) desktop consome o mesmo `.bag` (Source → RealSense2) e aproveita a IMU gravada.

---

## Observações

- O `.bag` gerado pelo app não é indexado no encerramento; o pipeline **reindexa automaticamente** antes de extrair (`rsmapper/bag_reindex.py`).
- A **visualização ao vivo** no app é sempre do quadro atual (nuvem de pontos instantânea). O **ambiente 3D acumulado e fiel** vem da reconstrução no PC — não há SLAM no aparelho.

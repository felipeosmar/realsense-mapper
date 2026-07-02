# RealSense Mapper — Design

**Data:** 2026-07-02
**Status:** aprovado (brainstorming concluído)

## Objetivo

Sistema em duas partes para mapear ambientes internos (salas do setor de
Automação) com uma câmera **RealSense D435i** conectada a um dispositivo
Android, gerando malhas 3D texturizadas para uso em VR e simulação
(Unity, Gazebo).

1. **App Android** — preview ao vivo + gravação bruta dos streams em `.bag`.
2. **Pipeline no PC** — scripts Python/Open3D que transformam o `.bag` em
   malha texturizada.

## Requisitos e decisões fixadas

| Tema | Decisão |
|---|---|
| Câmera | RealSense D435i (depth estéreo + IMU) |
| Conexão | USB-C 3.x OTG no dispositivo Android |
| Modo de captura | Híbrido: preview ao vivo + gravação bruta (sem SLAM no celular) |
| Formato de gravação | `.bag` nativo do librealsense (depth + color + IMU + calibração) |
| Transferência | Cópia manual (MTP via cabo USB); sem upload automático |
| Pós-processamento | Pipeline completo incluso no projeto (Python + Open3D) |
| Saída final | Malha texturizada `.glb` (VR/Unity), mais `.ply` e `.obj` |
| SDK Android | AAR oficial `com.intel.realsense:librealsense` (wrapper Java) |

**Racional das escolhas principais:**

- Gravar bruto no celular e reconstruir no PC mantém o app simples e
  permite reprocessar os mesmos scans quando o pipeline evoluir.
- O AAR oficial cuida de permissão USB, enumeração e gravação `.bag`
  com API Java — evita build NDK do librealsense do fonte.
- Open3D lê `.bag` diretamente e tem pipeline de reconstrução com
  loop closure entre fragmentos, suficiente para salas. RTAB-Map fica
  documentado como plano B para ambientes grandes (usa o IMU gravado).

## Estrutura do repositório

```
realsense-mapper/
├── android-app/     # App Kotlin (projeto Android Studio)
├── pc-pipeline/     # Scripts Python + Open3D
└── docs/            # Specs, guia de uso, roteiro de scan
```

## App Android

**Stack:** Kotlin, Views clássicas (XML) — o preview usa o
`GLRsSurfaceView` do wrapper, que é uma View tradicional; Compose só
adicionaria adaptação sem ganho. minSdk 26.

### Telas

1. **Captura**
   - Preview lado a lado: depth colorizado + RGB.
   - Botão Gravar/Parar, cronômetro, tamanho do arquivo em tempo real.
   - Indicador de status: desconectada / conectada / gravando.
   - Aviso visual se a taxa de frames cair (USB ruim ou celular lento).
2. **Scans**
   - Lista das gravações: nome, data, duração, tamanho.
   - Ações: apagar, compartilhar (share intent).

### Componente central

`RsCameraManager` — encapsula todo o ciclo de vida do librealsense:
contexto USB (`RsContext`), eventos de conexão/desconexão, configuração
de streams, iniciar/parar pipeline e gravação
(`config.enableRecordToFile`). A UI só fala com ele; nenhuma API
RealSense espalhada pelas Activities.

### Perfil de streams (gravação)

| Stream | Configuração |
|---|---|
| Depth | 848×480 @ 30 fps |
| Color | 1280×720 @ 30 fps |
| Gyro | 200 Hz |
| Accel | 250 Hz |

Estimativa: ~1–2 GB por minuto de scan. O IMU vai no bag mesmo sem uso
no pipeline atual (disponível para reprocessamento futuro).

### Armazenamento

`getExternalFilesDir()/scans/scan_AAAAMMDD_HHMMSS.bag` — visível por
MTP ao plugar no PC, sem permissões de storage especiais.

### Tratamento de erros

- **Desconexão durante gravação:** encerra o `.bag` de forma válida,
  marca o scan como interrompido e avisa o operador.
- **Espaço em disco baixo:** aviso antes de iniciar; parada automática
  da gravação com margem de segurança (limiar: 2 GB livres).
- **Porta USB 2.0 detectada:** reduz o perfil (depth 640×480 @ 15 fps,
  color 640×480 @ 15 fps) e exibe aviso persistente na tela de captura.

## Pipeline no PC

**Stack:** Python 3.10+, `open3d` (inclui leitor de `.bag` RealSense).
Instalação por `pip install -r requirements.txt` em venv.

### Etapa 1 — `extract.py scan.bag`

- Reproduz o bag, alinha depth→color frame a frame.
- Extrai para pasta de trabalho: `color/000001.jpg`,
  `depth/000001.png` (16-bit), `intrinsics.json`.
- Gera relatório: nº de frames, frames perdidos, duração.
- Etapa separada para permitir inspeção visual antes de reconstruir.

### Etapa 2 — `reconstruct.py <pasta>`

Pipeline de reconstrução Open3D em 4 fases:

1. **Fragmentos:** blocos de ~100 frames com odometria RGB-D interna.
2. **Registro global:** alinhamento entre fragmentos com detecção de
   loop closure e otimização de pose graph (corrige deriva acumulada).
3. **Integração TSDF:** fusão em volume colorido (voxel padrão 1 cm).
4. **Extração e exportação:** malha → simplificação (decimation
   configurável) → `.glb`, `.ply` e `.obj`.

### Configuração

`config.yaml` com defaults calibrados para salas: voxel size, tamanho
de fragmento, nível de simplificação. Uso típico não exige mexer.

## Testes e validação

- **App:** testes unitários da lógica de nomes/armazenamento/estado de
  gravação; roteiro de teste manual com câmera real (conectar, preview,
  gravar 30 s, desconectar no meio, verificar bag válido).
- **Pipeline:** teste de ponta a ponta com um bag curto de exemplo
  (gravado na primeira sessão com a câmera); validar que o `.glb` abre
  no Blender/Unity.
- **Critério de sucesso:** escanear uma sala andando devagar por
  ~2 min e obter malha texturizada reconhecível e utilizável em VR.

## Fora de escopo (YAGNI)

- SLAM/reconstrução em tempo real no celular.
- Upload automático para servidor.
- Suporte a outros modelos de câmera (D455, L515) — pode vir depois.
- Atualização de firmware da câmera pelo app (fazer no PC com
  `rs-fw-update`).

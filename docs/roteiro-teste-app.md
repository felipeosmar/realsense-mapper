# Roteiro de teste manual — RealSense Mapper (app)

Pré-requisitos: D435i com firmware atualizado (via `rs-fw-update` no PC), celular com USB-C 3.x, cabo USB-C↔USB-C de dados, APK debug instalado (`./gradlew :app:installDebug`).

## 1. Conexão

- [ ] Abrir o app sem câmera: status "Câmera desconectada".
- [ ] Conectar a D435i: Android pergunta permissão USB → conceder.
- [ ] Status muda para "Preview ativo" e o preview mostra depth colorizado + RGB.
- [ ] Sem aviso de USB 2.0 (se aparecer, trocar cabo/porta).

## 2. Gravação básica

- [ ] Tocar em Gravar: status "Gravando…", cronômetro e tamanho crescem.
- [ ] Gravar ~30 s varrendo a sala devagar e parar.
- [ ] Snackbar "Scan salvo: scan_<data>.bag".
- [ ] Tela de Scans lista o arquivo com tamanho > 100 MB.

## 3. Desconexão durante gravação

- [ ] Iniciar gravação e desconectar o cabo após ~10 s.
- [ ] App mostra "Gravação interrompida" e volta para "Câmera desconectada".
- [ ] Reconectar: preview volta sozinho.
- [ ] O arquivo aparece na lista de Scans (tamanho > 0).

## 4. Validação do .bag no PC

- [ ] Copiar o scan via MTP (pasta Android/data/br.senai.realsensemapper/files/scans/).
- [ ] `python extract.py scan_<data>.bag` roda sem erro e reporta frames > 0.
- [ ] Frames em out/<scan>/color/ têm imagem coerente.
- [ ] O bag do teste 3 (interrompido) também extrai sem erro.

## 5. Ponta a ponta

- [ ] Gravar ~2 min de uma sala, andando devagar, câmera sempre apontando para superfícies com textura (evitar paredes lisas de perto).
- [ ] `python reconstruct.py out/<scan>` termina e gera scene.glb.
- [ ] scene.glb abre no Blender com cores e a sala é reconhecível.

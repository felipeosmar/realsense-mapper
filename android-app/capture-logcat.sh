#!/usr/bin/env bash
#
# capture-logcat.sh — captura os logs do RealSense Mapper durante o teste com a D435i.
#
# O celular tem uma única porta USB-C, ocupada pela câmera durante o teste — então
# `adb logcat` por cabo não funciona nesse momento. Há dois caminhos:
#
#   1) adb SEM FIO (Wi-Fi): pareie enquanto o celular ainda está no cabo do PC
#      (câmera fora), depois troque o cabo para a câmera; os logs seguem via Wi-Fi.
#   2) ARQUIVO no aparelho: o app grava em .../files/logs/session_*.log. Depois do
#      teste, plugue o celular no PC e use --pull para copiar os arquivos.
#
# Uso:
#   ./capture-logcat.sh --setup-wifi   # celular no cabo USB do PC, câmera desconectada
#   ./capture-logcat.sh                # transmite o logcat filtrado (Wi-Fi ou USB)
#   ./capture-logcat.sh --pull         # copia os logs do aparelho p/ ./device-logs
#
set -euo pipefail

APP_ID="br.senai.realsensemapper"
TAG="RSMapper"          # tag única do AppLogger
PORT=5555

# --- localizar o adb ------------------------------------------------------------
if command -v adb >/dev/null 2>&1; then
  ADB="adb"
elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
  ADB="$HOME/Android/Sdk/platform-tools/adb"
else
  echo "erro: adb não encontrado (instale o platform-tools ou ajuste o PATH)." >&2
  exit 1
fi

setup_wifi() {
  echo "Pareando adb por Wi-Fi. O celular deve estar no cabo USB do PC agora."
  "$ADB" wait-for-device
  # descobre o IP do Wi-Fi (wlan0)
  local ip
  ip="$("$ADB" shell ip -f inet addr show wlan0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 | head -1)"
  if [ -z "$ip" ]; then
    ip="$("$ADB" shell ip route 2>/dev/null | awk '{print $9}' | tail -1)"
  fi
  if [ -z "$ip" ]; then
    echo "erro: não achei o IP do Wi-Fi. Conecte o celular a uma rede Wi-Fi e tente de novo." >&2
    exit 1
  fi
  "$ADB" tcpip "$PORT"
  sleep 2
  "$ADB" connect "$ip:$PORT"
  echo
  echo "OK — adb sem fio ligado em $ip:$PORT."
  echo "Agora desconecte o cabo do PC e conecte a câmera D435i no celular."
  echo "Depois rode:  ./capture-logcat.sh"
}

pull_logs() {
  mkdir -p device-logs
  echo "Copiando logs de $APP_ID para ./device-logs ..."
  if "$ADB" pull "/sdcard/Android/data/$APP_ID/files/logs" device-logs; then
    echo "OK — arquivos em ./device-logs/logs/"
  else
    echo "Falha ao copiar. O app já rodou ao menos uma vez? Caminho esperado:" >&2
    echo "  /sdcard/Android/data/$APP_ID/files/logs" >&2
    exit 1
  fi
}

stream_logs() {
  echo "Aguardando dispositivo (Wi-Fi ou USB)..."
  "$ADB" wait-for-device
  echo "Limpando o buffer e transmitindo logs. Ctrl-C para parar."
  echo "Filtro: $TAG (app) + AndroidRuntime/DEBUG/libc (crashes)."
  "$ADB" logcat -c || true
  # RSMapper: nossos eventos | AndroidRuntime: crash JVM | DEBUG+libc: crash nativo
  "$ADB" logcat -v time "$TAG:V" AndroidRuntime:E DEBUG:V libc:F "*:S"
}

case "${1:-}" in
  --setup-wifi) setup_wifi ;;
  --pull)       pull_logs ;;
  "")           stream_logs ;;
  -h|--help)    grep '^#' "$0" | sed 's/^# \{0,1\}//' ;;
  *)            echo "Uso: $0 [--setup-wifi | --pull]" >&2; exit 1 ;;
esac

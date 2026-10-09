#!/usr/bin/env bash
# Installs the Jarvis voice server on Ubuntu (22.04/24.04) as a systemd service.
#   sudo ./install.sh            Whisper + Edge voices (+ Piper available)
#   sudo ./install.sh --piper    also download the Polish Piper voices (offline voice)
#   sudo ./install.sh --xtts     also install XTTS for your own cloned voice (big download, GPU recommended)
#   sudo ./install.sh --gpu      also install the CUDA libraries Whisper needs on an NVIDIA GPU
set -euo pipefail

DIR=/opt/jarvis-voice
HERE="$(cd "$(dirname "$0")" && pwd)"
WANT_PIPER=0; WANT_XTTS=0; WANT_GPU=0
for arg in "$@"; do
  case "$arg" in
    --piper) WANT_PIPER=1 ;;
    --xtts) WANT_XTTS=1 ;;
    --gpu) WANT_GPU=1 ;;
    *) echo "unknown option $arg"; exit 1 ;;
  esac
done
[ "$(id -u)" -eq 0 ] || { echo "run with sudo"; exit 1; }

echo "== system packages"
apt-get update -qq
apt-get install -y -qq python3 python3-venv python3-pip ffmpeg curl >/dev/null

echo "== user and folders"
id jarvis-voice >/dev/null 2>&1 || useradd --system --home-dir "$DIR" --shell /usr/sbin/nologin jarvis-voice
mkdir -p "$DIR/voices" "$DIR/cache"
install -m 0644 "$HERE/server.py" "$DIR/server.py"

echo "== python environment (this can take a few minutes)"
[ -x "$DIR/venv/bin/python" ] || python3 -m venv "$DIR/venv"
"$DIR/venv/bin/pip" install -q --upgrade pip
"$DIR/venv/bin/pip" install -q fastapi "uvicorn[standard]" python-multipart faster-whisper edge-tts piper-tts
if [ "$WANT_GPU" -eq 1 ]; then
  "$DIR/venv/bin/pip" install -q nvidia-cublas-cu12 "nvidia-cudnn-cu12==9.*"
fi
if [ "$WANT_XTTS" -eq 1 ]; then
  "$DIR/venv/bin/pip" install -q coqui-tts
fi
if [ "$WANT_PIPER" -eq 1 ]; then
  for voice in pl_PL-darkman-medium pl_PL-gosia-medium; do
    "$DIR/venv/bin/python" -m piper.download_voices --download-dir "$DIR/voices" "$voice" || echo "could not download $voice"
  done
fi

echo "== service"
[ -f /etc/jarvis-voice.env ] || install -m 0644 "$HERE/jarvis-voice.env" /etc/jarvis-voice.env
install -m 0644 "$HERE/jarvis-voice.service" /etc/systemd/system/jarvis-voice.service
if [ "$WANT_GPU" -eq 1 ]; then
  # make the pip-installed CUDA libraries visible to Whisper
  LIBS=$("$DIR/venv/bin/python" -c 'import os, nvidia.cublas.lib, nvidia.cudnn.lib; print(os.path.dirname(nvidia.cublas.lib.__file__) + ":" + os.path.dirname(nvidia.cudnn.lib.__file__))')
  mkdir -p /etc/systemd/system/jarvis-voice.service.d
  printf '[Service]\nEnvironment=LD_LIBRARY_PATH=%s\n' "$LIBS" > /etc/systemd/system/jarvis-voice.service.d/gpu.conf
fi
chown -R jarvis-voice:jarvis-voice "$DIR"
systemctl daemon-reload
systemctl enable --now jarvis-voice
systemctl restart jarvis-voice

echo "== waiting for the server"
for i in $(seq 1 30); do
  curl -fsS http://127.0.0.1:8100/health && echo && break
  sleep 2
done
echo
echo "Done. Settings: /etc/jarvis-voice.env (then: sudo systemctl restart jarvis-voice)"
echo "Logs:     journalctl -u jarvis-voice -f"
echo "Core:     JARVIS_VOICE_STT_URL=http://127.0.0.1:8100  JARVIS_VOICE_TTS_URL=http://127.0.0.1:8100"

#!/usr/bin/env bash
# Idempotent Ubuntu CUDA build. Run as service owner; sudo is only used for missing packages.
set -euo pipefail
umask 027
VERSION=v1.1.0
COMMIT=074f1f3f730c1d17f34645d62c1859c74bedbdd8
ROOT=/opt/jarvis/engines/llamacpp
INSTALL_CUDA=${INSTALL_CUDA:-0}
. /etc/os-release
[[ "$ID" == ubuntu && ( "$VERSION_ID" == 22.04 || "$VERSION_ID" == 24.04 ) ]] || { echo 'Supported: Ubuntu 22.04/24.04 x86_64' >&2; exit 2; }
[[ $(uname -m) == x86_64 ]] || exit 2
command -v nvidia-smi >/dev/null || { echo 'Missing NVIDIA driver. Install the operator-qualified driver; this script will not replace drivers.' >&2; exit 3; }
nvidia-smi --query-gpu=name,driver_version,memory.total --format=csv
missing=()
command -v git >/dev/null || missing+=(git)
command -v cmake >/dev/null || missing+=(cmake)
command -v gcc >/dev/null || missing+=(build-essential)
command -v g++ >/dev/null || missing+=(build-essential)
command -v make >/dev/null || missing+=(build-essential)
command -v flock >/dev/null || missing+=(util-linux)
command -v curl >/dev/null || missing+=(curl)
command -v python3 >/dev/null || missing+=(python3)
if ((${#missing[@]})); then sudo -n apt-get update; sudo -n apt-get install -y --no-install-recommends "${missing[@]}" ca-certificates; fi
if ! command -v nvcc >/dev/null && [[ -x /usr/local/cuda-12.6/bin/nvcc ]]; then export PATH=/usr/local/cuda-12.6/bin:$PATH; fi
if ! command -v nvcc >/dev/null; then
 [[ "$INSTALL_CUDA" == 1 ]] || { echo 'Missing CUDA Toolkit. Rerun INSTALL_CUDA=1 to install NVIDIA cuda-toolkit-12-6 (no driver package), or provide nvcc in PATH.' >&2; exit 4; }
 distro=ubuntu${VERSION_ID//./}
 tmp=$(mktemp -d)
 trap 'rm -f -- "$tmp/cuda-keyring.deb"; rmdir -- "$tmp"' EXIT
 curl --fail --location --proto '=https' --tlsv1.2 "https://developer.download.nvidia.com/compute/cuda/repos/$distro/x86_64/cuda-keyring_1.1-1_all.deb" -o "$tmp/cuda-keyring.deb"
 sudo -n dpkg -i "$tmp/cuda-keyring.deb"
 sudo -n apt-get update
 # toolkit-only metapackage deliberately excludes cuda-drivers.
 sudo -n apt-get install -y --no-install-recommends cuda-toolkit-12-6
 export PATH=/usr/local/cuda-12.6/bin:$PATH
fi
nvcc --version
python3 -c 'import re,subprocess; v=re.search(r"release (\d+)\.(\d+)",subprocess.check_output(["nvcc","--version"],text=True)); assert v and int(v.group(1)) >= 12, "CUDA Toolkit >=12 required; provide a qualified toolkit in PATH without replacing drivers"'
# New llama.cpp may require a newer CMake than Ubuntu's default. Use official Kitware Python wheel in an isolated venv.
if ! python3 -c 'import subprocess,re; v=re.search(r"(\d+)\.(\d+)",subprocess.check_output(["cmake","--version"],text=True)); assert tuple(map(int,v.groups())) >= (3,28)' ; then
 sudo -n apt-get install -y --no-install-recommends python3-venv
 mkdir -p "$ROOT"
 python3 -m venv "$ROOT/build-tools"
 "$ROOT/build-tools/bin/pip" install --disable-pip-version-check cmake==3.31.6
 export PATH="$ROOT/build-tools/bin:$PATH"
fi
mkdir -p "$ROOT" /opt/jarvis/models/llamacpp
exec 9>"$ROOT/install.lock"
flock -n 9 || { echo 'Another installation is running' >&2; exit 5; }
DEST="$ROOT/$COMMIT"
if [[ -x "$DEST/bin/llama-server" && -f "$DEST/installation.json" ]]; then
 "$DEST/bin/llama-server" --version
 if [[ ! -e "$ROOT/current" && ! -L "$ROOT/current" ]]; then ln -sfnT "$DEST" "$ROOT/current.new"; mv -Tf "$ROOT/current.new" "$ROOT/current"; fi
 echo "Already installed: $DEST (existing current selection left unchanged)"
 exit 0
fi
SRC="$ROOT/source-$COMMIT"
if [[ ! -d "$SRC/.git" ]]; then git clone --no-checkout https://github.com/ggml-org/llama.cpp.git "$SRC"; fi
git -C "$SRC" fetch --depth 1 origin "$COMMIT"
git -C "$SRC" checkout --detach "$COMMIT"
[[ $(git -C "$SRC" rev-parse HEAD) == "$COMMIT" ]]
cmake -S "$SRC" -B "$SRC/build-cuda" -DCMAKE_BUILD_TYPE=Release -DGGML_CUDA=ON -DCMAKE_CUDA_ARCHITECTURES=89 -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DCMAKE_INSTALL_PREFIX="$DEST" -DCMAKE_INSTALL_RPATH='$ORIGIN/../lib'
cmake --build "$SRC/build-cuda" --target llama-server -j 4
# Preserve the colocated libraries and compiled-in relative runtime lookup used by upstream build outputs.
mkdir -p "$DEST/bin"
cp -a "$SRC/build-cuda/bin/." "$DEST/bin/"
"$DEST/bin/llama-server" --version > "$DEST/version.txt" 2>&1
"$DEST/bin/llama-server" --help > "$DEST/help.txt" 2>&1
"$DEST/bin/llama-server" --list-devices > "$DEST/devices.txt" 2>&1
# Fail before publishing installation if CUDA device discovery is absent.
grep -qi CUDA "$DEST/devices.txt" || { echo 'CUDA device discovery failed; inspect devices.txt' >&2; exit 6; }
python3 - "$DEST" "$VERSION" "$COMMIT" <<'PY'
import json,pathlib,sys,datetime,subprocess,hashlib
p=pathlib.Path(sys.argv[1]);binary=p/'bin/llama-server'
(p/'installation.json').write_text(json.dumps(dict(version=sys.argv[2],commit=sys.argv[3],installedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),cudaToolkit=subprocess.check_output(['nvcc','--version'],text=True),cmake=subprocess.check_output(['cmake','--version'],text=True),driver=subprocess.check_output(['nvidia-smi','--query-gpu=driver_version','--format=csv,noheader'],text=True),gpuGenerationVerified=False),indent=2))
PY
if [[ -e "$ROOT/current" || -L "$ROOT/current" ]]; then
 [[ -L "$ROOT/current" ]] || { echo 'Refusing to replace a non-symlink current path' >&2; exit 7; }
 readlink "$ROOT/current" > "$ROOT/previous-target.txt"
fi
ln -sfnT "$DEST" "$ROOT/current.new"
mv -Tf "$ROOT/current.new" "$ROOT/current"
echo "Installed $VERSION $COMMIT. No model loaded; no services restarted. GPU generation remains unverified."

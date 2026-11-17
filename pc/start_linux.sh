#!/usr/bin/env bash
# Phone Audio Bridge — PC side launcher (Linux)
# First run creates .venv and installs dependencies.
set -euo pipefail
cd "$(dirname "$0")"

# PortAudio system library is required by sounddevice.
if ! ldconfig -p 2>/dev/null | grep -q libportaudio; then
    echo "[提示] 未检测到 libportaudio，sounddevice 可能无法加载。"
    echo "       Debian/Ubuntu: sudo apt install libportaudio2"
    echo "       Fedora:        sudo dnf install portaudio"
    echo "       Arch:          sudo pacman -S portaudio"
fi

if [ ! -d .venv ]; then
    echo "[setup] 创建虚拟环境 .venv …"
    python3 -m venv .venv || {
        echo "[error] venv 创建失败。Debian/Ubuntu 需先装：sudo apt install python3-venv"
        exit 1
    }
fi

# shellcheck disable=SC1091
source .venv/bin/activate
pip install -q --upgrade pip
pip install -q -r requirements.txt

echo "============================================================"
echo " Phone Audio Bridge — PC 端（Linux）"
echo " 首次使用先运行一次： ./setup_linux_virtual.sh"
echo " 它会创建虚拟麦克风与虚拟扬声器（PipeWire/PulseAudio）。"
echo "============================================================"
exec python phone_audio_pc.py "$@"

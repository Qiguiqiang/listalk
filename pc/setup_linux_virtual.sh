#!/usr/bin/env bash
# ============================================================================
# Phone Audio Bridge — Linux virtual audio devices setup
#
# Creates, via pactl (works on BOTH PipeWire's pipewire-pulse and classic
# PulseAudio — no separate code path):
#
#   1) Virtual MICROPHONE other apps can record from:
#        null-sink  "PhoneMic"            <- the PC app plays phone-mic audio here
#        remap-src  "PhoneMicSource"      <- apps pick "PhoneAudioBridge-Mic"
#      (master = PhoneMic.monitor)
#
#   2) Virtual SPEAKER sink to capture PC sound for the phone:
#        null-sink  "PhoneSpeaker"        <- apps play here to send sound to phone
#        its monitor "PhoneSpeaker.monitor" is what the PC app captures
#      Optionally looped back to your real speakers (--with-loopback, default
#      ON) so you still hear the sound locally.
#
# Usage:
#   ./setup_linux_virtual.sh              # create (idempotent)
#   ./setup_linux_virtual.sh --no-loopback
#   ./setup_linux_virtual.sh --teardown   # remove everything it created
#
# Notes:
# - Module IDs are saved to .linux_virtual_modules so teardown is exact.
# - pw-cli equivalent commands are listed at the bottom of this file for
#   pure-PipeWire setups without pipewire-pulse.
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")"
STATE_FILE=".linux_virtual_modules"

SINK_MIC="PhoneMic"
SRC_MIC="PhoneMicSource"
SINK_SPK="PhoneSpeaker"
LOOPBACK=1

for arg in "$@"; do
    case "$arg" in
        --teardown) TEARDOWN=1 ;;
        --no-loopback) LOOPBACK=0 ;;
        *) echo "未知参数: $arg（可用: --teardown, --no-loopback）"; exit 2 ;;
    esac
done
TEARDOWN="${TEARDOWN:-0}"

command -v pactl >/dev/null || {
    echo "[error] 找不到 pactl。PipeWire: 装 pipewire-pulse；PulseAudio: 装 pulseaudio-utils。"
    exit 1
}

if [ "$TEARDOWN" = "1" ]; then
    if [ -f "$STATE_FILE" ]; then
        # unload in reverse creation order
        tac "$STATE_FILE" | while read -r mid; do
            [ -n "$mid" ] && pactl unload-module "$mid" 2>/dev/null && echo "已卸载模块 $mid"
        done
        rm -f "$STATE_FILE"
        echo "虚拟设备已移除。"
    else
        echo "没有找到 $STATE_FILE ，按名字兜底清理…"
        pactl list short modules | grep -E "sink_name=($SINK_MIC|$SINK_SPK)|source_name=$SRC_MIC|source=$SINK_SPK.monitor" \
            | awk '{print $1}' | sort -rn | while read -r mid; do pactl unload-module "$mid" 2>/dev/null; done
        echo "清理完成。"
    fi
    exit 0
fi

# --- detect stack -----------------------------------------------------------
if pactl info 2>/dev/null | grep -qi "pipewire"; then
    echo "[检测] 音频栈: PipeWire（经 pipewire-pulse 的 pactl 接口）"
else
    echo "[检测] 音频栈: PulseAudio"
fi

# --- idempotency: drop previous instances with our names --------------------
pactl list short modules | grep -E "sink_name=($SINK_MIC|$SINK_SPK)|source_name=$SRC_MIC|source=$SINK_SPK.monitor" \
    | awk '{print $1}' | sort -rn | while read -r mid; do pactl unload-module "$mid" 2>/dev/null; done
rm -f "$STATE_FILE"

load() {
    local mid
    mid=$(pactl load-module "$@") || { echo "[error] 加载模块失败: $*"; exit 1; }
    echo "$mid" >> "$STATE_FILE"
    echo "  模块 $mid: $*"
}

echo "[1/3] 创建虚拟麦克风（其他应用把它当麦克风，PC 端把手机声音播进来）…"
load module-null-sink sink_name=$SINK_MIC \
    sink_properties="device.description=PhoneAudioBridge-Mic rate=48000 channels=1"
load module-remap-source master=$SINK_MIC.monitor source_name=$SRC_MIC \
    source_properties="device.description=PhoneAudioBridge-Mic rate=48000 channels=1"

echo "[2/3] 创建虚拟扬声器汇（应用把声音播到这里 = 发到手机）…"
load module-null-sink sink_name=$SINK_SPK \
    sink_properties="device.description=PhoneAudioBridge-Speaker rate=48000 channels=1"

if [ "$LOOPBACK" = "1" ]; then
    DEFAULT_SINK=$(pactl get-default-sink 2>/dev/null || echo "")
    if [ -n "$DEFAULT_SINK" ] && [ "$DEFAULT_SINK" != "$SINK_SPK" ] && [ "$DEFAULT_SINK" != "$SINK_MIC" ]; then
        echo "[3/3] 把虚拟扬声器回环到真实扬声器 $DEFAULT_SINK（本地也能听到）…"
        load module-loopback source=$SINK_SPK.monitor sink="$DEFAULT_SINK" latency_msec=30
    else
        echo "[3/3] 跳过回环（未识别到真实默认扬声器），可用 --no-loopback 以外的方式手动回环。"
    fi
else
    echo "[3/3] 已按 --no-loopback 跳过本地回环。"
fi

echo
echo "============================================================"
echo "完成。接下来："
echo " 1) 启动 PC 端： ./start_linux.sh"
echo "    它会自动偏好名为 PhoneAudioBridge 的虚拟设备；若列表里只有"
echo "    pulse/pipewire 聚合设备，就选它，再用下面的默认设备路由兜底。"
echo " 2) 让其他应用用手机麦克风： 在应用里选麦克风“PhoneAudioBridge-Mic”，"
echo "    或设为系统默认： pactl set-default-source $SRC_MIC"
echo " 3) 把某个应用的声音发到手机： 在 pavucontrol 的“播放”页把该应用"
echo "    输出切到“PhoneAudioBridge-Speaker”，或设系统默认输出："
echo "        pactl set-default-sink $SINK_SPK"
echo " 4) 卸载虚拟设备： ./setup_linux_virtual.sh --teardown"
echo "============================================================"
pactl list short sinks | grep -E "$SINK_MIC|$SINK_SPK" || true
pactl list short sources | grep -E "$SRC_MIC|$SINK_SPK" || true

# ----------------------------------------------------------------------------
# Pure-PipeWire alternative (no pipewire-pulse), for reference:
#   pw-cli create-node adapter '{ factory.name=support.null-audio-sink
#       node.name=PhoneMic media.class=Audio/Sink object.linger=true
#       audio.rate=48000 audio.channels=1 }'
#   # a null sink's monitor port then shows up as a capture node; use
#   # pw-link / qpwgraph to route, or set it as default source via
#   # wpctl. pactl route above is recommended: pipewire-pulse ships with
#   # virtually every desktop PipeWire install.
# ----------------------------------------------------------------------------

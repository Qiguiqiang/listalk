#!/usr/bin/env python3
"""
Phone Audio Bridge — PC side (Windows + Linux, single file).

Phone mic -> PC (played into a virtual cable so WeChat/Zoom/OBS/games can use
the phone as their microphone) and PC sound -> phone speaker, over UDP/47731.
Protocol: see ../PROTOCOL.md — 16-byte header + PCM16 10 ms frames, adaptive
sample rate (48k -> 24k -> 16k), adaptive jitter buffer (20-60 ms),
auto-discovery, auto-reconnect, live RTT/loss/jitter stats.

Usage:
    python phone_audio_pc.py                 # auto-detect devices, run
    python phone_audio_pc.py --list-devices  # show audio devices
    python phone_audio_pc.py --output-device 12 --input-device 9
    python phone_audio_pc.py --save-config   # persist chosen devices

Requires: pip install sounddevice numpy  (PortAudio; on Linux: apt install
libportaudio2). On Windows install VB-CABLE (free, vb-audio.com) so other
apps can pick the phone up as a microphone; on Linux run
./setup_linux_virtual.sh once to create PipeWire/PulseAudio virtual devices.
"""

import argparse
import hashlib
import json
import os
import platform
import secrets
import socket
import struct
import sys
import threading
import time

# --------------------------------------------------------------------------
# Protocol (mirrors the Android app's Protocol.kt — keep in sync!)
# --------------------------------------------------------------------------

PORT = 47731
MAGIC = 0x5041
HEADER = struct.Struct("!HBBIQ")  # magic, type, flags, seq, timestamp_us
assert HEADER.size == 16

TYPE_AUDIO = 0
TYPE_DISCOVER = 1
TYPE_DISCOVER_RESP = 2
TYPE_HELLO = 3
TYPE_HELLO_ACK = 4
TYPE_PING = 5
TYPE_PONG = 6
TYPE_RATE_REQ = 7
TYPE_RATE_ACK = 8
TYPE_BYE = 9
TYPE_SET_VOLUME = 10  # PC -> phone: JSON {"speakerVolume":0.0..1.0} per-device speaker gain
TYPE_BEACON = 11  # PC presence beacon, 1/s broadcast: {name, os, deviceId, tokenHash}
# Camera relay (wave 4, plan §B.2): negotiated via HELLO/HELLO_ACK
# "caps":["video1"] — PROTO_VERSION stays 2.
TYPE_VIDEO_CONFIG = 12  # phone -> PC: JSON {codec,width,height,fps,rotation,mirrored,lensFacing,csd}
TYPE_VIDEO_DATA = 13    # phone -> PC: 10-byte frag subheader + H.264 AU chunk
TYPE_VIDEO_CTRL = 14    # both ways: JSON {action:"start"/"stop"/"state", ...}
TYPE_VIDEO_KEYFRAME_REQ = 15  # PC -> phone: empty, request an IDR (>=500 ms apart)
CAP_VIDEO1 = "video1"

CODEC_PCM16 = 0
CODEC_OPUS_RESERVED = 1  # v2 only

RATE_48K = 0
RATE_24K = 1
RATE_16K = 2
RATE_HZ = {RATE_48K: 48000, RATE_24K: 24000, RATE_16K: 16000}
HZ_RATE = {v: k for k, v in RATE_HZ.items()}


def make_flags(codec=CODEC_PCM16, rate_code=RATE_48K):
    return (codec & 0x03) | ((rate_code & 0x07) << 2)


def pack_packet(ptype, seq=0, timestamp_us=0, payload=b"", codec=CODEC_PCM16, rate_code=RATE_48K):
    return HEADER.pack(MAGIC, ptype, make_flags(codec, rate_code), seq, timestamp_us) + payload


def unpack_packet(data):
    """Return (header_dict, payload) or None if not one of our packets."""
    if len(data) < HEADER.size:
        return None
    magic, ptype, flags, seq, ts = HEADER.unpack_from(data, 0)
    if magic != MAGIC:
        return None
    header = {
        "type": ptype,
        "flags": flags,
        "codec": flags & 0x03,
        "rate_code": (flags >> 2) & 0x07,
        "seq": seq,
        "timestamp_us": ts,
    }
    return header, data[HEADER.size:]


def now_us():
    return time.monotonic_ns() // 1000


# --------------------------------------------------------------------------
# Identity / pairing (mirrors the Android registry concept)
# --------------------------------------------------------------------------

def token_hash(token):
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def verify_code_for(token, phone_device_id):
    """6-digit numeric-comparison code shown on BOTH screens during pairing."""
    n = int(hashlib.sha256(f"{token}:{phone_device_id}".encode("utf-8")).hexdigest(), 16)
    return f"{n % 900000 + 100000:06d}"


def decide_hello_auth(paired_phones, phone_id, token_hash_given, pair_code,
                      pc_token, pc_pair_code, pc_pair_code_valid, tofu_open):
    """
    Pure pairing/auth policy for an incoming HELLO. Returns (action, issue_token):
      ("auth", False)  already-paired phone presenting the right token hash
      ("pair", True)   new pairing granted (pair code / QR token / first-use window)
      ("reject", False)
    """
    if not phone_id:
        return "reject", False
    expected = token_hash(pc_token)
    if phone_id in paired_phones and token_hash_given and token_hash_given == expected:
        return "auth", False
    if pair_code and pc_pair_code_valid and pair_code == pc_pair_code:
        return "pair", True
    if token_hash_given and token_hash_given == expected:
        # QR flow: the phone scanned our QR and already holds the token,
        # but this PC has not registered the phone yet.
        return "pair", True
    if tofu_open and not paired_phones:
        return "pair", True
    return "reject", False


def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "127.0.0.1"


def pairing_payload(device_id, name, os_name, ip, port, token):
    """Exact string encoded into the pairing QR code (phone validates app/deviceId/token)."""
    return json.dumps({
        "app": "AirBridge", "v": 1, "deviceId": device_id, "name": name,
        "os": os_name, "ip": ip, "port": port, "token": token,
    })


def ensure_identity(cfg):
    """Create/persist this PC's deviceId + pairing token + phone registry."""
    changed = False
    if not cfg.get("device_id"):
        cfg["device_id"] = secrets.token_hex(6)
        changed = True
    if not cfg.get("token"):
        cfg["token"] = secrets.token_hex(16)
        changed = True
    if "paired_phones" not in cfg:
        cfg["paired_phones"] = {}
        changed = True
    return changed


def ensure_pair_code(cfg):
    """6-digit pairing code, rotated every 10 minutes (UI_SPEC_MULTIDEVICE §4.4)."""
    now = time.time()
    if not cfg.get("pair_code") or now > float(cfg.get("pair_code_expires", 0)):
        cfg["pair_code"] = f"{secrets.randbelow(900000) + 100000:06d}"
        cfg["pair_code_expires"] = now + 600
        return True
    return False


def frame_samples(rate_code):
    return RATE_HZ[rate_code] // 100  # 10 ms


def frame_bytes(rate_code):
    return frame_samples(rate_code) * 2  # PCM16 mono


def decide_rate(current_code, loss_pct, jitter_ms, good_windows):
    """
    Pure adaptation policy (mirrored on Android). Returns
    (action, new_code, good_windows) with action in {"down", "up", "none"}.
    """
    if (loss_pct > 5.0 or jitter_ms > 15.0) and current_code < RATE_16K:
        return "down", current_code + 1, 0
    if loss_pct < 1.0 and jitter_ms < 5.0 and current_code > RATE_48K:
        good_windows += 1
        if good_windows >= 15:  # ~30 s of clean link before stepping back up
            return "up", current_code - 1, 0
        return "none", current_code, good_windows
    return "none", current_code, 0


# --------------------------------------------------------------------------
# Jitter buffer (adaptive 20-60 ms target depth)
# --------------------------------------------------------------------------

class JitterBuffer:
    def __init__(self):
        self._buf = {}
        self._next_seq = None
        self._primed = False
        self.target_ms = 30
        self.underruns = 0
        self._lock = threading.Lock()

    def reset(self):
        with self._lock:
            self._buf.clear()
            self._next_seq = None
            self._primed = False

    def push(self, seq, payload):
        with self._lock:
            if self._next_seq is None or seq < self._next_seq - 200:
                self._next_seq = seq
                self._primed = False
                self._buf.clear()
            if seq >= (self._next_seq or 0):
                self._buf[seq] = payload
                while len(self._buf) > 20:  # cap ~200 ms
                    oldest = min(self._buf)
                    del self._buf[oldest]
                    self._next_seq = min(self._buf) if self._buf else oldest + 1

    def pop(self):
        """Next frame in sequence order, or None (not primed / concealed loss)."""
        with self._lock:
            if not self._primed:
                target_frames = max(2, min(6, self.target_ms // 10))
                if len(self._buf) >= target_frames:
                    self._next_seq = min(self._buf)
                    self._primed = True
                else:
                    return None
            payload = self._buf.pop(self._next_seq, None)
            self._next_seq += 1
            if payload is None:
                self.underruns += 1
            return payload

    def __len__(self):
        with self._lock:
            return len(self._buf)


# --------------------------------------------------------------------------
# Link statistics
# --------------------------------------------------------------------------

class LinkStats:
    def __init__(self):
        self.rx = 0
        self.tx = 0
        self.lost = 0
        self.rtt_ms = -1.0
        self.jitter_ms = 0.0
        self.loss_pct = 0.0
        self.level_in = 0.0   # PC capture level (PC sound -> phone)
        self.level_out = 0.0  # phone mic level (played to output device)
        self._last_seq = -1
        self._prev_transit = None
        self._jitter_us = 0.0
        self._win_rx = 0
        self._win_lost = 0
        self._win_start = time.monotonic()

    def on_audio_rx(self, seq, sender_ts_us):
        if self._last_seq >= 0:
            gap = seq - self._last_seq
            if gap > 1:
                self.lost += gap - 1
                self._win_lost += gap - 1
        if self._last_seq < 0 or seq > self._last_seq:
            self._last_seq = seq
        transit = now_us() - sender_ts_us
        if self._prev_transit is not None:
            d = abs(transit - self._prev_transit)
            self._jitter_us += (d - self._jitter_us) / 16.0
        self._prev_transit = transit
        self.rx += 1
        self._win_rx += 1
        self.jitter_ms = self._jitter_us / 1000.0

    def on_pong(self, sent_ts_us):
        rtt = (now_us() - sent_ts_us) / 1000.0
        if 0.0 <= rtt <= 5000.0:
            self.rtt_ms = rtt if self.rtt_ms < 0 else self.rtt_ms * 0.7 + rtt * 0.3

    def roll_window(self):
        """Call ~every 2 s. Returns True if a fresh loss figure is available."""
        now = time.monotonic()
        if now - self._win_start >= 2.0:
            total = self._win_rx + self._win_lost
            self.loss_pct = (self._win_lost * 100.0 / total) if total else 0.0
            self._win_rx = 0
            self._win_lost = 0
            self._win_start = now
            return True
        return False

    def reset_sequence(self):
        self._last_seq = -1
        self._prev_transit = None


# --------------------------------------------------------------------------
# Audio device auto-detection (virtual cables preferred)
# --------------------------------------------------------------------------

def output_device_score(name):
    """Output device = where phone-mic audio is played. Best: VB-CABLE input
    (its recording side then appears as a microphone to other apps)."""
    n = name.lower()
    score = 0
    if "cable input" in n:
        score = max(score, 100)
    if "blackhole" in n:
        score = max(score, 95)
    if "vb-audio" in n or "vb-cable" in n or "cable" in n:
        score = max(score, 85)
    if "voicemeeter" in n:
        score = max(score, 80)
    if "phoneaudiobridge" in n or "phone-mic" in n or "phone_mic" in n:
        score = max(score, 100)  # our Linux virtual sink (setup_linux_virtual.sh)
    if "monitor" in n:
        score = min(score, 10)  # monitors are capture-side, avoid for output
    return score


def input_device_score(name):
    """Input device = PC sound captured and sent to the phone. Best: a cable
    output / monitor / stereo-mix (loopback of what the PC plays)."""
    n = name.lower()
    score = 0
    if "cable output" in n:
        score = max(score, 100)
    if "phoneaudiobridge" in n or "phone-speaker" in n or "phone_speaker" in n:
        score = max(score, 100)  # our Linux virtual source
    if "monitor" in n:
        score = max(score, 95)
    if "blackhole" in n:
        score = max(score, 95)
    if "stereo mix" in n or "立体声混音" in n or "what u hear" in n:
        score = max(score, 90)
    if "loopback" in n:
        score = max(score, 85)
    if "vb-audio" in n or "vb-cable" in n or "cable" in n:
        score = max(score, 80)
    if "voicemeeter" in n:
        score = max(score, 75)
    return score


def list_devices(sd):
    devices = sd.query_devices()
    rows = []
    for i, d in enumerate(devices):
        rows.append({
            "index": i,
            "name": d["name"],
            "hostapi": sd.query_hostapis(d["hostapi"])["name"],
            "max_input": d["max_input_channels"],
            "max_output": d["max_output_channels"],
            "default_samplerate": d["default_samplerate"],
        })
    return rows


def pick_device(sd, kind, explicit_index=None):
    """kind: 'input' or 'output'. Returns (index, name, how)."""
    devices = list_devices(sd)
    if explicit_index is not None:
        for d in devices:
            if d["index"] == explicit_index:
                return explicit_index, d["name"], "配置指定"
        raise SystemExit(f"设备索引 {explicit_index} 不存在，先用 --list-devices 查看")
    score_fn = input_device_score if kind == "input" else output_device_score
    chan_key = "max_input" if kind == "input" else "max_output"
    best = None
    for d in devices:
        if d[chan_key] <= 0:
            continue
        s = score_fn(d["name"])
        if s > 0 and (best is None or s > best[0]):
            best = (s, d)
    if best is not None:
        return best[1]["index"], best[1]["name"], f"自动识别虚拟设备(score={best[0]})"
    default_idx = sd.default.device[0] if kind == "input" else sd.default.device[1]
    for d in devices:
        if d["index"] == default_idx:
            return default_idx, d["name"], "系统默认设备"
    raise SystemExit("没有可用的音频设备")


# --------------------------------------------------------------------------
# Bridge engine
# --------------------------------------------------------------------------

class VideoReassembler:
    """In-order H.264 access-unit reassembler for VIDEO_DATA (plan §B.2).

    Mirror of the Kotlin VideoReassembler (VideoPackets.kt): fragments
    are collected per frameId, AUs are emitted strictly in frameId
    order, a frame incomplete after 200 ms (or missing entirely while
    later frames complete) is dropped whole and raises keyframe_needed.
    The clock is injected (seconds, monotonic) so tests can drive time.
    """

    DEADLINE_S = 0.200
    MAX_FRAGS = 2048
    MAX_IN_FLIGHT = 4
    MAX_AU_BYTES = 2_000_000
    SUBHEADER = struct.Struct("!IHHBB")  # frameId, fragIndex, fragCount, flags, codec
    FLAG_KEYFRAME = 0x01
    FLAG_FRAME_END = 0x02

    def __init__(self):
        self.dropped_frames = 0
        self.completed_frames = 0
        self.keyframe_needed = False
        self._partials = {}   # frameId -> [fragCount, firstSeenS, {idx: bytes}, nbytes]
        self._completed = {}  # frameId -> bytes
        self._next = None     # next frameId to emit
        self._gap_since = -1.0

    def reset(self):
        self.__init__()

    def offer_payload(self, payload, now_s):
        """Feed one VIDEO_DATA payload (subheader + chunk). Returns the
        list of access units that became emittable, in frameId order."""
        if len(payload) < self.SUBHEADER.size:
            return self.expire(now_s)
        frame_id, frag_index, frag_count, _flags, _codec = self.SUBHEADER.unpack_from(payload)
        return self.offer(frame_id, frag_index, frag_count, payload[self.SUBHEADER.size:], now_s)

    def offer(self, frame_id, frag_index, frag_count, data, now_s):
        out = self._advance(now_s)
        if frag_count <= 0 or frag_count > self.MAX_FRAGS:
            return out
        if frag_index < 0 or frag_index >= frag_count:
            return out
        if self._next is None:
            self._next = frame_id
        elif frame_id < self._next:
            return out  # late / duplicate of an emitted or dropped frame
        if frame_id not in self._completed:
            p = self._partials.get(frame_id)
            if p is None:
                p = [frag_count, now_s, {}, 0]
                self._partials[frame_id] = p
            if p[0] != frag_count:
                return out  # inconsistent fragment: ignore
            if frag_index not in p[2]:
                if p[3] + len(data) > self.MAX_AU_BYTES:
                    return out  # poisoned frame: let the deadline reap it
                p[2][frag_index] = data
                p[3] += len(data)
                if len(p[2]) == p[0]:
                    del self._partials[frame_id]
                    self._completed[frame_id] = b"".join(p[2][i] for i in range(p[0]))
            self._evict_overflow()
        out += self._advance(now_s)
        self._note_gap(now_s)
        return out

    def expire(self, now_s):
        out = self._advance(now_s)
        self._note_gap(now_s)
        return out

    def _note_drop(self):
        self.dropped_frames += 1
        self.keyframe_needed = True

    def _advance(self, now_s):
        out = []
        while self._next is not None:
            ne = self._next
            done = self._completed.pop(ne, None)
            if done is not None:
                out.append(done)
                self.completed_frames += 1
                self._next = ne + 1
                self._gap_since = -1.0
                continue
            p = self._partials.get(ne)
            if p is not None:
                if now_s - p[1] > self.DEADLINE_S:
                    del self._partials[ne]
                    self._note_drop()
                    self._next = ne + 1
                    self._gap_since = -1.0
                    continue
                break  # blocking frame still within its deadline
            has_higher = any(k > ne for k in self._partials) or any(k > ne for k in self._completed)
            if not has_higher:
                break
            if self._gap_since < 0:
                self._gap_since = now_s
                break
            if now_s - self._gap_since > self.DEADLINE_S:
                self._note_drop()
                self._next = ne + 1
                self._gap_since = -1.0
                continue
            break
        stale = [k for k, p in self._partials.items()
                 if k != self._next and now_s - p[1] > self.DEADLINE_S]
        for k in stale:
            del self._partials[k]
            self._note_drop()
        return out

    def _note_gap(self, now_s):
        ne = self._next
        if ne is None or self._gap_since >= 0:
            return
        if ne in self._partials or ne in self._completed:
            return
        if any(k > ne for k in self._partials) or any(k > ne for k in self._completed):
            self._gap_since = now_s

    def _evict_overflow(self):
        while len(self._partials) > self.MAX_IN_FLIGHT:
            oldest = min(self._partials)
            del self._partials[oldest]
            self._note_drop()
            if self._next == oldest:
                rest = list(self._partials) + list(self._completed)
                self._next = min(rest) if rest else oldest + 1
                self._gap_since = -1.0


class Bridge:
    def __init__(self, port=PORT, input_device=None, output_device=None,
                 device_id="", token="", paired_phones=None, pair_code_fn=None,
                 on_state_save=None):
        self.port = port
        self.input_device = input_device
        self.output_device = output_device
        # pairing identity (see ensure_identity / ensure_pair_code)
        self.device_id = device_id
        self.token = token
        self.paired_phones = paired_phones if paired_phones is not None else {}
        self.pair_code_fn = pair_code_fn or (lambda: ("", False))
        self.on_state_save = on_state_save or (lambda: None)
        self.started_at = 0.0
        self.last_verify_code = ""
        self.last_pair_phone = ""
        self._last_reject_log = 0.0
        self.sock = None
        self.peer = None            # (ip, port) of the phone
        self.peer_name = "?"
        self.connected = False
        self.pc_to_phone = True     # channel toggles (GUI checkboxes / defaults on)
        self.phone_to_pc = True
        self.rate_code = RATE_48K
        self.stats = LinkStats()
        self.jb = JitterBuffer()
        self.running = False
        self._tx_seq = 0
        self._ping_seq = 0
        self._last_rx = 0.0
        self._pending_rate = -1
        self._pending_since = 0.0
        self._good_windows = 0
        self._in_built_rate = -1
        self._out_built_rate = -1
        self._lock = threading.Lock()
        # camera relay (wave 4): negotiated caps, stream state, and the
        # reassembled-Annex-B dump (video-capture.h264 in the cwd) that
        # makes this reference peer usable as a real-LAN verification end.
        self.phone_video_capable = False
        self.video_config = None
        self.video_reasm = VideoReassembler()
        self.video_packets = 0
        self.video_aus = 0
        self._video_dump = None
        self._last_keyframe_req = 0.0

    # -- socket helpers ----------------------------------------------------
    def _send(self, ptype, payload=b"", seq=0, ts=None, rate_code=None, addr=None):
        if self.sock is None:
            return
        pkt = pack_packet(
            ptype, seq=seq,
            timestamp_us=now_us() if ts is None else ts,
            payload=payload,
            rate_code=self.rate_code if rate_code is None else rate_code,
        )
        try:
            self.sock.sendto(pkt, addr or self.peer)
        except OSError:
            pass

    def stop(self):
        """Ask run() to shut down (used by the GUI's stop button)."""
        self.running = False

    # -- main loops ----------------------------------------------------------
    def run(self):
        import sounddevice as sd  # lazy: protocol tests don't need PortAudio

        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.sock.bind(("0.0.0.0", self.port))
        self.sock.settimeout(0.5)
        self.running = True
        self.started_at = time.time()
        threading.Thread(target=self._beacon_loop, name="beacon", daemon=True).start()

        in_idx, in_name, in_how = pick_device(sd, "input", self.input_device)
        out_idx, out_name, out_how = pick_device(sd, "output", self.output_device)
        self.input_device, self.output_device = in_idx, out_idx
        print(f"[设备] 电脑声音采集（发给手机）: #{in_idx} {in_name}（{in_how}）")
        print(f"[设备] 手机麦克风播放（给其他应用当麦）: #{out_idx} {out_name}（{out_how}）")
        print(f"[监听] UDP {self.port}，等待手机连接…（手机 App 点“开始”后点此电脑）")
        print("[回声] 本桥的回声抑制在手机端完成（系统 AEC + 参考信号压制 + 啸叫保护）。"
              "若电脑这边用音箱外放、又选了电脑自己的麦克风做输入，那是电脑自身的声学回路，与本桥无关。")

        threads = [
            threading.Thread(target=self._recv_loop, name="recv", daemon=True),
            threading.Thread(target=self._capture_loop, args=(sd,), name="capture", daemon=True),
            threading.Thread(target=self._playout_loop, args=(sd,), name="playout", daemon=True),
            threading.Thread(target=self._adapt_loop, name="adapt", daemon=True),
        ]
        for t in threads:
            t.start()
        try:
            while self.running:
                time.sleep(0.5)
        except KeyboardInterrupt:
            print("\n[退出] 正在关闭…")
        finally:
            self.running = False
            if self.peer:
                self._send(TYPE_BYE)
            try:
                self.sock.close()
            except OSError:
                pass

    def _beacon_loop(self):
        """Presence: broadcast identity every 1 s so paired phones auto-connect."""
        while self.running:
            if self.sock is not None and self.device_id:
                payload = json.dumps({
                    "app": "PhoneAudioBridge", "kind": "beacon",
                    "name": socket.gethostname(), "os": platform.system(),
                    "deviceId": self.device_id,
                    "tokenHash": token_hash(self.token) if self.token else "",
                }).encode("utf-8")
                pkt = pack_packet(TYPE_BEACON, payload=payload, rate_code=self.rate_code)
                try:
                    self.sock.sendto(pkt, ("255.255.255.255", self.port))
                except OSError:
                    pass
            time.sleep(1.0)

    def _recv_loop(self):
        while self.running:
            try:
                data, addr = self.sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            parsed = unpack_packet(data)
            if parsed is None:
                continue
            h, payload = parsed
            t = h["type"]
            if t == TYPE_DISCOVER:
                resp = json.dumps({
                    "app": "PhoneAudioBridge",
                    "kind": "discover_resp",
                    "name": socket.gethostname(),
                    "os": platform.system(),
                    "deviceId": self.device_id,
                    "tokenHash": token_hash(self.token) if self.token else "",
                }).encode("utf-8")
                self._send(TYPE_DISCOVER_RESP, payload=resp, addr=addr)
            elif t == TYPE_HELLO:
                try:
                    info = json.loads(payload.decode("utf-8", "replace"))
                except (ValueError, UnicodeDecodeError):
                    info = {}
                phone_id = str(info.get("deviceId", ""))
                pair_code, pair_code_valid = self.pair_code_fn()
                tofu_open = (time.time() - self.started_at) < 120 if self.started_at else False
                action, issue_token = decide_hello_auth(
                    self.paired_phones, phone_id,
                    str(info.get("tokenHash", "")), str(info.get("pairCode", "")),
                    self.token, pair_code, pair_code_valid, tofu_open,
                )
                if action == "reject":
                    if time.monotonic() - self._last_reject_log > 10:
                        self._last_reject_log = time.monotonic()
                        print(f"[配对] 拒绝未配对手机的连接请求 ({addr[0]})——请在手机上输入本机配对码或扫码配对")
                    continue
                self.peer = addr
                self.connected = True
                self._last_rx = time.monotonic()
                self.peer_name = info.get("name", "?")
                caps = info.get("caps")
                self.phone_video_capable = isinstance(caps, list) and CAP_VIDEO1 in caps
                want = HZ_RATE.get(int(info.get("rate", 48000)))
                if want is not None and want != self.rate_code:
                    self._apply_rate(want)
                ack = {
                    "app": "PhoneAudioBridge", "kind": "hello_ack",
                    "rate": RATE_HZ[self.rate_code], "codec": "pcm16",
                    "deviceId": self.device_id,
                    "name": socket.gethostname(),
                    "os": platform.system(),
                    "caps": [CAP_VIDEO1],
                }
                if issue_token:
                    self.paired_phones[phone_id] = {
                        "name": self.peer_name, "lastSeen": time.time(),
                    }
                    self.on_state_save()
                    ack["token"] = self.token
                    ack["verifyCode"] = verify_code_for(self.token, phone_id)
                    self.last_verify_code = ack["verifyCode"]
                    self.last_pair_phone = self.peer_name
                    print(f"[配对] 新手机已配对: {self.peer_name} ({phone_id})")
                    print(f"[配对] 核对验证码: {ack['verifyCode']}（应与手机确认屏上一致）")
                else:
                    if phone_id in self.paired_phones:
                        self.paired_phones[phone_id]["lastSeen"] = time.time()
                        self.on_state_save()
                self._send(TYPE_HELLO_ACK, payload=json.dumps(ack).encode("utf-8"), addr=addr)
                print(f"[连接] 手机 {self.peer_name} ({addr[0]}) 已连接，{RATE_HZ[self.rate_code]} Hz")
            elif t == TYPE_AUDIO:
                if self.peer is None:
                    self.peer = addr
                    self.connected = True
                self._last_rx = time.monotonic()
                self.stats.on_audio_rx(h["seq"], h["timestamp_us"])
                self.jb.push(h["seq"], payload)
            elif t == TYPE_PING:
                self._send(TYPE_PONG, seq=h["seq"], ts=h["timestamp_us"], addr=addr)
                self._last_rx = time.monotonic()
            elif t == TYPE_PONG:
                self.stats.on_pong(h["timestamp_us"])
                self._last_rx = time.monotonic()
            elif t == TYPE_RATE_REQ:
                if h["rate_code"] != self.rate_code:
                    self._apply_rate(h["rate_code"])
                self._send(TYPE_RATE_ACK, rate_code=self.rate_code, addr=addr)
            elif t == TYPE_RATE_ACK:
                if self._pending_rate >= 0 and h["rate_code"] == self._pending_rate:
                    self._apply_rate(self._pending_rate)
                    self._pending_rate = -1
            elif t == TYPE_BYE:
                print("[断开] 手机端已停止，等待重新连接…")
                self.connected = False
            elif t == TYPE_VIDEO_CONFIG:
                try:
                    self.video_config = json.loads(payload.decode("utf-8", "replace"))
                except ValueError:
                    self.video_config = None
                self.video_reasm.reset()
                self.video_packets = 0
                self.video_aus = 0
                if self._video_dump is not None:
                    self._video_dump.close()
                self._video_dump = open("video-capture.h264", "wb")
                cfg = self.video_config or {}
                print(f"[视频] VIDEO_CONFIG {cfg.get('width')}x{cfg.get('height')}@{cfg.get('fps')} "
                      f"codec={cfg.get('codec')} rotation={cfg.get('rotation')} mirrored={cfg.get('mirrored')}")
            elif t == TYPE_VIDEO_DATA:
                self.video_packets += 1
                self._last_rx = time.monotonic()
                for au in self.video_reasm.offer_payload(payload, time.monotonic()):
                    self.video_aus += 1
                    if self._video_dump is not None:
                        self._video_dump.write(au)
                        self._video_dump.flush()
                if self.video_reasm.keyframe_needed and time.monotonic() - self._last_keyframe_req >= 0.5:
                    self._last_keyframe_req = time.monotonic()
                    self.video_reasm.keyframe_needed = False
                    self._send(TYPE_VIDEO_KEYFRAME_REQ, addr=addr)
            elif t == TYPE_VIDEO_CTRL:
                try:
                    ctrl = json.loads(payload.decode("utf-8", "replace"))
                except ValueError:
                    ctrl = {}
                print(f"[视频] VIDEO_CTRL from phone: {ctrl}")

    # -- camera relay control (wave 4) --------------------------------------
    def start_video(self, quality="720p"):
        """Ask the phone to start its camera relay (VIDEO_CTRL start).
        Only meaningful when the phone advertised caps ["video1"]."""
        if not self.phone_video_capable or self.peer is None:
            return False
        payload = json.dumps({"action": "start", "quality": quality}).encode("utf-8")
        self._send(TYPE_VIDEO_CTRL, payload=payload)
        return True

    def stop_video(self):
        if self.peer is None:
            return
        payload = json.dumps({"action": "stop"}).encode("utf-8")
        self._send(TYPE_VIDEO_CTRL, payload=payload)

    def _capture_loop(self, sd):
        """PC sound (selected INPUT device) -> phone speaker."""
        import numpy as np
        stream = None
        while self.running:
            if not self.connected or self.peer is None or not self.pc_to_phone:
                time.sleep(0.02)
                continue
            if stream is None or self._in_built_rate != self.rate_code:
                if stream is not None:
                    try:
                        stream.stop(); stream.close()
                    except Exception:
                        pass
                try:
                    stream = sd.InputStream(
                        samplerate=RATE_HZ[self.rate_code], channels=1, dtype="int16",
                        blocksize=frame_samples(self.rate_code),
                        device=self.input_device, latency="low",
                    )
                    stream.start()
                    self._in_built_rate = self.rate_code
                except Exception as e:
                    print(f"[采集] 打开输入设备失败: {e}")
                    time.sleep(1.0)
                    continue
            try:
                data, _overflowed = stream.read(frame_samples(self.rate_code))
            except Exception:
                stream = None
                time.sleep(0.05)
                continue
            arr = np.frombuffer(data.tobytes(), dtype=np.int16)
            if arr.size:
                rms = float(np.sqrt(np.mean(arr.astype(np.float64) ** 2))) / 32768.0
                self.stats.level_in = max(min(1.0, rms * 3.0), self.stats.level_in * 0.72)
            self._tx_seq += 1
            self.stats.tx += 1
            self._send(TYPE_AUDIO, payload=data.tobytes(), seq=self._tx_seq)
        if stream is not None:
            try:
                stream.stop(); stream.close()
            except Exception:
                pass

    def _playout_loop(self, sd):
        """Phone mic -> selected OUTPUT device (virtual cable preferred)."""
        import numpy as np
        stream = None
        while self.running:
            if not self.connected or not self.phone_to_pc:
                time.sleep(0.02)
                continue
            if stream is None or self._out_built_rate != self.rate_code:
                if stream is not None:
                    try:
                        stream.stop(); stream.close()
                    except Exception:
                        pass
                try:
                    stream = sd.OutputStream(
                        samplerate=RATE_HZ[self.rate_code], channels=1, dtype="int16",
                        blocksize=frame_samples(self.rate_code),
                        device=self.output_device, latency="low",
                    )
                    stream.start()
                    self._out_built_rate = self.rate_code
                except Exception as e:
                    print(f"[播放] 打开输出设备失败: {e}")
                    time.sleep(1.0)
                    continue
            payload = self.jb.pop()
            if payload is None:
                time.sleep(0.003)
                continue
            arr = np.frombuffer(payload, dtype=np.int16)
            if arr.size:
                rms = float(np.sqrt(np.mean(arr.astype(np.float64) ** 2))) / 32768.0
                self.stats.level_out = max(min(1.0, rms * 3.0), self.stats.level_out * 0.72)
            try:
                stream.write(arr)
            except Exception:
                stream = None
                time.sleep(0.05)
        if stream is not None:
            try:
                stream.stop(); stream.close()
            except Exception:
                pass

    def _adapt_loop(self):
        last_status = 0.0
        while self.running:
            time.sleep(1.0)
            if not self.running:
                break
            s = self.stats
            s.jitter_ms = s._jitter_us / 1000.0
            self.jb.target_ms = int(max(20, min(60, s.jitter_ms * 2.5))) if s.jitter_ms > 0 else 30
            fresh = s.roll_window()
            if self.connected and self.peer:
                self._ping_seq += 1
                self._send(TYPE_PING, seq=self._ping_seq)
                if self._last_rx and time.monotonic() - self._last_rx > 5.0:
                    print("[重连] 5 秒无手机数据，标记断开，等待手机自动重连…")
                    self.connected = False
                    self.jb.reset()
                if fresh and self._pending_rate < 0:
                    action, new_code, self._good_windows = decide_rate(
                        self.rate_code, s.loss_pct, s.jitter_ms, self._good_windows)
                    if action != "none":
                        self._pending_rate = new_code
                        self._pending_since = time.monotonic()
                        self._send(TYPE_RATE_REQ, rate_code=new_code)
                        print(f"[自适应] 链路 loss={s.loss_pct:.1f}% jitter={s.jitter_ms:.1f}ms "
                              f"→ 申请采样率 {RATE_HZ[new_code]} Hz")
                if self._pending_rate >= 0 and time.monotonic() - self._pending_since > 1.5:
                    self._apply_rate(self._pending_rate)
                    self._pending_rate = -1
            now = time.monotonic()
            if now - last_status >= 2.0:
                last_status = now
                self._print_status()

    def _apply_rate(self, rate_code):
        if rate_code == self.rate_code:
            return
        self.rate_code = rate_code
        self.stats.reset_sequence()
        self.jb.reset()
        print(f"[自适应] 采样率切换为 {RATE_HZ[rate_code]} Hz")

    def _print_status(self):
        s = self.stats
        if not self.connected:
            print("[状态] 未连接（等待手机发现/握手）")
            return
        def bar(level):
            n = int(level * 20)
            return "█" * n + "·" * (20 - n)
        rtt = f"{s.rtt_ms:.0f} ms" if s.rtt_ms >= 0 else "--"
        print(
            f"[状态] {self.peer_name} | {RATE_HZ[self.rate_code]} Hz | RTT {rtt} | "
            f"丢包 {s.loss_pct:.1f}% | 抖动 {s.jitter_ms:.1f} ms | 缓冲 {self.jb.target_ms} ms | "
            f"发 {s.tx} / 收 {s.rx} 包\n"
            f"       手机麦克风电平 [{bar(s.level_out)}]  电脑声音电平 [{bar(s.level_in)}]"
        )


# --------------------------------------------------------------------------
# Config + CLI
# --------------------------------------------------------------------------

DEFAULT_CONFIG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "phone_audio_pc.json")


def load_config(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_config(path, cfg):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    print(f"[配置] 已保存到 {path}: {cfg}")


def main(argv=None):
    ap = argparse.ArgumentParser(description="Phone Audio Bridge — PC 端（Windows/Linux）")
    ap.add_argument("--list-devices", action="store_true", help="列出全部音频设备后退出")
    ap.add_argument("--input-device", type=int, default=None, help="采集设备索引（电脑声音→手机）")
    ap.add_argument("--output-device", type=int, default=None, help="播放设备索引（手机麦→电脑应用）")
    ap.add_argument("--port", type=int, default=PORT, help="UDP 端口（默认 47731）")
    ap.add_argument("--config", default=DEFAULT_CONFIG, help="配置文件路径")
    ap.add_argument("--save-config", action="store_true", help="把本次设备选择写入配置文件")
    ap.add_argument("--gui", action="store_true", help="打开 tkinter 状态窗（声音接力 SoundRelay 桌面界面）")
    args = ap.parse_args(argv)

    try:
        import sounddevice as sd
    except ImportError:
        print("缺少依赖：请先运行 start_windows.bat / start_linux.sh，或执行 pip install sounddevice numpy")
        print("（Linux 还需要系统包 libportaudio2：sudo apt install libportaudio2）")
        return 2

    if args.list_devices:
        try:
            def_in, def_out = sd.default.device
        except Exception:
            def_in = def_out = -1
        print(f"{'idx':>4}  {'方向':<4} {'虚拟设备评分':<6} 名称 [hostapi]")
        for d in list_devices(sd):
            kinds = []
            if d["max_input"] > 0:
                kinds.append(f"输入(score={input_device_score(d['name'])})")
            if d["max_output"] > 0:
                kinds.append(f"输出(score={output_device_score(d['name'])})")
            marks = []
            if d["index"] == def_in:
                marks.append("默认输入")
            if d["index"] == def_out:
                marks.append("默认输出")
            print(f"{d['index']:>4}  {'/'.join(kinds):<18} {d['name']} [{d['hostapi']}] {' '.join(marks)}")
        return 0

    cfg = load_config(args.config)
    input_device = args.input_device if args.input_device is not None else cfg.get("input_device")
    output_device = args.output_device if args.output_device is not None else cfg.get("output_device")
    port = args.port or cfg.get("port", PORT)

    # identity + pairing code (persisted in the same config file)
    dirty = ensure_identity(cfg)
    dirty = ensure_pair_code(cfg) or dirty
    if dirty:
        save_config(args.config, cfg)

    def pair_code_fn():
        if ensure_pair_code(cfg):
            save_config(args.config, cfg)
        return cfg.get("pair_code", ""), time.time() < float(cfg.get("pair_code_expires", 0))

    def on_state_save():
        cfg["paired_phones"] = bridge.paired_phones
        save_config(args.config, cfg)

    bridge = Bridge(
        port=port, input_device=input_device, output_device=output_device,
        device_id=cfg["device_id"], token=cfg["token"],
        paired_phones=cfg["paired_phones"], pair_code_fn=pair_code_fn,
        on_state_save=on_state_save,
    )
    if args.save_config:
        in_idx, in_name, _ = pick_device(sd, "input", input_device)
        out_idx, out_name, _ = pick_device(sd, "output", output_device)
        cfg.update({"input_device": in_idx, "output_device": out_idx, "port": port})
        save_config(args.config, cfg)
        bridge.input_device, bridge.output_device = in_idx, out_idx

    if args.gui:
        try:
            import tkinter  # noqa: F401
        except ImportError:
            print("[GUI] 当前 Python 没有 tkinter，改用控制台模式。（Windows 官方 Python 自带；Linux: sudo apt install python3-tk）")
        else:
            return run_gui(bridge, cfg, args.config, pair_code_fn)

    print_pairing_info(bridge, cfg)
    bridge.run()
    return 0


def print_pairing_info(bridge, cfg):
    code, _valid = bridge.pair_code_fn()
    payload = pairing_payload(bridge.device_id, socket.gethostname(), platform.system(),
                               local_ip(), bridge.port, bridge.token)
    print("=" * 60)
    print(" 声音接力 SoundRelay — 配对信息")
    print(f" 配对码（10 分钟内有效）: {code}")
    print(f" 二维码内容（手机扫码用）: {payload}")
    try:
        import qrcode
        qr = qrcode.QRCode(border=1)
        qr.add_data(payload)
        qr.make()
        qr.print_ascii(invert=True)
    except ImportError:
        print(" （装了 qrcode 库后这里会直接打印二维码：pip install qrcode）")
    print(" 已配对的手机打开声音接力会自动连接，无需再配对。")
    print("=" * 60)


def run_gui(bridge, cfg, config_path, pair_code_fn):
    """tkinter status window (UI_SPEC §4 + UI_SPEC_MULTIDEVICE pairing card).
    Same design tokens as the phone. Closing the window stops the service."""
    import threading as _threading
    import tkinter as tk
    from tkinter import ttk

    try:
        import sounddevice as sd
    except ImportError:
        print("[GUI] 缺少 sounddevice，无法打开状态窗")
        return 2

    BG = "#0B0E13"; SURFACE = "#151B24"; SURFACE_HI = "#1C2531"; SURFACE_IN = "#0F141C"
    STROKE = "#26303F"; ACCENT = "#2CE8A5"; ACCENT_DIM = "#12362A"; SKY = "#45C7F2"
    WARN = "#FFB224"; ERROR = "#FF5C5C"; ERROR_DIM = "#3A1D22"
    TEXT1 = "#F2F5F9"; TEXT2 = "#9AA7B8"; TEXT3 = "#5F6D80"; ON_ACCENT = "#052417"
    if platform.system() == "Windows":
        FONT = ("Microsoft YaHei UI", 10)
        FONT_B = ("Microsoft YaHei UI", 10, "bold")
        FONT_H = ("Microsoft YaHei UI", 13, "bold")
        FONT_MONO = ("Consolas", 10)
    else:
        FONT = ("Noto Sans CJK SC", 10)
        FONT_B = ("Noto Sans CJK SC", 10, "bold")
        FONT_H = ("Noto Sans CJK SC", 13, "bold")
        FONT_MONO = ("Monospace", 10)

    root = tk.Tk()
    root.title("声音接力 SoundRelay")
    root.geometry("520x760")
    root.resizable(False, False)
    root.configure(bg=BG)

    def card(parent, **kw):
        return tk.Frame(parent, bg=SURFACE, highlightbackground=STROKE,
                        highlightthickness=1, **kw)

    # header
    hdr = tk.Frame(root, bg=BG); hdr.pack(fill="x", padx=16, pady=(14, 4))
    status_var = tk.StringVar(value="● 未启动")
    tk.Label(hdr, textvariable=status_var, bg=BG, fg=TEXT2, font=FONT_B).pack(side="left")
    tk.Label(hdr, text=f"端口 {bridge.port}", bg=SURFACE_IN, fg=TEXT2, font=FONT).pack(side="right")
    tk.Label(root, text="声音接力 SoundRelay · 把电脑变成手机的外接声卡",
             bg=BG, fg=TEXT2, font=FONT).pack(anchor="w", padx=16)

    # hero
    hero = card(root); hero.pack(fill="x", padx=16, pady=10)
    hero_title = tk.Label(hero, text="等待手机连接", bg=SURFACE, fg=TEXT1, font=FONT_H)
    hero_title.pack(anchor="w", padx=14, pady=(12, 0))
    hero_sub = tk.Label(hero, text="在手机上打开声音接力，点「开始搜索」即可自动找到这台电脑",
                        bg=SURFACE, fg=TEXT2, font=FONT, wraplength=470, justify="left")
    hero_sub.pack(anchor="w", padx=14)
    badge_var = tk.StringVar(value="-- ms")
    badge = tk.Label(hero, textvariable=badge_var, bg=SURFACE_IN, fg=TEXT2, font=FONT_MONO)
    badge.pack(anchor="w", padx=14, pady=(6, 0))
    spark = tk.Canvas(hero, width=480, height=56, bg=SURFACE_IN, highlightthickness=0)
    spark.pack(padx=14, pady=(8, 0))
    stats_var = tk.StringVar(value="丢包 --% · 抖动 -- ms · 缓冲 -- ms")
    tk.Label(hero, textvariable=stats_var, bg=SURFACE, fg=TEXT2, font=FONT_MONO).pack(anchor="w", padx=14, pady=(6, 12))

    # devices
    dev_card = card(root); dev_card.pack(fill="x", padx=16, pady=(0, 10))
    tk.Label(dev_card, text="音频设备", bg=SURFACE, fg=TEXT1, font=FONT_B).pack(anchor="w", padx=14, pady=(12, 4))
    devices = list_devices(sd)
    out_names = [f"#{d['index']} {d['name']}" for d in devices if d["max_output"] > 0]
    in_names = [f"#{d['index']} {d['name']}" for d in devices if d["max_input"] > 0]
    name_to_idx = {}
    for d in devices:
        name_to_idx[f"#{d['index']} {d['name']}"] = d["index"]
        name_to_idx[d["name"]] = d["index"]
    try:
        _oi, out_pick_name, _ = pick_device(sd, "output", bridge.output_device)
        _ii, in_pick_name, _ = pick_device(sd, "input", bridge.input_device)
    except SystemExit:
        out_pick_name = in_pick_name = ""
    tk.Label(dev_card, text="手机麦克风输出 →（给其他应用当麦克风）", bg=SURFACE, fg=TEXT2, font=FONT).pack(anchor="w", padx=14)
    out_combo = ttk.Combobox(dev_card, values=out_names, state="readonly", width=58)
    out_combo.pack(padx=14, fill="x")
    for i, n in enumerate(out_names):
        if n.endswith(out_pick_name):
            out_combo.current(i)
    out_badge = tk.Label(dev_card, text="", bg=SURFACE, fg=ACCENT, font=FONT)
    out_badge.pack(anchor="w", padx=14)
    tk.Label(dev_card, text="送到手机的声音 ←（采集电脑声音）", bg=SURFACE, fg=TEXT2, font=FONT).pack(anchor="w", padx=14)
    in_combo = ttk.Combobox(dev_card, values=in_names, state="readonly", width=58)
    in_combo.pack(padx=14, fill="x")
    for i, n in enumerate(in_names):
        if n.endswith(in_pick_name):
            in_combo.current(i)
    in_badge = tk.Label(dev_card, text="", bg=SURFACE, fg=ACCENT, font=FONT)
    in_badge.pack(anchor="w", padx=14, pady=(0, 10))

    def device_badge(name, kind):
        score = input_device_score(name) if kind == "input" else output_device_score(name)
        if score <= 0:
            return "还没检测到虚拟声卡，其他软件将选不到手机麦克风" if kind == "output" and platform.system() == "Windows" else ""
        if "phoneaudiobridge" in name.lower():
            return "✓ 已识别 PipeWire/PulseAudio 虚拟设备"
        return "✓ 已自动识别虚拟声卡"

    def refresh_device_badges():
        out_badge.config(text=device_badge(out_combo.get(), "output"),
                         fg=ACCENT if output_device_score(out_combo.get()) > 0 else WARN)
        in_badge.config(text=device_badge(in_combo.get(), "input"),
                        fg=ACCENT if input_device_score(in_combo.get()) > 0 else WARN)
    out_combo.bind("<<ComboboxSelected>>", lambda _e: refresh_device_badges())
    in_combo.bind("<<ComboboxSelected>>", lambda _e: refresh_device_badges())
    refresh_device_badges()

    # channels
    chan = card(root); chan.pack(fill="x", padx=16, pady=(0, 10))
    tk.Label(chan, text="通道", bg=SURFACE, fg=TEXT1, font=FONT_B).pack(anchor="w", padx=14, pady=(12, 2))
    var_mic = tk.BooleanVar(value=True)
    var_spk = tk.BooleanVar(value=True)
    def apply_channels():
        bridge.phone_to_pc = var_mic.get()
        bridge.pc_to_phone = var_spk.get()
    tk.Checkbutton(chan, text="麦克风 → 电脑（手机当麦克风）", variable=var_mic, command=apply_channels,
                   bg=SURFACE, fg=TEXT1, font=FONT, selectcolor=SURFACE_IN,
                   activebackground=SURFACE, activeforeground=TEXT1).pack(anchor="w", padx=10)
    tk.Checkbutton(chan, text="电脑 → 手机（电脑声音从手机播放）", variable=var_spk, command=apply_channels,
                   bg=SURFACE, fg=TEXT1, font=FONT, selectcolor=SURFACE_IN,
                   activebackground=SURFACE, activeforeground=TEXT1).pack(anchor="w", padx=10, pady=(0, 10))

    # pairing card
    pair = card(root); pair.pack(fill="x", padx=16, pady=(0, 10))
    tk.Label(pair, text="配对新手机", bg=SURFACE, fg=TEXT1, font=FONT_B).pack(anchor="w", padx=14, pady=(12, 2))
    code_var = tk.StringVar(value="")
    tk.Label(pair, textvariable=code_var, bg=SURFACE, fg=ACCENT,
             font=("Consolas", 20, "bold")).pack(anchor="w", padx=14)
    verify_var = tk.StringVar(value="")
    tk.Label(pair, textvariable=verify_var, bg=SURFACE, fg=WARN, font=FONT).pack(anchor="w", padx=14)
    qr_label = tk.Label(pair, bg=SURFACE)
    qr_holder = {"img": None, "shown": False}

    def toggle_qr():
        if qr_holder["shown"]:
            qr_label.pack_forget()
            qr_holder["shown"] = False
            qr_btn.config(text="显示配对二维码")
            return
        payload = pairing_payload(bridge.device_id, socket.gethostname(), platform.system(),
                                  local_ip(), bridge.port, bridge.token)
        try:
            import qrcode
            from PIL import ImageTk
            img = qrcode.make(payload)
            img = img.resize((190, 190))
            qr_holder["img"] = ImageTk.PhotoImage(img)
            qr_label.config(image=qr_holder["img"], text="")
        except Exception:
            qr_label.config(image="", text=payload, fg=TEXT2, font=FONT_MONO, wraplength=470, justify="left")
        qr_label.pack(anchor="w", padx=14, pady=(2, 6))
        qr_holder["shown"] = True
        qr_btn.config(text="隐藏配对二维码")

    qr_btn = tk.Button(pair, text="显示配对二维码", command=toggle_qr,
                       bg=SURFACE_HI, fg=TEXT1, font=FONT, relief="flat")
    qr_btn.pack(anchor="w", padx=14, pady=(2, 12))

    # start/stop
    state = {"thread": None}
    btn_var = tk.StringVar(value="启动服务")
    def toggle_service():
        if state["thread"] is None:
            bridge.output_device = name_to_idx.get(out_combo.get(), bridge.output_device)
            bridge.input_device = name_to_idx.get(in_combo.get(), bridge.input_device)
            cfg["paired_phones"] = bridge.paired_phones
            state["thread"] = _threading.Thread(target=bridge.run, daemon=True)
            state["thread"].start()
            btn_var.set("停止服务")
            out_combo.config(state="disabled"); in_combo.config(state="disabled")
        else:
            bridge.stop()
            state["thread"] = None
            btn_var.set("启动服务")
            out_combo.config(state="readonly"); in_combo.config(state="readonly")
    tk.Button(root, textvariable=btn_var, command=toggle_service,
              bg=ACCENT, fg=ON_ACCENT, font=FONT_B, relief="flat", height=2).pack(fill="x", padx=16)
    tk.Label(root, text="服务运行中 · 本机 IP 供手机手动连接时填写",
             bg=BG, fg=TEXT3, font=FONT).pack(pady=(6, 0))

    rtt_history = []
    def poll():
        code, valid = pair_code_fn()
        remain = int(float(cfg.get("pair_code_expires", 0)) - time.time())
        code_var.set(f"配对码 {code}" + (f"（剩 {max(0, remain) // 60} 分钟）" if valid else ""))
        if bridge.last_verify_code:
            verify_var.set(f"核对验证码 {bridge.last_verify_code}（与手机上一致再确认 · {bridge.last_pair_phone}）")
        s = bridge.stats
        if bridge.connected:
            status_var.set("● 已连接手机")
            hero_title.config(text=f"已连接手机 · {bridge.peer_name}")
            hero_sub.config(text=f"{bridge.peer[0] if bridge.peer else ''} · {RATE_HZ[bridge.rate_code]} Hz")
            if s.rtt_ms >= 0:
                word = "流畅" if s.rtt_ms < 30 else ("一般" if s.rtt_ms <= 80 else "偏高")
                color = ACCENT if s.rtt_ms < 30 else (WARN if s.rtt_ms <= 80 else ERROR)
                badge_var.set(f"{s.rtt_ms:.0f} ms · {word}")
                badge.config(fg=color)
                rtt_history.append(s.rtt_ms)
                rtt_history[:] = rtt_history[-60:]
            stats_var.set(f"丢包 {s.loss_pct:.1f}% · 抖动 {s.jitter_ms:.1f} ms · 缓冲 {bridge.jb.target_ms} ms · 发 {s.tx} / 收 {s.rx}")
        elif state["thread"] is not None:
            status_var.set("● 等待手机连接")
            hero_title.config(text="等待手机…")
            ips = []
            try:
                ips = [i[4][0] for i in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)]
                ips = sorted(set(x for x in ips if not x.startswith("127.")))
            except OSError:
                pass
            hero_sub.config(text="在手机上打开声音接力，点「开始搜索」即可自动找到这台电脑" +
                                 (f"\n本机 IP：{'、'.join(ips)}" if ips else ""))
            badge_var.set("-- ms"); badge.config(fg=TEXT2)
        else:
            status_var.set("● 未启动")
        # sparkline (last 60 s of RTT)
        spark.delete("all")
        if len(rtt_history) >= 2:
            maxv = max(50.0, max(rtt_history) * 1.2)
            pts = []
            for i, v in enumerate(rtt_history):
                x = 6 + i * (468 / 59)
                y = 50 - (v / maxv) * 44
                pts.extend([x, y])
            spark.create_line(*pts, fill=ACCENT, width=2)
        root.after(500, poll)
    poll()

    def on_close():
        bridge.stop()
        root.destroy()
    root.protocol("WM_DELETE_WINDOW", on_close)
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())

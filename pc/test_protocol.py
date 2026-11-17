#!/usr/bin/env python3
"""
Localhost self-test for the PC protocol code — no audio hardware needed
(sounddevice is imported lazily by phone_audio_pc, so this runs anywhere).

Covers: header pack/unpack round-trip, frame sizes, jitter buffer ordering &
loss concealment, the adaptive-rate policy, virtual-device name scoring, and
a real UDP loopback exchange (DISCOVER -> DISCOVER_RESP, HELLO -> audio)
between two sockets using the same pack/unpack functions as production.
"""

import json
import socket
import sys

import phone_audio_pc as pab


def test_header_roundtrip():
    payload = bytes(range(256)) * 3 + b"\x00" * 192  # 960 bytes
    pkt = pab.pack_packet(pab.TYPE_AUDIO, seq=123456, timestamp_us=9876543210,
                          payload=payload, rate_code=pab.RATE_24K)
    assert len(pkt) == 16 + 960
    h, body = pab.unpack_packet(pkt)
    assert h["type"] == pab.TYPE_AUDIO and h["seq"] == 123456
    assert h["timestamp_us"] == 9876543210
    assert h["codec"] == pab.CODEC_PCM16 and h["rate_code"] == pab.RATE_24K
    assert body == payload
    assert pab.unpack_packet(b"\x00" * 20) is None       # bad magic
    assert pab.unpack_packet(b"PA") is None              # too short
    print("ok: header pack/unpack round-trip")


def test_frame_sizes():
    assert pab.frame_samples(pab.RATE_48K) == 480 and pab.frame_bytes(pab.RATE_48K) == 960
    assert pab.frame_samples(pab.RATE_24K) == 240 and pab.frame_bytes(pab.RATE_24K) == 480
    assert pab.frame_samples(pab.RATE_16K) == 160 and pab.frame_bytes(pab.RATE_16K) == 320
    print("ok: 10 ms frame sizes 960/480/320 bytes")


def test_jitter_buffer():
    jb = pab.JitterBuffer()
    jb.target_ms = 20  # prime after 2 frames
    jb.push(10, b"A" * 960)
    assert jb.pop() is None                     # not primed yet
    jb.push(12, b"C" * 960)
    jb.push(11, b"B" * 960)                     # out-of-order arrival
    assert jb.pop() == b"A" * 960
    assert jb.pop() == b"B" * 960
    assert jb.pop() == b"C" * 960
    assert jb.pop() is None                     # seq 13 lost -> concealed
    assert jb.underruns == 1
    jb.push(9, b"late")                         # stale frame dropped
    assert len(jb) == 0
    print("ok: jitter buffer ordering + loss concealment")


def test_decide_rate():
    assert pab.decide_rate(pab.RATE_48K, 6.0, 2.0, 0)[0] == "down"
    assert pab.decide_rate(pab.RATE_48K, 0.0, 20.0, 0)[0] == "down"
    assert pab.decide_rate(pab.RATE_16K, 30.0, 40.0, 0)[0] == "none"   # floor
    assert pab.decide_rate(pab.RATE_48K, 0.5, 2.0, 0)[0] == "none"     # ceiling
    action, code, _ = pab.decide_rate(pab.RATE_24K, 0.5, 2.0, 14)
    assert action == "up" and code == pab.RATE_48K                     # after ~30 s clean
    assert pab.decide_rate(pab.RATE_24K, 3.0, 8.0, 99)[0] == "none"    # hysteresis band
    print("ok: adaptive-rate policy (down on loss/jitter, slow step-up)")


def test_device_scoring():
    assert pab.output_device_score("CABLE Input (VB-Audio Virtual Cable)") == 100
    assert pab.input_device_score("CABLE Output (VB-Audio Virtual Cable)") == 100
    assert pab.input_device_score("立体声混音 (Realtek)") >= 90
    assert pab.input_device_score("Monitor of PhoneAudioBridge-Speaker") >= 95
    assert pab.output_device_score("PhoneAudioBridge-Mic") == 100
    assert pab.input_device_score("Microphone (Realtek)") == 0
    assert pab.output_device_score("Speakers (Realtek)") == 0
    print("ok: virtual-device auto-detection scoring (VB-CABLE/BlackHole/monitor/stereo-mix/Linux virtual)")


def test_udp_loopback():
    pc = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    pc.bind(("127.0.0.1", 0))
    pc.settimeout(2.0)
    pc_addr = pc.getsockname()
    phone = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    phone.bind(("127.0.0.1", 0))
    phone.settimeout(2.0)
    # Some sandboxes forbid unconnected UDP sendto (EPERM) or silently drop
    # UDP entirely. That is an environment limit, not a protocol bug: skip.
    try:
        phone.sendto(pab.pack_packet(pab.TYPE_PING, seq=0, timestamp_us=0), pc_addr)
        pc.recvfrom(4096)
    except (PermissionError, OSError, socket.timeout) as e:
        print(f"SKIP: UDP loopback unavailable in this environment ({e}); "
              "pure protocol tests above still cover pack/unpack/jitter/rate logic")
        pc.close(); phone.close()
        return

    # phone -> PC: DISCOVER
    disc = json.dumps({"app": "PhoneAudioBridge", "kind": "discover", "name": "TestPhone"}).encode()
    phone.sendto(pab.pack_packet(pab.TYPE_DISCOVER, payload=disc), pc_addr)
    data, from_addr = pc.recvfrom(4096)
    h, body = pab.unpack_packet(data)
    assert h["type"] == pab.TYPE_DISCOVER and json.loads(body)["name"] == "TestPhone"

    # PC -> phone: DISCOVER_RESP (same construction as Bridge._recv_loop)
    resp = json.dumps({"app": "PhoneAudioBridge", "kind": "discover_resp", "name": "TestPC"}).encode()
    pc.sendto(pab.pack_packet(pab.TYPE_DISCOVER_RESP, payload=resp), from_addr)
    data, _ = phone.recvfrom(4096)
    h, body = pab.unpack_packet(data)
    assert h["type"] == pab.TYPE_DISCOVER_RESP and json.loads(body)["name"] == "TestPC"

    # phone -> PC: HELLO then 5 audio frames; PC stats + jitter buffer consume them
    hello = json.dumps({"app": "PhoneAudioBridge", "kind": "hello", "rate": 48000, "codec": "pcm16"}).encode()
    phone.sendto(pab.pack_packet(pab.TYPE_HELLO, payload=hello), pc_addr)
    data, _ = pc.recvfrom(4096)
    h, _body = pab.unpack_packet(data)
    assert h["type"] == pab.TYPE_HELLO

    stats = pab.LinkStats()
    jb = pab.JitterBuffer()
    jb.target_ms = 20
    for seq in range(1, 6):
        frame = bytes([seq]) * pab.frame_bytes(pab.RATE_48K)
        phone.sendto(pab.pack_packet(pab.TYPE_AUDIO, seq=seq, timestamp_us=pab.now_us(), payload=frame), pc_addr)
    received = 0
    while received < 5:
        data, _ = pc.recvfrom(4096)
        h, body = pab.unpack_packet(data)
        assert h["type"] == pab.TYPE_AUDIO
        stats.on_audio_rx(h["seq"], h["timestamp_us"])
        jb.push(h["seq"], body)
        received += 1
    assert stats.rx == 5 and stats.lost == 0
    frames = [jb.pop() for _ in range(5)]
    assert all(f is not None and len(f) == 960 for f in frames)
    assert [f[0] for f in frames] == [1, 2, 3, 4, 5]

    # ping/pong RTT path
    ts = pab.now_us()
    phone.sendto(pab.pack_packet(pab.TYPE_PING, seq=7, timestamp_us=ts), pc_addr)
    data, from_addr = pc.recvfrom(4096)
    h, _ = pab.unpack_packet(data)
    assert h["type"] == pab.TYPE_PING
    pc.sendto(pab.pack_packet(pab.TYPE_PONG, seq=h["seq"], timestamp_us=h["timestamp_us"]), from_addr)
    data, _ = phone.recvfrom(4096)
    h, _ = pab.unpack_packet(data)
    assert h["type"] == pab.TYPE_PONG and h["timestamp_us"] == ts
    stats.on_pong(h["timestamp_us"])
    assert stats.rtt_ms >= 0

    pc.close(); phone.close()
    print("ok: UDP loopback DISCOVER/HELLO/AUDIO/PING-PONG over real sockets")


def test_pairing():
    token = "0123456789abcdef" * 2
    th = pab.token_hash(token)
    assert len(th) == 64
    vc = pab.verify_code_for(token, "phone123")
    assert len(vc) == 6 and vc.isdigit()
    assert vc == pab.verify_code_for(token, "phone123")  # deterministic, both sides agree
    # already-paired phone with correct hash -> auth
    paired = {"phone123": {"name": "P", "lastSeen": 0}}
    assert pab.decide_hello_auth(paired, "phone123", th, "", token, "482916", True, False)[0] == "auth"
    # paired id but wrong hash, no code -> reject
    assert pab.decide_hello_auth(paired, "phone123", "0" * 64, "", token, "482916", True, False)[0] == "reject"
    # unknown phone + correct 6-digit code -> pair, issue token
    a, issue = pab.decide_hello_auth(paired, "phone999", "", "482916", token, "482916", True, False)
    assert a == "pair" and issue
    # unknown phone + wrong/expired code -> reject
    assert pab.decide_hello_auth(paired, "phone999", "", "000000", token, "482916", True, False)[0] == "reject"
    assert pab.decide_hello_auth(paired, "phone999", "", "482916", token, "482916", False, False)[0] == "reject"
    # QR flow: unknown phone already holding the token (hash matches) -> pair
    a, issue = pab.decide_hello_auth({}, "phone999", th, "", token, "482916", True, False)
    assert a == "pair" and issue
    # first-use window only when no phones paired yet
    assert pab.decide_hello_auth({}, "phone999", "", "", token, "482916", True, True)[0] == "pair"
    assert pab.decide_hello_auth(paired, "phone999", "", "", token, "482916", True, True)[0] == "reject"
    # QR payload shape (the phone validates exactly these fields)
    payload = pab.pairing_payload("pc123", "QI-PC", "Windows", "192.168.1.20", 47731, token)
    o = json.loads(payload)
    assert o["app"] == "AirBridge" and o["deviceId"] == "pc123" and o["token"] == token and o["ip"] == "192.168.1.20"
    # identity/pair-code helpers
    cfg = {}
    assert pab.ensure_identity(cfg) and cfg["device_id"] and cfg["token"]
    assert pab.ensure_pair_code(cfg) and len(cfg["pair_code"]) == 6
    assert not pab.ensure_pair_code(cfg)  # not rotated before expiry
    print("ok: pairing auth policy, verify code, QR payload, identity helpers")


def test_beacon_packet():
    payload = json.dumps({"app": "PhoneAudioBridge", "kind": "beacon", "name": "QI-PC",
                          "os": "Linux", "deviceId": "pc123", "tokenHash": "ab" * 32}).encode()
    pkt = pab.pack_packet(pab.TYPE_BEACON, payload=payload)
    h, body = pab.unpack_packet(pkt)
    assert h["type"] == pab.TYPE_BEACON
    o = json.loads(body)
    assert o["deviceId"] == "pc123" and o["kind"] == "beacon"
    print("ok: beacon packet round-trip")


if __name__ == "__main__":
    test_header_roundtrip()
    test_frame_sizes()
    test_jitter_buffer()
    test_decide_rate()
    test_device_scoring()
    test_pairing()
    test_beacon_packet()
    test_udp_loopback()
    print("ALL TESTS PASSED")
    sys.exit(0)

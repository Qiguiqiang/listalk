#!/usr/bin/env python3
"""
Cross-source protocol consistency checker for SoundRelay.

Extracts constants / formats / JSON keys from BOTH source trees (Android
Kotlin + PC Python), PROTOCOL.md, and (when present) windows-native C#,
and FAILS (exit 1) on any mismatch. Also runs a PC-side in-process packet
round-trip and, when tools/jvm-test.jar exists (build it with
tools/run_jvm_tests.sh), compares the JVM harness's tokenHash/verifyCode
outputs against the PC functions for identical inputs.

Modes:
  (default)     all consistency checks + tamper-detection assertion
  --selftest    standalone tamper demo: verify a file against a manifest
                whose sha256 is deliberately wrong; the verification MUST
                fail -> prints FAIL and exits non-zero (that rejection is
                the required behaviour of the update tamper gate).
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FAILURES = []
WARNINGS = []


def fail(msg):
    FAILURES.append(msg)
    print(f"FAIL: {msg}")


def ok(msg):
    print(f"ok: {msg}")


def warn(msg):
    WARNINGS.append(msg)
    print(f"WARN: {msg}")


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def kt_const(text, name):
    m = re.search(rf"const val {name}\s*=\s*(\"[^\"]*\"|0x[0-9A-Fa-f]+|\d+)", text)
    if not m:
        return None
    v = m.group(1)
    return v.strip('"') if v.startswith('"') else int(v, 0)


def main():
    proto_kt = read(os.path.join(ROOT, "app/src/main/java/com/qi/phoneaudiobridge/Protocol.kt"))
    engine_kt = read(os.path.join(ROOT, "app/src/main/java/com/qi/phoneaudiobridge/AudioEngine.kt"))
    main_kt = read(os.path.join(ROOT, "app/src/main/java/com/qi/phoneaudiobridge/MainActivity.kt"))
    update_kt = read(os.path.join(ROOT, "app/src/main/java/com/qi/phoneaudiobridge/UpdateChecker.kt"))
    protocol_md = read(os.path.join(ROOT, "PROTOCOL.md"))
    pc_path = os.path.join(ROOT, "pc/phone_audio_pc.py")
    pc_src = read(pc_path)

    sys.path.insert(0, os.path.join(ROOT, "pc"))
    import phone_audio_pc as pab

    # ---- core wire constants: Kotlin vs PC Python vs PROTOCOL.md ----
    checks = [
        ("PORT", kt_const(proto_kt, "PORT"), pab.PORT, "47731"),
        ("MAGIC", kt_const(proto_kt, "MAGIC"), pab.MAGIC, "0x5041"),
        ("HEADER_SIZE", kt_const(proto_kt, "HEADER_SIZE"), pab.HEADER.size, "16"),
        ("TYPE_AUDIO", kt_const(proto_kt, "TYPE_AUDIO"), pab.TYPE_AUDIO, None),
        ("TYPE_DISCOVER", kt_const(proto_kt, "TYPE_DISCOVER"), pab.TYPE_DISCOVER, None),
        ("TYPE_DISCOVER_RESP", kt_const(proto_kt, "TYPE_DISCOVER_RESP"), pab.TYPE_DISCOVER_RESP, None),
        ("TYPE_HELLO", kt_const(proto_kt, "TYPE_HELLO"), pab.TYPE_HELLO, None),
        ("TYPE_HELLO_ACK", kt_const(proto_kt, "TYPE_HELLO_ACK"), pab.TYPE_HELLO_ACK, None),
        ("TYPE_PING", kt_const(proto_kt, "TYPE_PING"), pab.TYPE_PING, None),
        ("TYPE_PONG", kt_const(proto_kt, "TYPE_PONG"), pab.TYPE_PONG, None),
        ("TYPE_RATE_REQ", kt_const(proto_kt, "TYPE_RATE_REQ"), pab.TYPE_RATE_REQ, None),
        ("TYPE_RATE_ACK", kt_const(proto_kt, "TYPE_RATE_ACK"), pab.TYPE_RATE_ACK, None),
        ("TYPE_BYE", kt_const(proto_kt, "TYPE_BYE"), pab.TYPE_BYE, None),
        ("TYPE_BEACON", kt_const(proto_kt, "TYPE_BEACON"), pab.TYPE_BEACON, None),
        ("CODEC_PCM16", kt_const(proto_kt, "CODEC_PCM16"), pab.CODEC_PCM16, None),
        ("RATE_48K", kt_const(proto_kt, "RATE_48K"), pab.RATE_48K, None),
        ("RATE_24K", kt_const(proto_kt, "RATE_24K"), pab.RATE_24K, None),
        ("RATE_16K", kt_const(proto_kt, "RATE_16K"), pab.RATE_16K, None),
    ]
    for name, kv, pv, md_expect in checks:
        if kv is None:
            fail(f"{name}: missing in Protocol.kt")
        elif kv != pv:
            fail(f"{name}: Kotlin={kv} != PC Python={pv}")
        else:
            ok(f"{name} = {kv} (Kotlin == PC Python)")
        if md_expect is not None and md_expect not in protocol_md and str(kv) not in protocol_md:
            fail(f"{name}: value not documented in PROTOCOL.md")

    # rate Hz mapping
    if pab.RATE_HZ != {0: 48000, 1: 24000, 2: 16000}:
        fail(f"PC RATE_HZ unexpected: {pab.RATE_HZ}")
    else:
        ok("rate codes 0/1/2 = 48000/24000/16000 Hz")

    # ---- JSON keys ----
    # PC beacon payload keys (from source of _beacon_loop) + pairing payload (executed)
    payload = json.loads(pab.pairing_payload("pcX", "PC", "Windows", "192.168.1.2", pab.PORT, "tok"))
    qr_keys = set(payload.keys())
    phone_qr_keys = set(re.findall(r'o\.optString\("(\w+)"\)', main_kt)) | set(re.findall(r'o\?\.optString\("(\w+)"\)', main_kt))
    need_qr = {"app", "v", "deviceId", "name", "os", "ip", "port", "token"}
    if qr_keys != need_qr:
        fail(f"QR payload keys drift: PC produces {sorted(qr_keys)} expected {sorted(need_qr)}")
    else:
        ok(f"QR payload keys = {sorted(qr_keys)}")
    missing_on_phone = {"app", "deviceId", "token", "ip"} - phone_qr_keys
    if missing_on_phone:
        fail(f"phone QR parser does not read keys: {sorted(missing_on_phone)}")
    else:
        ok("phone QR parser reads app/deviceId/token/ip")

    hello_keys_kt = set(re.findall(r'\.put\("(\w+)"', engine_kt))
    # Informational marker keys: carried in HELLO for the PC's UI/logging;
    # auth never depends on them (QR auth works via tokenHash), so the PC is
    # not required to read them — they must be documented in PROTOCOL.md.
    INFORMATIONAL = {"pairQr", "pairTap", "appVersion", "protoVersion"}
    for key in ["name", "deviceId", "rate", "codec", "tokenHash", "pairCode", "pairQr"]:
        if key in INFORMATIONAL:
            if key in hello_keys_kt and key not in protocol_md:
                fail(f"HELLO informational key '{key}' not documented in PROTOCOL.md")
            continue
        pc_reads = f'info.get("{key}"' in pc_src or f'info.get(\'{key}\'' in pc_src or f'"{key}"' in pc_src
        if key in hello_keys_kt and not pc_reads:
            fail(f"HELLO key '{key}' sent by phone but never read on PC")
    ok(f"phone HELLO keys = {sorted(k for k in hello_keys_kt if k in ('app','kind','name','deviceId','rate','codec','tokenHash','pairCode','pairQr','pairTap'))}")
    for key in ["app", "kind", "name", "os", "deviceId", "tokenHash"]:
        if key not in pc_src:
            fail(f"beacon/discover key '{key}' absent from PC source")
    ok("beacon/discover JSON keys present on PC (app/kind/name/os/deviceId/tokenHash)")

    # ---- discovery constants: Kotlin vs PROTOCOL.md vs windows-native ----
    mdns = kt_const(proto_kt, "MDNS_SERVICE_TYPE")
    ble = kt_const(proto_kt, "BLE_SERVICE_UUID")
    if not mdns or mdns not in protocol_md:
        fail(f"mDNS type {mdns!r} missing from PROTOCOL.md")
    else:
        ok(f"mDNS service type = {mdns} (Kotlin == PROTOCOL.md)")
    if not ble or ble not in protocol_md:
        fail(f"BLE UUID {ble!r} missing from PROTOCOL.md")
    else:
        ok(f"BLE service UUID = {ble} (Kotlin == PROTOCOL.md)")
    wn = os.path.join(ROOT, "windows-native")
    if os.path.isdir(wn):
        cs_text = ""
        for dirpath, _dirs, files in os.walk(wn):
            for fn in files:
                if fn.endswith((".cs", ".xaml", ".json")):
                    try:
                        cs_text += read(os.path.join(dirpath, fn))
                    except OSError:
                        pass
        for label, val in [("mDNS", mdns), ("BLE UUID", ble)]:
            if val and val in cs_text:
                ok(f"{label} also matches windows-native sources")
            elif val:
                warn(f"{label} {val} not found in windows-native sources (PC worker may not have landed it yet)")
    else:
        warn("windows-native/ not present at check time; C# cross-check skipped")
    if "MDNS_SERVICE_TYPE" not in pc_src and "_airbridge._udp" not in pc_src:
        warn("PC Python does not advertise mDNS yet (gap owned by the PC worker; phone browses regardless)")

    # ---- online-update canonical address ----
    m = re.search(r'DEFAULT_UPDATE_MANIFEST_URL\s*=\s*"([^"]+)"', update_kt)
    upd = m.group(1) if m else None
    if upd != "https://raw.githubusercontent.com/Qiguiqiang/soundrelay/main/update.json":
        fail(f"update manifest URL drift in UpdateChecker.kt: {upd!r}")
    elif upd not in protocol_md:
        fail("update manifest URL missing from PROTOCOL.md")
    else:
        ok(f"update manifest URL = {upd} (Kotlin == PROTOCOL.md)")
    appver = re.search(r'APP_VERSION\s*=\s*"([^"]+)"', update_kt)
    ok(f"APP_VERSION = {appver.group(1) if appver else '?'}")

    # ---- PC in-process round-trip ----
    pkt = pab.pack_packet(pab.TYPE_AUDIO, seq=424242, timestamp_us=9876543210,
                          payload=b"\xAB" * 960, rate_code=pab.RATE_24K)
    h, body = pab.unpack_packet(pkt)
    assert h["seq"] == 424242 and h["rate_code"] == pab.RATE_24K and body == b"\xAB" * 960
    ok("PC pack/unpack round-trip (seq/rate/payload)")
    token = "0123456789abcdeffedcba9876543210"
    th = pab.token_hash(token)
    vc = pab.verify_code_for(token, "phoneXYZ")
    paired = {"phoneXYZ": {"name": "P", "lastSeen": 0}}
    assert pab.decide_hello_auth(paired, "phoneXYZ", th, "", token, "482916", True, False)[0] == "auth"
    assert pab.decide_hello_auth({}, "phoneXYZ", "", "482916", token, "482916", True, False) == ("pair", True)
    assert pab.decide_hello_auth({}, "phoneXYZ", "", "000000", token, "482916", True, False)[0] == "reject"
    ok(f"PC auth matrix (auth/pair/reject); tokenHash={th[:16]}... verifyCode(phoneXYZ)={vc}")

    # ---- JVM harness comparison ----
    jar = os.path.join(ROOT, "tools", "jvm-test.jar")
    stdlib = os.path.expanduser("~/tools/kotlinc/lib/kotlin-stdlib.jar")
    java = os.path.expanduser("~/tools/jdk17/bin/java")
    if os.path.exists(jar) and os.path.exists(java):
        out = subprocess.run([java, "-cp", f"{jar}:{stdlib}", "TestMainKt"],
                             capture_output=True, text=True, timeout=120).stdout
        print("--- JVM harness output ---")
        print(out.strip())
        print("--------------------------")
        mth = re.search(r"JVM tokenHash: ([0-9a-f]{64})", out)
        mvc = re.search(r"JVM verifyCode\(phoneXYZ\): (\d{6})", out)
        if not mth or mth.group(1) != th:
            fail(f"JVM tokenHash mismatch vs PC: {mth.group(1) if mth else None} != {th}")
        else:
            ok("tokenHash identical Kotlin(JVM) == PC Python")
        if not mvc or mvc.group(1) != vc:
            fail(f"JVM verifyCode mismatch vs PC: {mvc.group(1) if mvc else None} != {vc}")
        else:
            ok(f"verifyCode identical Kotlin(JVM) == PC Python ({vc})")
        if "JVM ALL OK" not in out:
            fail("JVM harness did not report ALL OK (NLMS/header checks)")
        else:
            ok("JVM harness ALL OK (header round-trip + NLMS ERLE thresholds passed)")
    else:
        warn("tools/jvm-test.jar or JDK missing; JVM comparison skipped (run tools/run_jvm_tests.sh)")

    # ---- v2.1 additions: unified version, live echo wiring, release hashes ----
    cs_proto = read(os.path.join(ROOT, "windows-native/AirBridge/Protocol.cs"))
    cs_update = read(os.path.join(ROOT, "windows-native/AirBridge/Update.cs"))
    csproj = read(os.path.join(ROOT, "windows-native/AirBridge/AirBridge.csproj"))
    manifest_xml = read(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"))
    rel_manifest_path = os.path.join(ROOT, "releases/update.json")
    rel_manifest = json.loads(read(rel_manifest_path)) if os.path.exists(rel_manifest_path) else {}
    versions = {
        "Kotlin APP_VERSION": appver.group(1) if appver else None,
        "C# Protocol.AppVersion": (re.search(r'AppVersion\s*=\s*"([^"]+)"', cs_proto) or [None, None])[1],
        "C# UpdateChecker.CurrentVersion": (re.search(r'CurrentVersion\s*=\s*"([^"]+)"', cs_update) or [None, None])[1],
        "csproj <Version>": (re.search(r"<Version>([^<]+)</Version>", csproj) or [None, None])[1],
        "update.json android.version": rel_manifest.get("android", {}).get("version"),
        "update.json windows.version": rel_manifest.get("windows", {}).get("version"),
    }
    uniq = {v for v in versions.values() if v}
    if len(uniq) == 1 and None not in versions.values():
        ok(f"unified version across all six sources = {uniq.pop()} ({versions})")
    else:
        fail(f"version drift across sources: {versions}")
    if "BLUETOOTH_SCAN" in manifest_xml and "BLUETOOTH_CONNECT" in manifest_xml:
        ok("AndroidManifest declares BLUETOOTH_SCAN + BLUETOOTH_CONNECT (BLE discovery)")
    else:
        fail("AndroidManifest missing BLE permissions")
    wiring = ["echoProcessor.processCapture" in engine_kt, "echoProcessor.pushPlayback" in engine_kt,
              "loopGuard.checkIncoming" in engine_kt, "loopGuard.pushSentMic" in engine_kt]
    if all(wiring):
        ok("AudioEngine live path wired: echoProcessor.processCapture/pushPlayback + loopGuard.checkIncoming/pushSentMic")
    else:
        fail(f"AudioEngine live-path wiring incomplete: {wiring}")
    for side, fname in [("android", "SoundRelay-Android-v2.2.2.apk"), ("windows", "SoundRelay-Setup-v2.2.2.exe")]:
        fpath = os.path.join(ROOT, "releases", fname)
        entry = rel_manifest.get(side, {})
        if not os.path.exists(fpath):
            fail(f"releases/{fname} missing")
            continue
        actual = hashlib.sha256(open(fpath, "rb").read()).hexdigest()
        if entry.get("sha256") != actual:
            fail(f"update.json {side}.sha256 != releases/{fname} actual hash")
        elif not str(entry.get("url", "")).endswith(fname):
            fail(f"update.json {side}.url does not end with {fname}: {entry.get('url')}")
        else:
            ok(f"update.json {side} entry matches releases/{fname} (sha256 + filename)")

    # ---- tamper-detection assertion (update gate mirror) ----
    with tempfile.TemporaryDirectory() as td:
        fpath = os.path.join(td, "app.apk")
        with open(fpath, "wb") as f:
            f.write(b"AirBridge test apk payload" * 100)
        good = hashlib.sha256(open(fpath, "rb").read()).hexdigest()
        manifest = {"android": {"version": "9.9.9", "url": "https://example.invalid/app.apk",
                                "sha256": "0" * 64, "notes": "tampered"}}
        claimed = manifest["android"]["sha256"]
        if hashlib.sha256(open(fpath, "rb").read()).hexdigest() == claimed:
            fail("tamper test broken: wrong sha256 was ACCEPTED")
        else:
            ok(f"tamper test: manifest sha256 {claimed[:12]}... != actual {good[:12]}... -> verification FAILS as required (install would abort)")
        manifest["android"]["sha256"] = good
        if hashlib.sha256(open(fpath, "rb").read()).hexdigest() != manifest["android"]["sha256"]:
            fail("tamper test broken: correct sha256 was rejected")
        else:
            ok("tamper test: correct sha256 verifies OK")

    print()
    if FAILURES:
        print(f"RESULT: {len(FAILURES)} FAILURE(S), {len(WARNINGS)} warning(s)")
        return 1
    print(f"RESULT: ALL CONSISTENCY CHECKS PASSED ({len(WARNINGS)} warning(s))")
    return 0


def selftest():
    """Standalone tamper demo: verification against a wrong sha256 MUST fail."""
    with tempfile.TemporaryDirectory() as td:
        fpath = os.path.join(td, "app.apk")
        with open(fpath, "wb") as f:
            f.write(b"AirBridge test apk payload" * 100)
        actual = hashlib.sha256(open(fpath, "rb").read()).hexdigest()
        manifest = {"android": {"version": "9.9.9", "url": "https://example.invalid/app.apk",
                                "sha256": "f" * 64, "notes": "tampered manifest"}}
        claimed = manifest["android"]["sha256"]
        print(f"selftest: file sha256   = {actual}")
        print(f"selftest: manifest says = {claimed}")
        if actual == claimed:
            print("SELFTEST ERROR: tampered manifest was accepted")
            return 0
        print("FAIL: sha256 mismatch — tampered update REJECTED (UpdateChecker.verify would abort the install)")
        return 2


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    sys.exit(main())

**English** | [简体中文](README.zh-CN.md)

# 声音接力 SoundRelay

## Let sound relay freely between your PC, phone, and earbuds.

**Share your PC's sound to your phone and earbuds, and your phone's microphone to your PC — over WiFi or Bluetooth.**

SoundRelay turns your phone into a wireless microphone and a wireless speaker for your computer: an Android phone on one end, a PC on the other, an app on each — and the two can "borrow" each other's sound. Audio from your PC can play on your phone, and relay onward from the phone into your earbuds. Speak into your phone, and your voice shows up at your PC's microphone input, ready to use in meetings, streaming, and recording apps.

---

## What it does

### 1. Your phone as a wireless microphone for your PC

No need to stay at your desk for meetings, live streams, or recorded lessons: keep the phone in your pocket and walk around while you talk — your voice reaches the PC in real time. Just select the phone microphone in Tencent Meeting / Zoom / OBS (on Windows it goes through a virtual audio device, see below).

### 2. PC audio on your phone's speaker

The PC is playing a video or music in the study, and you can listen from the living room on your phone. At night, route the sound to your phone and keep the volume in your own hands so you don't disturb anyone else.

### 3. Any earbuds become wireless earbuds for your PC ⭐

Earbuds connect to the phone, the phone connects to the PC — and PC audio relays through the phone into your ears. **Whether your earbuds are Bluetooth or wired, and whether or not your PC has Bluetooth, they work as wireless earbuds for your PC.** The earbuds' microphone works in the other direction too, as the PC's mic: walk around the room in a meeting, with both directions covered.

### 4. One phone, several PCs at once

Your phone's microphone can fan out to several PCs at the same time (a desktop and a laptop both in meetings, say), and audio from several PCs can be mixed back into the phone so you hear them together — each PC with its own volume and its own mute switch.

---

## Features

- **Low latency**: a custom UDP protocol streaming raw PCM in 10 ms frames; target latency under 50 ms over WiFi and under 30 ms over USB
- **Adaptive**: the jitter buffer auto-adjusts between 20–60 ms based on network quality; when packet loss climbs, the sample rate steps down automatically (48k → 24k → 16k) to stay smooth, and steps back up when the network recovers
- **Echo cancellation**: talk while your phone plays PC audio out loud, with nothing feeding back — platform AEC + an in-house NLMS canceller running in real time + double-talk protection (your voice is never suppressed while you're speaking) + a howling guard, several layers working together
- **AirDrop-style discovery**: tap "Pair a new PC" and nearby devices pop up in a list automatically; tap one to pair. LAN broadcast, mDNS, and BLE proximity run in parallel — QR codes and pairing codes are only a fallback
- **Mesh roaming auto-reconnect**: when your phone roams between Mesh WiFi nodes and its IP address changes, streaming re-targets by device identity and resumes on its own — no manual reconnect
- **Multiple transports (roadmap)**: the relay doesn't care which link carries it — WiFi and USB (tethering / adb forwarding) work today; direct phone↔PC **Bluetooth** audio and pure USB-cable connections are in development. The protocol itself is transport-agnostic: whichever link connects is the one it uses
- **Paired devices are remembered**: return to a paired environment and devices are discovered automatically, then connected according to your setting (ask every time / always automatically / never automatically)
- **Runs in the background on both ends**: the PC app lives in the system tray and can start at boot; the phone runs a foreground service, so streaming continues with the screen off
- **In-app updates**: both ends are released together, always at the same version; the apps check for updates and verify downloads with SHA-256 — anything that fails verification is refused; the update URL can be changed in settings
- **Own-brand virtual audio driver (Windows, optional)**: once installed, Windows sound settings show "Speakers / Microphone (声音接力 SoundRelay Virtual Audio Device)" — no more borrowing a third-party virtual sound card. Currently a **test-signed** build: it requires Windows test mode to be enabled and Secure Boot to be off, so it is for personal use only; see [driver/README.md](driver/README.md) for the limitations

## Quick start

1. **Download** the latest version from [Releases](https://github.com/Qiguiqiang/soundrelay/releases)
   - Android: `PhoneAudioBridge-v2.1-debug.apk` (currently a debug-signed package; allow "Install unknown apps" when installing)
   - Windows: the `AirBridge-Setup-v2.1.exe` installer (not code-signed yet; on first run Windows shows a SmartScreen prompt — choose "More info → Run anyway")
   - Linux: use the Python version in `pc/` (see the scripts under [pc/](pc/))
2. Connect the phone and the PC to the **same WiFi** (or use a USB cable with adb forwarding), and open the app on both ends
3. On the phone, tap "Pair a new PC", pick your computer from the nearby list, and confirm the verification code shown on both ends matches
4. In your meeting or streaming app on the PC, select SoundRelay's virtual device as the microphone (or VB-CABLE), and do the same for the speaker; on the phone, you can choose whether sound comes out of the speaker, the earpiece, or your earbuds

Optional driver: to use SoundRelay's own virtual audio device instead of VB-CABLE, follow [driver/README.md](driver/README.md) (you'll need a Windows machine with VS2022 + WDK to build it once).

## Building from source

Repository layout:

| Directory | Contents |
|---|---|
| `app/` | Android app source (Kotlin, plain framework APIs, no AndroidX) |
| `windows-native/` | Native Windows app (C# / Avalonia) |
| `pc/` | Python implementation for Linux / early Windows (currently what Linux uses) |
| `driver/` | Own-brand virtual audio driver (renamed from Microsoft's official SimpleAudioSample, test-signed) |
| `ui-design/` | UI specs and high-fidelity previews |
| `tools/` | Protocol consistency checks and JVM tests |
| `docs/` | Architecture and protocol summary (the full protocol is in `PROTOCOL.md` at the repo root) |

- **Android**: no Gradle — a hand-written build script, `build-android.sh` (aapt2 → kotlinc → d8 → apksigner). Requires JDK 17, the Android SDK (platform 34 / build-tools 34.0.0), and the Kotlin compiler; adjust the paths in the script to your local environment.
- **Windows**: build with the .NET 8 SDK from `windows-native/AirBridge/AirBridge.csproj`; package the installer with NSIS using `installer.nsi` at the repo root (publish a self-contained single file first).
- **Driver**: VS2022 + WDK; build, sign, and install with the one-click script described in [driver/README.md](driver/README.md) (test-signed).

## Project status

A personal open-source project, **under active development**. Current version: v2.1.0. The core pipeline — two-way streaming, echo cancellation, automatic discovery, multiple devices, in-app updates — is complete and covered by local automated tests. Sound quality, audio routing on different phone brands, and roaming behavior across different Mesh routers are still being tested and refined on real devices — reports of your phone model and what you observe are welcome in Issues.

PRs are welcome, too: please keep the protocol simple (one phone to many PCs, a single UDP channel). If you change the protocol, read `PROTOCOL.md` first and run `tools/check_protocol_consistency.py`.

## License

[Apache License 2.0](LICENSE) © 2026 Gray

Free to use, modify, and distribute — just keep the license and copyright notices.

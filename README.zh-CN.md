[English](README.md) | **简体中文**

# 声音接力 SoundRelay

## 让声音在电脑、手机和耳机之间自由接力。

**电脑的声音共享到手机和耳机，手机的麦克风共享给电脑 —— 通过 WiFi 或蓝牙。**

声音接力把你的手机变成电脑的无线麦克风和无线扬声器：一端是 Android 手机，一端是电脑，两端装上 App 就能互相「借」声音。电脑放出的声音可以在手机上响、可以顺着手机再接力进你的耳机；你对着手机说话，声音就出现在电脑的麦克风里，会议、直播、录课软件直接能用。

---

## 它能干嘛

### ① 手机当电脑的无线麦克风

开会、直播、录课时不用守着电脑：手机揣兜里走动着说，声音实时进电脑，腾讯会议 / Zoom / OBS 里选到手机麦克风即可（Windows 走虚拟音频设备，见下文）。

### ② 电脑的声音播到手机扬声器

电脑在书房放视频、放音乐，人在客厅用手机就能听；晚上怕吵到家人，把声音转到手机、音量自己控制。

### ③ 任何耳机，变成电脑的无线耳机 ⭐

耳机连手机、手机连电脑 —— 电脑的声音经手机接力进耳机。**不管你的耳机是蓝牙的还是有线的、电脑有没有蓝牙，都能当电脑的无线耳机用**，耳机上的麦克风还能反过来给电脑当麦克风：戴着耳机在屋里走动开会，两头都通。

### ④ 一台手机，同时连多台电脑

手机麦克风可以同时扇出给多台电脑（比如台式机 + 笔记本同时在开会），多台电脑的声音也能混音回手机一起听，每台电脑独立音量、独立开关。

---

## 特性

- **低延迟**：自研 UDP 协议，10 ms 一帧 PCM 直传，WiFi 下目标延迟 < 50 ms，USB 连接 < 30 ms
- **自适应**：抖动缓冲 20–60 ms 按网络质量自动调；丢包高时自动降采样率（48k → 24k → 16k）保流畅，网络恢复自动升回
- **回声消除**：手机外放电脑声音的同时说话也不回灌 —— 平台 AEC + 自研 NLMS 实时抵消 + 双讲保护（你说话时不压你的声音）+ 啸叫守卫，多层一起工作
- **AirDrop 式自动发现**：点「配对新电脑」自动弹出附近设备列表，点选即配对；局域网广播 + mDNS + BLE 近感三路并行，扫码和配对码只是兜底
- **Mesh 漫游自动重连**：WiFi 在 Mesh 节点间切换、手机 IP 变化后，按设备身份自动重新对准、恢复推流，不用手动重连
- **多通道接入（路线图）**：接力不挑链路 —— 当前已支持 WiFi 与 USB（网络共享 / adb 转发）；手机↔电脑**蓝牙直连**传声音、纯 USB 数据线直连正在开发中，协议本身与链路无关，通了哪条走哪条
- **配对过的设备会被记住**：回到配对过的环境自动发现、按你的设置自动连接（可设为每次询问 / 总是自动 / 从不自动）
- **两端后台工作**：电脑端托盘常驻、可开机自启；手机端前台服务保活，熄屏照常推流
- **在线更新**：两端同版本成套发布，App 内按内置更新地址检查更新，下载后 SHA-256 校验、校验不过拒绝安装
- **自有品牌虚拟音频驱动（Windows，可选）**：装上后 Windows 声音设置里出现「扬声器 / 麦克风（声音接力 SoundRelay 虚拟音频设备）」，不用再借第三方虚拟声卡。当前为**测试签名版**：需要开启 Windows 测试模式并关闭 Secure Boot，仅适合自用，限制见 [driver/README.md](driver/README.md)

## 快速开始

1. **下载**：到 [Releases](https://github.com/Qiguiqiang/soundrelay/releases) 下载最新版
   - Android：`SoundRelay-Android-v2.2.4.apk`（安装时请允许「安装未知应用」）
   - Windows：`SoundRelay-Setup-v2.2.4.exe` 安装包（未做代码签名，首次运行 Windows 会弹 SmartScreen 提示，点「更多信息 → 仍要运行」）
   - Linux：用 `pc/` 目录的 Python 版（见 [pc/](pc/) 内脚本）
2. 手机和电脑连**同一个 WiFi**（或用 USB 线 + adb 转发），两端打开 App
3. 手机上点「配对新电脑」，在附近列表里点你的电脑，按提示核对两端显示的验证码一致即可
4. 在电脑的会议/直播软件里，把麦克风选为声音接力的虚拟设备（或 VB-CABLE），扬声器同理；手机端可以选声音从扬声器、听筒还是耳机出

驱动（可选）：想用声音接力自有虚拟音频设备而不是 VB-CABLE 的，按 [driver/README.md](driver/README.md) 操作（需要一台装有 VS2022 + WDK 的 Windows 机器先构建一次）。

## 从源码构建

仓库结构：

| 目录 | 内容 |
|---|---|
| `app/` | Android 端源码（Kotlin，纯框架 API，无 AndroidX） |
| `windows-native/` | Windows 原生端（C# / Avalonia） |
| `pc/` | Linux / 早期 Windows 的 Python 实现（Linux 端目前用这个） |
| `driver/` | 自有品牌虚拟音频驱动（基于微软官方 SimpleAudioSample 样本改名，测试签名） |
| `ui-design/` | UI 规格与高保真预览 |
| `tools/` | 协议一致性检查与 JVM 测试 |
| `docs/` | 架构与协议摘要（完整协议见根目录 `PROTOCOL.md`） |

- **Android**：无 Gradle，采用手工构建脚本 `build-android.sh`（aapt2 → kotlinc → d8 → apksigner）。需要 JDK 17、Android SDK（platform 34 / build-tools 34.0.0）、Kotlin 编译器；脚本内路径按本地环境调整。
- **Windows**：.NET 8 SDK 打开 `windows-native/AirBridge/AirBridge.csproj` 构建；安装包用 NSIS 跑根目录 `installer.nsi` 打包（需先 publish 自包含单文件）。
- **驱动**：VS2022 + WDK，按 [driver/README.md](driver/README.md) 的一键脚本构建签名并安装（测试签名版）。

## 项目状态

个人开源项目，**积极开发中**。当前版本 v2.2.4。核心链路（双向推流、回声消除、自动发现、多设备、在线更新）已完成并通过本地自动化测试；听感、不同品牌手机的音频路由、各家 Mesh 路由器下的漫游表现等，仍在持续真机实测和打磨中 —— 欢迎在 Issues 里反馈你的机型和现象。

也欢迎 PR：协议保持简单（一台手机对多台电脑、单 UDP 通道），改协议请先看 `PROTOCOL.md` 并跑 `tools/check_protocol_consistency.py`。

## 许可证

[Apache License 2.0](LICENSE) © 2026 Gray

免费使用、修改和分发，保留许可证与版权声明即可。

---

## English Summary

**SoundRelay — let sound relay freely between your PC, your phone, and your earphones.**

SoundRelay turns an Android phone into a wireless microphone and speaker for your computer, over WiFi (or USB/Bluetooth-assisted discovery):

- **Phone as a wireless mic** for meetings, streaming and recording on the PC
- **PC audio played on the phone** — speaker, earpiece, or earphones
- **Any earphones become your PC's wireless earphones**: earphones pair with the phone, the phone pairs with the PC, and PC audio relays through the phone into your ears — the earphone mic works back into the PC, too
- **One phone, many PCs**: mic fan-out to several PCs at once, their audio mixed back

Highlights: custom UDP protocol with 10 ms PCM frames, adaptive jitter buffer and sample-rate fallback, multi-layer echo cancellation (platform AEC + in-house NLMS with double-talk protection), AirDrop-style auto discovery (LAN broadcast + mDNS + BLE), Mesh-roaming auto-reconnect, background operation on both ends, and in-app updates with SHA-256 verification. An optional branded virtual audio driver for Windows (test-signed) is included under `driver/`.

Personal open-source project under active development. Licensed under the [Apache License 2.0](LICENSE).

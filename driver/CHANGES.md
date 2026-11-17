# CHANGES — 相对微软上游样本的全部改动

基底：microsoft/Windows-driver-samples，路径 `audio/simpleaudiosample`
（微软官方 Simple Audio Sample：一个扬声器 + 一个麦克风阵列端点、WaveRT、根枚举虚拟适配器——结构与声桥需求一一对应，故选它而非端点臃肿的 SYSVAD。）
上游提交：`d49bb7d72f3651952b2b6e4ad28d14fe2247eb69`（2026-11-05），许可证 MS-PL（见 src/SimpleAudioSample 上游文件与仓库 LICENSE 口径）。

与上游逐文件 diff 的结果：**仅 2 个文件被修改**，其余 44 个文件与上游逐字节一致。

## 1. `src/SimpleAudioSample/Source/Main/SimpleAudioSample.inx`（INF 源文件，构建时生成 .inf）

[Strings] 本地化字符串区（上游本身就留了 TODO 占位符，正是为改名预留的位置）：

| 字符串 | 上游值 | 改后 |
|---|---|---|
| ProviderName | `TODO-Set-Provider` | `AirBridge` |
| MfgName | `TODO-Set-Manufacturer` | `AirBridge` |
| MsCopyRight | `TODO-Set-Copyright` | `Copyright (C) 2026 AirBridge` |
| SIMPLEAUDIOSAMPLE_SA.DeviceDesc（**适配器设备名**，决定声音设置里括号内的名字、设备管理器显示名） | `Virtual Audio Device (WDM) - Simple Audio Sample` | `声桥 AirBridge 虚拟音频设备` |
| SimpleAudioSample.SvcDesc（驱动服务显示名） | `Virtual Audio Device (WDM) - Simple Audio Sample Driver` | `声桥 AirBridge 虚拟音频设备驱动` |
| WaveSpeaker.szPname | `Simple Audio Sample Wave Speaker` | `AirBridge Wave Speaker` |
| TopologySpeaker.szPname | `Simple Audio Sample Topology Speaker` | `AirBridge Topology Speaker` |
| WaveMicArray1.szPname | `Simple Audio Sample Wave Microphone Array - Front` | `AirBridge Wave Microphone` |
| TopologyMicArray1.szPname | `Simple Audio Sample Topology Microphone Array - Front` | `AirBridge Topology Microphone` |
| MicArray1CustomName（上游未被任何节引用，仅同步品牌化） | `Internal Microphone Array - Front` | `AirBridge Microphone` |
| DriverVer | `02/22/2016, 1.0.0.1` | `11/17/2026, 2.0.0.0` |

**未改（刻意保留，与上游一致）**：
- 硬件 ID `ROOT\SimpleAudioSample` 与根枚举安装模型——按任务要求保持与样本安装模型兼容，devcon 创建设备节点仍用此 ID。
- CatalogFile 名 `SimpleAudioSample.cat`、服务名 `SimpleAudioSample`、.sys 文件名 `simpleaudiosample.sys`——均为内部标识，不出现在用户可见的端点名里，为最小改动保留。
- 全部 KS 类别/接口 GUID、引脚名 GUID（NAME_SIMPLE_AUDIO_SAMPLE、MICARRAY1_CUSTOM_NAME 等）——上游未在注册表 MediaCategories 中注册这些自定义名的友好名，端点显示名前半段由桥引脚 Category 决定（见第 2 条），保留原 GUID 避免无谓冲突。

## 2. `src/SimpleAudioSample/Source/Filters/micarray1toptable.h`（1 行）

第 64 行，麦克风拓扑桥引脚（bridge pin）的 Category：

```diff
-        &KSNODETYPE_MICROPHONE_ARRAY,                 // Category
+        &KSNODETYPE_MICROPHONE,                       // Category
```

原因：端点显示名的前半段来自桥引脚 Category 的系统本地化名。上游是「麦克风阵列」，改后为「麦克风」，与扬声器侧（上游即 `KSNODETYPE_SPEAKER` →「扬声器」，未动）配对，最终显示为：

- 输出：**扬声器 （声桥 AirBridge 虚拟音频设备）**
- 输入：**麦克风 （声桥 AirBridge 虚拟音频设备）**

内部标识（如 `eMicArrayDevice1`、MicArray* 函数/表名）不出现在任何用户界面，未改。

## 3. 新增文件（非上游）

- `build-and-sign.bat`、`install.bat`、`uninstall.bat`：Windows 侧构建签名/安装/卸载脚本。
- `一键安装.bat`（后加）：状态驱动的一键全自动脚本，内部复用 `build-and-sign.bat` 与 `install.bat` 的逻辑（编译签名 → 证书 → 开测试模式 → 写 RunOnce 重启后续装 → devcon 装设备并验活）；`INTEGRATION-IN-SETUP.md`（后加）：驱动并入主安装包的集成说明。两者均不改动上游样本代码。
- `cert/driver-test-cert.cer` / `cert/driver-test-cert.pfx`：本包生成的测试签名证书（OpenSSL，EKU=codeSigning，私钥仅存在于 .pfx 内，**TEST ONLY**）。
- `README.md`（包说明）、本文件、`src/UPSTREAM.txt`（上游获取方式记录）。

## 4. 本包阶段「未做」的事（诚实标注）

- **音频数据通路尚未接 AirBridge App**：上游样本的 render 端点收到音频后按样本逻辑处理（丢弃/模拟），capture 端点输出样本的模拟信号（静音/测试音类行为，以样本代码为准）。本包的目标是「品牌端点出现且可被系统识别选中」；把 WaveRT 环形缓冲与 App 通过共享内存/IOCTL 打通是下一开发步（见调研报告 §2.2 的设计），不在本包内。
- 未在任何 Windows 机器上实际编译/安装验证（本沙箱为 Linux，无 WDK）。脚本已按微软文档与 WDK 常规路径编写并复核，但首次在 Windows 上跑通前，README 中的预期界面描述属于「按机制推导」，不是实测截图结论。

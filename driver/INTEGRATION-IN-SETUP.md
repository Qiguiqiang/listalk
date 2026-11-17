# INTEGRATION-IN-SETUP — 虚拟音频驱动并入主安装包（给主 Setup 开发用）

目标：主安装包 `SoundRelay-Setup`（NSIS）在安装时**可选组件**形式带上本驱动，用户勾选后体验与 `一键安装.bat` 一致：装完 → 重启一次 → 自动续装 → 声音设置出现声音接力设备。本文是接口约定，不是实现代码。

## 1. 前提：二进制从哪来

- 驱动三件套 `SimpleAudioSample.inf / simpleaudiosample.sys / SimpleAudioSample.cat` 由 `build-output\` 提供，必须在**打包 Setup 之前**在 Windows 构建机上产出（跑 `一键安装.bat` 的构建步或 `build-and-sign.bat`）。
- Setup 工程应把 `build-output\` 缺失设为**构建期报错**（或至少禁用该组件并在构建日志里大声警告），绝不能打进一个没有驱动文件的安装包还让组件可勾选。
- 随组件一起打包：`cert\driver-test-cert.cer`、续装脚本（直接复用 `一键安装.bat`，它本身就是状态驱动的）、`uninstall.bat` 里的移除逻辑（见 §5）。

## 2. 组件页（NSIS）

- 形式：`Section`（可选、默认**不勾选**），放在主程序 Section 之后。
- 组件名（界面原文）：`声音接力虚拟音频设备驱动（测试签名）`
- 组件说明（Description 悬停/侧栏原文，建议照抄）：

  > 安装后，Windows 声音设置里会出现「扬声器 / 麦克风（声音接力 SoundRelay 虚拟音频设备）」，电脑声音可直接选它送往手机。注意：本驱动为测试签名版，需要关闭 Secure Boot、开启 Windows 测试模式（桌面右下角有水印），并在安装后重启一次、自动完成剩余安装。仅建议在自己的电脑上安装。不勾选也不影响使用（App 会自动回退 VB-CABLE）。

- 若打包时无 `build-output\`：该 Section 置灰并在说明里写明「本安装包未包含驱动文件」。

## 3. 组件安装流程（Section 内顺序）

1. 释放文件到 `$INSTDIR\driver\`：三件套 + `driver-test-cert.cer` + `一键安装.bat`（作续装入口）。
2. 检测 Secure Boot：`nsExec` 调 PowerShell `Confirm-SecureBootUEFI`；为 `True` 时弹 MessageBox 说明「需先在 BIOS 关闭 Secure Boot，本次跳过驱动安装，主程序不受影响」，**跳过本组件剩余步骤、不中断整个安装**。
3. 导入证书：`certutil -addstore -f Root` 与 `-f TrustedPublisher`（`nsExec::ExecToLog`，检查返回码，失败则提示并跳过）。
4. 开测试模式：`bcdedit /set testsigning on`（同上检查返回码）。
5. 写续装：注册表 `HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\RunOnce` 值名 `AirBridgeDriverSetup`，值 `"$INSTDIR\driver\一键安装.bat"`。续装脚本会认出「证书已导入、测试模式已开」，直接进入 devcon 装设备 + 验活并在成功后自删该 RunOnce 值。
6. 结尾页提供重启选项（NSIS `Reboot` / 自定义结尾页单选「现在重启」默认选中）：文案注明「重启后驱动安装会自动继续，登录后请点掉一次 UAC 提示」。

> 注：主安装程序本身以管理员权限运行时，第 3–5 步无需再提权；续装在下次登录时由 RunOnce 触发，`一键安装.bat` 自带提权逻辑。

## 4. App 侧衔接（已定口径，不变）

- PC 端 App 按端点友好名包含「声音接力 SoundRelay 虚拟音频设备」识别自有驱动（输出/输入各一）；命中走驱动通路（通路打通后），**未命中自动回退 VB-CABLE** 并在界面提示「未安装声音接力虚拟音频设备」。
- 因此组件没装、装失败、被跳过，App 都必须能正常工作——驱动永远是增强项，不是前置条件。

## 5. 卸载

- 主卸载程序：先调 devcon `remove ROOT\SimpleAudioSample`（devcon 随组件释放一份到 `$INSTDIR\driver\` 或复用 WDK 路径查找，与 `uninstall.bat` 同逻辑），再 `pnputil /delete-driver` 删除驱动包，最后删 `$INSTDIR\driver\` 与 RunOnce 残值。
- **证书删除与关闭测试模式只提示、不默认执行**（机器上可能有其他测试签名驱动在用），与 `uninstall.bat` 的逐项询问口径一致；可在卸载结尾页给一行文字指引。

## 6. 路线 B 落地后要改什么

拿到 EV 证书 + 微软证明签名后：同一组件去掉第 3–5 步的证书导入/测试模式/RunOnce 续装，改为 `pnputil /add-driver /install` 静默安装，组件说明删去测试模式与重启文案——组件名与 App 识别口径不变，用户无感升级。

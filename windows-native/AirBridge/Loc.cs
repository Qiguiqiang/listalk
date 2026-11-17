using System.Globalization;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.VisualTree;

namespace AirBridge;

/// <summary>
/// Localization (v2.2): follows the OS UI culture — CultureInfo.CurrentUICulture.
/// zh* → Chinese (the XAML/code source strings), everything else → English (default).
/// The brand name 「声音接力 SoundRelay」 is never translated.
/// Static XAML strings are translated by walking the visual tree (ApplyTo);
/// dynamic strings go through Tr/T/F at their assignment sites.
/// </summary>
public static class Loc
{
    public static bool IsZh =>
        CultureInfo.CurrentUICulture.Name.StartsWith("zh", StringComparison.OrdinalIgnoreCase);
    public static bool IsEn => !IsZh;

    /// <summary>Pick by current culture.</summary>
    public static string T(string zh, string en) => IsZh ? zh : en;

    /// <summary>Translate a known Chinese source string; unknown strings pass through.</summary>
    public static string Tr(string zh) => IsZh ? zh : (Map.TryGetValue(zh, out var en) ? en : zh);

    /// <summary>Localized string.Format.</summary>
    public static string F(string zhFormat, string enFormat, params object?[] args) =>
        string.Format(IsZh ? zhFormat : enFormat, args);

    public static readonly Dictionary<string, string> Map = new()
    {
        // ---- title bar / window chrome ----
        ["最小化"] = "Minimize",
        ["最大化 / 还原"] = "Maximize / Restore",
        ["关闭到托盘"] = "Close to tray",
        // ---- sidebar / navigation ----
        ["设备"] = "Devices",
        ["设置"] = "Settings",
        ["更新"] = "Updates",
        ["关于"] = "About",
        ["未连接"] = "Not connected",
        ["等待手机连接"] = "Waiting for phone",
        ["有新版本"] = "Update available",
        // ---- device page: status bar / hero ----
        ["断线会自动重连"] = "Reconnects automatically",
        ["打开更新检查"] = "Open update check",
        ["● LIVE · 已连接手机"] = "● LIVE · Phone connected",
        ["采样率 "] = "Sample rate ",
        ["回声消除 "] = "Echo cancellation ",
        ["手机端"] = "On phone",
        [" 帧"] = " frame",
        ["端口 "] = "Port ",
        ["版本不一致 · 请先更新手机端"] = "Version mismatch · Update the phone first",
        ["断开连接"] = "Disconnect",
        ["在手机上打开声音接力即可自动找到这台电脑"] = "Open SoundRelay on your phone and it will find this PC automatically",
        ["查看附近设备"] = "See nearby devices",
        ["启动服务"] = "Start service",
        ["停止服务"] = "Stop service",
        // ---- stats strip / sparkline ----
        ["丢包率"] = "Packet loss",
        ["抖动"] = "Jitter",
        ["抖动缓冲"] = "Jitter buffer",
        ["上行（麦克风）"] = "Uplink (mic)",
        ["下行（扬声器）"] = "Downlink (speaker)",
        [" ms · 自适应"] = " ms · Adaptive",
        [" ms · 固定"] = " ms · Fixed",
        ["延迟 · 近 60 秒"] = "Latency · Last 60 s",
        ["当前 -- ms"] = "Now -- ms",
        // ---- channel cards ----
        ["音频通道"] = "Audio channels",
        ["两条通道可独立开关"] = "Each channel can be toggled separately",
        ["麦克风 → 电脑"] = "Microphone → PC",
        ["手机的声音送到电脑，当麦克风用"] = "Phone audio goes to the PC as a microphone",
        ["输出到电脑的设备（软件在这里选手机麦克风）"] = "Output device on the PC (apps pick the phone mic here)",
        ["声音接力设备"] = "SoundRelay device",
        ["电脑 → 扬声器"] = "PC → Speaker",
        ["电脑的声音送到手机播放"] = "PC audio plays on the phone",
        ["采集电脑上的哪个声音送到手机"] = "Which PC sound source goes to the phone",
        ["手机音量"] = "Phone volume",
        ["关闭窗口将最小化到系统托盘，推流不会中断 · 在手机上也能断开这台电脑"] = "Closing the window minimizes to the tray — streaming continues · You can also disconnect this PC from the phone",
        ["推荐"] = "Recommended",
        ["备用"] = "Fallback",
        ["会与麦克风通道形成回路"] = "Would form a loop with the microphone channel",
        ["✓ 已自动识别虚拟声卡"] = "✓ Virtual audio device detected automatically",
        ["还没检测到虚拟声卡，其他软件将选不到手机麦克风（请安装声音接力虚拟音频设备或 VB-CABLE）"] = "No virtual audio device detected yet — other apps cannot pick the phone microphone (install the SoundRelay virtual audio device or VB-CABLE)",
        ["✓ 已自动识别虚拟声卡 / 环回"] = "✓ Virtual audio device / loopback detected automatically",
        // ---- virtual device status ----
        ["已安装 · 扬声器 / 麦克风（声音接力 SoundRelay 虚拟音频设备），其他软件可直接选用"] = "Installed · Speaker / Microphone (SoundRelay Virtual Audio Device) — other apps can select it directly",
        ["正常工作"] = "Working",
        ["未检测到自有虚拟设备，当前由 VB-CABLE 等第三方虚拟线缆兜底（备用）"] = "SoundRelay virtual device not detected — a third-party cable such as VB-CABLE is used as fallback",
        ["备用方案"] = "Fallback in use",
        ["未检测到虚拟音频设备：请安装声音接力虚拟音频设备组件，或安装 VB-CABLE 作为备用"] = "No virtual audio device detected: install the SoundRelay virtual audio device component, or VB-CABLE as a fallback",
        ["未安装"] = "Not installed",
        ["正在检测…"] = "Detecting…",
        ["检测中"] = "Detecting",
        // ---- discover page ----
        ["设备发现"] = "Device discovery",
        ["同一局域网内自动发现，无需输 IP"] = "Auto-discovery on the same LAN — no IP entry needed",
        ["重新扫描"] = "Rescan",
        ["正在扫描附近的手机…"] = "Scanning for nearby phones…",
        ["通过局域网广播、mDNS 与蓝牙近感三路并行查找"] = "Searching via LAN broadcast, mDNS and Bluetooth proximity in parallel",
        ["已配对设备"] = "Paired devices",
        ["配对过一次，之后自动识别"] = "Pair once — recognized automatically afterwards",
        ["（暂无已配对手机）"] = "(No paired phones yet)",
        ["附近的新设备"] = "New devices nearby",
        ["暂无新设备 · 让手机打开声音接力并连上同一 WiFi"] = "No new devices · Open SoundRelay on the phone and join the same Wi-Fi",
        ["其他配对方式（次选）"] = "Other pairing methods (fallback)",
        ["扫码或在手机上输入配对码 —— 附近发现是主路径，这两个是兜底。"] = "Scan the QR code or enter the pairing code on the phone — nearby discovery is the main path; these are fallbacks.",
        ["配对码"] = "Pairing code",
        ["配对时手机上会弹出确认，核对两端显示的 6 位配对码一致即可 · 扫码与手动输入 IP 在手机端「其他方式」里"] = "The phone will ask for confirmation — check the 6-digit code matches on both ends · QR scan and manual IP entry live under \"Other methods\" on the phone",
        ["连接"] = "Connect",
        ["自动连接"] = "Auto-connect",
        ["配对"] = "Pair",
        ["已发起…"] = "Requested…",
        ["推流中"] = "Streaming",
        ["在线"] = "Online",
        ["离线"] = "Offline",
        ["未配对"] = "Not paired",
        ["未知手机"] = "Unknown phone",
        [" · 蓝牙近感已确认在附近"] = " · Bluetooth proximity confirmed nearby",
        // ---- settings page ----
        ["修改即时生效，无需重启"] = "Changes apply immediately — no restart needed",
        ["连接与重连"] = "Connection & reconnect",
        ["手机回到附近时"] = "When the phone comes back nearby",
        ["离开超过 10 分钟再回来时的处理方式；短暂掉线（60 秒内）始终自动续连，不受此项影响"] = "What to do when it returns after 10+ minutes away; brief drops (under 60 s) always resume automatically regardless of this setting",
        ["每次询问"] = "Ask every time",
        ["总是自动连接"] = "Always connect automatically",
        ["从不自动"] = "Never automatically",
        ["开机自动启动"] = "Start on login",
        ["登录 Windows 后在托盘静默启动，不弹窗"] = "Starts silently in the tray after Windows login — no window pops up",
        ["关闭窗口时最小化到托盘"] = "Minimize to tray when the window closes",
        ["关掉窗口不退出，服务和推流继续；从托盘菜单可彻底退出"] = "Closing the window does not quit — service and streaming continue; quit fully from the tray menu",
        ["音频"] = "Audio",
        ["回声消除"] = "Echo cancellation",
        ["手机外放电脑声音时，消除被手机麦克风收回去的回声（手机端处理）"] = "Removes the echo the phone mic picks up when PC sound plays aloud on the phone (handled on the phone)",
        ["手机端处理 · 已启用"] = "On phone · Enabled",
        ["采样率策略"] = "Sample-rate policy",
        ["自适应"] = "Adaptive",
        ["锁定 48 kHz"] = "Lock 48 kHz",
        ["锁定 24 kHz"] = "Lock 24 kHz",
        ["锁定 16 kHz"] = "Lock 16 kHz",
        ["网络变差时自动降低采样率保流畅，恢复后自动升回"] = "Lowers the sample rate automatically when the network degrades, and raises it again when it recovers",
        ["声音接力虚拟音频设备"] = "SoundRelay Virtual Audio Device",
        ["自动检查更新"] = "Check for updates automatically",
        ["当前版本 v2.2.0 · 发现新版本时在侧边栏提醒"] = "Current version v2.2.0 · New versions are flagged in the sidebar",
        ["检查更新"] = "Check for updates",
        ["更新地址"] = "Update URL",
        ["更新包来源；将来可改为你自己的服务器"] = "Where update packages come from; can point to your own server later",
        ["更新状态"] = "Update status",
        ["尚未检查"] = "Not checked yet",
        ["手机与电脑版本一致性"] = "Phone / PC version match",
        ["两端同版本成套更新；版本不一致时连接会明确提示先更新哪一端，不会静默失败"] = "Both ends update together at the same version; on a mismatch the connection clearly says which end to update first — never a silent failure",
        ["通知"] = "Notifications",
        ["连接状态通知"] = "Connection status notifications",
        ["手机连接、断开、重连成功时更新托盘提示"] = "Updates the tray hint when the phone connects, disconnects or reconnects",
        ["回归询问通知"] = "Return prompt notification",
        ["手机离开后重新出现时，弹通知询问是否连接（跟随上方重连模式）"] = "Notifies and asks whether to connect when the phone reappears (follows the reconnect mode above)",
        ["诊断"] = "Diagnostics",
        ["桥接服务"] = "Bridge service",
        ["服务运行中 · 关闭窗口将最小化到托盘（可在设置里关掉）"] = "Service running · Closing the window minimizes to the tray (can be changed in Settings)",
        ["服务已停止 · 手机无法发现这台电脑"] = "Service stopped · Phones cannot discover this PC",
        ["暂停桥接"] = "Pause bridge",
        ["恢复桥接"] = "Resume bridge",
        ["发现层状态"] = "Discovery status",
        ["刷新设备列表"] = "Refresh device list",
        ["运行日志"] = "Log",
        ["声音接力 SoundRelay · 设置保存在本机，不上传任何数据"] = "SoundRelay · Settings are stored locally — nothing is uploaded",
        // ---- about page ----
        ["让声音在电脑、手机和耳机之间自由接力"] = "Let sound relay freely between PC, phone and headphones",
        ["传输协议"] = "Transport",
        ["局域网 UDP 直连，不经任何服务器，声音数据不出本地网络"] = "Direct LAN UDP — no server in between; audio never leaves the local network",
        ["虚拟音频设备"] = "Virtual audio device",
        ["本机"] = "This PC",
        ["开源许可"] = "Open-source license",
        ["Apache License 2.0 · 免费供所有人使用"] = "Apache License 2.0 · Free for everyone",
        // ---- tray menu ----
        ["声音接力 SoundRelay · 未连接"] = "SoundRelay · Not connected",
        ["打开主窗口"] = "Open main window",
        ["暂停推流"] = "Pause streaming",
        ["恢复推流"] = "Resume streaming",
        ["断开"] = "Disconnect",
        ["设置…"] = "Settings…",
        ["退出声音接力"] = "Exit SoundRelay",
        ["声音接力 SoundRelay · 重连中"] = "SoundRelay · Reconnecting",
        ["声音接力 SoundRelay · 等待手机连接"] = "SoundRelay · Waiting for phone",
        // ---- connection states (dynamic) ----
        ["已暂停"] = "Paused",
        ["重连中"] = "Reconnecting",
        ["服务未启动"] = "Service not running",
        ["在托盘或设置 → 诊断里恢复"] = "Resume from the tray or Settings → Diagnostics",
        ["连接中断，正在重连…"] = "Connection lost — reconnecting…",
        ["手机回来会自动续连"] = "Resumes automatically when the phone returns",
        [" · 重连中"] = " · Reconnecting",
        ["等待手机打开声音接力"] = "Waiting for the phone to open SoundRelay",
        ["服务已停止"] = "Service stopped",
        ["等待手机连接"] = "Waiting for phone",
        ["流畅"] = "Smooth",
        ["一般"] = "Fair",
        ["偏高"] = "High",
        ["未知"] = "unknown",
        ["桥接服务已停止，手机无法发现这台电脑"] = "The bridge service is stopped — phones cannot discover this PC",
        // ---- update modal / four states ----
        ["发现新版本"] = "Update available",
        ["正在获取更新清单…"] = "Fetching the update manifest…",
        ["正在检查更新…"] = "Checking for updates…",
        ["取消"] = "Cancel",
        ["关闭"] = "Close",
        ["知道了"] = "OK",
        [" · 下载后校验 SHA-256"] = " · SHA-256 verified after download",
        ["本次更新"] = "What's new",
        ["手机端会同步提示更新，两端同版本才能继续连接。"] = "The phone will also prompt to update — both ends must be on the same version to stay connected.",
        ["电脑端已是最新，只需更新手机"] = "This PC is up to date — only the phone needs updating",
        ["用手机扫描下方二维码打开更新页。"] = "Scan the QR code below with your phone to open the update page.",
        ["用手机扫码打开手机端更新页"] = "Scan with your phone to open the phone update page",
        ["已是最新版本"] = "You are up to date",
        ["检查更新失败"] = "Update check failed",
        ["正在下载…"] = "Downloading…",
        ["稍后提醒"] = "Remind me later",
        ["下载并安装"] = "Download & install",
        ["已暂缓更新（点「检查更新」可再次下载）"] = "Update postponed (use \"Check for updates\" to download again)",
        ["更新清单无效（内容无法解析）"] = "Invalid update manifest (could not be parsed)",
        ["下载完成，正在校验 SHA-256…"] = "Download complete — verifying SHA-256…",
        ["校验不通过，已删除下载文件"] = "Verification failed — download deleted",
        ["安装包 SHA-256 校验不通过，文件已删除，不会运行未经验证的安装包。请稍后重试。"] = "The installer's SHA-256 does not match — the file was deleted and no unverified installer will be run. Please try again later.",
        ["更新包校验失败（SHA-256 不一致），已中止安装"] = "Update package failed verification (SHA-256 mismatch) — install aborted",
        ["下载完成，校验通过 · 即将启动安装…"] = "Download complete, verified · Starting the installer…",
        ["校验通过，正在启动安装程序…"] = "Verified — starting the installer…",
        // ---- audio routing warnings (AudioService, surfaced in UI) ----
        ["采集源是手机麦克风输出设备的环回——手机的声音会被原样发回手机"] = "The capture source is a loopback of the phone-mic output device — the phone's own audio would be sent straight back to it",
        ["采集源与手机麦克风共用同一条虚拟线缆（CABLE Output ← CABLE Input）——手机麦克风会混入电脑声音被发回"] = "The capture source shares one virtual cable with the phone microphone (CABLE Output ← CABLE Input) — the phone mic would be mixed into PC audio and sent back",
        ["未启动"] = "Not started",
        ["非 Windows，跳过"] = "Not Windows — skipped",
    };

    /// <summary>Translate every static string in the window's visual tree
    /// (TextBlock text + inline Runs, string Button contents, tooltips).
    /// No-op in Chinese UI culture — XAML already carries the Chinese source.</summary>
    public static void ApplyTo(Control root)
    {
        if (IsZh) return;
        foreach (var v in root.GetVisualDescendants())
        {
            if (v is TextBlock tb)
            {
                if (!string.IsNullOrEmpty(tb.Text)) tb.Text = Tr(tb.Text!);
                if (tb.Inlines is not null)
                    foreach (var inline in tb.Inlines)
                        if (inline is Run run && !string.IsNullOrEmpty(run.Text))
                            run.Text = Tr(run.Text);
            }
            if (v is Button btn && btn.Content is string s && s.Length > 0)
                btn.Content = Tr(s);
            if (v is Control c)
            {
                var tip = ToolTip.GetTip(c);
                if (tip is string ts && ts.Length > 0) ToolTip.SetTip(c, Tr(ts));
            }
        }
    }
}

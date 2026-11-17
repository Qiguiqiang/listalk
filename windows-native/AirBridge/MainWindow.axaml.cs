using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Threading;
using Microsoft.Win32;
using QRCoder;

namespace AirBridge;

public partial class MainWindow : Window
{
    public static Action? ExitAction;
    private bool _reallyExit;

    private readonly AppConfig _config;
    private readonly AppSettings _settings;
    private readonly BridgeEngine _engine;
    private readonly AudioService _audio;
    private readonly MdnsDiscovery _mdns;
    private readonly BleAdvertiser _ble = new();

    private TrayIcon? _tray;
    private NativeMenuItem? _trayStatus;
    private NativeMenuItem? _trayPause;
    private readonly List<double> _rttHistory = new();
    private readonly List<string> _logLines = new();
    private string _lastQrPayload = "";
    private bool _wasConnected;
    private bool _uiReady;

    // brushes (UI_SPEC tokens)
    private static readonly IBrush Accent = new SolidColorBrush(Color.Parse("#2CE8A5"));
    private static readonly IBrush Warn = new SolidColorBrush(Color.Parse("#FFB224"));
    private static readonly IBrush ErrorB = new SolidColorBrush(Color.Parse("#FF5C5C"));
    private static readonly IBrush Text2 = new SolidColorBrush(Color.Parse("#9AA7B8"));
    private static readonly IBrush Text1 = new SolidColorBrush(Color.Parse("#F2F5F9"));

    public MainWindow()
    {
        _config = AppConfig.Load();
        if (_config.EnsureIdentity()) _config.Save();
        if (_config.EnsurePairCode()) _config.Save();
        _settings = AppSettings.Load();
        _engine = new BridgeEngine(_config, _settings);
        _audio = new AudioService(_engine);
        _mdns = new MdnsDiscovery(_engine);

        InitializeComponent();
        Title = "声桥 AirBridge";
        try { Icon = new WindowIcon(AssetLoader.Open(new Uri("avares://AirBridge/airbridge.ico"))); } catch { }

        _engine.Log += msg => Dispatcher.UIThread.Post(() => AppendLog(msg));
        _audio.Log += msg => Dispatcher.UIThread.Post(() => AppendLog(msg));
        _mdns.Log += msg => Dispatcher.UIThread.Post(() => AppendLog(msg));
        _ble.Log += msg => Dispatcher.UIThread.Post(() => AppendLog(msg));

        SetupTray();
        InitControls();
        _uiReady = true;

        // Background-first: engine, discovery and audio start immediately and keep
        // running in the tray when the window is closed-to-tray.
        _engine.Start();
        _mdns.Start();
        _ble.Start(_config);
        StartAudioFromSelection();
        AutoStart.Apply(_settings.StartOnLogin);

        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        timer.Tick += (_, _) => Poll();
        timer.Start();
        Poll();

        // Online update: background check a few seconds after startup, non-blocking;
        // the user is only prompted when a newer version actually exists.
        _ = Task.Run(async () =>
        {
            await Task.Delay(4000);
            await CheckForUpdatesAsync(manual: false);
        });
    }

    private bool _updateBusy;
    /// <summary>Full update flow: fetch manifest -> compare -> notes dialog -> download to
    /// %TEMP% -> SHA-256 gate (abort + error dialog on mismatch, never launch unverified)
    /// -> launch NSIS installer (upgrades in place) -> exit app.</summary>
    private async Task CheckForUpdatesAsync(bool manual)
    {
        if (_updateBusy) return;
        _updateBusy = true;
        try
        {
            string url = string.IsNullOrWhiteSpace(_settings.UpdateManifestUrl)
                ? UpdateChecker.UpdateManifestUrl : _settings.UpdateManifestUrl.Trim();
            // v2.2 defect B: a click must be VISIBLE immediately and land
            // on exactly one stated outcome — never a silent no-op. The
            // settings status line carries the state; the log carries the
            // fetch diagnostics (URL + HTTP status + manifest versions).
            if (manual) tvUpdateStatus.Text = "正在检查更新…";
            var report = await UpdateChecker.FetchReportAsync(url);
            AppendLog($"[更新] {report.Summary}");
            if (report.Error is not null || report.Manifest is null)
            {
                string reason = report.Error ?? "更新清单无效（内容无法解析）";
                tvUpdateStatus.Text = $"检查失败：{reason}";
                if (manual) await UiDialogs.InfoAsync(this, "检查更新失败", $"检查失败：{reason}");
                return;
            }
            var manifest = report.Manifest;
            var info = manifest?.Windows;
            var android = manifest?.Android;
            bool winNewer = info is not null && UpdateChecker.IsNewer(info.Version);
            // partner (Android) update: newer than this release, or newer than the
            // connected phone's reported appVersion when we know it.
            string phoneBaseline = _engine.PhoneAppVersion.Length > 0 ? _engine.PhoneAppVersion : UpdateChecker.CurrentVersion;
            bool androidNewer = android is not null &&
                (UpdateChecker.IsNewer(android.Version) || UpdateChecker.IsNewerThan(android.Version, phoneBaseline));
            if (!winNewer && !androidNewer)
            {
                tvUpdateStatus.Text = $"已是最新版本（当前 {UpdateChecker.CurrentVersion}）";
                if (manual) await UiDialogs.InfoAsync(this, "检查更新", $"已是最新版本（当前 {UpdateChecker.CurrentVersion}）。");
                return;
            }
            if (!winNewer && androidNewer)
            {
                tvUpdateStatus.Text = $"手机端有新版 v{android!.Version}（本机已是最新 {UpdateChecker.CurrentVersion}）";
                // only the phone side has an update: show it with a scannable QR
                string atext = $"手机端有新版 v{android!.Version}\n\n{android.Notes}\n\n用手机扫描下方二维码打开更新页。";
                if (manual)
                    await UpdateDialog.ShowAsync(this, $"手机端有新版 v{android.Version}", atext, android.Url, "知道了", null);
                else
                {
                    tvBanner.Text = $"手机端有新版 v{android.Version} · 到设置里点「检查更新」扫码更新";
                    tvBanner.IsVisible = true;
                    AppendLog($"[更新] 手机端有新版 v{android.Version}（检查更新对话框内有扫码）");
                }
                return;
            }
            info = info!;
            tvUpdateStatus.Text = $"发现新版本 {info.Version}（当前 {UpdateChecker.CurrentVersion}）";
            string winText = $"当前版本 {UpdateChecker.CurrentVersion}，最新版本 {info.Version}。\n\n更新说明：\n{info.Notes}" +
                (androidNewer ? $"\n\n手机端有新版 v{android!.Version}：扫描下方二维码，用手机打开更新页（两端同版本成套更新）。" : "") +
                "\n\n下载并安装？安装包会先校验 SHA-256，校验通过才会运行。";
            bool go = await UpdateDialog.ShowAsync(this, $"发现新版本 {info.Version}", winText,
                androidNewer ? android!.Url : null, "下载并安装", "稍后再说");
            if (!go) { tvUpdateStatus.Text = $"发现新版本 {info.Version}，已暂缓（点「检查更新」可再次下载）"; return; }
            tvUpdateStatus.Text = $"正在下载新版本 {info.Version}…";
            AppendLog($"[更新] 开始下载 {info.Version} …");
            string file = await UpdateChecker.DownloadAsync(info);
            bool ok = await UpdateChecker.VerifySha256Async(file, info.Sha256);
            if (!ok)
            {
                try { File.Delete(file); } catch { }
                tvUpdateStatus.Text = "更新包校验失败（SHA-256 不一致），已中止安装";
                await UiDialogs.InfoAsync(this, "更新已中止",
                    "安装包 SHA-256 校验不通过，文件已删除，不会运行未经验证的安装包。请稍后重试。");
                AppendLog("[更新] SHA-256 校验失败，已中止并删除下载文件");
                return;
            }
            tvUpdateStatus.Text = "校验通过，正在启动安装程序…";
            AppendLog("[更新] SHA-256 校验通过，启动安装程序并退出…");
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(file) { UseShellExecute = true });
            RequestExit();
        }
        catch (Exception ex)
        {
            tvUpdateStatus.Text = $"更新失败：{ex.Message}";
            if (manual) await UiDialogs.InfoAsync(this, "更新失败", ex.Message);
            else AppendLog($"[更新] 失败：{ex.Message}");
        }
        finally { _updateBusy = false; }
    }

    // ---------------- controls ----------------
    private void InitControls()
    {
        cbMic.IsChecked = _settings.MicToPc;
        cbSpeaker.IsChecked = _settings.PcToPhone;
        cbMic.IsCheckedChanged += (_, _) => { _settings.MicToPc = cbMic.IsChecked == true; _settings.Save(); };
        cbSpeaker.IsCheckedChanged += (_, _) => { _settings.PcToPhone = cbSpeaker.IsChecked == true; _settings.Save(); };

        RefreshDeviceCombos();
        comboOutput.SelectionChanged += (_, _) => OnDeviceSelectionChanged();
        comboInput.SelectionChanged += (_, _) => OnDeviceSelectionChanged();
        btnRefreshDevices.Click += (_, _) => { RefreshDeviceCombos(); StartAudioFromSelection(); };

        comboRate.ItemsSource = new[] { "自动（按链路质量 48k→24k→16k）", "固定 48000 Hz", "固定 24000 Hz", "固定 16000 Hz" };
        comboRate.SelectedIndex = _settings.SampleRatePreference switch { "48000" => 1, "24000" => 2, "16000" => 3, _ => 0 };
        comboRate.SelectionChanged += (_, _) =>
        {
            _settings.SampleRatePreference = comboRate.SelectedIndex switch { 1 => "48000", 2 => "24000", 3 => "16000", _ => "auto" };
            _settings.Save();
            _engine.ApplyRatePreference();
        };

        cbAutoStart.IsChecked = _settings.StartOnLogin;
        cbCloseToTray.IsChecked = _settings.CloseToTray;
        cbNotifications.IsChecked = _settings.Notifications;
        cbAutoReconnect.IsChecked = _settings.AllowPairedAutoReconnect;
        cbAutoStart.IsCheckedChanged += (_, _) => { _settings.StartOnLogin = cbAutoStart.IsChecked == true; _settings.Save(); AutoStart.Apply(_settings.StartOnLogin); AppendLog($"[设置] 开机自启 {(_settings.StartOnLogin ? "开" : "关")}（注册表 HKCU Run）"); };
        cbCloseToTray.IsCheckedChanged += (_, _) => { _settings.CloseToTray = cbCloseToTray.IsChecked == true; _settings.Save(); };
        cbNotifications.IsCheckedChanged += (_, _) => { _settings.Notifications = cbNotifications.IsChecked == true; _settings.Save(); };
        cbAutoReconnect.IsCheckedChanged += (_, _) => { _settings.AllowPairedAutoReconnect = cbAutoReconnect.IsChecked == true; _settings.Save(); AppendLog($"[设置] 允许已配对手机自动重连 {(_settings.AllowPairedAutoReconnect ? "开" : "关")}"); };

        txtUpdateUrl.Text = string.IsNullOrWhiteSpace(_settings.UpdateManifestUrl)
            ? UpdateChecker.UpdateManifestUrl : _settings.UpdateManifestUrl;
        txtUpdateUrl.TextChanged += (_, _) =>
        {
            if (!_uiReady) return;
            _settings.UpdateManifestUrl = (txtUpdateUrl.Text ?? "").Trim();
            _settings.Save();
        };
        btnCheckUpdate.Click += async (_, _) => await CheckForUpdatesAsync(manual: true);
        btnVersionUpdate.Click += async (_, _) => await CheckForUpdatesAsync(manual: true);

        btnPairSelected.Click += (_, _) =>
        {
            if (listNearby.SelectedItem is NearbyDevice d)
            {
                if (d.Paired) { _engine.TapConnect(d); tvNearbyHint.Text = $"已请求 {d.Name} 连接（60 秒内放行它的自动重连）"; }
                else { _engine.TapPair(d); tvNearbyHint.Text = $"正在与 {d.Name} 配对：请看手机上的确认屏，并核对下方验证码一致后在手机上确认"; }
            }
            else tvNearbyHint.Text = "先在列表里点选一台手机";
        };
        listNearby.DoubleTapped += (_, _) => btnPairSelected.RaiseEvent(new Avalonia.Interactivity.RoutedEventArgs(Button.ClickEvent));

        btnPause.Click += (_, _) => TogglePause();
        btnToggleService.Click += (_, _) =>
        {
            if (_engine.Running) { _engine.Stop(); _audio.Stop(); btnToggleService.Content = "启动服务"; }
            else { _engine.Start(); StartAudioFromSelection(); btnToggleService.Content = "停止服务"; }
        };
    }

    private void RefreshDeviceCombos()
    {
        var (inputs, outputs) = _audio.Enumerate();
        comboOutput.ItemsSource = outputs;
        comboInput.ItemsSource = inputs;
        var outPick = _audio.PickOutput(outputs, _config.OutputDeviceId);
        // auto-pick never lands on the capture twin of the mic device (5b)
        var inPick = _audio.PickInput(inputs, _config.InputDeviceId, outPick);
        comboOutput.SelectedItem = outPick;
        comboInput.SelectedItem = inPick;
        UpdateDeviceBadges();
    }

    private void UpdateDeviceBadges()
    {
        if (comboOutput.SelectedItem is AudioDeviceInfo o)
        {
            tvOutBadge.Text = o.Score > 0 ? "✓ 已自动识别虚拟声卡" : "还没检测到虚拟声卡，其他软件将选不到手机麦克风（请安装 VB-CABLE）";
            tvOutBadge.Foreground = o.Score > 0 ? Accent : Warn;
        }
        if (comboInput.SelectedItem is AudioDeviceInfo i)
        {
            // defect 5b: a capture source containing the mic render is flagged
            // right at selection time (Start() also blocks it from sending)
            var conflict = comboOutput.SelectedItem is AudioDeviceInfo od
                ? AudioService.RoutingConflict(i, od) : null;
            if (conflict is not null)
            {
                tvInBadge.Text = "⚠ " + conflict;
                tvInBadge.Foreground = Warn;
            }
            else
            {
                tvInBadge.Text = i.Score > 0 ? "✓ 已自动识别虚拟声卡 / 环回" : "";
                tvInBadge.Foreground = i.Score > 0 ? Accent : Warn;
            }
        }
    }

    private void OnDeviceSelectionChanged()
    {
        if (!_uiReady) return;
        UpdateDeviceBadges();
        StartAudioFromSelection();
    }

    private void StartAudioFromSelection()
    {
        if (comboInput.SelectedItem is not AudioDeviceInfo inp || comboOutput.SelectedItem is not AudioDeviceInfo outp) return;
        try
        {
            _audio.Start(inp, outp);
            _config.InputDeviceId = inp.Id; _config.OutputDeviceId = outp.Id; _config.InputIsLoopback = inp.IsLoopback;
            _config.Save();
        }
        catch (Exception ex) { AppendLog($"[音频] 启动失败：{ex.Message}"); }
    }

    // ---------------- tray / background ----------------
    private void SetupTray()
    {
        _trayStatus = new NativeMenuItem("声桥 AirBridge · 未连接") { IsEnabled = false };
        var open = new NativeMenuItem("打开窗口");
        open.Click += (_, _) => ShowFromTray();
        _trayPause = new NativeMenuItem("暂停桥接");
        _trayPause.Click += (_, _) => TogglePause();
        var exit = new NativeMenuItem("退出");
        exit.Click += (_, _) => RequestExit();
        var menu = new NativeMenu();
        menu.Items.Add(_trayStatus);
        menu.Items.Add(new NativeMenuItemSeparator());
        menu.Items.Add(open);
        menu.Items.Add(_trayPause);
        menu.Items.Add(new NativeMenuItemSeparator());
        menu.Items.Add(exit);
        _tray = new TrayIcon { ToolTipText = "声桥 AirBridge", Menu = menu };
        try { _tray.Icon = new WindowIcon(AssetLoader.Open(new Uri("avares://AirBridge/airbridge.ico"))); } catch { }
        _tray.Clicked += (_, _) => ShowFromTray();
        TrayIcon.SetIcons(Application.Current!, new TrayIcons { _tray });
    }

    private void ShowFromTray()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    public void RequestExit()
    {
        _reallyExit = true;
        _settings.Save(); _config.Save();
        try { _engine.Stop(); } catch { }
        try { _audio.Dispose(); } catch { }
        try { _mdns.Dispose(); } catch { }
        _ble.Stop();
        if (_tray is not null) _tray.IsVisible = false;
        ExitAction?.Invoke();
        Close();
    }

    protected override void OnClosing(WindowClosingEventArgs e)
    {
        if (!_reallyExit && _settings.CloseToTray)
        {
            e.Cancel = true;
            Hide(); // beacon + connection acceptance keep running in the tray
            return;
        }
        if (!_reallyExit) RequestExit();
        base.OnClosing(e);
    }

    private void TogglePause()
    {
        _settings.Paused = !_settings.Paused;
        _settings.Save();
        btnPause.Content = _settings.Paused ? "恢复桥接" : "暂停桥接";
        if (_trayPause is not null) _trayPause.Header = _settings.Paused ? "恢复桥接" : "暂停桥接";
        AppendLog(_settings.Paused ? "[桥接] 已暂停（发现与配对仍在运行，音频已静默）" : "[桥接] 已恢复");
    }

    // ---------------- periodic UI ----------------
    private void Poll()
    {
        // pair-code rotation (10 min) + QR refresh
        if (_config.EnsurePairCode()) _config.Save();
        tvPairCode.Text = _config.PairCode;
        int left = _config.PairCodeSecondsLeft;
        tvPairCountdown.Text = $"配对码 {_config.PairCode}（剩 {left / 60} 分 {left % 60} 秒，过期自动轮换）";
        string payload = _engine.PairingQrPayload();
        if (payload != _lastQrPayload)
        {
            _lastQrPayload = payload;
            try
            {
                var gen = new QRCodeGenerator();
                var data = gen.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
                var png = new PngByteQRCode(data).GetGraphic(8);
                imgQr.Source = new Bitmap(new MemoryStream(png));
            }
            catch (Exception ex) { AppendLog($"[二维码] 生成失败：{ex.Message}"); }
        }
        tvVerify.Text = string.IsNullOrEmpty(_engine.LastVerifyCode) ? ""
            : $"核对验证码 {_engine.LastVerifyCode}（与手机上一致再确认 · {_engine.LastPairPhone}）";

        // hero / stats
        var s = _engine.Stats;
        if (_engine.Connected)
        {
            tvStatus.Text = "● 已连接手机"; tvStatus.Foreground = Accent;
            tvHeroTitle.Text = $"已连接手机 · {_engine.PeerName}";
            tvHeroSub.Text = $"{_engine.Peer?.Address} · {Protocol.RateHz(_engine.RateCode)} Hz" +
                (_engine.PhoneAppVersion.Length > 0 ? $" · 手机端 v{_engine.PhoneAppVersion}" : "");
            if (s.RttMs >= 0)
            {
                string word = s.RttMs < 30 ? "流畅" : (s.RttMs <= 80 ? "一般" : "偏高");
                tvBadge.Text = $"{s.RttMs:F0} ms · {word}";
                tvBadge.Foreground = s.RttMs < 30 ? Accent : (s.RttMs <= 80 ? Warn : ErrorB);
                _rttHistory.Add(s.RttMs);
                while (_rttHistory.Count > 60) _rttHistory.RemoveAt(0);
            }
            tvStats.Text = $"丢包 {s.LossPct:F1}% · 抖动 {s.JitterMs:F1} ms · 缓冲 {_engine.Jb.TargetMs} ms · 发 {s.Tx} / 收 {s.Rx}";
            if (!_wasConnected && _settings.Notifications)
            {
                tvBanner.Text = $"✓ 已连接 {_engine.PeerName}";
                tvBanner.IsVisible = true;
                if (_tray is not null) _tray.ToolTipText = $"声桥 AirBridge · 已连接 {_engine.PeerName}";
            }
            _wasConnected = true;
        }
        else
        {
            tvStatus.Text = _engine.Running ? "● 等待手机连接" : "● 未启动";
            tvStatus.Foreground = Text2;
            tvHeroTitle.Text = _engine.Running ? "等待手机…" : "服务未启动";
            tvHeroSub.Text = $"在手机上打开声桥即可自动找到这台电脑 · 本机 IP：{BridgeEngine.LocalIp()}";
            tvBadge.Text = "-- ms"; tvBadge.Foreground = Text2;
            tvBanner.IsVisible = false;
            _wasConnected = false;
            if (_tray is not null && _settings.Notifications) _tray.ToolTipText = "声桥 AirBridge · 等待手机连接";
        }
        // loop / routing warnings (defect 5b): rail banner, always visible
        string? loopMsg = _engine.LoopDetected
            ? "⚠ 检测到手机麦克风声音被回传，已暂停向手机发送电脑声音（相关度 " + _engine.Loop.LastCorrelation.ToString("F2") + "），请改选采集设备"
            : _audio.RoutingWarning;
        tvLoopWarn.Text = loopMsg ?? "";
        tvLoopWarn.IsVisible = loopMsg is not null;
        // unified-versioning mismatch: loud in hero + status, audio keeps running
        if (_engine.VersionMismatch)
        {
            versionWarnPanel.IsVisible = true;
            tvVersionWarn.Text = $"版本不一致：手机端 v{(_engine.PhoneAppVersion.Length > 0 ? _engine.PhoneAppVersion : "未知")}，本机 v{Protocol.AppVersion}，请先更新手机端";
            if (_engine.Connected) { tvStatus.Text = "⚠ 已连接 · 版本不一致"; tvStatus.Foreground = Warn; }
        }
        else versionWarnPanel.IsVisible = false;
        if (_trayStatus is not null)
            _trayStatus.Header = _engine.Connected ? $"声桥 AirBridge · 已连接 {_engine.PeerName}（{s.RttMs:F0} ms）" : "声桥 AirBridge · 等待手机连接";
        meterMic.Value = Math.Clamp(s.LevelOut, 0, 1);
        meterSpk.Value = Math.Clamp(s.LevelIn, 0, 1);
        if (!_engine.Connected) { s.LevelIn *= 0.8; s.LevelOut *= 0.8; }

        // sparkline (last 60 RTT samples)
        if (_rttHistory.Count >= 2 && sparkCanvas.Bounds.Width > 0)
        {
            double w = sparkCanvas.Bounds.Width, h = 56;
            double maxv = Math.Max(50.0, _rttHistory.Max() * 1.2);
            var pts = new Points();
            for (int i = 0; i < _rttHistory.Count; i++)
                pts.Add(new Point(6 + i * (w - 12) / 59.0, h - 6 - (_rttHistory[i] / maxv) * (h - 12)));
            sparkLine.Points = pts;
        }

        // nearby + paired lists (preserve nearby selection)
        string? selKey = (listNearby.SelectedItem as NearbyDevice)?.Key;
        var nearby = _engine.NearbySnapshot();
        listNearby.ItemsSource = nearby;
        if (selKey is not null) listNearby.SelectedItem = nearby.FirstOrDefault(d => d.Key == selKey);
        var paired = _config.PairedPhones
            .Select(kv => $"{kv.Value.Name} · {kv.Key} · 上次连接 {DateTimeOffset.FromUnixTimeSeconds((long)kv.Value.LastSeen).LocalDateTime:MM-dd HH:mm}" + (kv.Value.AppVersion.Length > 0 ? $" · 手机端 v{kv.Value.AppVersion}" : ""))
            .ToList();
        listPaired.ItemsSource = paired.Count > 0 ? paired : new List<string> { "（暂无已配对手机）" };

        tvDiscoveryStatus.Text = $"发现层：UDP 广播/信标 已运行 · mDNS {_mdns.Status} · BLE {_ble.Status}";
        if (!string.IsNullOrEmpty(_engine.LastEvent)) tvNearbyHint.Text = _engine.LastEvent;
    }

    private void AppendLog(string msg)
    {
        _logLines.Add($"{DateTime.Now:HH:mm:ss} {msg}");
        while (_logLines.Count > 200) _logLines.RemoveAt(0);
        txtLog.Text = string.Join("\n", _logLines);
        txtLog.CaretIndex = txtLog.Text?.Length ?? 0;
    }
}

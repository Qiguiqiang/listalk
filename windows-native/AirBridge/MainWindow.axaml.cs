using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Threading;
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
    private NativeMenuItem? _trayMic;
    private NativeMenuItem? _traySpk;
    private NativeMenuItem? _trayDisc;

    private readonly List<double> _rttHistory = new();
    private readonly List<string> _logLines = new();
    private string _lastQrPayload = "";
    private bool _wasConnected;
    private bool _uiReady;

    private enum Page { Device, Discover, Settings, About }
    private Page _page = Page.Device;
    private DateTime _discoverShownAt = DateTime.UtcNow;
    private DateTime? _connectedSinceUtc;
    private DateTime _lastSparkPush = DateTime.MinValue;
    private DateTime _lastPollUtc = DateTime.UtcNow;
    private long _lastRx, _lastTx;
    private string _lastListSig = "";
    private System.Threading.Timer? _volSendTimer;
    private bool _volSynced;        // phone volume pulled from HELLO for this session
    private bool _userDisconnected; // B-08: session ended by the user — no reconnect UI
    private bool _chanSingle;
    private bool _everConnected;
    private DateTime? _disconnectedAtUtc;
    private string _lastPeerName = "";

    private List<AudioDeviceInfo> _inputDevices = new();
    private List<AudioDeviceInfo> _outputDevices = new();
    private AudioDeviceInfo? _selInput;
    private AudioDeviceInfo? _selOutput;

    // brushes (UI_SPEC tokens)
    private static readonly IBrush Accent = new SolidColorBrush(Color.Parse("#2CE8A5"));
    private static readonly IBrush Warn = new SolidColorBrush(Color.Parse("#FFB224"));
    private static readonly IBrush ErrorB = new SolidColorBrush(Color.Parse("#FF5C5C"));
    private static readonly IBrush Text2 = new SolidColorBrush(Color.Parse("#9AA7B8"));
    private static readonly IBrush Text3 = new SolidColorBrush(Color.Parse("#5F6D80"));
    private static readonly IBrush SurfaceInB = new SolidColorBrush(Color.Parse("#0F141C"));
    private static readonly IBrush WarnDim = new SolidColorBrush(Color.Parse("#1FFFB224"));
    private static readonly IBrush AccentDim = new SolidColorBrush(Color.Parse("#12362A"));
    private static readonly IBrush SkyDim = new SolidColorBrush(Color.Parse("#12303F"));
    private static readonly IBrush Text1B = new SolidColorBrush(Color.Parse("#F2F5F9"));
    private static readonly IBrush ErrorDim = new SolidColorBrush(Color.Parse("#3A1D22"));
    private static readonly IBrush AccentBorder = new SolidColorBrush(Color.Parse("#592CE8A5"));
    private static readonly IBrush WarnBorder = new SolidColorBrush(Color.Parse("#59FFB224"));
    private static readonly IBrush ErrorBorder = new SolidColorBrush(Color.Parse("#59FF5C5C"));

    public MainWindow()
    {
        _config = AppConfig.Load();
        if (_config.EnsureIdentity()) _config.Save();
        if (_config.EnsurePairCode()) _config.Save();
        _settings = AppSettings.Load();
        StartupLog.Stage("MainWindow: config + settings loaded");
        _engine = new BridgeEngine(_config, _settings);
        _engine.VirtualCamera = new VirtualCameraService(_engine);
        _audio = new AudioService(_engine);
        _mdns = new MdnsDiscovery(_engine);

        InitializeComponent();
        StartupLog.Stage("MainWindow: XAML loaded");
        Title = "听说 LisTalk";
        try { Icon = new WindowIcon(AssetLoader.Open(new Uri("avares://AirBridge/airbridge.ico"))); } catch { }

        _engine.Log += msg => Dispatcher.UIThread.Post(() => AppendLog(msg));
        _audio.Log += msg => Dispatcher.UIThread.Post(() => AppendLog(msg));
        _mdns.Log += msg => Dispatcher.UIThread.Post(() => AppendLog(msg));
        _ble.Log += msg => Dispatcher.UIThread.Post(() => AppendLog(msg));

        SetupTray();
        StartupLog.Stage("MainWindow: tray ready");
        InitControls();
        Loc.ApplyTo(this); // static XAML strings -> system UI language (no-op for zh)
        _uiReady = true;
        StartupLog.Stage("MainWindow: controls ready");

        // Background-first: engine, discovery and audio start immediately and keep
        // running in the tray when the window is closed-to-tray.
        // FB-2 hardening: each subsystem start is individually guarded — a
        // UDP bind conflict (e.g. a previous version still holding the port)
        // or a broken audio stack must degrade to the in-app "service not
        // running" state, never kill the window before it appears.
        try { _engine.Start(); StartupLog.Stage("MainWindow: bridge engine started"); }
        catch (Exception ex)
        {
            StartupLog.Error("BridgeEngine.Start", ex);
            AppendLog($"[服务] 桥接服务启动失败：{ex.Message}（启动日志见 %LOCALAPPDATA%\\AirBridge\\logs\\startup.log）");
        }
        // FB-2 hardening, same rule as the engine start above (B-13):
        // mDNS/BLE are optional discovery transports — a start failure
        // degrades discovery (their Status strings keep saying 未启动 in
        // the diagnostics row), it must never kill window construction.
        try { _mdns.Start(); StartupLog.Stage("MainWindow: mDNS started"); }
        catch (Exception ex)
        {
            StartupLog.Error("MdnsDiscovery.Start", ex);
            AppendLog($"[发现] mDNS 启动失败（不影响广播发现）：{ex.Message}");
        }
        try { _ble.Start(_config); StartupLog.Stage("MainWindow: BLE advertiser started"); }
        catch (Exception ex)
        {
            StartupLog.Error("BleAdvertiser.Start", ex);
            AppendLog($"[发现] 蓝牙近感启动失败（不影响局域网发现）：{ex.Message}");
        }
        StartAudioFromSelection();
        AutoStart.Apply(_settings.StartOnLogin);

        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        timer.Tick += (_, _) => Poll();
        timer.Start();
        var meterTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) };
        meterTimer.Tick += (_, _) => PollMeters();
        meterTimer.Start();
        try { Poll(); StartupLog.Stage("MainWindow: first poll done"); }
        catch (Exception ex) { StartupLog.Error("first Poll", ex); }
        StartupLog.StartupCompleted = true;
        StartupLog.Stage("MainWindow: startup complete");

        // Online update: background check a few seconds after startup, non-blocking;
        // the user is only prompted when a newer version actually exists.
        if (_settings.AutoCheckUpdates)
        {
            _ = Task.Run(async () =>
            {
                await Task.Delay(4000);
                await CheckForUpdatesAsync(manual: false);
            });
        }
    }

    // ---------------- title bar / navigation ----------------
    private void InitTitleBar()
    {
        tbMachine.Text = "· " + Environment.MachineName;
        btnMin.Click += (_, _) => WindowState = WindowState.Minimized;
        btnMax.Click += (_, _) => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
        btnCloseWin.Click += (_, _) => Close();
        // FB-5 (v2.2.3): maximize button shows the restore glyph while maximized.
        PropertyChanged += (_, e) =>
        {
            if (e.Property == WindowStateProperty)
            {
                bool max = WindowState == WindowState.Maximized;
                pathMaxIcon.IsVisible = !max;
                pathRestoreIcon.IsVisible = max;
            }
            if (e.Property == WindowStateProperty || e.Property == IsVisibleProperty)
                UpdateDiscoveryActive();
        };
        titleBar.PointerPressed += (_, e) =>
        {
            if (!e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) return;
            for (var v = e.Source as Visual; v is not null; v = v.Parent as Visual)
                if (v is Button) return; // clicks on the window buttons are not drags
            BeginMoveDrag(e);
        };
        navDevice.Click += (_, _) => ShowPage(Page.Device);
        navSettings.Click += (_, _) => ShowPage(Page.Settings);
        navAbout.Click += (_, _) => ShowPage(Page.About);
        navUpdate.Click += (_, _) => OpenUpdateEntry();
        btnSideUpdate.Click += (_, _) => OpenUpdateEntry();
    }

    /// <summary>Full-rate sweep hint: since the discovery hardening
    /// (§5.5) the engine keeps sweeping at a reduced rate in the
    /// background whenever a paired phone is unconnected, so page
    /// navigation must not gate it any more — DiscoveryActive only
    /// selects the full rate while the window is actually up.</summary>
    private void UpdateDiscoveryActive()
    {
        _engine.DiscoveryActive = IsVisible && WindowState != WindowState.Minimized;
    }

    private void ShowPage(Page p)
    {
        _page = p;
        pageDevice.IsVisible = p == Page.Device;
        pageDiscover.IsVisible = p == Page.Discover;
        pageSettings.IsVisible = p == Page.Settings;
        pageAbout.IsVisible = p == Page.About;
        UpdateDiscoveryActive();
        SetNav(navDevice, navDeviceBar, p == Page.Device);
        SetNav(navSettings, navSettingsBar, p == Page.Settings);
        SetNav(navUpdate, navUpdateBar, false);
        SetNav(navAbout, navAboutBar, p == Page.About);
        if (p == Page.Discover) { _discoverShownAt = DateTime.UtcNow; _lastListSig = ""; }
    }

    private static void SetNav(Button btn, Border bar, bool on)
    {
        if (on) { if (!btn.Classes.Contains("on")) btn.Classes.Add("on"); }
        else btn.Classes.Remove("on");
        bar.IsVisible = on;
    }

    // ---------------- controls ----------------
    private void InitControls()
    {
        InitTitleBar();
        sideVersion.Text = "v" + UpdateChecker.CurrentVersion;
        navAboutVer.Text = "v" + UpdateChecker.CurrentVersion;
        tbAboutVer.Text = Loc.F("版本 v{0} · Windows 版", "Version v{0} · Windows", UpdateChecker.CurrentVersion);
        tbSetFoot.Text = Loc.F("听说 LisTalk v{0} · 设置保存在本机，不上传任何数据", "LisTalk v{0} · Settings are stored locally — nothing is uploaded", UpdateChecker.CurrentVersion);
        tbPortRun.Text = _config.Port.ToString();
        tbLocalChip.Text = Loc.F("本机 {0} · {1}", "This PC {0} · {1}", Environment.MachineName, BridgeEngine.LocalIp());
        tbAboutProto.Text = Loc.F("UDP · {0} · PCM16 · 协议 v{1}", "UDP · {0} · PCM16 · Protocol v{1}", _config.Port, Protocol.ProtoVersion);
        tvAboutLocal.Text = Loc.F("{0} · {1} · 设备 ID {2}…", "{0} · {1} · Device ID {2}…", Environment.MachineName, BridgeEngine.LocalIp(), _config.DeviceId[..Math.Min(6, _config.DeviceId.Length)]);

        // channels
        swMic.IsChecked = _settings.MicToPc;
        swSpk.IsChecked = _settings.PcToPhone;
        swMic.CheckedChanged += (_, v) => { _settings.MicToPc = v; _settings.Save(); SyncTrayChannels(); UpdateChannelCards(); };
        swSpk.CheckedChanged += (_, v) => { _settings.PcToPhone = v; _settings.Save(); SyncTrayChannels(); UpdateChannelCards(); };
        volSlider.Value = _settings.PhoneVolumePercent;
        volVal.Text = $"{_settings.PhoneVolumePercent}%";
        volSlider.ValueChanged += (_, e) =>
        {
            int v = (int)Math.Round(e.NewValue);
            volVal.Text = $"{v}%";
            if (_uiReady)
            {
                _settings.PhoneVolumePercent = v; _settings.Save();
                _volSynced = true; // an explicit user value supersedes the HELLO sync
                // remote volume: trailing-edge throttle ~100 ms -> TYPE_SET_VOLUME
                _volSendTimer?.Dispose();
                _volSendTimer = new System.Threading.Timer(
                    _ => Dispatcher.UIThread.Post(() => _engine.SendPhoneVolume(v)),
                    null, 100, System.Threading.Timeout.Infinite);
            }
        };
        UpdateChannelCards();

        RefreshDeviceCombos();
        btnRefreshDevices.Click += (_, _) => { RefreshDeviceCombos(); StartAudioFromSelection(); };

        chanGrid.SizeChanged += (_, e) =>
        {
            bool single = e.NewSize.Width < 720;
            if (single == _chanSingle) return;
            _chanSingle = single;
            if (single)
            {
                chanGrid.ColumnDefinitions = new ColumnDefinitions("*");
                chanGrid.RowDefinitions = new RowDefinitions("Auto,Auto");
                Grid.SetColumn(cardSpk, 0); Grid.SetRow(cardSpk, 1);
                cardMic.Margin = new Thickness(0); cardSpk.Margin = new Thickness(0, 14, 0, 0);
            }
            else
            {
                chanGrid.ColumnDefinitions = new ColumnDefinitions("*,*");
                chanGrid.RowDefinitions = new RowDefinitions("Auto");
                Grid.SetColumn(cardSpk, 1); Grid.SetRow(cardSpk, 0);
                cardMic.Margin = new Thickness(0, 0, 7, 0); cardSpk.Margin = new Thickness(7, 0, 0, 0);
            }
        };

        // hero / service actions
        btnDisconnect.Click += (_, _) => DisconnectSession();
        btnStartService.Click += (_, _) => SetServiceRunning(true);
        btnGotoDiscover.Click += (_, _) => ShowPage(Page.Discover);
        btnToggleService.Click += (_, _) => SetServiceRunning(!_engine.Running);
        btnPause.Click += (_, _) => TogglePause();
        SyncPauseLabels(); // B-03: labels mirror the persisted Paused from the start
        btnRepairFirewall.Click += async (_, _) =>
        {
            btnRepairFirewall.IsEnabled = false;
            AppendLog("[防火墙] 正在修复入站规则（将弹出 UAC 提权）…");
            bool ok = await _engine.Firewall.RepairAsync();
            AppendLog(ok ? "[防火墙] 入站规则已修复" : $"[防火墙] 修复未完成：{_engine.Firewall.Detail}");
            btnRepairFirewall.IsEnabled = true;
            Poll();
        };
        btnRescan.Click += (_, _) =>
        {
            _discoverShownAt = DateTime.UtcNow;
            AppendLog("[发现] 已重新扫描（广播信标每秒一轮，三路发现持续运行）");
        };

        // settings: reconnect segmented (ask / always / never)
        segReconnect.SetItems(new[] { Loc.Tr("每次询问"), Loc.Tr("总是自动连接"), Loc.Tr("从不自动") }, ReconnectModeIndex());
        segReconnect.SelectionChanged += (_, idx) => SetReconnectMode(idx switch { 0 => "ask", 1 => "always", _ => "never" }, fromUi: true);
        segRate.SetItems(new[] { Loc.Tr("自适应"), Loc.Tr("锁定 48 kHz"), Loc.Tr("锁定 24 kHz"), Loc.Tr("锁定 16 kHz") },
            _settings.SampleRatePreference switch { "48000" => 1, "24000" => 2, "16000" => 3, _ => 0 });
        segRate.SelectionChanged += (_, idx) =>
        {
            _settings.SampleRatePreference = idx switch { 1 => "48000", 2 => "24000", 3 => "16000", _ => "auto" };
            _settings.Save();
            _engine.ApplyRatePreference();
        };
        swAutoStart.IsChecked = _settings.StartOnLogin;
        swCloseToTray.IsChecked = _settings.CloseToTray;
        swNotifications.IsChecked = _settings.Notifications;
        swReturnAsk.IsChecked = _settings.ReturnAskNotify;
        swAutoCheck.IsChecked = _settings.AutoCheckUpdates;
        swAutoStart.CheckedChanged += (_, v) => { _settings.StartOnLogin = v; _settings.Save(); AutoStart.Apply(v); AppendLog($"[设置] 开机自启 {(v ? "开" : "关")}（注册表 HKCU Run）"); };
        swCloseToTray.CheckedChanged += (_, v) => { _settings.CloseToTray = v; _settings.Save(); };
        swNotifications.CheckedChanged += (_, v) => { _settings.Notifications = v; _settings.Save(); };
        swReturnAsk.CheckedChanged += (_, v) => { _settings.ReturnAskNotify = v; _settings.Save(); };
        swAutoCheck.CheckedChanged += (_, v) => { _settings.AutoCheckUpdates = v; _settings.Save(); };

        txtExtraSubnets.Text = _settings.ExtraSubnets;
        txtExtraSubnets.TextChanged += (_, _) =>
        {
            if (!_uiReady) return;
            _settings.ExtraSubnets = (txtExtraSubnets.Text ?? "").Trim();
            _settings.Save();
        };
        btnCheckUpdate.Click += async (_, _) => await CheckForUpdatesAsync(manual: true);
        btnVersionUpdate.Click += async (_, _) => await CheckForUpdatesAsync(manual: true);

        // update modal buttons
        modalLater.Click += (_, _) => { HideUpdateModal(); tvUpdateStatus.Text = Loc.Tr("已暂缓更新（点「检查更新」可再次下载）"); };
        modalClose.Click += (_, _) => HideUpdateModal();
        modalGo.Click += async (_, _) => await DownloadAndInstallAsync();
        updateScrim.PointerPressed += (_, e) =>
        {
            if (e.Source == updateScrim && !_updateBusy) HideUpdateModal();
        };
    }

    private string ReconnectMode()
    {
        if (_settings.ReconnectMode is "ask" or "always" or "never") return _settings.ReconnectMode;
        return _settings.AllowPairedAutoReconnect ? "always" : "never";
    }
    private int ReconnectModeIndex() => ReconnectMode() switch { "ask" => 0, "always" => 1, _ => 2 };

    /// <summary>Reconnect mode is one setting shown in two places (Settings segmented
    /// control + per-row switch on the Discover page). The engine gate is global
    /// (AllowPairedAutoReconnect), so "always" is the only mode that auto-connects;
    /// "ask"/"never" both hold phone-initiated reconnects for a manual tap.</summary>
    private void SetReconnectMode(string mode, bool fromUi)
    {
        _settings.ReconnectMode = mode;
        _settings.AllowPairedAutoReconnect = mode == "always";
        _settings.Save();
        segReconnect.Select(ReconnectModeIndex(), raise: false);
        _lastListSig = ""; // discover rows re-render their auto-connect switches
        AppendLog($"[设置] 手机回到附近时：{mode switch { "ask" => "每次询问", "always" => "总是自动连接", _ => "从不自动" }}");
    }

    private void UpdateChannelCards()
    {
        cardMic.Opacity = _settings.MicToPc ? 1.0 : 0.62;
        cardSpk.Opacity = _settings.PcToPhone ? 1.0 : 0.62;
    }

    // ---------------- audio device pickers ----------------
    private void RefreshDeviceCombos()
    {
        // FB-2 hardening: this runs synchronously in the constructor path
        // (InitControls) on top of COM audio enumeration — if that throws
        // (audio service down, stack disturbed by a driver install), the
        // window must still appear with an empty device list, not die.
        try
        {
            var (inputs, outputs) = _audio.Enumerate();
            _inputDevices = inputs;
            _outputDevices = outputs;
            _selOutput = _audio.PickOutput(outputs, _config.OutputDeviceId);
            // auto-pick never lands on the capture twin of the mic device (5b)
            _selInput = _audio.PickInput(inputs, _config.InputDeviceId, _selOutput);
            UpdatePickerButtons();
            BuildPickerLists();
            UpdateDeviceBadges();
            UpdateVdevStatus();
        }
        catch (Exception ex)
        {
            StartupLog.Error("RefreshDeviceCombos", ex);
            AppendLog($"[设备] 设备列表刷新失败：{ex.Message}");
        }
    }

    private void UpdatePickerButtons()
    {
        if (_selOutput is not null)
        {
            pickOutName.Text = _selOutput.Name;
            SetPickerTag(pickOutTag, pickOutTagText, _selOutput.Score, sky: false);
        }
        if (_selInput is not null)
        {
            pickInName.Text = _selInput.Name;
            SetPickerTag(pickInTag, pickInTagText, _selInput.Score, sky: true);
        }
    }

    private static void SetPickerTag(Border tag, TextBlock text, int score, bool sky)
    {
        if (score >= 110) // own branded LisTalk driver
        {
            tag.IsVisible = true;
            tag.Background = sky ? SkyDim : AccentDim;
            text.Text = Loc.Tr("听说设备");
            text.Foreground = sky ? new SolidColorBrush(Color.Parse("#45C7F2")) : Accent;
        }
        else if (score > 0)
        {
            tag.IsVisible = true;
            tag.Background = SurfaceInB;
            text.Text = Loc.Tr("备用");
            text.Foreground = Text2;
        }
        else tag.IsVisible = false;
    }

    private void BuildPickerLists()
    {
        pickOutList.Children.Clear();
        foreach (var d in _outputDevices)
            pickOutList.Children.Add(BuildPickerItem(d, isInput: false));
        pickInList.Children.Clear();
        foreach (var d in _inputDevices)
            pickInList.Children.Add(BuildPickerItem(d, isInput: true));
    }

    private Button BuildPickerItem(AudioDeviceInfo d, bool isInput)
    {
        var stack = new StackPanel { Spacing = 2 };
        var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        row.Children.Add(new TextBlock { Text = d.Name, FontSize = 12.5, TextWrapping = TextWrapping.Wrap });
        string tagText = d.Score >= 110 ? Loc.Tr("推荐") : d.Score > 0 ? Loc.Tr("备用") : "";
        if (tagText.Length > 0)
        {
            var tag = new TextBlock { Text = tagText, FontSize = 10.5, Foreground = Text3, VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
            Grid.SetColumn(tag, 1);
            row.Children.Add(tag);
        }
        stack.Children.Add(row);
        string? conflict = isInput && _selOutput is not null ? AudioService.RoutingConflict(d, _selOutput) : null;
        if (conflict is not null)
            stack.Children.Add(new TextBlock { Text = Loc.Tr("会与麦克风通道形成回路"), FontSize = 11, Foreground = Warn, TextWrapping = TextWrapping.Wrap });
        var btn = new Button { Content = stack, IsEnabled = conflict is null };
        btn.Classes.Add("pickitem");
        bool selected = isInput ? d.Id == _selInput?.Id : d.Id == _selOutput?.Id;
        if (selected) btn.Classes.Add("sel");
        btn.Click += (_, _) =>
        {
            if (isInput) _selInput = d; else _selOutput = d;
            if (isInput) (pickInBtn.Flyout as Flyout)?.Hide(); else (pickOutBtn.Flyout as Flyout)?.Hide();
            UpdatePickerButtons();
            BuildPickerLists();
            UpdateDeviceBadges();
            StartAudioFromSelection();
        };
        return btn;
    }

    private void UpdateDeviceBadges()
    {
        if (_selOutput is not null)
        {
            tvOutBadge.Text = _selOutput.Score > 0 ? Loc.Tr("✓ 已自动识别虚拟声卡") : Loc.Tr("还没检测到虚拟声卡，其他软件将选不到手机麦克风（请安装听说虚拟音频设备或 VB-CABLE）");
            tvOutBadge.Foreground = _selOutput.Score > 0 ? Accent : Warn;
        }
        if (_selInput is not null)
        {
            var conflict = _selOutput is not null ? AudioService.RoutingConflict(_selInput, _selOutput) : null;
            if (conflict is not null)
            {
                tvInBadge.Text = "⚠ " + conflict;
                tvInBadge.Foreground = Warn;
            }
            else
            {
                tvInBadge.Text = _selInput.Score > 0 ? Loc.Tr("✓ 已自动识别虚拟声卡 / 环回") : "";
                tvInBadge.Foreground = _selInput.Score > 0 ? Accent : Warn;
            }
        }
    }

    /// <summary>Virtual-device status (Settings 音频 group + About): own branded
    /// driver present → 正常工作； only third-party cable → 备用； none → 未安装.</summary>
    private void UpdateVdevStatus()
    {
        bool own = _inputDevices.Any(d => d.Score >= 110) || _outputDevices.Any(d => d.Score >= 110);
        bool cable = _inputDevices.Any(d => d.Score > 0) || _outputDevices.Any(d => d.Score > 0);
        string desc, pill;
        IBrush pillBg, pillFg;
        if (own)
        {
            desc = Loc.Tr("已安装 · 扬声器 / 麦克风（听说 LisTalk 虚拟音频设备），其他软件可直接选用");
            pill = Loc.Tr("正常工作"); pillBg = AccentDim; pillFg = Accent;
        }
        else if (cable)
        {
            desc = Loc.Tr("未检测到自有虚拟设备，当前由 VB-CABLE 等第三方虚拟线缆兜底（备用）");
            pill = Loc.Tr("备用方案"); pillBg = WarnDim; pillFg = Warn;
        }
        else
        {
            desc = Loc.Tr("未检测到虚拟音频设备：请安装听说虚拟音频设备组件，或安装 VB-CABLE 作为备用");
            pill = Loc.Tr("未安装"); pillBg = WarnDim; pillFg = Warn;
        }
        tvVdevDesc.Text = desc;
        tvAboutVdev.Text = desc;
        pillVdev.Background = pillBg;
        pillVdevText.Text = pill;
        pillVdevText.Foreground = pillFg;
    }

    private void StartAudioFromSelection()
    {
        if (_selInput is null || _selOutput is null) return;
        try
        {
            _audio.Start(_selInput, _selOutput);
            _config.InputDeviceId = _selInput.Id; _config.OutputDeviceId = _selOutput.Id; _config.InputIsLoopback = _selInput.IsLoopback;
            _config.Save();
        }
        catch (Exception ex) { AppendLog($"[音频] 启动失败：{ex.Message}"); }
    }

    /// <summary>B-08: 断开连接 ends only the current phone session; the
    /// service (and discovery) keeps running — the Settings 停止服务
    /// button is the one that stops everything, and it says so. The
    /// _userDisconnected flag keeps Poll() out of the reconnect branch:
    /// an intentional disconnect lands on the idle state, not on a
    /// 120 s "reconnecting" fiction.</summary>
    private void DisconnectSession()
    {
        _userDisconnected = true;
        _engine.DisconnectSession();
        AppendLog("[服务] 已断开当前手机会话（服务继续运行，手机可重新连接）");
        Poll();
    }

    private void SetServiceRunning(bool run)
    {
        if (run && !_engine.Running)
        {
            _engine.Start();
            StartAudioFromSelection();
            AppendLog("[服务] 桥接服务已启动");
        }
        else if (!run && _engine.Running)
        {
            _engine.Stop();
            _audio.Stop();
            AppendLog("[服务] 桥接服务已停止（已通知手机断开）");
        }
        Poll();
    }

    // ---------------- online update (modal overlay, spec §3.11) ----------------
    private bool _updateBusy;
    private UpdateChecker.UpdateInfo? _pendingWin;
    private UpdateChecker.UpdateInfo? _pendingAndroid;

    private void OpenUpdateEntry()
    {
        if (_pendingWin is not null) ShowFoundModal(_pendingWin, _pendingAndroid);
        else _ = CheckForUpdatesAsync(manual: true);
    }

    private void ShowUpdateModal() { updateScrim.IsVisible = true; }
    private void HideUpdateModal() { updateScrim.IsVisible = false; }

    private void ModalButtons(bool later, bool go, bool close, string? closeText = null)
    {
        modalLater.IsVisible = later;
        modalGo.IsVisible = go;
        modalGo.IsEnabled = true;
        modalGo.Opacity = 1;
        modalClose.IsVisible = close;
        modalClose.Content = closeText ?? Loc.Tr("关闭");
    }

    private void SetModalChecking()
    {
        ShowUpdateModal();
        modalTitle.Text = Loc.Tr("检查更新");
        modalVerFrom.Text = "v" + UpdateChecker.CurrentVersion;
        modalVerTo.Text = "";
        modalVerExtra.Text = Loc.Tr("正在获取更新清单…");
        modalNotesBox.IsVisible = true;
        modalNotes.Text = Loc.Tr("正在检查更新…");
        modalQr.IsVisible = false; modalQrCap.IsVisible = false;
        modalProg.IsVisible = false; modalError.IsVisible = false;
        ModalButtons(later: false, go: false, close: true, Loc.Tr("取消"));
    }

    private void ShowFoundModal(UpdateChecker.UpdateInfo info, UpdateChecker.UpdateInfo? android)
    {
        ShowUpdateModal();
        modalTitle.Text = Loc.Tr("发现新版本");
        modalVerFrom.Text = "v" + UpdateChecker.CurrentVersion;
        modalVerTo.Text = "v" + info.Version;
        modalVerExtra.Text = Loc.Tr(" · 下载后校验 SHA-256");
        modalNotesBox.IsVisible = true;
        modalNotes.Text = Loc.Tr("本次更新") + "\n" + info.Notes +
            (android is not null ? Loc.F("\n\n手机端同步有新版 v{0}，请在手机上同步更新（两端同版本成套更新）。", "\n\nThe phone also has an update (v{0}) — please update it too (both ends update together at the same version).", android.Version) : "");
        modalQr.IsVisible = false; modalQrCap.IsVisible = false;
        modalProg.IsVisible = false; modalError.IsVisible = false;
        modalFoot.Text = Loc.Tr("手机端会同步提示更新，两端同版本才能继续连接。");
        ModalButtons(later: true, go: true, close: false);
    }

    private void ShowPhoneOnlyModal(UpdateChecker.UpdateInfo android)
    {
        ShowUpdateModal();
        modalTitle.Text = Loc.F("手机端有新版 v{0}", "Phone update available: v{0}", android.Version);
        modalVerFrom.Text = Loc.F("本机 v{0}", "This PC v{0}", UpdateChecker.CurrentVersion);
        modalVerTo.Text = "";
        modalVerExtra.Text = Loc.Tr("电脑端已是最新，只需更新手机");
        modalNotesBox.IsVisible = true;
        modalNotes.Text = android.Notes + "\n\n" + Loc.Tr("用手机扫描下方二维码打开更新页。");
        modalProg.IsVisible = false; modalError.IsVisible = false;
        try
        {
            var gen = new QRCodeGenerator();
            var data = gen.CreateQrCode(android.Url, QRCodeGenerator.ECCLevel.M);
            var png = new PngByteQRCode(data).GetGraphic(8);
            modalQr.Source = new Bitmap(new MemoryStream(png));
            modalQr.IsVisible = true; modalQrCap.IsVisible = true;
        }
        catch { modalQr.IsVisible = false; modalQrCap.IsVisible = false; }
        ModalButtons(later: false, go: false, close: true, Loc.Tr("知道了"));
    }

    private void SetModalUpToDate()
    {
        ShowUpdateModal();
        modalTitle.Text = Loc.Tr("检查更新");
        modalVerFrom.Text = "v" + UpdateChecker.CurrentVersion;
        modalVerTo.Text = "";
        modalVerExtra.Text = Loc.Tr("已是最新版本");
        modalNotesBox.IsVisible = true;
        modalNotes.Text = Loc.F("已是最新版本（当前 {0}）。", "You are up to date (current {0}).", UpdateChecker.CurrentVersion);
        modalQr.IsVisible = false; modalQrCap.IsVisible = false;
        modalProg.IsVisible = false; modalError.IsVisible = false;
        ModalButtons(later: false, go: false, close: true);
    }

    private void SetModalCheckFailed(string reason)
    {
        ShowUpdateModal();
        modalTitle.Text = Loc.Tr("检查更新失败");
        modalVerFrom.Text = "v" + UpdateChecker.CurrentVersion;
        modalVerTo.Text = "";
        modalVerExtra.Text = "";
        modalNotesBox.IsVisible = false;
        modalQr.IsVisible = false; modalQrCap.IsVisible = false;
        modalProg.IsVisible = false;
        modalError.Text = Loc.F("检查失败：{0}", "Check failed: {0}", reason);
        modalError.IsVisible = true;
        ModalButtons(later: false, go: false, close: true);
    }

    /// <summary>Full update flow: fetch manifest -> compare -> modal (found / phone-only /
    /// up-to-date / failed — exactly one visible outcome, never a silent no-op, v2.2
    /// defect B) -> download with real progress -> SHA-256 gate -> launch installer.</summary>
    private async Task CheckForUpdatesAsync(bool manual)
    {
        if (_updateBusy) return;
        _updateBusy = true;
        try
        {
            // FB-10/FB-7: the update address is no longer user-editable —
            // checks always use the built-in manifest URL. Any value an old
            // version persisted in settings.json is ignored (the property
            // was removed from AppSettings, so the stale field is dropped
            // on the next save).
            string url = UpdateChecker.UpdateManifestUrl;
            if (manual) { tvUpdateStatus.Text = Loc.Tr("正在检查更新…"); SetModalChecking(); }
            var report = await UpdateChecker.FetchReportAsync(url);
            AppendLog($"[更新] {report.Summary}");
            if (report.Error is not null || report.Manifest is null)
            {
                string reason = report.Error ?? Loc.Tr("更新清单无效（内容无法解析）");
                tvUpdateStatus.Text = Loc.F("检查失败：{0}", "Check failed: {0}", reason);
                if (manual) SetModalCheckFailed(reason);
                return;
            }
            var manifest = report.Manifest;
            var info = manifest?.Windows;
            var android = manifest?.Android;
            tvUpdDesc.Text = Loc.F("当前版本 v{0} · 上次检查 {1} · 发现新版本时在侧边栏提醒", "Current version v{0} · Last checked {1} · New versions are flagged in the sidebar", UpdateChecker.CurrentVersion, DateTime.Now.ToString("HH:mm"));
            bool winNewer = info is not null && UpdateChecker.IsNewer(info.Version);
            string phoneBaseline = _engine.PhoneAppVersion.Length > 0 ? _engine.PhoneAppVersion : UpdateChecker.CurrentVersion;
            bool androidNewer = android is not null &&
                (UpdateChecker.IsNewer(android.Version) || UpdateChecker.IsNewerThan(android.Version, phoneBaseline));
            if (!winNewer && !androidNewer)
            {
                _pendingWin = null; _pendingAndroid = null;
                navUpdateDot.IsVisible = false; btnSideUpdate.IsVisible = false;
                tvUpdateStatus.Text = Loc.F("已是最新版本（当前 {0}）", "You are up to date (current {0})", UpdateChecker.CurrentVersion);
                if (manual) SetModalUpToDate();
                return;
            }
            if (!winNewer && androidNewer)
            {
                _pendingAndroid = android;
                tvUpdateStatus.Text = Loc.F("手机端有新版 v{0}（本机已是最新 {1}）", "Phone update available: v{0} (this PC is up to date on {1})", android!.Version, UpdateChecker.CurrentVersion);
                if (manual) ShowPhoneOnlyModal(android);
                else
                {
                    tvBanner.Text = Loc.F("手机端有新版 v{0} · 点侧边栏「更新」扫码更新", "Phone update available: v{0} · Open Updates in the sidebar to scan and update", android.Version);
                    bannerInfo.IsVisible = true;
                    AppendLog($"[更新] 手机端有新版 v{android.Version}（更新弹窗内有扫码）");
                }
                return;
            }
            _pendingWin = info!;
            _pendingAndroid = androidNewer ? android : null;
            navUpdateDot.IsVisible = true;
            btnSideUpdate.Content = Loc.F("有新版本 {0}", "Update {0} available", info!.Version);
            btnSideUpdate.IsVisible = true;
            tvUpdateStatus.Text = Loc.F("发现新版本 {0}（当前 {1}）", "Update {0} available (current {1})", info.Version, UpdateChecker.CurrentVersion);
            ShowFoundModal(info, _pendingAndroid);
        }
        catch (Exception ex)
        {
            tvUpdateStatus.Text = Loc.F("更新失败：{0}", "Update failed: {0}", ex.Message);
            if (manual) SetModalCheckFailed(ex.Message);
            else AppendLog($"[更新] 失败：{ex.Message}");
        }
        finally { _updateBusy = false; }
    }

    private async Task DownloadAndInstallAsync()
    {
        var info = _pendingWin;
        if (info is null || _updateBusy) return;
        _updateBusy = true;
        try
        {
            modalProg.IsVisible = true;
            modalError.IsVisible = false;
            modalGo.IsEnabled = false; modalGo.Opacity = 0.55;
            modalLater.IsEnabled = false;
            modalProgText.Text = Loc.F("正在下载 LisTalk-Setup-v{0}.exe…", "Downloading LisTalk-Setup-v{0}.exe…", info.Version);
            modalProgPct.Text = "0%";
            progFill.Width = 0;
            progFill.Background = Tokens.B("ProgGradient");
            tvUpdateStatus.Text = Loc.F("正在下载新版本 {0}…", "Downloading update {0}…", info.Version);
            AppendLog($"[更新] 开始下载 {info.Version} …");
            var progress = new Progress<double>(pct => Dispatcher.UIThread.Post(() =>
            {
                modalProgPct.Text = $"{pct:F0}%";
                double w = progTrack.Bounds.Width;
                if (w > 0) progFill.Width = w * pct / 100.0;
            }));
            string file = await UpdateChecker.DownloadAsync(info, progress);
            modalProgText.Text = Loc.Tr("下载完成，正在校验 SHA-256…");
            bool ok = await UpdateChecker.VerifySha256Async(file, info.Sha256);
            if (!ok)
            {
                try { File.Delete(file); } catch { }
                // spec §3.11 verify-failed state: error bar, no retry-install entry
                progFill.Background = ErrorB;
                modalProgText.Text = Loc.Tr("校验不通过，已删除下载文件");
                modalError.Text = Loc.Tr("安装包 SHA-256 校验不通过，文件已删除，不会运行未经验证的安装包。请稍后重试。");
                modalError.IsVisible = true;
                tvUpdateStatus.Text = Loc.Tr("更新包校验失败（SHA-256 不一致），已中止安装");
                AppendLog("[更新] SHA-256 校验失败，已中止并删除下载文件");
                ModalButtons(later: false, go: false, close: true);
                return;
            }
            modalProgText.Text = Loc.Tr("下载完成，校验通过 · 即将启动安装…");
            modalProgPct.Text = "100%";
            if (progTrack.Bounds.Width > 0) progFill.Width = progTrack.Bounds.Width;
            tvUpdateStatus.Text = Loc.Tr("校验通过，正在启动安装程序…");
            AppendLog("[更新] SHA-256 校验通过，启动安装程序并退出…");
            await Task.Delay(600);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(file) { UseShellExecute = true });
            RequestExit();
        }
        catch (Exception ex)
        {
            modalError.Text = Loc.F("更新失败：{0}", "Update failed: {0}", ex.Message);
            modalError.IsVisible = true;
            tvUpdateStatus.Text = Loc.F("更新失败：{0}", "Update failed: {0}", ex.Message);
            ModalButtons(later: false, go: false, close: true);
        }
        finally
        {
            _updateBusy = false;
            modalLater.IsEnabled = true;
        }
    }

    // ---------------- tray / background ----------------
    private void SetupTray()
    {
        _trayStatus = new NativeMenuItem(Loc.Tr("听说 LisTalk · 未连接")) { IsEnabled = false };
        var open = new NativeMenuItem(Loc.Tr("打开主窗口"));
        open.Click += (_, _) => ShowFromTray();
        _trayPause = new NativeMenuItem(Loc.Tr("暂停推流"));
        _trayPause.Click += (_, _) => TogglePause();
        _trayMic = new NativeMenuItem(Loc.Tr("麦克风 → 电脑")) { ToggleType = NativeMenuItemToggleType.CheckBox, IsChecked = _settings.MicToPc };
        _trayMic.Click += (_, _) => SetChannelFromTray(mic: true);
        _traySpk = new NativeMenuItem(Loc.Tr("电脑 → 扬声器")) { ToggleType = NativeMenuItemToggleType.CheckBox, IsChecked = _settings.PcToPhone };
        _traySpk.Click += (_, _) => SetChannelFromTray(mic: false);
        _trayDisc = new NativeMenuItem(Loc.Tr("断开")) { IsEnabled = false };
        _trayDisc.Click += (_, _) => DisconnectSession();
        var settings = new NativeMenuItem(Loc.Tr("设置…"));
        settings.Click += (_, _) => { ShowFromTray(); ShowPage(Page.Settings); };
        var exit = new NativeMenuItem(Loc.Tr("退出听说"));
        exit.Click += (_, _) => RequestExit();
        var menu = new NativeMenu();
        menu.Items.Add(_trayStatus);
        menu.Items.Add(new NativeMenuItemSeparator());
        menu.Items.Add(open);
        menu.Items.Add(_trayPause);
        menu.Items.Add(new NativeMenuItemSeparator());
        menu.Items.Add(_trayMic);
        menu.Items.Add(_traySpk);
        menu.Items.Add(new NativeMenuItemSeparator());
        menu.Items.Add(_trayDisc);
        menu.Items.Add(settings);
        menu.Items.Add(new NativeMenuItemSeparator());
        menu.Items.Add(exit);
        _tray = new TrayIcon { ToolTipText = "听说 LisTalk", Menu = menu };
        try { _tray.Icon = new WindowIcon(AssetLoader.Open(new Uri("avares://AirBridge/airbridge.ico"))); } catch { }
        _tray.Clicked += (_, _) => ShowFromTray();
        TrayIcon.SetIcons(Application.Current!, new TrayIcons { _tray });
    }

    private void SetChannelFromTray(bool mic)
    {
        bool v = mic ? !_settings.MicToPc : !_settings.PcToPhone;
        if (mic) { _settings.MicToPc = v; swMic.IsChecked = v; }
        else { _settings.PcToPhone = v; swSpk.IsChecked = v; }
        _settings.Save();
        SyncTrayChannels();
        UpdateChannelCards();
    }

    private void SyncTrayChannels()
    {
        if (_trayMic is not null) _trayMic.IsChecked = _settings.MicToPc;
        if (_traySpk is not null) _traySpk.IsChecked = _settings.PcToPhone;
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
        SyncPauseLabels();
        AppendLog(_settings.Paused ? "[桥接] 已暂停（发现与配对仍在运行，音频已静默）" : "[桥接] 已恢复");
        Poll();
    }

    /// <summary>B-03: the pause labels live in two places (Settings button,
    /// tray item) and must always mirror the persisted state — including
    /// at startup, where TogglePause never ran and the XAML/tray constants
    /// previously claimed "pause" while already paused.</summary>
    private void SyncPauseLabels()
    {
        btnPause.Content = _settings.Paused ? Loc.Tr("恢复桥接") : Loc.Tr("暂停桥接");
        if (_trayPause is not null) _trayPause.Header = _settings.Paused ? Loc.Tr("恢复推流") : Loc.Tr("暂停推流");
    }

    // ---------------- periodic UI ----------------
    private void PollMeters()
    {
        if (!_engine.Connected)
        {
            // spec §4: while disconnected / reconnecting the meters freeze at zero
            meterMic.Level = 0; meterSpk.Level = 0;
            dbMic.Text = "— dB"; dbSpk.Text = "— dB";
            return;
        }
        var s = _engine.Stats;
        meterMic.Level = _settings.MicToPc ? Math.Clamp(s.LevelOut, 0, 1) : 0;
        meterSpk.Level = _settings.PcToPhone ? Math.Clamp(s.LevelIn, 0, 1) : 0;
        dbMic.Text = _settings.MicToPc && meterMic.Level > 0.02 ? $"{-26 + meterMic.Level * 22:F0} dB" : "— dB";
        dbSpk.Text = _settings.PcToPhone && meterSpk.Level > 0.02 ? $"{-30 + meterSpk.Level * 22:F0} dB" : "— dB";
    }

    private void Poll()
    {
        // pair-code rotation (10 min) + QR refresh
        if (_config.EnsurePairCode()) _config.Save();
        tvPairCode.Text = _config.PairCode;
        int left = _config.PairCodeSecondsLeft;
        tvPairCountdown.Text = Loc.F("配对码 {0}（剩 {1} 分 {2} 秒，过期自动轮换）", "Pairing code {0} ({1} min {2} s left, auto-rotates on expiry)", _config.PairCode, left / 60, left % 60);
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
        bool hasVerify = !string.IsNullOrEmpty(_engine.LastVerifyCode);
        verifyBar.IsVisible = hasVerify;
        if (hasVerify)
            tvVerify.Text = Loc.F("核对验证码 {0}（与手机上一致再确认 · {1}）", "Verify code {0} (confirm only if it matches the phone · {1})", _engine.LastVerifyCode, _engine.LastPairPhone);

        var s = _engine.Stats;
        var now = DateTime.UtcNow;
        double dt = Math.Max(0.1, (now - _lastPollUtc).TotalSeconds);
        _lastPollUtc = now;

        if (_engine.Connected)
        {
            if (!_wasConnected)
            {
                _connectedSinceUtc = now;
                if (_settings.Notifications)
                {
                    tvBanner.Text = Loc.F("✓ 已连接 {0}", "✓ Connected to {0}", _engine.PeerName);
                    bannerInfo.IsVisible = true;
                    if (_tray is not null) _tray.ToolTipText = Loc.F("听说 LisTalk · 已连接 {0}", "LisTalk · Connected to {0}", _engine.PeerName);
                }
            }
            _wasConnected = true;
            _everConnected = true;
            _disconnectedAtUtc = null;
            _userDisconnected = false; // a live session supersedes an earlier user disconnect
            _lastPeerName = _engine.PeerName;

            // Phone volume (volume-streams §3b): once per session, when the
            // phone has reported its gain in HELLO, pull the slider to the
            // phone's value — the phone is the single source of truth.
            // <0 (unpaired HELLO / pre-fix phone) leaves the persisted
            // slider position untouched and sends nothing.
            if (!_volSynced && _engine.PhoneSpeakerVolume >= 0)
            {
                int pv = Protocol.PercentForGain(_engine.PhoneSpeakerVolume);
                if ((int)Math.Round(volSlider.Value) != pv)
                {
                    volSlider.Value = pv; // ValueChanged re-sends the same value — idempotent, harmless
                    volVal.Text = $"{pv}%";
                }
                _settings.PhoneVolumePercent = pv; _settings.Save();
                _volSynced = true;
            }

            devStatusWord.Text = _settings.Paused ? Loc.Tr("已暂停") : Loc.Tr("推流中");
            devStatusWord.Foreground = Text1B;
            devPulse.IsActive = true;
            sidePulse.IsActive = true;
            var since = _connectedSinceUtc is not null ? now - _connectedSinceUtc.Value : TimeSpan.Zero;
            devStatusInfo1.Text = Loc.F("已推流 {0}:{1:D2}", "Streaming {0}:{1:D2}", (int)since.TotalMinutes, since.Seconds);
            devStatusInfo2.Text = _settings.Paused ? Loc.Tr("在托盘或设置 → 诊断里恢复") : Loc.Tr("断线会自动重连");

            hero.IsVisible = true;
            heroEmpty.IsVisible = false;
            // B-06: the eyebrow follows the real state — LIVE only while
            // actually streaming, never through pause (or reconnect below).
            heroEyebrow.Text = _settings.Paused ? Loc.Tr("已暂停 · 已连接手机") : Loc.Tr("● LIVE · 已连接手机");
            heroEyebrow.Foreground = _settings.Paused ? Warn : Accent;
            heroName.Text = _engine.PeerName;
            heroSub.Text = $"{_engine.Peer?.Address} · {Protocol.RateHz(_engine.RateCode) / 1000} kHz" +
                (_engine.PhoneAppVersion.Length > 0 ? Loc.F(" · 听说 App v{0}", " · LisTalk App v{0}", _engine.PhoneAppVersion) : "");
            tbChipRate.Text = $"{Protocol.RateHz(_engine.RateCode) / 1000} kHz";
            if (s.RttMs >= 0)
            {
                string word = s.RttMs < 30 ? Loc.Tr("流畅") : (s.RttMs <= 80 ? Loc.Tr("一般") : Loc.Tr("偏高"));
                latNum.Text = $"{s.RttMs:F0}";
                latWord.Text = $" ms · {word}";
                var tier = s.RttMs < 30 ? Accent : (s.RttMs <= 80 ? Warn : ErrorB);
                latNum.Foreground = tier; latWord.Foreground = tier;
                latBadge.Background = s.RttMs < 30 ? AccentDim : s.RttMs <= 80 ? WarnDim : ErrorDim;
                latBadge.BorderBrush = s.RttMs < 30 ? AccentBorder : s.RttMs <= 80 ? WarnBorder : ErrorBorder;
                sparkNow.Text = Loc.F("当前 {0} ms", "Now {0} ms", s.RttMs.ToString("F0"));
                if ((now - _lastSparkPush).TotalSeconds >= 1)
                {
                    _lastSparkPush = now;
                    _rttHistory.Add(s.RttMs);
                    while (_rttHistory.Count > 60) _rttHistory.RemoveAt(0);
                    spark.SetPoints(_rttHistory);
                }
            }
            statLoss.Text = $"{s.LossPct:F1}";
            statJitter.Text = $"{s.JitterMs:F1}";
            statBuffer.Text = $"{_engine.Jb.TargetMs}";
            statBufferNote.Text = _settings.SampleRatePreference == "auto" ? Loc.Tr(" ms · 自适应") : Loc.Tr(" ms · 固定");
            int frameBytes = Protocol.FrameSamples(_engine.RateCode) * 2;
            statUp.Text = $"{(s.Rx - _lastRx) * frameBytes * 8.0 / dt / 1000:F0}";
            statDown.Text = $"{(s.Tx - _lastTx) * frameBytes * 8.0 / dt / 1000:F0}";
            // B-03: while paused the sidebar must not claim 推流中 either.
            sideConnTitle.Text = _settings.Paused
                ? Loc.F("已暂停 · {0}", "Paused · {0}", _engine.PeerName)
                : Loc.F("推流中 · {0}", "Streaming · {0}", _engine.PeerName);
            sideConnSub.Text = s.RttMs >= 0
                ? $"UDP · {s.RttMs:F0} ms · {Protocol.RateHz(_engine.RateCode) / 1000} kHz"
                : $"UDP · {Protocol.RateHz(_engine.RateCode) / 1000} kHz";
            chipMismatch.IsVisible = _engine.VersionMismatch;
        }
        else if (_everConnected && _engine.Running && !_userDisconnected &&
                 (_wasConnected || (_disconnectedAtUtc is not null && (now - _disconnectedAtUtc.Value).TotalSeconds < 120)))
        {
            // spec §4 reconnect state: no page jump — status bar turns warn,
            // hero stays with a 「重连中」 badge, meters freeze at zero (PollMeters).
            if (_wasConnected) _disconnectedAtUtc = now;
            _wasConnected = false;
            _connectedSinceUtc = null;
            _volSynced = false; // the next HELLO re-reports the phone gain
            devStatusWord.Text = Loc.Tr("重连中");
            devStatusWord.Foreground = Warn;
            devPulse.IsActive = true;
            sidePulse.IsActive = true;
            devStatusInfo1.Text = Loc.Tr("连接中断，正在重连…");
            devStatusInfo2.Text = Loc.Tr("手机回来会自动续连");
            hero.IsVisible = true;
            heroEmpty.IsVisible = false;
            // B-06: no LIVE claim and no stale sub-line while the link is down.
            heroEyebrow.Text = Loc.Tr("重连中 · 连接中断");
            heroEyebrow.Foreground = Warn;
            heroName.Text = _lastPeerName;
            heroSub.Text = Loc.Tr("连接中断 · 正在重连，手机回来会自动续连");
            // B-07: the strip must not present pre-drop numbers as current.
            statLoss.Text = "--"; statJitter.Text = "--"; statBuffer.Text = "--";
            statUp.Text = "--"; statDown.Text = "--";
            latNum.Text = "—";
            latWord.Text = Loc.Tr(" · 重连中");
            latNum.Foreground = Warn; latWord.Foreground = Warn;
            latBadge.Background = WarnDim;
            latBadge.BorderBrush = WarnBorder;
            sparkNow.Text = Loc.Tr("当前 -- ms");
            sideConnTitle.Text = Loc.F("重连中 · {0}", "Reconnecting · {0}", _lastPeerName);
            sideConnSub.Text = Loc.Tr("连接中断，正在重连…");
            chipMismatch.IsVisible = false;
            bannerInfo.IsVisible = false;
            if (_tray is not null && _settings.Notifications) _tray.ToolTipText = Loc.Tr("听说 LisTalk · 重连中");
        }
        else
        {
            _wasConnected = false;
            _connectedSinceUtc = null;
            _volSynced = false;
            devStatusWord.Text = _engine.Running ? Loc.Tr("未连接") : Loc.Tr("服务未启动");
            devStatusWord.Foreground = Text1B;
            devPulse.IsActive = false;
            sidePulse.IsActive = false;
            devStatusInfo1.Text = _engine.Running ? Loc.Tr("等待手机打开听说") : Loc.Tr("服务已停止");
            devStatusInfo2.Text = Loc.F("本机 IP {0}", "This PC IP {0}", BridgeEngine.LocalIp());
            hero.IsVisible = false;
            heroEmpty.IsVisible = true;
            heroEmptyTitle.Text = _engine.Running ? Loc.Tr("等待手机连接") : Loc.Tr("服务已停止");
            heroEmptyDesc.Text = _engine.Running
                ? Loc.F("在手机上打开听说即可自动找到这台电脑 · 本机 IP：{0}", "Open LisTalk on your phone and it will find this PC automatically · This PC IP: {0}", BridgeEngine.LocalIp())
                : Loc.Tr("桥接服务已停止，手机无法发现这台电脑");
            btnStartService.IsVisible = !_engine.Running;
            btnGotoDiscover.IsVisible = _engine.Running;
            statLoss.Text = "--"; statJitter.Text = "--"; statBuffer.Text = "--";
            statUp.Text = "--"; statDown.Text = "--";
            sparkNow.Text = Loc.Tr("当前 -- ms");
            sideConnTitle.Text = Loc.Tr("未连接");
            sideConnSub.Text = _engine.Running ? Loc.Tr("等待手机连接") : Loc.Tr("服务未启动");
            chipMismatch.IsVisible = false;
            bannerInfo.IsVisible = false;
            if (_tray is not null && _settings.Notifications) _tray.ToolTipText = Loc.Tr("听说 LisTalk · 等待手机连接");
        }
        _lastRx = s.Rx; _lastTx = s.Tx;

        // loop / routing warnings (defect 5b): always visible while active
        string? loopMsg = _engine.LoopDetected
            ? Loc.F("⚠ 检测到手机麦克风声音被回传，已暂停向手机发送电脑声音（相关度 {0}），请改选采集设备", "⚠ Phone mic audio is being looped back — PC sound to the phone is paused (correlation {0}). Please pick a different capture device.", _engine.Loop.LastCorrelation.ToString("F2"))
            : _audio.RoutingWarning;
        tvLoopWarn.Text = loopMsg ?? "";
        bannerLoop.IsVisible = loopMsg is not null;

        // unified-versioning mismatch: loud banner + settings pill, audio keeps running
        if (_engine.VersionMismatch)
        {
            bannerVersion.IsVisible = true;
            tvVersionWarn.Text = Loc.F("版本不一致：手机端 v{0}，本机 v{1}，请先更新手机端", "Version mismatch: phone v{0}, this PC v{1} — please update the phone first", _engine.PhoneAppVersion.Length > 0 ? _engine.PhoneAppVersion : Loc.Tr("未知"), Protocol.AppVersion);
            pillVersions.Background = WarnDim;
            pillVersionsText.Text = Loc.F("手机 v{0} · 电脑 v{1}", "Phone v{0} · PC v{1}", _engine.PhoneAppVersion.Length > 0 ? _engine.PhoneAppVersion : "?", Protocol.AppVersion);
            pillVersionsText.Foreground = Warn;
        }
        else
        {
            bannerVersion.IsVisible = false;
            pillVersions.Background = AccentDim;
            pillVersionsText.Text = _engine.Connected ? Loc.F("两端均为 {0}", "Both ends on {0}", Protocol.AppVersion) : Loc.Tr("未连接");
            pillVersionsText.Foreground = Accent;
        }
        if (_trayStatus is not null)
            // B-03: the tray status item follows the same paused truth as
            // the sidebar — 推流中 only while actually streaming.
            _trayStatus.Header = _engine.Connected
                ? _settings.Paused
                    ? Loc.F("听说 LisTalk · 已暂停 · {0}", "LisTalk · Paused · {0}", _engine.PeerName)
                    : Loc.F("听说 LisTalk · 推流中 · {0} · {1} ms", "LisTalk · Streaming · {0} · {1} ms", _engine.PeerName, s.RttMs.ToString("F0"))
                : Loc.Tr("听说 LisTalk · 未连接");
        if (_trayDisc is not null)
        {
            _trayDisc.Header = _engine.Connected ? Loc.F("断开 {0}", "Disconnect {0}", _engine.PeerName) : Loc.Tr("断开");
            _trayDisc.IsEnabled = _engine.Connected;
        }
        btnToggleService.Content = _engine.Running ? Loc.Tr("停止服务") : Loc.Tr("启动服务");
        tvSvcDesc.Text = _engine.Running ? Loc.Tr("服务运行中 · 关闭窗口将最小化到托盘（可在设置里关掉）") : Loc.Tr("服务已停止 · 手机无法发现这台电脑");
        // R-W10 (first half): the UDP claim follows the engine state
        // instead of being a constant, and the wave-1 health line
        // (per-channel counters + verdict + firewall state) rides along
        // so "why is nothing found" is answerable from this row.
        tvDiscoveryStatus.Text = Loc.F("发现层：UDP 广播/信标 {0} · mDNS {1} · BLE {2}", "Discovery: UDP broadcast/beacon {0} · mDNS {1} · BLE {2}",
                _engine.Running ? Loc.Tr("已运行") : Loc.Tr("未运行"), _mdns.Status, _ble.Status)
            + "\n" + _engine.DiscoveryHealthText;
        btnRepairFirewall.IsVisible = _engine.Firewall.CurrentState != FirewallGuard.State.Ok;
        tbScanInfo.Text = Loc.F("通过局域网广播、mDNS 与蓝牙近感三路并行查找 · 已扫描 {0} 秒", "Searching via LAN broadcast, mDNS and Bluetooth proximity · {0} s elapsed", (int)(now - _discoverShownAt).TotalSeconds);

        RebuildDeviceLists();
    }

    // ---------------- discover page rows ----------------
    private void RebuildDeviceLists()
    {
        var nearby = _engine.NearbySnapshot();
        var sig = new System.Text.StringBuilder();
        foreach (var kv in _config.PairedPhones) sig.Append($"P{kv.Key}|{kv.Value.Name}|{kv.Value.LastSeen}|{kv.Value.LastIp}|");
        foreach (var d in nearby) sig.Append($"N{d.Key}|{d.Name}|{d.Ip}|{d.Online}|{d.Paired}|{string.Join("+", d.Transports)}|");
        sig.Append(_engine.Connected ? _engine.PeerName : "-");
        sig.Append(ReconnectMode());
        if (sig.ToString() == _lastListSig) return;
        _lastListSig = sig.ToString();

        // paired rows (config is the source of truth; nearby adds live state)
        pairedList.Children.Clear();
        foreach (var kv in _config.PairedPhones)
        {
            var live = nearby.FirstOrDefault(d => d.DeviceId == kv.Key);
            bool isCurrent = _engine.Connected && live is not null && live.Name == _engine.PeerName;
            // v2.2.3 FB-6: fall back to the learned/stored IP when the phone
            // is not currently visible (e.g. on another subnet).
            string ip = live?.Ip ?? (string.IsNullOrEmpty(kv.Value.LastIp) ? "—" : kv.Value.LastIp);
            string transport = live is not null && live.Transports.Count > 0 ? "WiFi" : "—";
            var seen = DateTimeOffset.FromUnixTimeSeconds((long)kv.Value.LastSeen).LocalDateTime;
            string seenText = seen.Date == DateTime.Today ? Loc.F("今天 {0}", "Today {0}", seen.ToString("HH:mm"))
                : seen.Date == DateTime.Today.AddDays(-1) ? Loc.F("昨天 {0}", "Yesterday {0}", seen.ToString("HH:mm")) : seen.ToString("MM-dd HH:mm");
            string sub = Loc.F("{0} · {1} · 上次连接 {2}", "{0} · {1} · Last connected {2}", ip, transport, seenText);
            var action = new StackPanel { Orientation = Avalonia.Layout.Orientation.Horizontal, Spacing = 8, VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
            if (live is not null && live.Online && !isCurrent)
            {
                var connect = new Button { Content = Loc.Tr("连接"), Classes = { "btn", "sm", "ghost" } };
                var dev = live;
                connect.Click += (_, _) => { _engine.TapConnect(dev); AppendLog($"[发现] 已请求 {dev.Name} 连接（60 秒内放行它的自动重连）"); };
                action.Children.Add(connect);
            }
            else if (!isCurrent && !string.IsNullOrEmpty(kv.Value.LastIp))
            {
                // offline but we know its last address (v2.2.3 FB-6): poke it
                // directly by unicast — the only way to reach it across subnets.
                var connect = new Button { Content = Loc.Tr("连接"), Classes = { "btn", "sm", "ghost" } };
                var id = kv.Key; var nm = kv.Value.Name;
                connect.Click += (_, _) => { _engine.TapConnectStored(id); AppendLog($"[发现] 已向 {nm} 的上次地址发起连接（60 秒内放行它的自动重连）"); };
                action.Children.Add(connect);
            }
            action.Children.Add(new TextBlock { Text = Loc.Tr("自动连接"), FontSize = 11.5, Foreground = Text3, VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center });
            var sw = new SwitchControl { Width = 38, Height = 22, Small = true, IsChecked = ReconnectMode() == "always", VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
            sw.CheckedChanged += (_, v) => SetReconnectMode(v ? "always" : "never", fromUi: true);
            action.Children.Add(sw);
            string pillText = isCurrent ? Loc.Tr("推流中") : live is not null && live.Online ? Loc.Tr("在线") : Loc.Tr("离线");
            pairedList.Children.Add(BuildDeviceRow(kv.Value.Name, sub, paired: true,
                tablet: kv.Value.Name.Contains("Tab"), pillText, pillKind: isCurrent ? 0 : 1, action));
        }
        pairedEmpty.IsVisible = _config.PairedPhones.Count == 0;

        // nearby unpaired rows
        nearbyList.Children.Clear();
        int newCount = 0;
        foreach (var d in nearby.Where(d => !d.Paired))
        {
            newCount++;
            bool ble = d.Transports.Any(t => t.Contains("ble", StringComparison.OrdinalIgnoreCase));
            string sub = $"{d.Ip} · WiFi" + (ble ? Loc.Tr(" · 蓝牙近感已确认在附近") : "");
            var pair = new Button { Content = Loc.Tr("配对"), Classes = { "btn", "sm", "pri" } };
            var dev = d;
            pair.Click += (_, _) =>
            {
                _engine.TapPair(dev);
                pair.Content = Loc.Tr("已发起…");
                pair.IsEnabled = false;
                AppendLog($"[发现] 正在与 {dev.Name} 配对：请看手机上的确认屏，并核对验证码一致后在手机上确认");
            };
            nearbyList.Children.Add(BuildDeviceRow(
                string.IsNullOrEmpty(d.Name) ? Loc.Tr("未知手机") : d.Name, sub, paired: false,
                tablet: d.Name.Contains("Tab"), Loc.Tr("未配对"), pillKind: 2, pair));
        }
        nearbyEmpty.IsVisible = newCount == 0;
    }

    private Border BuildDeviceRow(string name, string sub, bool paired, bool tablet, string pillText, int pillKind, Control action)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto,Auto") };
        var iconBorder = new Border
        {
            Width = 40, Height = 40, CornerRadius = new CornerRadius(10),
            Background = SurfaceInB, BorderBrush = Tokens.B("Stroke"), BorderThickness = new Thickness(1),
        };
        this.TryFindResource(tablet ? "IconTablet" : "IconPhone", out var iconRes);
        iconBorder.Child = new Avalonia.Controls.Shapes.Path
        {
            Data = iconRes as Avalonia.Media.StreamGeometry,
            Stroke = pillKind == 0 ? Accent : Text2, StrokeThickness = 1.7, Stretch = Stretch.Uniform,
            Width = 20, Height = 20, HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center, VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        grid.Children.Add(iconBorder);
        var texts = new StackPanel { Margin = new Thickness(13, 0, 0, 0), VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        texts.Children.Add(new TextBlock { Text = name, FontSize = 14, FontWeight = FontWeight.SemiBold, Foreground = Tokens.B("Text1") });
        texts.Children.Add(new TextBlock { Text = sub, FontSize = 11.5, Foreground = Text2, FontFamily = new FontFamily("Consolas, JetBrains Mono"), Margin = new Thickness(0, 2, 0, 0) });
        Grid.SetColumn(texts, 1);
        grid.Children.Add(texts);
        var pill = new Border
        {
            CornerRadius = new CornerRadius(999), Padding = new Thickness(9, 2.5), VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Background = pillKind == 0 ? AccentDim : pillKind == 2 ? WarnDim : SurfaceInB,
            Child = new TextBlock
            {
                Text = pillText, FontSize = 10.5, FontWeight = FontWeight.SemiBold,
                Foreground = pillKind == 0 ? Accent : pillKind == 2 ? Warn : Text2,
            },
        };
        Grid.SetColumn(pill, 2);
        grid.Children.Add(pill);
        action.Margin = new Thickness(12, 0, 0, 0);
        Grid.SetColumn(action, 3);
        grid.Children.Add(action);
        return new Border
        {
            Background = paired ? Tokens.B("HeroGradient") : Tokens.B("Surface"),
            BorderBrush = Tokens.B(paired ? "StrokeHi" : "Stroke"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(13),
            Padding = new Thickness(16, 13),
            Child = grid,
        };
    }

    private void AppendLog(string msg)
    {
        _logLines.Add($"{DateTime.Now:HH:mm:ss} {msg}");
        while (_logLines.Count > 200) _logLines.RemoveAt(0);
        txtLog.Text = string.Join("\n", _logLines);
        txtLog.CaretIndex = txtLog.Text?.Length ?? 0;
    }
}

using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace AirBridge;

/// <summary>One phone camera stream's parameters, from VIDEO_CONFIG.
/// CsdBytes is the Annex-B SPS+PPS stream the decoder must see first;
/// Rotation/Mirrored/LensFacing describe the raw frames (the phone
/// bakes no transform in) — the stage-2 virtual camera applies them;
/// the stage-1 preview shows the frames as sent.</summary>
public sealed record VideoStreamConfig(
    int Width, int Height, int Fps, int Rotation, bool Mirrored, string LensFacing, byte[]? CsdBytes);

public sealed class NearbyDevice
{
    public string DeviceId { get; set; } = "";
    public string Name { get; set; } = "";
    public string Os { get; set; } = "";
    public string Ip { get; set; } = "";
    public DateTime LastSeenUtc { get; set; } = DateTime.MinValue;
    public HashSet<string> Transports { get; } = new();
    public bool Paired { get; set; }
    // Presence window ~7 s, aligned with the phone (hardening §5.9, F16).
    public bool Online => (DateTime.UtcNow - LastSeenUtc).TotalSeconds <= 7;
    public string Key => !string.IsNullOrEmpty(DeviceId) ? DeviceId : Ip;
    public override string ToString() =>
        $"{(string.IsNullOrEmpty(Name) ? "未知手机" : Name)} · {Ip} · {(Paired ? "已配对" : "未配对")} · {(Online ? "在线" : "离线")} · {string.Join("+", Transports)}";
}

/// <summary>
/// UDP bridge engine — a faithful C# port of pc/phone_audio_pc.py Bridge:
/// beacon 1/s, DISCOVER→DISCOVER_RESP, HELLO auth (pair code / QR tokenHash / TOFU 120 s /
/// already-paired), PING/PONG 1 s, adaptive rate, 5 s link-death, jitter buffer feed.
/// Extensions for the v2 PC-first flow (wire-compatible; one additive packet type,
/// TYPE_SET_VOLUME, added by the volume-stream fix — old phones ignore unknown types):
///  - nearby-device registry fed by UDP DISCOVER/HELLO and by mDNS (deduped by deviceId);
///  - scoped pair/connect windows opened when the user taps a phone in 「添加手机」.
/// </summary>
public sealed class BridgeEngine
{
    public readonly AppConfig Config;
    public readonly AppSettings Settings;
    public readonly LinkStats Stats = new();
    public readonly JitterBuffer Jb = new();
    /// <summary>Defect 5b runtime net: own-voice return detection on the speaker path.</summary>
    public readonly LoopDetector Loop = new();
    public bool LoopDetected => Loop.LoopDetected;

    public bool Running { get; private set; }
    public bool Connected { get; private set; }
    public bool Paused => Settings.Paused;
    public string PeerName { get; private set; } = "?";
    public IPEndPoint? Peer { get; private set; }
    public int RateCode { get; private set; } = Protocol.Rate48k;
    public DateTime StartedAtUtc { get; private set; }
    public string LastVerifyCode { get; private set; } = "";
    public string LastPairPhone { get; private set; } = "";
    public string LastEvent { get; private set; } = "";
    /// <summary>Version fields parsed from the phone's HELLO (unified-versioning rule).</summary>
    public string PhoneAppVersion { get; private set; } = "";
    public int PhoneProtoVersion { get; private set; } = -1; // -1 = not reported
    /// <summary>The phone's per-device speaker gain for THIS PC (0..1), reported in
    /// HELLO — the value its device-card slider holds. The PC's 手机音量 slider
    /// initializes from this (single source of truth), never from a local guess.
    /// -1 = not reported yet (unpaired HELLO, or a pre-fix phone).</summary>
    public double PhoneSpeakerVolume { get; private set; } = -1;
    /// <summary>True when the connected phone's protoVersion != 2 or appVersion != ours.
    /// Audio still connects; the UI must surface this loudly (never silent).</summary>
    public bool VersionMismatch => Connected &&
        (PhoneProtoVersion != Protocol.ProtoVersion || PhoneAppVersion != Protocol.AppVersion);

    // ---- camera relay (wave 4) ----
    /// <summary>True when the connected phone advertised caps ["video1"] in HELLO.</summary>
    public bool PhoneVideoCapable { get; private set; }
    /// <summary>The phone reported (VIDEO_CTRL state) or implied (VIDEO_CONFIG) an active camera stream.</summary>
    public bool VideoStreaming { get; private set; }
    /// <summary>Latest VIDEO_CONFIG from the phone (null until the first stream starts).</summary>
    public VideoStreamConfig? CurrentVideoConfig { get; private set; }
    /// <summary>Wave-4 stage-2: MF virtual-camera feeder, assigned by
    /// MainWindow. Started/stopped with StartVideo/StopVideo. Null in
    /// headless/test contexts — video then stays preview-only.</summary>
    public VirtualCameraService? VirtualCamera { get; set; }
    public long VideoPacketsReceived => Interlocked.Read(ref _videoPacketsReceived);
    public long VideoAccessUnits { get; private set; }
    public long VideoFramesDropped { get; private set; }
    public long VideoFramesDecoded { get; private set; }
    /// <summary>Raised (on the decoder thread) when a new preview frame is available.</summary>
    public event Action? VideoFrameArrived;

    private readonly ConcurrentQueue<byte[]> _videoRxQueue = new();
    private readonly VideoReassembler _videoReasm = new();
    private H264Decoder? _videoDecoder;
    private volatile bool _videoDecoderDirty;
    private long _videoPacketsReceived;
    private int _lastReasmDropped;
    private readonly object _frameLock = new();
    private byte[]? _latestFrame;
    private int _latestFrameW, _latestFrameH;
    private long _latestFrameIndex = -1;
    private long _videoFrameSeq;

    public event Action? StateChanged;
    public event Action<string>? Log;
    public event Action<int>? RateChanged; // new rate code

    private UdpClient? _udp;
    private CancellationTokenSource? _cts;
    private uint _txSeq, _pingSeq;
    private DateTime _lastRxUtc = DateTime.MinValue;
    private int _pendingRate = -1;
    private DateTime _pendingSinceUtc;
    private int _goodWindows;
    private readonly object _nearbyLock = new();
    private readonly Dictionary<string, NearbyDevice> _nearby = new(); // key = deviceId or ip
    private readonly Dictionary<string, DateTime> _pairWindows = new();    // deviceId -> expiry (PC-tap pairing)
    private readonly Dictionary<string, DateTime> _connectWindows = new(); // deviceId -> expiry (PC-tap connect)
    private readonly object _winLock = new();

    // ---- cross-subnet sweep (v2.2.3, FB-6) ----
    // Broadcast/mDNS cannot cross the routers between Gray's routed subnets
    // (192.168.23/88/99.0/24). The sweep unicasts the SAME beacon payload to every
    // host of the candidate /24s computed by SubnetSweep.cs (local
    // interfaces + paired phones' learned last-IP subnets + user extras);
    // a phone treats a unicast beacon exactly like a broadcast one
    // (presence), and answers with its own discovery/HELLO. Pure logic and
    // bounds live in SubnetSweep.cs / SweepPlan (mirror of the Android
    // SubnetSweep.kt, JVM-proven by tools/TestSweep.kt).
    private readonly SweepPlan _sweep = new();
    private string _sweepSig = "";
    private DateTime _sweepCycleDoneUtc = DateTime.MinValue;
    private DateTime _lastPairSaveUtc = DateTime.MinValue;
    /// <summary>Set by the UI: true while the window is up on the
    /// Device/Discover page. Since the hardening this is only a
    /// FULL-RATE hint: the sweep no longer stops when it is false —
    /// in the background (tray) it keeps running at a reduced rate
    /// whenever a paired phone is unconnected (§5.5, F10).</summary>
    public volatile bool DiscoveryActive;
    private const int SweepBatchPerTick = 10;   // per 250 ms tick ≈ 40 probes/s (foreground)
    private const int SweepBatchPerTickBg = 4;  // background ≈ 16 probes/s
    private const double SweepCyclePauseSec = 5.0;
    private const double SweepCyclePauseBgSec = 60.0;

    // ---- discovery hardening: diagnostics, firewall, paired poke ----
    /// <summary>Per-channel discovery counters (§5.4).</summary>
    public readonly DiscoveryDiag.Counters Diag = new();
    /// <summary>Firewall self-check + one-click elevated repair (§5.3).
    /// The check runs once at engine start; the UI reads the state and
    /// calls Firewall.RepairAsync() from the diagnostics repair entry.</summary>
    public readonly FirewallGuard Firewall = new();
    private long _startedMs;
    private long _lastPairedPokeMs;
    private DiscoveryDiag.Verdict _verdict = DiscoveryDiag.Verdict.None;
    private List<IPAddress> _directedBroadcasts = new();
    private DateTime _broadcastListUtc = DateTime.MinValue;
    private static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    /// <summary>Engine log lines go to the UI event AND the persistent
    /// ring-buffer file (§5.4) — a "cannot discover" report must be
    /// diagnosable after a restart, not only while the window lives.</summary>
    private void EmitLog(string msg)
    {
        EngineLog.Write(msg);
        var handler = Log;
        handler?.Invoke(msg);
    }

    public BridgeEngine(AppConfig config, AppSettings settings)
    {
        Config = config; Settings = settings;
        Loop.Log += msg => EmitLog(msg);
        if (settings.SampleRatePreference != "auto" &&
            int.TryParse(settings.SampleRatePreference, out var hz))
            RateCode = Protocol.RateCodeForHz(hz);
    }

    public IReadOnlyList<NearbyDevice> NearbySnapshot()
    {
        lock (_nearbyLock)
        {
            SweepNearbyLocked();
            // paired pinned first, then online, then last-seen desc
            return _nearby.Values
                .OrderByDescending(d => d.Paired)
                .ThenByDescending(d => d.Online)
                .ThenByDescending(d => d.LastSeenUtc)
                .ToList();
        }
    }

    private void SweepNearbyLocked()
    {
        var now = DateTime.UtcNow;
        foreach (var key in _nearby.Keys.ToList())
        {
            var d = _nearby[key];
            d.Paired = Config.PairedPhones.ContainsKey(d.DeviceId);
            if (!d.Paired && (now - d.LastSeenUtc).TotalSeconds > 7)
                _nearby.Remove(key); // presence rule: expire ~7 s without beacon (aligned window)
        }
    }

    public void NoteNearby(string deviceId, string name, string os, string ip, string transport)
    {
        lock (_nearbyLock)
        {
            string key = !string.IsNullOrEmpty(deviceId) ? deviceId : ip;
            if (!_nearby.TryGetValue(key, out var d))
            {
                // merge an ip-keyed placeholder into the deviceId entry when identity arrives
                if (!string.IsNullOrEmpty(deviceId) && _nearby.TryGetValue(ip, out var byIp))
                { d = byIp; _nearby.Remove(ip); _nearby[key] = d; }
                else { d = new NearbyDevice(); _nearby[key] = d; }
            }
            if (!string.IsNullOrEmpty(deviceId)) d.DeviceId = deviceId;
            if (!string.IsNullOrEmpty(name)) d.Name = name;
            if (!string.IsNullOrEmpty(os)) d.Os = os;
            if (!string.IsNullOrEmpty(ip)) d.Ip = ip;
            d.LastSeenUtc = DateTime.UtcNow;
            d.Transports.Add(transport);
            if (transport == "mdns") Diag.Inc(DiscoveryDiag.Channel.Mdns, DiscoveryDiag.Dir.Received);
            d.Paired = Config.PairedPhones.ContainsKey(d.DeviceId);
        }
        LearnPairedIp(deviceId, ip); // v2.2.3 FB-6: persist paired phones' IPs for the sweep
        StateChanged?.Invoke();
    }

    // ---- PC-tap actions from 「添加手机」 ----
    /// <summary>One-tap pairing: open a 120 s scoped pair window for exactly this phone and
    /// nudge it with a unicast DISCOVER_RESP so its discovery UI surfaces this PC. The phone's
    /// next HELLO then pairs (HELLO_ACK carries token + verifyCode) — no typing anywhere.</summary>
    public void TapPair(NearbyDevice d)
    {
        lock (_winLock) _pairWindows[d.DeviceId] = DateTime.UtcNow.AddSeconds(120);
        if (IPAddress.TryParse(d.Ip, out var ip))
            SendDiscoverResp(new IPEndPoint(ip, Protocol.Port));
        LastEvent = Loc.F("已向 {0} 发起配对，等待手机确认…", "Pairing requested from {0} — waiting for confirmation on the phone…", d.Name);
        EmitLog(LastEvent);
        StateChanged?.Invoke();
    }

    /// <summary>One-tap connect for an already-paired phone (used when 允许已配对手机自动重连 is off).</summary>
    public void TapConnect(NearbyDevice d)
    {
        lock (_winLock) _connectWindows[d.DeviceId] = DateTime.UtcNow.AddSeconds(60);
        if (IPAddress.TryParse(d.Ip, out var ip))
            SendDiscoverResp(new IPEndPoint(ip, Protocol.Port));
        LastEvent = Loc.F("已请求 {0} 连接…", "Connect requested from {0}…", d.Name);
        StateChanged?.Invoke();
    }

    /// <summary>Paired fast reconnect for a phone that is NOT currently visible
    /// (v2.2.3 FB-6): unicast the DISCOVER_RESP + beacon straight to its stored
    /// last IP — across subnet boundaries broadcast could never reach — and
    /// open the same 60 s connect window. The phone's own reconnect policy
    /// still decides whether it HELLOs back.</summary>
    public void TapConnectStored(string deviceId)
    {
        if (!Config.PairedPhones.TryGetValue(deviceId, out var pp)) return;
        if (string.IsNullOrEmpty(pp.LastIp) || !IPAddress.TryParse(pp.LastIp, out var ip)) return;
        lock (_winLock) _connectWindows[deviceId] = DateTime.UtcNow.AddSeconds(60);
        var ep = new IPEndPoint(ip, Protocol.Port);
        SendDiscoverResp(ep);
        Send(Protocol.TypeBeacon, BeaconPayload(), 0, 0, RateCode, ep);
        LastEvent = Loc.F("已向 {0} 的上次地址 {1} 发起连接…", "Connecting to {0} at its last address {1}…", pp.Name, pp.LastIp);
        EmitLog(LastEvent);
        StateChanged?.Invoke();
    }

    private bool InWindow(Dictionary<string, DateTime> map, string id)
    {
        lock (_winLock)
            return !string.IsNullOrEmpty(id) && map.TryGetValue(id, out var exp) && DateTime.UtcNow < exp;
    }

    /// <summary>Periodic paired poke (hardening §5.1): every ~15 s while
    /// unconnected, unicast DISCOVER_RESP + BEACON to every paired phone's
    /// stored last IP — the automatic form of the manual TapConnectStored,
    /// with ONE deliberate difference: it does NOT open a connect window.
    /// The window is the PC user's tap-consent for the
    /// AllowPairedAutoReconnect=false case; opening it automatically
    /// would silently override that setting. The poke only delivers
    /// presence across subnet boundaries; the phone's own consent
    /// machinery still decides whether to HELLO back, and the pairing
    /// policy still judges that HELLO exactly as before.</summary>
    private void PokePairedStored()
    {
        foreach (var (_, pp) in Config.PairedPhones)
        {
            if (string.IsNullOrEmpty(pp.LastIp) || !IPAddress.TryParse(pp.LastIp, out var ip)) continue;
            var ep = new IPEndPoint(ip, Protocol.Port);
            SendDiscoverResp(ep);
            Send(Protocol.TypeBeacon, BeaconPayload(), 0, 0, RateCode, ep);
            Diag.Inc(DiscoveryDiag.Channel.Direct, DiscoveryDiag.Dir.Sent, 2);
        }
    }

    /// <summary>The latest discovery verdict (§5.4), recomputed every
    /// adapt tick from the channel counters.</summary>
    public DiscoveryDiag.Verdict CurrentVerdict => _verdict;

    /// <summary>Discovery health snapshot for the diagnostics UI (§5.4):
    /// counters, verdict, firewall state, sweep state — everything the
    /// "why is nothing found" question needs, in one object.</summary>
    public sealed record DiscoveryHealth(
        DiscoveryDiag.Snapshot Counters,
        DiscoveryDiag.Verdict Verdict,
        FirewallGuard.State FirewallState,
        string FirewallDetail,
        bool? ActiveProfileIsPublic,
        bool SweepForeground,
        int SweepSubnets,
        int SweepRemaining);

    public DiscoveryHealth GetDiscoveryHealth() => new(
        Diag.TakeSnapshot(),
        _verdict,
        Firewall.CurrentState,
        Firewall.Detail,
        Firewall.ActiveProfileIsPublic,
        DiscoveryActive,
        _sweep.SubnetCount,
        _sweep.RemainingCount);

    /// <summary>One-line plain-language discovery health (diagnostics row).</summary>
    public string DiscoveryHealthText
    {
        get
        {
            var h = GetDiscoveryHealth();
            string Ch(DiscoveryDiag.Channel c) => $"{h.Counters.Sent(c)}/{h.Counters.Received(c)}";
            string verdictText = h.Verdict switch
            {
                DiscoveryDiag.Verdict.SendNoReceive => Loc.T(
                    "已发出信标但从未收到任何数据包——很可能被 Windows 防火墙拦截",
                    "Beacons are being sent but no packet has ever been received — the Windows Firewall is the likely cause"),
                _ => Loc.T("暂无异常结论", "No verdict"),
            };
            string fwText = h.FirewallState switch
            {
                FirewallGuard.State.Ok => Loc.T("防火墙规则已就绪", "firewall rule OK"),
                FirewallGuard.State.RuleMissing => Loc.T("防火墙规则缺失（可一键修复）", "firewall rule missing (one-click repair available)"),
                _ => Loc.T("防火墙状态未知", "firewall state unknown"),
            };
            return Loc.F(
                "发现 发/收 — 广播 {0} · mDNS {1} · 扫描 {2} · 直连 {3} · {4} · 判定：{5}",
                "Discovery sent/received — broadcast {0} · mDNS {1} · sweep {2} · direct {3} · {4} · verdict: {5}",
                Ch(DiscoveryDiag.Channel.Broadcast), Ch(DiscoveryDiag.Channel.Mdns),
                Ch(DiscoveryDiag.Channel.Sweep), Ch(DiscoveryDiag.Channel.Direct),
                fwText, verdictText);
        }
    }

    // ---- lifecycle ----
    public void Start()
    {
        if (Running) return;
        _cts = new CancellationTokenSource();
        _udp = new UdpClient();
        _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udp.EnableBroadcast = true;
        _udp.Client.Bind(new IPEndPoint(IPAddress.Any, Config.Port));
        Running = true;
        StartedAtUtc = DateTime.UtcNow;
        _startedMs = NowMs();
        var ct = _cts.Token;
        Task.Run(() => RecvLoop(ct), ct);
        Task.Run(() => BeaconLoop(ct), ct);
        Task.Run(() => AdaptLoop(ct), ct);
        Task.Run(() => SweepLoop(ct), ct);
        Task.Run(() => VideoLoop(ct), ct);
        EmitLog($"[监听] UDP {Config.Port}，等待手机连接…");
        StateChanged?.Invoke();
        // Firewall self-check (§5.3): read-only, background, fail-safe.
        // A missing rule is the #1 cause of "PC sends but never receives"
        // — it must be a logged, UI-visible state, not a silent void.
        _ = Task.Run(async () =>
        {
            var st = await Firewall.CheckAsync();
            EmitLog(st == FirewallGuard.State.Ok
                ? "[防火墙] 入站规则已就绪（UDP 47731）"
                : $"[防火墙] 入站规则{(st == FirewallGuard.State.RuleMissing ? "缺失或被禁用" : "状态未知")}：{Firewall.Detail}" +
                  (Firewall.ActiveProfileIsPublic == true ? "；当前网络为公用(Public)配置" : "") +
                  "——手机可能能看到电脑但连不上，可在诊断里一键修复");
            StateChanged?.Invoke();
        });
    }

    public void Stop()
    {
        if (!Running) return;
        Running = false;
        try { if (Peer is not null) Send(Protocol.TypeBye, ReadOnlySpan<byte>.Empty, 0, 0, RateCode, Peer); } catch { }
        _cts?.Cancel();
        try { _udp?.Close(); } catch { }
        Connected = false;
        VideoStreaming = false;
        try { _videoDecoder?.Dispose(); } catch { }
        _videoDecoder = null;
        try { VirtualCamera?.Stop(); } catch { }
        StateChanged?.Invoke();
    }

    /// <summary>Per-session disconnect (B-08): end ONLY the current phone
    /// session — BYE to the peer, session state cleared — while the engine,
    /// discovery and beacons keep running, so this PC stays discoverable
    /// and a phone can connect again without restarting anything. Distinct
    /// from Stop(), which tears the whole service down. If the phone's
    /// reconnect mode is "always" it may re-HELLO on its own afterwards —
    /// that is the phone-side policy the user chose, not a resurrection.</summary>
    public void DisconnectSession()
    {
        if (Peer is null && !Connected) return;
        try { if (Peer is not null) Send(Protocol.TypeBye, ReadOnlySpan<byte>.Empty, 0, 0, RateCode, Peer); } catch { }
        EmitLog($"[断开] 已断开与 {PeerName} 的会话（服务与发现继续运行）");
        Connected = false;
        Peer = null;
        Jb.Reset();
        StateChanged?.Invoke();
    }

    // ---- packet helpers ----
    private void Send(byte type, ReadOnlySpan<byte> payload, uint seq, ulong ts, int rateCode, IPEndPoint addr)
    {
        var udp = _udp; if (udp is null) return;
        var pkt = Protocol.Pack(type, seq, ts == 0 ? (ulong)Protocol.MonotonicUs() : ts, payload, Protocol.CodecPcm16, rateCode);
        try { udp.Send(pkt, pkt.Length, addr); } catch { }
    }
    private void SendToPeer(byte type, ReadOnlySpan<byte> payload = default, uint seq = 0, ulong ts = 0, int? rateCode = null)
    { if (Peer is not null) Send(type, payload, seq, ts, rateCode ?? RateCode, Peer); }

    public void SendAudioFrame(byte[] pcmFrame)
    {
        if (!Connected || Peer is null || Paused || !Settings.PcToPhone) return;
        // Loop net (5b): always feed the detector; while a return loop is
        // detected the contaminated speaker stream is NOT sent on.
        Loop.FeedSpeakerFrame(pcmFrame);
        if (Loop.LoopDetected) return;
        _txSeq++; Stats.Tx++;
        SendToPeer(Protocol.TypeAudio, pcmFrame, _txSeq);
    }

    private static byte[] JsonBytes(object o) => Encoding.UTF8.GetBytes(JsonSerializer.Serialize(o));

    /// <summary>Remote per-device volume (TYPE_SET_VOLUME): drives the phone's speaker
    /// gain for THIS PC — the same registry value the phone's device-card slider edits,
    /// applied at the same mix point. The UI throttles drag events (~100 ms) and calls
    /// this with its 0–100 percent scale; conversion goes through the one shared
    /// mapping (Protocol.GainForPercent). No-op while disconnected.</summary>
    public void SendPhoneVolume(int percent)
    {
        if (!Connected || Peer is null) return;
        var payload = JsonBytes(new Dictionary<string, object?>
        { ["speakerVolume"] = Protocol.GainForPercent(percent) });
        SendToPeer(Protocol.TypeSetVolume, payload);
    }

    private void SendDiscoverResp(IPEndPoint addr)
    {
        var resp = new Dictionary<string, object?>
        {
            ["app"] = "PhoneAudioBridge", ["kind"] = "discover_resp",
            ["name"] = Environment.MachineName, ["os"] = "Windows",
            ["deviceId"] = Config.DeviceId,
            ["tokenHash"] = string.IsNullOrEmpty(Config.Token) ? "" : Protocol.TokenHash(Config.Token),
            ["appVersion"] = Protocol.AppVersion,
            ["protoVersion"] = Protocol.ProtoVersion,
        };
        Send(Protocol.TypeDiscoverResp, JsonBytes(resp), 0, 0, RateCode, addr);
    }

    private byte[] BeaconPayload() => JsonBytes(new Dictionary<string, object?>
    {
        ["app"] = "PhoneAudioBridge", ["kind"] = "beacon",
        ["name"] = Environment.MachineName, ["os"] = "Windows",
        ["deviceId"] = Config.DeviceId,
        ["tokenHash"] = string.IsNullOrEmpty(Config.Token) ? "" : Protocol.TokenHash(Config.Token),
        ["appVersion"] = Protocol.AppVersion,
        ["protoVersion"] = Protocol.ProtoVersion,
    });

    private void BeaconLoop(CancellationToken ct)
    {
        var broadcast = new IPEndPoint(IPAddress.Broadcast, Config.Port);
        while (!ct.IsCancellationRequested && Running)
        {
            try
            {
                if (!string.IsNullOrEmpty(Config.DeviceId))
                {
                    var payload = BeaconPayload();
                    Send(Protocol.TypeBeacon, payload, 0, 0, RateCode, broadcast);
                    Diag.Inc(DiscoveryDiag.Channel.Broadcast, DiscoveryDiag.Dir.Sent);
                    // F2 (hardening §5.3): the limited broadcast egresses
                    // only via the default-route interface. On a
                    // multi-homed PC, ALSO send each interface's directed
                    // broadcast (x.x.x.255) — the OS routes those out of
                    // the owning interface, so phones on the PC's other
                    // subnets hear us too.
                    foreach (var db in DirectedBroadcasts())
                    {
                        Send(Protocol.TypeBeacon, payload, 0, 0, RateCode, new IPEndPoint(db, Config.Port));
                        Diag.Inc(DiscoveryDiag.Channel.Broadcast, DiscoveryDiag.Dir.Sent);
                    }
                }
            }
            catch { }
            try { Task.Delay(1000, ct).Wait(ct); } catch { break; }
        }
    }

    /// <summary>Directed broadcast address of every Up IPv4 interface
    /// (ip | ~mask; /24 assumed when the mask is unavailable), deduped,
    /// refreshed at most every 10 s.</summary>
    private List<IPAddress> DirectedBroadcasts()
    {
        if ((DateTime.UtcNow - _broadcastListUtc).TotalSeconds < 10) return _directedBroadcasts;
        _broadcastListUtc = DateTime.UtcNow;
        var outp = new List<IPAddress>();
        try
        {
            foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (IPAddress.IsLoopback(ua.Address)) continue;
                    if (!SubnetSweep.TryParseIpv4(ua.Address.ToString(), out int ip)) continue;
                    int mask = unchecked((int)0xFFFFFF00);
                    if (ua.IPv4Mask != null && SubnetSweep.TryParseIpv4(ua.IPv4Mask.ToString(), out int m) && m != 0)
                        mask = m;
                    var bc = IPAddress.Parse(SubnetSweep.FormatIpv4(ip | ~mask));
                    if (!outp.Contains(bc)) outp.Add(bc);
                }
            }
        }
        catch { }
        _directedBroadcasts = outp;
        return outp;
    }

    // ---- cross-subnet sweep driver (v2.2.3 FB-6) ----
    private void SweepLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && Running)
        {
            try { Task.Delay(250, ct).Wait(ct); } catch { break; }
            if (!Running) break;
            try { DriveSweep(); } catch { /* a bad interface enumeration must not kill the loop */ }
        }
    }

    private void DriveSweep()
    {
        // candidate inputs: local interface IPs (split physical/virtual —
        // the ordering ranks paired subnets first, then physical, then
        // virtual, F9), paired phones' learned IPs, the user's
        // extra-subnet setting. Rebuild the plan when they change.
        var localPhysical = new List<int>();
        var localVirtual = new List<int>();
        var ownIps = new HashSet<int>();
        try
        {
            foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                bool isVirtual = SubnetSweep.IsVirtualInterface(ni.Name, ni.Description, ni.NetworkInterfaceType);
                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (IPAddress.IsLoopback(ua.Address)) continue;
                    if (SubnetSweep.TryParseIpv4(ua.Address.ToString(), out int v))
                    { (isVirtual ? localVirtual : localPhysical).Add(v); ownIps.Add(v); }
                }
            }
        }
        catch { }
        var pairedIps = new List<int>();
        foreach (var pp in Config.PairedPhones.Values)
            if (!string.IsNullOrEmpty(pp.LastIp) && SubnetSweep.TryParseIpv4(pp.LastIp, out int pv))
                pairedIps.Add(pv);
        var extras = SubnetSweep.SplitSpecs(Settings.ExtraSubnets ?? "");
        var localIps = localPhysical.Concat(localVirtual).ToList();
        string sig = string.Join(",", localIps) + "|" + string.Join(",", pairedIps) + "|" + string.Join(",", extras);
        if (sig != _sweepSig)
        {
            _sweepSig = sig;
            _sweep.Reset(SubnetSweep.CandidateSubnets(localPhysical, localVirtual, pairedIps, extras), pairedIps, ownIps);
            _sweepCycleDoneUtc = DateTime.MinValue;
        }
        // Sweep modes (hardening §5.5, F10): the tray is this product's
        // NORMAL state, so the old UI gate (DiscoveryActive) no longer
        // turns the sweep off — it only selects the rate. Background =
        // engine running, window not in discovery mode, at least one
        // paired phone unconnected: reduced rate (small batches, ~60 s
        // between cycles; priority hosts — paired last IPs — are always
        // probed first in every cycle). Idle only when there is nothing
        // paired to look for and the UI is not asking.
        bool foreground = DiscoveryActive;
        bool background = !foreground && !Connected && Config.PairedPhones.Count > 0;
        if ((!foreground && !background) || string.IsNullOrEmpty(Config.DeviceId)) return;
        double pauseSec = foreground ? SweepCyclePauseSec : SweepCyclePauseBgSec;
        if (_sweep.IsCycleComplete)
        {
            if (_sweepCycleDoneUtc == DateTime.MinValue) _sweepCycleDoneUtc = DateTime.UtcNow;
            if ((DateTime.UtcNow - _sweepCycleDoneUtc).TotalSeconds >= pauseSec)
            { _sweep.BeginCycle(); _sweepCycleDoneUtc = DateTime.MinValue; }
            else return;
        }
        var batch = _sweep.NextBatch(foreground ? SweepBatchPerTick : SweepBatchPerTickBg);
        if (batch.Count == 0) return;
        var payload = BeaconPayload();
        foreach (int target in batch)
        {
            var ep = new IPEndPoint(IPAddress.Parse(SubnetSweep.FormatIpv4(target)), Config.Port);
            Send(Protocol.TypeBeacon, payload, 0, 0, RateCode, ep);
            Diag.Inc(DiscoveryDiag.Channel.Sweep, DiscoveryDiag.Dir.Sent);
        }
    }

    /// <summary>A packet from [addr] answers the sweep: don't re-probe that
    /// host until the next cycle. Called for discovery/session packets only
    /// (not the audio hot path).</summary>
    private void NoteSweepResponse(IPAddress addr)
    {
        Diag.Inc(DiscoveryDiag.Channel.Sweep, DiscoveryDiag.Dir.Received);
        if (SubnetSweep.TryParseIpv4(addr.ToString(), out int v)) _sweep.NoteResponse(v);
    }

    /// <summary>Learn + persist a paired phone's current IP (throttled save),
    /// feeding candidate source (b) of the cross-subnet sweep.</summary>
    private void LearnPairedIp(string deviceId, string ip)
    {
        if (string.IsNullOrEmpty(deviceId) || string.IsNullOrEmpty(ip)) return;
        if (!Config.PairedPhones.TryGetValue(deviceId, out var pp)) return;
        if (pp.LastIp == ip) return;
        pp.LastIp = ip;
        if ((DateTime.UtcNow - _lastPairSaveUtc).TotalSeconds >= 15)
        {
            _lastPairSaveUtc = DateTime.UtcNow;
            Config.Save();
        }
    }

    private void RecvLoop(CancellationToken ct)
    {
        var udp = _udp!;
        udp.Client.ReceiveTimeout = 500;
        var remote = new IPEndPoint(IPAddress.Any, 0);
        while (!ct.IsCancellationRequested && Running)
        {
            byte[] buffer;
            IPEndPoint addr;
            try
            {
                EndPoint ep = remote;
                buffer = new byte[65536]; int n = udp.Client.ReceiveFrom(buffer, ref ep); Array.Resize(ref buffer, n);
                addr = (IPEndPoint)ep;
            }
            catch (SocketException) { continue; }
            catch { break; }
            if (!Protocol.TryUnpack(buffer, out var h, out var payloadSpan)) continue;
            var payload = payloadSpan.ToArray();
            var type = h.Type; var seq = h.Seq; var ts = h.TimestampUs; var rateCode = h.RateCode;
            switch (type)
            {
                case Protocol.TypeDiscover:
                {
                    Diag.Inc(DiscoveryDiag.Channel.Broadcast, DiscoveryDiag.Dir.Received);
                    NoteSweepResponse(addr.Address);
                    var info = ParseJson(payload);
                    NoteNearby(Str(info, "deviceId"), Str(info, "name"), Str(info, "os"), addr.Address.ToString(), "udp");
                    SendDiscoverResp(addr);
                    break;
                }
                case Protocol.TypeHello:
                    Diag.Inc(DiscoveryDiag.Channel.Direct, DiscoveryDiag.Dir.Received);
                    NoteSweepResponse(addr.Address);
                    HandleHello(payload, addr);
                    break;
                case Protocol.TypeAudio:
                    if (Peer is null) { Peer = addr; Connected = true; }
                    else RetargetPeerIfRoamed(addr);
                    _lastRxUtc = DateTime.UtcNow;
                    Stats.OnAudioRx(seq, ts);
                    Jb.Push(seq, payload);
                    Loop.FeedMicFrame(payload);
                    break;
                case Protocol.TypePing:
                    NoteSweepResponse(addr.Address);
                    Send(Protocol.TypePong, ReadOnlySpan<byte>.Empty, seq, ts, RateCode, addr);
                    if (Peer is not null) RetargetPeerIfRoamed(addr);
                    _lastRxUtc = DateTime.UtcNow;
                    break;
                case Protocol.TypePong:
                    NoteSweepResponse(addr.Address);
                    Stats.OnPong(ts);
                    _lastRxUtc = DateTime.UtcNow;
                    break;
                case Protocol.TypeRateReq:
                    if (rateCode != RateCode) ApplyRate(rateCode);
                    Send(Protocol.TypeRateAck, ReadOnlySpan<byte>.Empty, 0, 0, RateCode, addr);
                    break;
                case Protocol.TypeRateAck:
                    if (_pendingRate >= 0 && rateCode == _pendingRate) { ApplyRate(_pendingRate); _pendingRate = -1; }
                    break;
                case Protocol.TypeBye:
                    EmitLog("[断开] 手机端已停止，等待重新连接…");
                    Connected = false;
                    VideoStreaming = false;
                    StateChanged?.Invoke();
                    break;
                case Protocol.TypeVideoConfig:
                    HandleVideoConfig(payload);
                    break;
                case Protocol.TypeVideoData:
                    // Hard rule (plan §B.2): the receive loop only enqueues
                    // the payload — RecvLoop already copied it out of the
                    // socket buffer. Reassembly and decode run on the
                    // video thread; this thread never blocks on video.
                    _videoRxQueue.Enqueue(payload);
                    Interlocked.Increment(ref _videoPacketsReceived);
                    break;
                case Protocol.TypeVideoCtrl:
                    HandleVideoCtrl(payload);
                    break;
            }
        }
    }

    /// <summary>
    /// Mesh-roaming return path (v2.2 defect A): when the phone roams to
    /// another mesh node its source address changes mid-session. The PC
    /// must follow the phone's CURRENT address — learned from the packets
    /// the phone is actually sending (AUDIO/PING), not just from HELLO —
    /// otherwise speaker audio keeps going to the phone's dead old IP
    /// while discovery still "sees" it. (Same trust model as the rest of
    /// the audio path: pairing is authenticated at HELLO; audio frames
    /// were already accepted from the live peer address.)
    /// The PC's own IP changing needs no handling here: the socket binds
    /// IPAddress.Any, beacons broadcast every 1 s carry this PC's
    /// deviceId, and the phone re-resolves its session from the beacon's
    /// source address (never from a stored IP) — see phone PeerDirectory.
    /// </summary>
    private void RetargetPeerIfRoamed(IPEndPoint addr)
    {
        if (Peer is null || addr.Equals(Peer)) return;
        EmitLog($"[漫游] 手机地址已变更 {Peer.Address} → {addr.Address}，发送目标已跟随");
        Peer = addr;
        StateChanged?.Invoke();
    }

    private void HandleHello(byte[] payload, IPEndPoint addr)
    {
        var info = ParseJson(payload);
        string phoneId = Str(info, "deviceId");
        string phoneName = Str(info, "name");
        // unified versioning: parse the phone's reported versions from HELLO
        PhoneAppVersion = Str(info, "appVersion");
        PhoneProtoVersion = info.TryGetValue("protoVersion", out var pvEl) &&
            pvEl.ValueKind == JsonValueKind.Number && pvEl.TryGetInt32(out var pv) ? pv : -1;
        NoteNearby(phoneId, phoneName, Str(info, "os"), addr.Address.ToString(), "udp");
        Config.EnsurePairCode();
        bool tofuOpen = (DateTime.UtcNow - StartedAtUtc).TotalSeconds < 120;
        var (action, issueToken) = PairingPolicy.DecideHelloAuth(
            Config.PairedPhones, phoneId,
            Str(info, "tokenHash"), Str(info, "pairCode"),
            Config.Token, Config.PairCode, Config.PairCodeValid, tofuOpen,
            InWindow(_pairWindows, phoneId), InWindow(_connectWindows, phoneId),
            Settings.AllowPairedAutoReconnect);
        if (action == PairingPolicy.Action.Reject)
        {
            EmitLog($"[配对] 拒绝未配对手机的连接请求 ({addr.Address})——请在「添加手机」中点选该手机配对，或让手机输入配对码/扫码");
            return;
        }
        lock (_winLock) { _pairWindows.Remove(phoneId); _connectWindows.Remove(phoneId); }
        Peer = addr; Connected = true; _lastRxUtc = DateTime.UtcNow;
        PeerName = string.IsNullOrEmpty(phoneName) ? "?" : phoneName;
        // Phone-reported per-device speaker gain (see PhoneSpeakerVolume).
        if (info.TryGetValue("speakerVolume", out var svEl) && svEl.ValueKind == JsonValueKind.Number &&
            svEl.TryGetDouble(out var sv)) PhoneSpeakerVolume = Math.Clamp(sv, 0.0, 1.0);
        // Camera-relay capability (wave 4): the phone advertises caps in
        // HELLO; video is enabled only when both ends list "video1".
        PhoneVideoCapable = false;
        if (info.TryGetValue("caps", out var capsEl) && capsEl.ValueKind == JsonValueKind.Array)
        {
            foreach (var c in capsEl.EnumerateArray())
                if (c.ValueKind == JsonValueKind.String && c.GetString() == Protocol.CapVideo1)
                    PhoneVideoCapable = true;
        }
        if (info.TryGetValue("rate", out var rateEl) && rateEl.ValueKind == JsonValueKind.Number &&
            rateEl.TryGetInt32(out var wantHz))
        {
            int want = Protocol.RateCodeForHz(wantHz);
            if (Settings.SampleRatePreference == "auto" && want != RateCode) ApplyRate(want);
        }
        var ack = new Dictionary<string, object?>
        {
            ["app"] = "PhoneAudioBridge", ["kind"] = "hello_ack",
            ["rate"] = Protocol.RateHz(RateCode), ["codec"] = "pcm16",
            ["deviceId"] = Config.DeviceId, ["name"] = Environment.MachineName, ["os"] = "Windows",
            ["appVersion"] = Protocol.AppVersion, ["protoVersion"] = Protocol.ProtoVersion,
            ["caps"] = new[] { Protocol.CapVideo1 },
        };
        if (issueToken)
        {
            Config.PairedPhones[phoneId] = new AppConfig.PairedPhone
            { Name = PeerName, LastSeen = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
              AppVersion = PhoneAppVersion, ProtoVersion = Math.Max(0, PhoneProtoVersion),
              LastIp = addr.Address.ToString() };
            Config.Save();
            ack["token"] = Config.Token;
            ack["verifyCode"] = Protocol.VerifyCodeFor(Config.Token, phoneId);
            LastVerifyCode = (string)ack["verifyCode"]!;
            LastPairPhone = PeerName;
            LastEvent = Loc.F("新手机已配对：{0}，核对验证码 {1}", "New phone paired: {0} — verify code {1}", PeerName, LastVerifyCode);
            EmitLog($"[配对] {LastEvent}（应与手机确认屏上一致）");
        }
        else if (Config.PairedPhones.TryGetValue(phoneId, out var pp))
        {
            pp.LastSeen = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            pp.LastIp = addr.Address.ToString(); // v2.2.3 FB-6: learned for the sweep
            if (PhoneAppVersion.Length > 0) pp.AppVersion = PhoneAppVersion;
            if (PhoneProtoVersion >= 0) pp.ProtoVersion = PhoneProtoVersion;
            Config.Save();
        }
        if (VersionMismatch)
            EmitLog($"[版本] 版本不一致：手机端 v{(PhoneAppVersion.Length > 0 ? PhoneAppVersion : "未知")}（proto {PhoneProtoVersion}），本机 v{Protocol.AppVersion}（proto {Protocol.ProtoVersion}）——仍可连接，请先更新手机端");
        Send(Protocol.TypeHelloAck, JsonBytes(ack), 0, 0, RateCode, addr);
        EmitLog($"[连接] 手机 {PeerName} ({addr.Address}) 已连接，{Protocol.RateHz(RateCode)} Hz");
        StateChanged?.Invoke();
    }

    // ---- camera relay (wave 4): receive path + control ----

    /// <summary>Ask the phone to start its camera relay (VIDEO_CTRL start).
    /// False when there is no connected, video-capable phone.</summary>
    public bool StartVideo(string quality = "720p")
    {
        if (!Connected || Peer is null || !PhoneVideoCapable) return false;
        SendToPeer(Protocol.TypeVideoCtrl, JsonBytes(new Dictionary<string, object?>
            { ["action"] = "start", ["quality"] = quality }));
        VirtualCamera?.Start();
        return true;
    }

    /// <summary>Ask the phone to stop its camera relay (VIDEO_CTRL stop).</summary>
    public void StopVideo()
    {
        VirtualCamera?.Stop();
        if (Peer is null) return;
        SendToPeer(Protocol.TypeVideoCtrl, JsonBytes(new Dictionary<string, object?>
            { ["action"] = "stop" }));
    }

    /// <summary>The newest decoded preview frame (BGRA, Width*Height*4),
    /// when one newer than [afterIndex] exists. The array is the decoder's
    /// own buffer — copy it if it must outlive the call.</summary>
    public bool TryGetLatestVideoFrame(long afterIndex, out byte[]? bgra, out int width, out int height, out long index)
    {
        lock (_frameLock)
        {
            bgra = _latestFrame; width = _latestFrameW; height = _latestFrameH; index = _latestFrameIndex;
            return bgra is not null && index != afterIndex;
        }
    }

    private void HandleVideoConfig(byte[] payload)
    {
        var info = ParseJson(payload);
        int IntOf(string key, int dflt) =>
            info.TryGetValue(key, out var el) && el.ValueKind == JsonValueKind.Number && el.TryGetInt32(out var v) ? v : dflt;
        byte[]? csd = null;
        var csdB64 = Str(info, "csd");
        if (csdB64.Length > 0)
        {
            try { csd = Convert.FromBase64String(csdB64); } catch { csd = null; }
        }
        CurrentVideoConfig = new VideoStreamConfig(
            IntOf("width", 0), IntOf("height", 0), IntOf("fps", 30), IntOf("rotation", 0),
            info.TryGetValue("mirrored", out var mir) && mir.ValueKind == JsonValueKind.True,
            Str(info, "lensFacing"), csd);
        _videoReasm.Reset();
        _lastReasmDropped = 0;
        _videoDecoderDirty = true; // the video thread performs the (re)start
        VideoStreaming = true;
        var cfg = CurrentVideoConfig;
        EmitLog($"[视频] 摄像头推流开始：{cfg.Width}x{cfg.Height}@{cfg.Fps} rotation={cfg.Rotation} mirrored={cfg.Mirrored}");
        StateChanged?.Invoke();
    }

    private void HandleVideoCtrl(byte[] payload)
    {
        var info = ParseJson(payload);
        if (Str(info, "action") != "state") return;
        bool streaming = info.TryGetValue("streaming", out var el) && el.ValueKind == JsonValueKind.True;
        VideoStreaming = streaming;
        var reason = Str(info, "reason");
        EmitLog(streaming ? "[视频] 手机已开始摄像头推流" : $"[视频] 手机已停止摄像头推流{(reason.Length > 0 ? $"（{reason}）" : "")}");
        StateChanged?.Invoke();
    }

    /// <summary>Video worker: drain the receive queue → reassemble →
    /// decode. Runs the loss deadlines and the keyframe-request policy
    /// (≥500 ms apart). Nothing on this thread can stall audio: the
    /// receive loop only enqueues, the audio loops never touch it.</summary>
    private void VideoLoop(CancellationToken ct)
    {
        long lastKfReqMs = 0;
        while (!ct.IsCancellationRequested && Running)
        {
            bool didWork = false;
            if (_videoDecoderDirty)
            {
                _videoDecoderDirty = false;
                RestartVideoDecoder();
            }
            while (_videoRxQueue.TryDequeue(out var payload))
            {
                didWork = true;
                if (!VideoPackets.TryParseFrag(payload, out var fh, out var data)) continue;
                foreach (var au in _videoReasm.Offer(fh.FrameId, fh.FragIndex, fh.FragCount, fh.Keyframe, data, NowMs()))
                {
                    VideoAccessUnits++;
                    _videoDecoder?.WriteAccessUnit(au);
                }
                SyncVideoDropStats();
            }
            foreach (var au in _videoReasm.Expire(NowMs()))
            {
                VideoAccessUnits++;
                _videoDecoder?.WriteAccessUnit(au);
                didWork = true;
            }
            SyncVideoDropStats();
            if (_videoReasm.KeyframeNeeded && Peer is not null && NowMs() - lastKfReqMs >= 500)
            {
                lastKfReqMs = NowMs();
                _videoReasm.ClearKeyframeNeeded();
                SendToPeer(Protocol.TypeVideoKeyframeReq, ReadOnlySpan<byte>.Empty);
            }
            if (!didWork) { try { Task.Delay(10, ct).Wait(ct); } catch { break; } }
        }
    }

    private void SyncVideoDropStats()
    {
        int d = _videoReasm.DroppedFrames;
        // The reassembler resets on every VIDEO_CONFIG (counters restart);
        // accumulate the delta either way so the engine total is monotone.
        VideoFramesDropped += d >= _lastReasmDropped ? d - _lastReasmDropped : d;
        _lastReasmDropped = d;
    }

    private void RestartVideoDecoder()
    {
        var cfg = CurrentVideoConfig;
        if (cfg is null || cfg.Width <= 0 || cfg.Height <= 0) return;
        _videoDecoder ??= CreateVideoDecoder();
        if (_videoDecoder.Start(cfg.Width, cfg.Height, cfg.CsdBytes))
            EmitLog($"[视频] 解码器已启动（ffmpeg 子进程，{cfg.Width}x{cfg.Height}）");
        else
            EmitLog($"[视频] 解码器启动失败：{_videoDecoder.LastError}");
    }

    private H264Decoder CreateVideoDecoder()
    {
        var dec = new H264Decoder();
        dec.FrameDecoded += (bgra, w, h, _) =>
        {
            lock (_frameLock)
            {
                _latestFrame = bgra; _latestFrameW = w; _latestFrameH = h;
                _latestFrameIndex = ++_videoFrameSeq;
            }
            VideoFramesDecoded++;
            VideoFrameArrived?.Invoke();
        };
        return dec;
    }

    private void AdaptLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && Running)
        {
            try { Task.Delay(1000, ct).Wait(ct); } catch { break; }
            if (!Running) break;
            var s = Stats;
            // Periodic paired poke (§5.1): stored-IP attempts are no
            // longer only a manual button — a paired phone that comes
            // online later is retried every ~15 s.
            if (!Connected && Config.PairedPhones.Count > 0 &&
                DiscoveryDiag.ProbeSchedule.IsDue(_lastPairedPokeMs, NowMs()))
            {
                _lastPairedPokeMs = NowMs();
                PokePairedStored();
            }
            // Discovery verdict (§5.4): rule-based over the counters,
            // logged when it changes (the ring-buffer file keeps it).
            // sweepGatedOff is false by construction now (§5.5 removed
            // the gate); the PC has no HELLO-ack/presence asymmetry, so
            // visibleUnreachable/anyAutoPaused do not apply here.
            var snap = Diag.TakeSnapshot();
            var v = DiscoveryDiag.VerdictPolicy.Select(
                NowMs(), _startedMs, snap.TotalSent, snap.TotalReceived,
                Config.PairedPhones.Count > 0,
                visibleUnreachable: false, anyAutoPaused: false,
                onLanNetwork: null, sweepGatedOff: false);
            if (v != _verdict)
            {
                _verdict = v;
                if (v == DiscoveryDiag.Verdict.SendNoReceive)
                    EmitLog("[发现] 已持续发出信标/探测但从未收到任何数据包——入站很可能被 Windows 防火墙拦截（见诊断中的防火墙状态）");
                StateChanged?.Invoke();
            }
            Loop.Evaluate();
            Jb.TargetMs = s.JitterMs > 0 ? (int)Math.Clamp(s.JitterMs * 2.5, 20, 60) : 30;
            bool fresh = s.RollWindow();
            if (Connected && Peer is not null)
            {
                _pingSeq++;
                SendToPeer(Protocol.TypePing, ReadOnlySpan<byte>.Empty, _pingSeq);
                if (_lastRxUtc != DateTime.MinValue && (DateTime.UtcNow - _lastRxUtc).TotalSeconds > 5.0)
                {
                    EmitLog("[重连] 5 秒无手机数据，标记断开，等待手机自动重连…");
                    Connected = false; Jb.Reset();
                }
                if (Settings.SampleRatePreference == "auto")
                {
                    if (fresh && _pendingRate < 0)
                    {
                        var (act, newCode, good) = RatePolicy.DecideRate(RateCode, s.LossPct, s.JitterMs, _goodWindows);
                        _goodWindows = good;
                        if (act != "none")
                        {
                            _pendingRate = newCode; _pendingSinceUtc = DateTime.UtcNow;
                            SendToPeer(Protocol.TypeRateReq, ReadOnlySpan<byte>.Empty, 0, 0, newCode);
                            EmitLog($"[自适应] loss={s.LossPct:F1}% jitter={s.JitterMs:F1}ms → 申请采样率 {Protocol.RateHz(newCode)} Hz");
                        }
                    }
                    if (_pendingRate >= 0 && (DateTime.UtcNow - _pendingSinceUtc).TotalSeconds > 1.5)
                    { ApplyRate(_pendingRate); _pendingRate = -1; }
                }
            }
            StateChanged?.Invoke();
        }
    }

    private void ApplyRate(int code)
    {
        if (code == RateCode) return;
        RateCode = code;
        Loop.Reset(); // detector history is rate-domain samples
        Stats.ResetSequence(); Jb.Reset();
        EmitLog($"[自适应] 采样率切换为 {Protocol.RateHz(code)} Hz");
        RateChanged?.Invoke(code);
        StateChanged?.Invoke();
    }

    /// <summary>Called when the user changes the sample-rate preference in Settings.</summary>
    public void ApplyRatePreference()
    {
        if (Settings.SampleRatePreference != "auto" &&
            int.TryParse(Settings.SampleRatePreference, out var hz))
            ApplyRate(Protocol.RateCodeForHz(hz));
    }

    private static Dictionary<string, JsonElement> ParseJson(byte[] payload)
    {
        var dict = new Dictionary<string, JsonElement>();
        try
        {
            using var doc = JsonDocument.Parse(payload);
            foreach (var p in doc.RootElement.EnumerateObject()) dict[p.Name] = p.Value.Clone();
        }
        catch { }
        return dict;
    }
    private static string Str(Dictionary<string, JsonElement> d, string key) =>
        d.TryGetValue(key, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    public static string LocalIp()
    {
        try
        {
            using var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            s.Connect("8.8.8.8", 80);
            return ((IPEndPoint)s.LocalEndPoint!).Address.ToString();
        }
        catch
        {
            try { return Dns.GetHostEntry(Dns.GetHostName()).AddressList.FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork)?.ToString() ?? "127.0.0.1"; }
            catch { return "127.0.0.1"; }
        }
    }

    /// <summary>Exact QR payload string per PROTOCOL.md.</summary>
    public string PairingQrPayload() => JsonSerializer.Serialize(new Dictionary<string, object?>
    {
        ["app"] = "AirBridge", ["v"] = 1, ["deviceId"] = Config.DeviceId,
        ["name"] = Environment.MachineName, ["os"] = "Windows",
        ["ip"] = LocalIp(), ["port"] = Config.Port, ["token"] = Config.Token,
    });
}

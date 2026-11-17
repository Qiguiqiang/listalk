using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace AirBridge;

public sealed class NearbyDevice
{
    public string DeviceId { get; set; } = "";
    public string Name { get; set; } = "";
    public string Os { get; set; } = "";
    public string Ip { get; set; } = "";
    public DateTime LastSeenUtc { get; set; } = DateTime.MinValue;
    public HashSet<string> Transports { get; } = new();
    public bool Paired { get; set; }
    public bool Online => (DateTime.UtcNow - LastSeenUtc).TotalSeconds <= 5;
    public string Key => !string.IsNullOrEmpty(DeviceId) ? DeviceId : Ip;
    public override string ToString() =>
        $"{(string.IsNullOrEmpty(Name) ? "未知手机" : Name)} · {Ip} · {(Paired ? "已配对" : "未配对")} · {(Online ? "在线" : "离线")} · {string.Join("+", Transports)}";
}

/// <summary>
/// UDP bridge engine — a faithful C# port of pc/phone_audio_pc.py Bridge:
/// beacon 1/s, DISCOVER→DISCOVER_RESP, HELLO auth (pair code / QR tokenHash / TOFU 120 s /
/// already-paired), PING/PONG 1 s, adaptive rate, 5 s link-death, jitter buffer feed.
/// Extensions for the v2 PC-first flow (wire-compatible, no new packet types):
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
    /// <summary>True when the connected phone's protoVersion != 2 or appVersion != ours.
    /// Audio still connects; the UI must surface this loudly (never silent).</summary>
    public bool VersionMismatch => Connected &&
        (PhoneProtoVersion != Protocol.ProtoVersion || PhoneAppVersion != Protocol.AppVersion);

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

    public BridgeEngine(AppConfig config, AppSettings settings)
    {
        Config = config; Settings = settings;
        Loop.Log += msg => Log?.Invoke(msg);
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
            if (!d.Paired && (now - d.LastSeenUtc).TotalSeconds > 5)
                _nearby.Remove(key); // presence rule: expire ~5 s without beacon
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
            d.Paired = Config.PairedPhones.ContainsKey(d.DeviceId);
        }
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
        LastEvent = $"已向 {d.Name} 发起配对，等待手机确认…";
        Log?.Invoke(LastEvent);
        StateChanged?.Invoke();
    }

    /// <summary>One-tap connect for an already-paired phone (used when 允许已配对手机自动重连 is off).</summary>
    public void TapConnect(NearbyDevice d)
    {
        lock (_winLock) _connectWindows[d.DeviceId] = DateTime.UtcNow.AddSeconds(60);
        if (IPAddress.TryParse(d.Ip, out var ip))
            SendDiscoverResp(new IPEndPoint(ip, Protocol.Port));
        LastEvent = $"已请求 {d.Name} 连接…";
        StateChanged?.Invoke();
    }

    private bool InWindow(Dictionary<string, DateTime> map, string id)
    {
        lock (_winLock)
            return !string.IsNullOrEmpty(id) && map.TryGetValue(id, out var exp) && DateTime.UtcNow < exp;
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
        var ct = _cts.Token;
        Task.Run(() => RecvLoop(ct), ct);
        Task.Run(() => BeaconLoop(ct), ct);
        Task.Run(() => AdaptLoop(ct), ct);
        Log?.Invoke($"[监听] UDP {Config.Port}，等待手机连接…");
        StateChanged?.Invoke();
    }

    public void Stop()
    {
        if (!Running) return;
        Running = false;
        try { if (Peer is not null) Send(Protocol.TypeBye, ReadOnlySpan<byte>.Empty, 0, 0, RateCode, Peer); } catch { }
        _cts?.Cancel();
        try { _udp?.Close(); } catch { }
        Connected = false;
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

    private void BeaconLoop(CancellationToken ct)
    {
        var broadcast = new IPEndPoint(IPAddress.Broadcast, Config.Port);
        while (!ct.IsCancellationRequested && Running)
        {
            try
            {
                if (!string.IsNullOrEmpty(Config.DeviceId))
                {
                    var payload = new Dictionary<string, object?>
                    {
                        ["app"] = "PhoneAudioBridge", ["kind"] = "beacon",
                        ["name"] = Environment.MachineName, ["os"] = "Windows",
                        ["deviceId"] = Config.DeviceId,
                        ["tokenHash"] = string.IsNullOrEmpty(Config.Token) ? "" : Protocol.TokenHash(Config.Token),
                        ["appVersion"] = Protocol.AppVersion,
                        ["protoVersion"] = Protocol.ProtoVersion,
                    };
                    Send(Protocol.TypeBeacon, JsonBytes(payload), 0, 0, RateCode, broadcast);
                }
            }
            catch { }
            try { Task.Delay(1000, ct).Wait(ct); } catch { break; }
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
                    var info = ParseJson(payload);
                    NoteNearby(Str(info, "deviceId"), Str(info, "name"), Str(info, "os"), addr.Address.ToString(), "udp");
                    SendDiscoverResp(addr);
                    break;
                }
                case Protocol.TypeHello:
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
                    Send(Protocol.TypePong, ReadOnlySpan<byte>.Empty, seq, ts, RateCode, addr);
                    if (Peer is not null) RetargetPeerIfRoamed(addr);
                    _lastRxUtc = DateTime.UtcNow;
                    break;
                case Protocol.TypePong:
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
                    Log?.Invoke("[断开] 手机端已停止，等待重新连接…");
                    Connected = false;
                    StateChanged?.Invoke();
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
        Log?.Invoke($"[漫游] 手机地址已变更 {Peer.Address} → {addr.Address}，发送目标已跟随");
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
            Log?.Invoke($"[配对] 拒绝未配对手机的连接请求 ({addr.Address})——请在「添加手机」中点选该手机配对，或让手机输入配对码/扫码");
            return;
        }
        lock (_winLock) { _pairWindows.Remove(phoneId); _connectWindows.Remove(phoneId); }
        Peer = addr; Connected = true; _lastRxUtc = DateTime.UtcNow;
        PeerName = string.IsNullOrEmpty(phoneName) ? "?" : phoneName;
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
        };
        if (issueToken)
        {
            Config.PairedPhones[phoneId] = new AppConfig.PairedPhone
            { Name = PeerName, LastSeen = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
              AppVersion = PhoneAppVersion, ProtoVersion = Math.Max(0, PhoneProtoVersion) };
            Config.Save();
            ack["token"] = Config.Token;
            ack["verifyCode"] = Protocol.VerifyCodeFor(Config.Token, phoneId);
            LastVerifyCode = (string)ack["verifyCode"]!;
            LastPairPhone = PeerName;
            LastEvent = $"新手机已配对：{PeerName}，核对验证码 {LastVerifyCode}";
            Log?.Invoke($"[配对] {LastEvent}（应与手机确认屏上一致）");
        }
        else if (Config.PairedPhones.TryGetValue(phoneId, out var pp))
        {
            pp.LastSeen = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            if (PhoneAppVersion.Length > 0) pp.AppVersion = PhoneAppVersion;
            if (PhoneProtoVersion >= 0) pp.ProtoVersion = PhoneProtoVersion;
            Config.Save();
        }
        if (VersionMismatch)
            Log?.Invoke($"[版本] 版本不一致：手机端 v{(PhoneAppVersion.Length > 0 ? PhoneAppVersion : "未知")}（proto {PhoneProtoVersion}），本机 v{Protocol.AppVersion}（proto {Protocol.ProtoVersion}）——仍可连接，请先更新手机端");
        Send(Protocol.TypeHelloAck, JsonBytes(ack), 0, 0, RateCode, addr);
        Log?.Invoke($"[连接] 手机 {PeerName} ({addr.Address}) 已连接，{Protocol.RateHz(RateCode)} Hz");
        StateChanged?.Invoke();
    }

    private void AdaptLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && Running)
        {
            try { Task.Delay(1000, ct).Wait(ct); } catch { break; }
            if (!Running) break;
            var s = Stats;
            Loop.Evaluate();
            Jb.TargetMs = s.JitterMs > 0 ? (int)Math.Clamp(s.JitterMs * 2.5, 20, 60) : 30;
            bool fresh = s.RollWindow();
            if (Connected && Peer is not null)
            {
                _pingSeq++;
                SendToPeer(Protocol.TypePing, ReadOnlySpan<byte>.Empty, _pingSeq);
                if (_lastRxUtc != DateTime.MinValue && (DateTime.UtcNow - _lastRxUtc).TotalSeconds > 5.0)
                {
                    Log?.Invoke("[重连] 5 秒无手机数据，标记断开，等待手机自动重连…");
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
                            Log?.Invoke($"[自适应] loss={s.LossPct:F1}% jitter={s.JitterMs:F1}ms → 申请采样率 {Protocol.RateHz(newCode)} Hz");
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
        Log?.Invoke($"[自适应] 采样率切换为 {Protocol.RateHz(code)} Hz");
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

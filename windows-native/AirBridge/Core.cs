using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace AirBridge;

/// <summary>Persisted state in %LOCALAPPDATA%\AirBridge\config.json (identity + pairing + devices)
/// and settings.json (user settings). Mirrors the Python config semantics.</summary>
public sealed class AppConfig
{
    [JsonPropertyName("device_id")] public string DeviceId { get; set; } = "";
    [JsonPropertyName("token")] public string Token { get; set; } = "";
    [JsonPropertyName("paired_phones")] public Dictionary<string, PairedPhone> PairedPhones { get; set; } = new();
    [JsonPropertyName("pair_code")] public string PairCode { get; set; } = "";
    [JsonPropertyName("pair_code_expires")] public double PairCodeExpires { get; set; }
    [JsonPropertyName("input_device")] public string? InputDeviceId { get; set; }   // NAudio endpoint id
    [JsonPropertyName("output_device")] public string? OutputDeviceId { get; set; } // NAudio endpoint id
    [JsonPropertyName("input_loopback")] public bool InputIsLoopback { get; set; }
    [JsonPropertyName("port")] public int Port { get; set; } = Protocol.Port;

    public sealed class PairedPhone
    {
        [JsonPropertyName("name")] public string Name { get; set; } = "";
        [JsonPropertyName("lastSeen")] public double LastSeen { get; set; }
        [JsonPropertyName("appVersion")] public string AppVersion { get; set; } = "";
        [JsonPropertyName("protoVersion")] public int ProtoVersion { get; set; }
    }

    public static string DirPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AirBridge");
    public static string FilePath => Path.Combine(DirPath, "config.json");

    private static readonly JsonSerializerOptions JsonOpts = new() { WriteIndented = true };
    private readonly object _lock = new();

    public static AppConfig Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<AppConfig>(File.ReadAllText(FilePath)) ?? new AppConfig();
        }
        catch { /* corrupt config -> fresh identity */ }
        return new AppConfig();
    }

    public void Save()
    {
        lock (_lock)
        {
            Directory.CreateDirectory(DirPath);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, JsonOpts));
        }
    }

    private static string Hex(int bytes)
    {
        var b = RandomNumberGenerator.GetBytes(bytes);
        return Convert.ToHexString(b).ToLowerInvariant();
    }

    /// <summary>ensure_identity(): create/persist deviceId (12 hex) + token (32 hex) like Python secrets.token_hex(6)/(16).</summary>
    public bool EnsureIdentity()
    {
        bool changed = false;
        if (string.IsNullOrEmpty(DeviceId)) { DeviceId = Hex(6); changed = true; }
        if (string.IsNullOrEmpty(Token)) { Token = Hex(16); changed = true; }
        PairedPhones ??= new();
        return changed;
    }

    /// <summary>ensure_pair_code(): 6-digit code rotated every 10 minutes.</summary>
    public bool EnsurePairCode()
    {
        double now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        if (string.IsNullOrEmpty(PairCode) || now > PairCodeExpires)
        {
            PairCode = (RandomNumberGenerator.GetInt32(900000) + 100000).ToString("D6");
            PairCodeExpires = now + 600;
            return true;
        }
        return false;
    }
    public int PairCodeSecondsLeft => Math.Max(0, (int)(PairCodeExpires - DateTimeOffset.UtcNow.ToUnixTimeSeconds()));
    public bool PairCodeValid => PairCodeSecondsLeft > 0;
}

public sealed class AppSettings
{
    public bool StartOnLogin { get; set; } = false;
    public bool CloseToTray { get; set; } = true;
    public bool Notifications { get; set; } = true;
    /// <summary>"auto" | "48000" | "24000" | "16000"</summary>
    public string SampleRatePreference { get; set; } = "auto";
    public bool AllowPairedAutoReconnect { get; set; } = true;
    /// <summary>Online-update manifest URL (editable in Settings; default = fixed hosted address).</summary>
    public string UpdateManifestUrl { get; set; } = UpdateChecker.UpdateManifestUrl;
    public bool MicToPc { get; set; } = true;
    public bool PcToPhone { get; set; } = true;
    public bool Paused { get; set; } = false;

    public static string FilePath => Path.Combine(AppConfig.DirPath, "settings.json");
    private static readonly JsonSerializerOptions JsonOpts = new() { WriteIndented = true };

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings();
        }
        catch { }
        return new AppSettings();
    }
    public void Save()
    {
        Directory.CreateDirectory(AppConfig.DirPath);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, JsonOpts));
    }
}

/// <summary>HELLO auth policy — exact port of Python decide_hello_auth(), plus the PC-initiated
/// scoped pairing window (user tapped this exact device in 添加手机): that deviceId's next HELLO pairs.</summary>
public static class PairingPolicy
{
    public enum Action { Auth, Pair, Reject }
    public static (Action action, bool issueToken) DecideHelloAuth(
        IReadOnlyDictionary<string, AppConfig.PairedPhone> pairedPhones, string phoneId,
        string tokenHashGiven, string pairCode, string pcToken, string pcPairCode,
        bool pcPairCodeValid, bool tofuOpen, bool scopedPairWindowForThisPhone, bool scopedConnectWindowForThisPhone,
        bool allowPairedAutoReconnect)
    {
        if (string.IsNullOrEmpty(phoneId)) return (Action.Reject, false);
        string expected = Protocol.TokenHash(pcToken);
        if (pairedPhones.ContainsKey(phoneId) && !string.IsNullOrEmpty(tokenHashGiven) && tokenHashGiven == expected)
        {
            // Already-paired reconnect. If the user turned 允许已配对手机自动重连 off,
            // a phone-initiated reconnect is held unless the user tapped 连接 for it here.
            if (!allowPairedAutoReconnect && !scopedConnectWindowForThisPhone)
                return (Action.Reject, false);
            return (Action.Auth, false);
        }
        if (!string.IsNullOrEmpty(pairCode) && pcPairCodeValid && pairCode == pcPairCode)
            return (Action.Pair, true);
        if (!string.IsNullOrEmpty(tokenHashGiven) && tokenHashGiven == expected)
            return (Action.Pair, true); // QR flow
        if (scopedPairWindowForThisPhone)
            return (Action.Pair, true); // PC-initiated one-tap pairing (user tapped this phone)
        if (tofuOpen && pairedPhones.Count == 0)
            return (Action.Pair, true);
        return (Action.Reject, false);
    }
}

/// <summary>Adaptive jitter buffer — port of Python JitterBuffer. Target depth 20–60 ms.</summary>
public sealed class JitterBuffer
{
    private readonly Dictionary<uint, byte[]> _buf = new();
    private readonly object _lock = new();
    private uint? _nextSeq;
    private bool _primed;
    public int TargetMs = 30;
    public long Underruns;

    public void Reset() { lock (_lock) { _buf.Clear(); _nextSeq = null; _primed = false; } }

    public void Push(uint seq, byte[] payload)
    {
        lock (_lock)
        {
            if (_nextSeq is null || (long)seq < (long)_nextSeq.Value - 200)
            { _nextSeq = seq; _primed = false; _buf.Clear(); }
            if (seq >= _nextSeq.Value)
            {
                _buf[seq] = payload;
                while (_buf.Count > 20)
                {
                    uint oldest = _buf.Keys.Min();
                    _buf.Remove(oldest);
                    _nextSeq = _buf.Count > 0 ? _buf.Keys.Min() : oldest + 1;
                }
            }
        }
    }

    public byte[]? Pop()
    {
        lock (_lock)
        {
            if (!_primed)
            {
                int targetFrames = Math.Clamp(TargetMs / 10, 2, 6);
                if (_buf.Count >= targetFrames) { _nextSeq = _buf.Keys.Min(); _primed = true; }
                else return null;
            }
            uint seq = _nextSeq!.Value;
            byte[]? payload = _buf.GetValueOrDefault(seq);
            _buf.Remove(seq);
            _nextSeq = seq + 1;
            if (payload is null) Underruns++;
            return payload;
        }
    }
    public int Count { get { lock (_lock) return _buf.Count; } }
}

/// <summary>Link statistics — port of Python LinkStats (loss via seq gaps, RFC-style jitter, RTT EWMA).</summary>
public sealed class LinkStats
{
    public long Rx, Tx, Lost;
    public double RttMs = -1, JitterMs, LossPct;
    public double LevelIn, LevelOut; // In = PC capture -> phone; Out = phone mic -> PC output
    private long _lastSeq = -1;
    private double? _prevTransit;
    private double _jitterUs;
    private long _winRx, _winLost;
    private double _winStart = NowSec();
    private static double NowSec() => Protocol.MonotonicUs() / 1e6;

    public void OnAudioRx(uint seq, ulong senderTsUs)
    {
        if (_lastSeq >= 0)
        {
            long gap = (long)seq - _lastSeq;
            if (gap > 1) { Lost += gap - 1; _winLost += gap - 1; }
        }
        if (_lastSeq < 0 || seq > _lastSeq) _lastSeq = seq;
        double transit = Protocol.MonotonicUs() - (double)senderTsUs;
        if (_prevTransit is not null)
        {
            double d = Math.Abs(transit - _prevTransit.Value);
            _jitterUs += (d - _jitterUs) / 16.0;
        }
        _prevTransit = transit;
        Rx++; _winRx++;
        JitterMs = _jitterUs / 1000.0;
    }

    public void OnPong(ulong sentTsUs)
    {
        double rtt = (Protocol.MonotonicUs() - (double)sentTsUs) / 1000.0;
        if (rtt is >= 0 and <= 5000)
            RttMs = RttMs < 0 ? rtt : RttMs * 0.7 + rtt * 0.3;
    }

    public bool RollWindow()
    {
        double now = NowSec();
        if (now - _winStart >= 2.0)
        {
            long total = _winRx + _winLost;
            LossPct = total > 0 ? _winLost * 100.0 / total : 0;
            _winRx = 0; _winLost = 0; _winStart = now;
            return true;
        }
        return false;
    }

    public void ResetSequence() { _lastSeq = -1; _prevTransit = null; }
}

/// <summary>Adaptive-rate policy — exact port of Python decide_rate().</summary>
public static class RatePolicy
{
    public static (string action, int newCode, int goodWindows) DecideRate(int currentCode, double lossPct, double jitterMs, int goodWindows)
    {
        if ((lossPct > 5.0 || jitterMs > 15.0) && currentCode < Protocol.Rate16k)
            return ("down", currentCode + 1, 0);
        if (lossPct < 1.0 && jitterMs < 5.0 && currentCode > Protocol.Rate48k)
        {
            goodWindows++;
            if (goodWindows >= 15) return ("up", currentCode - 1, 0); // ~30 s clean
            return ("none", currentCode, goodWindows);
        }
        return ("none", currentCode, goodWindows);
    }
}

/// <summary>Virtual-device scoring — exact port of Python output_device_score / input_device_score.</summary>
public static class DeviceScoring
{
    public static int OutputScore(string name)
    {
        string n = name.ToLowerInvariant();
        int score = 0;
        if (n.Contains("声桥") || n.Contains("airbridge")) score = Math.Max(score, 110); // own branded driver wins
        if (n.Contains("cable input")) score = Math.Max(score, 100);
        if (n.Contains("blackhole")) score = Math.Max(score, 95);
        if (n.Contains("vb-audio") || n.Contains("vb-cable") || n.Contains("cable")) score = Math.Max(score, 85);
        if (n.Contains("voicemeeter")) score = Math.Max(score, 80);
        if (n.Contains("phoneaudiobridge") || n.Contains("phone-mic") || n.Contains("phone_mic")) score = Math.Max(score, 100);
        if (n.Contains("monitor")) score = Math.Min(score, 10);
        return score;
    }
    public static int InputScore(string name)
    {
        string n = name.ToLowerInvariant();
        int score = 0;
        if (n.Contains("声桥") || n.Contains("airbridge")) score = Math.Max(score, 110); // own branded driver wins
        if (n.Contains("cable output")) score = Math.Max(score, 100);
        if (n.Contains("phoneaudiobridge") || n.Contains("phone-speaker") || n.Contains("phone_speaker")) score = Math.Max(score, 100);
        if (n.Contains("monitor")) score = Math.Max(score, 95);
        if (n.Contains("blackhole")) score = Math.Max(score, 95);
        if (n.Contains("stereo mix") || n.Contains("立体声混音") || n.Contains("what u hear")) score = Math.Max(score, 90);
        if (n.Contains("loopback")) score = Math.Max(score, 85);
        if (n.Contains("vb-audio") || n.Contains("vb-cable") || n.Contains("cable")) score = Math.Max(score, 80);
        if (n.Contains("voicemeeter")) score = Math.Max(score, 75);
        return score;
    }
}

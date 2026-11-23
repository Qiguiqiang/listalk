using System.Net;
using Makaretu.Dns;
using Microsoft.Win32;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Storage.Streams;

namespace AirBridge;

/// <summary>
/// Canonical BLE service UUID for LisTalk presence — fixed by the Android side in
/// Protocol.kt (BLE_SERVICE_UUID, first 128 bits of SHA-256("AirBridge-BLE-Service-v2"))
/// and adopted verbatim here so both ends interoperate.
/// BLE-UUID: 9072f6d1-ec44-0c52-ad20-dc70f9bc5a38
/// </summary>
public static class BleIdentity
{
    public const string ServiceUuid = "9072f6d1-ec44-0c52-ad20-dc70f9bc5a38";
    public const string MdnsServiceType = "_airbridge._udp";
}

/// <summary>
/// mDNS transport (Makaretu.Dns.Multicast):
///  - Advertises this PC as service type exactly "_airbridge._udp" on port 47731 with
///    TXT records: deviceId, name, os, port (plus app=AirBridge).
///  - Listens for answers from other _airbridge._udp instances and merges them into the
///    engine's nearby registry (deduped by deviceId there).
/// Runtime behaviour on real Windows networks is unverified in this build environment.
/// </summary>
public sealed class MdnsDiscovery : IDisposable
{
    private readonly BridgeEngine _engine;
    private MulticastService? _mdns;
    private ServiceDiscovery? _sd;
    private CancellationTokenSource? _queryCts;
    private Func<string> _status = () => Loc.Tr("未启动");
    public string Status => _status(); // computed: re-renders in the current UI language on every read (board item #2)
    public event Action<string>? Log;

    public MdnsDiscovery(BridgeEngine engine) => _engine = engine;

    public void Start()
    {
        try
        {
            _mdns = new MulticastService();
            _sd = new ServiceDiscovery(_mdns);
            var profile = new ServiceProfile(
                $"LisTalk-{_engine.Config.DeviceId}", BleIdentity.MdnsServiceType, Protocol.Port);
            profile.AddProperty("deviceId", _engine.Config.DeviceId);
            profile.AddProperty("name", Environment.MachineName);
            profile.AddProperty("os", "Windows");
            profile.AddProperty("port", Protocol.Port.ToString());
            profile.AddProperty("app", "AirBridge");
            _sd.Advertise(profile);

            _mdns.AnswerReceived += OnAnswerReceived;
            _mdns.Start();

            _queryCts = new CancellationTokenSource();
            var ct = _queryCts.Token;
            Task.Run(async () =>
            {
                while (!ct.IsCancellationRequested)
                {
                    try { _mdns?.SendQuery($"{BleIdentity.MdnsServiceType}.local", DnsClass.IN, DnsType.PTR); } catch { }
                    try { await Task.Delay(3000, ct); } catch { break; }
                }
            }, ct);
            _status = () => Loc.F("已广播 {0}（deviceId={1}）", "Broadcasting {0} (deviceId={1})", BleIdentity.MdnsServiceType, _engine.Config.DeviceId);
            Log?.Invoke($"[mDNS] {Status}");
        }
        catch (Exception ex)
        {
            _status = () => Loc.F("启动失败：{0}", "Start failed: {0}", ex.Message);
            Log?.Invoke($"[mDNS] {Status}");
        }
    }

    // Merge discovered instances: collect TXT/SRV/A answers per instance name.
    private readonly Dictionary<string, Dictionary<string, string>> _pendingTxt = new();
    private readonly Dictionary<string, string> _pendingIp = new();

    private void OnAnswerReceived(object? sender, MessageEventArgs e)
    {
        try
        {
            string ip = e.RemoteEndPoint.Address.ToString();
            foreach (var rr in e.Message.Answers)
            {
                if (rr is TXTRecord txt)
                {
                    string inst = txt.Name.ToString();
                    var dict = _pendingTxt.GetValueOrDefault(inst) ?? new Dictionary<string, string>();
                    foreach (var s in txt.Strings)
                    {
                        var kv = s.Split('=', 2);
                        if (kv.Length == 2) dict[kv[0]] = kv[1];
                    }
                    _pendingTxt[inst] = dict;
                    _pendingIp[inst] = ip;
                    TryUpsert(inst);
                }
                else if (rr is ARecord a)
                {
                    _pendingIp[a.Name.ToString()] = a.Address.ToString();
                }
            }
        }
        catch { /* malformed answers are ignored */ }
    }

    private void TryUpsert(string inst)
    {
        if (!_pendingTxt.TryGetValue(inst, out var txt)) return;
        if (!txt.TryGetValue("deviceId", out var deviceId) || string.IsNullOrEmpty(deviceId)) return;
        if (deviceId == _engine.Config.DeviceId) return; // that's us
        string ip = _pendingIp.GetValueOrDefault(inst) ?? "";
        _engine.NoteNearby(deviceId,
            txt.GetValueOrDefault("name") ?? "",
            txt.GetValueOrDefault("os") ?? "", ip, "mdns");
    }

    public void Dispose()
    {
        _queryCts?.Cancel();
        try { _sd?.Dispose(); } catch { }
        try { _mdns?.Dispose(); } catch { }
    }
}

/// <summary>
/// BLE advertisement transport (WinRT Windows.Devices.Bluetooth.Advertisement).
/// Advertises the canonical LisTalk service UUID plus manufacturer data:
///   CompanyId 0xFFFF (test), payload layout:
///     bytes 0-1 : 'A','B' magic (0x41 0x42)
///     byte  2   : version 0x01
///     bytes 3-14: deviceId, ASCII (12 hex chars)
///     bytes 15-22: first 8 hex chars of tokenHash, ASCII
/// A phone scanning for the service UUID gets the PC's deviceId hint and can
/// fast-track its LAN HELLO once the PC is also heard on WiFi/USB (PROTOCOL.md BLE design).
/// Runtime behaviour is unverified here (no Windows/BLE hardware in the build sandbox).
/// </summary>
public sealed class BleAdvertiser
{
    private BluetoothLEAdvertisementPublisher? _publisher;
    private Func<string> _status = () => Loc.Tr("未启动");
    public string Status => _status(); // computed: re-renders in the current UI language on every read (board item #2)
    public event Action<string>? Log;

    public void Start(AppConfig config)
    {
        if (!OperatingSystem.IsWindows())
        { _status = () => Loc.Tr("非 Windows，跳过"); return; }
        try
        {
            _publisher = new BluetoothLEAdvertisementPublisher();
            _publisher.Advertisement.ServiceUuids.Add(Guid.Parse(BleIdentity.ServiceUuid));

            // Service data (AD type 0x21, 128-bit UUID service data): UUID in BLE
            // little-endian order + PC deviceId as UTF-8 — exactly what Android
            // BleDiscovery.kt reads via getServiceData(BLE_SERVICE_UUID).
            var sdWriter = new DataWriter();
            var sdBytes = new List<byte>();
            sdBytes.AddRange(Guid.Parse(BleIdentity.ServiceUuid).ToByteArray());
            sdBytes.AddRange(System.Text.Encoding.UTF8.GetBytes(config.DeviceId));
            sdWriter.WriteBytes(sdBytes.ToArray());
            _publisher.Advertisement.DataSections.Add(
                new BluetoothLEAdvertisementDataSection(0x21, sdWriter.DetachBuffer()));
            string th = string.IsNullOrEmpty(config.Token) ? "" : Protocol.TokenHash(config.Token);
            var payloadBytes = new List<byte> { 0x41, 0x42, 0x01 };
            payloadBytes.AddRange(System.Text.Encoding.ASCII.GetBytes(Pad(config.DeviceId, 12)));
            payloadBytes.AddRange(System.Text.Encoding.ASCII.GetBytes(Pad(th, 8)));
            var writer = new DataWriter();
            writer.WriteBytes(payloadBytes.ToArray());
            var mdata = new BluetoothLEManufacturerData(0xFFFF, writer.DetachBuffer());
            _publisher.Advertisement.ManufacturerData.Add(mdata);
            _publisher.StatusChanged += (_, e) =>
            {
                _status = () => Loc.F("发布器状态：{0}", "Publisher state: {0}", e.Status);
                Log?.Invoke($"[BLE] {Status}");
            };
            _publisher.Start();
            _status = () => Loc.F("已开始广播 UUID {0}", "Broadcasting UUID {0}", BleIdentity.ServiceUuid);
            Log?.Invoke($"[BLE] {Status}");
        }
        catch (Exception ex)
        {
            _status = () => Loc.F("启动失败：{0}: {1}", "Start failed: {0}: {1}", ex.GetType().Name, ex.Message);
            Log?.Invoke($"[BLE] {Status}");
        }
    }

    private static string Pad(string s, int len) =>
        s.Length >= len ? s[..len] : s.PadRight(len, '0');

    public void Stop()
    {
        try { _publisher?.Stop(); } catch { }
        _publisher = null;
    }
}

/// <summary>开机自启：HKCU\Software\Microsoft\Windows\CurrentVersion\Run value "AirBridge".</summary>
public static class AutoStart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "AirBridge";

    public static void Apply(bool enabled)
    {
        if (!OperatingSystem.IsWindows()) return;
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
            if (key is null) return;
            if (enabled)
            {
                string exe = Environment.ProcessPath ?? "";
                if (exe.Length > 0) key.SetValue(ValueName, $"\"{exe}\"");
            }
            else key.DeleteValue(ValueName, throwOnMissingValue: false);
        }
        catch { /* registry unavailable — setting stays persisted in settings.json */ }
    }
}

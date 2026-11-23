using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace AirBridge;

/// <summary>
/// Wire protocol — exact port of PROTOCOL.md / pc/phone_audio_pc.py / Android Protocol.kt.
/// 16-byte big-endian header: magic 0x5041, type, flags, seq (u32), timestamp_us (u64).
/// flags: bits0-1 codec, bits2-4 sample-rate code.
/// </summary>
public static class Protocol
{
    public const int Port = 47731;
    /// <summary>Unified release versioning (Gray's rule): one release = same version both ends.</summary>
    public const string AppVersion = "2.3.2";
    public const int ProtoVersion = 2;
    public const ushort Magic = 0x5041;
    public const int HeaderSize = 16;

    public const byte TypeAudio = 0;
    public const byte TypeDiscover = 1;
    public const byte TypeDiscoverResp = 2;
    public const byte TypeHello = 3;
    public const byte TypeHelloAck = 4;
    public const byte TypePing = 5;
    public const byte TypePong = 6;
    public const byte TypeRateReq = 7;
    public const byte TypeRateAck = 8;
    public const byte TypeBye = 9;
    /// <summary>PC → phone remote volume: JSON {"speakerVolume":0.0..1.0} sets this PC's
    /// per-device speaker gain on the phone (the value its device-card slider edits).</summary>
    public const byte TypeSetVolume = 10;
    public const byte TypeBeacon = 11;

    // ---- camera relay (wave 4, plan §B.2): same port/header, negotiated via
    // the "caps":["video1"] field in HELLO / HELLO_ACK — ProtoVersion stays 2.
    /// <summary>Phone → PC, JSON {codec,width,height,fps,rotation,mirrored,lensFacing,csd(base64 SPS+PPS)}; sent x3 on (re)start.</summary>
    public const byte TypeVideoConfig = 12;
    /// <summary>Phone → PC, binary: 10-byte fragmentation subheader + H.264 AU chunk (see VideoPackets).</summary>
    public const byte TypeVideoData = 13;
    /// <summary>Both ways, JSON: PC {action:"start"/"stop", quality} / phone {action:"state", streaming, reason?}.</summary>
    public const byte TypeVideoCtrl = 14;
    /// <summary>PC → phone, empty: decoder needs a fresh IDR; phone rate-limits to >=500 ms.</summary>
    public const byte TypeVideoKeyframeReq = 15;
    /// <summary>Capability token for the camera relay, carried in HELLO / HELLO_ACK "caps".</summary>
    public const string CapVideo1 = "video1";

    public const int CodecPcm16 = 0;
    public const int Rate48k = 0;
    public const int Rate24k = 1;
    public const int Rate16k = 2;

    public static int RateHz(int code) => code switch { Rate24k => 24000, Rate16k => 16000, _ => 48000 };
    public static int RateCodeForHz(int hz) => hz switch { 24000 => Rate24k, 16000 => Rate16k, _ => Rate48k };
    public static int FrameSamples(int code) => RateHz(code) / 100; // 10 ms
    public static int FrameBytes(int code) => FrameSamples(code) * 2;

    /// <summary>The single volume mapping every surface converts through: PC UI sliders
    /// speak percent (0–100), the phone's per-device gain is 0–1. Mirrors Android
    /// Protocol.kt gainForPercent/percentForGain exactly (AwayFromZero == Kotlin
    /// roundToInt for the non-negative range); pinned by tools/TestVolume.kt.</summary>
    public static double GainForPercent(int percent) => Math.Clamp(percent, 0, 100) / 100.0;
    public static int PercentForGain(double gain) =>
        (int)Math.Round(Math.Clamp(gain, 0.0, 1.0) * 100.0, MidpointRounding.AwayFromZero);
    public static byte MakeFlags(int codec, int rateCode) => (byte)((codec & 0x03) | ((rateCode & 0x07) << 2));

    public static long NowUs() => Environment.TickCount64 * 1000L; // monotonic-ish; Stopwatch-based below is authoritative
    private static readonly System.Diagnostics.Stopwatch Sw = System.Diagnostics.Stopwatch.StartNew();
    public static long MonotonicUs() => Sw.ElapsedTicks * 1_000_000L / System.Diagnostics.Stopwatch.Frequency;

    public readonly record struct Header(byte Type, byte Flags, uint Seq, ulong TimestampUs)
    {
        public int Codec => Flags & 0x03;
        public int RateCode => (Flags >> 2) & 0x07;
    }

    public static byte[] Pack(byte type, uint seq = 0, ulong ts = 0, ReadOnlySpan<byte> payload = default, int codec = CodecPcm16, int rateCode = Rate48k)
    {
        var buf = new byte[HeaderSize + payload.Length];
        BinaryPrimitives.WriteUInt16BigEndian(buf.AsSpan(0), Magic);
        buf[2] = type;
        buf[3] = MakeFlags(codec, rateCode);
        BinaryPrimitives.WriteUInt32BigEndian(buf.AsSpan(4), seq);
        BinaryPrimitives.WriteUInt64BigEndian(buf.AsSpan(8), ts);
        payload.CopyTo(buf.AsSpan(HeaderSize));
        return buf;
    }

    public static bool TryUnpack(ReadOnlySpan<byte> data, out Header header, out ReadOnlySpan<byte> payload)
    {
        header = default; payload = default;
        if (data.Length < HeaderSize) return false;
        if (BinaryPrimitives.ReadUInt16BigEndian(data) != Magic) return false;
        header = new Header(
            data[2], data[3],
            BinaryPrimitives.ReadUInt32BigEndian(data.Slice(4)),
            BinaryPrimitives.ReadUInt64BigEndian(data.Slice(8)));
        payload = data.Slice(HeaderSize);
        return true;
    }

    // ---- identity / pairing helpers (identical formulas to Python/Android) ----
    public static string TokenHash(string token) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token))).ToLowerInvariant();

    /// <summary>verifyCode = (int(SHA256(token + ":" + phoneDeviceId)) % 900000) + 100000, 6 digits.</summary>
    public static string VerifyCodeFor(string token, string phoneDeviceId)
    {
        var hex = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes($"{token}:{phoneDeviceId}")));
        // Python: int(hex,16) % 900000 — big integer mod; do it digit-wise to match exactly.
        long mod = 0;
        foreach (var c in hex)
        {
            int v = c is >= '0' and <= '9' ? c - '0' : (c is >= 'A' and <= 'F' ? c - 'A' + 10 : c - 'a' + 10);
            mod = (mod * 16 + v) % 900000;
        }
        return (mod + 100000).ToString("D6");
    }
}

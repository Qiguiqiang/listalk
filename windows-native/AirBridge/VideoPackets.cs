using System.Buffers.Binary;

namespace AirBridge;

/// <summary>
/// Camera-relay wire layer (wave 4, plan §B.2) — the exact mirror of the
/// Kotlin VideoPackets.kt / VideoReassembler, which the JVM harness
/// (tools/TestVideo.kt) pins; this side is pinned to the same golden
/// subheader bytes and behaviour by the SmokeTest video stage.
///
/// A VIDEO_DATA payload is a 10-byte subheader + up to MaxFragData bytes
/// of one H.264 access unit:
///   offset 0, 4 B: frameId (per stream, starts at 0, +1 per frame)
///   offset 4, 2 B: fragIndex (0-based)
///   offset 6, 2 B: fragCount
///   offset 8, 1 B: flags — bit0 keyframe, bit1 last frag of the frame
///   offset 9, 1 B: codec (0 = h264; the header's audio codec bits are
///                  NOT used for video types)
/// The whole datagram (16-byte header included) never exceeds
/// MaxDatagramBytes. The frame's capture timestamp rides the protocol
/// header's timestamp field.
/// </summary>
public static class VideoPackets
{
    public const int MaxDatagramBytes = 1200;
    public const int SubheaderSize = 10;
    public const int MaxFragData = MaxDatagramBytes - Protocol.HeaderSize - SubheaderSize; // 1174

    public const int FlagKeyframe = 0x01;
    public const int FlagFrameEnd = 0x02;

    /// <summary>fragCount sanity bound: 2048 frags x 1174 B ~ 2.4 MB per AU.</summary>
    public const int MaxFrags = 2048;

    public const int CodecH264 = 0;

    public readonly record struct FragHeader(
        int FrameId, int FragIndex, int FragCount, bool Keyframe, bool FrameEnd, int Codec);

    public static byte[] PackSubheader(int frameId, int fragIndex, int fragCount,
        bool keyframe, bool frameEnd, int codec = CodecH264)
    {
        var buf = new byte[SubheaderSize];
        PackSubheaderInto(buf, 0, frameId, fragIndex, fragCount, keyframe, frameEnd, codec);
        return buf;
    }

    public static void PackSubheaderInto(Span<byte> dst, int offset, int frameId, int fragIndex,
        int fragCount, bool keyframe, bool frameEnd, int codec = CodecH264)
    {
        BinaryPrimitives.WriteInt32BigEndian(dst.Slice(offset), frameId);
        BinaryPrimitives.WriteUInt16BigEndian(dst.Slice(offset + 4), (ushort)fragIndex);
        BinaryPrimitives.WriteUInt16BigEndian(dst.Slice(offset + 6), (ushort)fragCount);
        int flags = (keyframe ? FlagKeyframe : 0) | (frameEnd ? FlagFrameEnd : 0);
        dst[offset + 8] = (byte)flags;
        dst[offset + 9] = (byte)codec;
    }

    public static bool TryUnpackSubheader(ReadOnlySpan<byte> data, int offset, out FragHeader header)
    {
        header = default;
        if (data.Length - offset < SubheaderSize) return false;
        int flags = data[offset + 8];
        header = new FragHeader(
            BinaryPrimitives.ReadInt32BigEndian(data.Slice(offset)),
            BinaryPrimitives.ReadUInt16BigEndian(data.Slice(offset + 4)),
            BinaryPrimitives.ReadUInt16BigEndian(data.Slice(offset + 6)),
            (flags & FlagKeyframe) != 0,
            (flags & FlagFrameEnd) != 0,
            data[offset + 9]);
        return true;
    }

    /// <summary>Parse one VIDEO_DATA payload (after the 16-byte protocol header).</summary>
    public static bool TryParseFrag(ReadOnlySpan<byte> payload, out FragHeader header, out byte[] data)
    {
        data = Array.Empty<byte>();
        if (!TryUnpackSubheader(payload, 0, out header)) return false;
        data = payload.Slice(SubheaderSize).ToArray();
        return true;
    }

    /// <summary>
    /// Split one access unit into full protocol datagrams with consecutive
    /// header seqs starting at firstSeq; every packet of the frame carries
    /// the frame's capture timestampUs in the header.
    /// </summary>
    public static List<byte[]> Packetize(int frameId, byte[] au, bool keyframe, ulong timestampUs, uint firstSeq)
    {
        if (au.Length == 0) throw new ArgumentException("cannot packetize an empty access unit");
        int fragCount = (au.Length + MaxFragData - 1) / MaxFragData;
        if (fragCount > MaxFrags) throw new ArgumentException($"access unit too large: {au.Length} B");
        var outp = new List<byte[]>(fragCount);
        for (int i = 0; i < fragCount; i++)
        {
            int off = i * MaxFragData;
            int len = Math.Min(MaxFragData, au.Length - off);
            var payload = new byte[SubheaderSize + len];
            PackSubheaderInto(payload, 0, frameId, i, fragCount, keyframe, i == fragCount - 1);
            Array.Copy(au, off, payload, SubheaderSize, len);
            // Protocol.Pack writes MakeFlags(codec, rate) into the header flags
            // byte; for video types those audio bits are unused — Pack with the
            // defaults writes 0, which is exactly the video convention.
            outp.Add(Protocol.Pack(Protocol.TypeVideoData, firstSeq + (uint)i, timestampUs, payload));
        }
        return outp;
    }
}

/// <summary>
/// In-order reassembler for one video stream — semantics identical to
/// the Kotlin VideoReassembler: fragments are collected per frameId; a
/// frame is emitted only when complete AND all earlier frames have been
/// emitted or dropped. A frame incomplete after the deadline — or
/// missing entirely while later frames complete — is dropped whole and
/// raises KeyframeNeeded. The emission base is the first frameId seen
/// after construction / Reset (production resets on every VIDEO_CONFIG
/// and the phone starts each stream at frameId 0 with a keyframe).
/// Not thread-safe: the owning video thread serializes. The clock is
/// injected (monotonic milliseconds) so tests drive time.
/// </summary>
public sealed class VideoReassembler
{
    private readonly long _deadlineMs;
    private readonly int _maxInFlight;
    private readonly int _maxAuBytes;

    public int DroppedFrames { get; private set; }
    public int CompletedFrames { get; private set; }
    public bool KeyframeNeeded { get; private set; }
    public int InFlightFrames => _partials.Count;

    private sealed class Partial
    {
        public required int FragCount;
        public required long FirstSeenMs;
        public required byte[]?[] Frags;
        public int Received;
        public int Bytes;
    }

    private readonly Dictionary<int, Partial> _partials = new();
    private readonly Dictionary<int, byte[]> _completed = new();
    private int? _nextExpected;
    private long _gapSinceMs = -1;

    public VideoReassembler(long deadlineMs = 200, int maxInFlight = 4, int maxAuBytes = 2_000_000)
    {
        _deadlineMs = deadlineMs;
        _maxInFlight = maxInFlight;
        _maxAuBytes = maxAuBytes;
    }

    public void ClearKeyframeNeeded() => KeyframeNeeded = false;

    public void Reset()
    {
        _partials.Clear();
        _completed.Clear();
        _nextExpected = null;
        _gapSinceMs = -1;
        DroppedFrames = 0;
        CompletedFrames = 0;
        KeyframeNeeded = false;
    }

    /// <summary>Offer one fragment; returns AUs that became emittable, in frameId order.</summary>
    public List<byte[]> Offer(int frameId, int fragIndex, int fragCount, bool keyframe, byte[] data, long nowMs)
    {
        var outp = Advance(nowMs);
        if (fragCount <= 0 || fragCount > VideoPackets.MaxFrags) return outp;
        if (fragIndex < 0 || fragIndex >= fragCount) return outp;
        if (_nextExpected is null) _nextExpected = frameId;
        else if (frameId < _nextExpected.Value) return outp; // late / duplicate
        if (!_completed.ContainsKey(frameId))
        {
            if (!_partials.TryGetValue(frameId, out var p))
            {
                p = new Partial { FragCount = fragCount, FirstSeenMs = nowMs, Frags = new byte[]?[fragCount] };
                _partials[frameId] = p;
            }
            if (p.FragCount != fragCount) return outp; // inconsistent fragment: ignore
            if (p.Frags[fragIndex] is null)
            {
                if (p.Bytes + data.Length > _maxAuBytes) return outp; // poisoned: deadline reaps it
                p.Frags[fragIndex] = data;
                p.Received++;
                p.Bytes += data.Length;
                if (p.Received == p.FragCount)
                {
                    _partials.Remove(frameId);
                    var au = new byte[p.Bytes];
                    int off = 0;
                    foreach (var f in p.Frags)
                    {
                        Array.Copy(f!, 0, au, off, f!.Length);
                        off += f.Length;
                    }
                    _completed[frameId] = au;
                }
            }
            EvictOverflow();
        }
        outp.AddRange(Advance(nowMs));
        NoteGap(nowMs);
        return outp;
    }

    /// <summary>Drive the loss deadlines without a new fragment.</summary>
    public List<byte[]> Expire(long nowMs)
    {
        var outp = Advance(nowMs);
        NoteGap(nowMs);
        return outp;
    }

    private List<byte[]> Advance(long nowMs)
    {
        var outp = new List<byte[]>();
        while (_nextExpected is int ne)
        {
            if (_completed.Remove(ne, out var done))
            {
                outp.Add(done);
                CompletedFrames++;
                _nextExpected = ne + 1;
                _gapSinceMs = -1;
                continue;
            }
            if (_partials.TryGetValue(ne, out var p))
            {
                if (nowMs - p.FirstSeenMs > _deadlineMs)
                {
                    _partials.Remove(ne);
                    NoteDrop();
                    _nextExpected = ne + 1;
                    _gapSinceMs = -1;
                    continue;
                }
                break; // blocking frame still within its deadline
            }
            bool hasHigher = _partials.Keys.Any(k => k > ne) || _completed.Keys.Any(k => k > ne);
            if (!hasHigher) break;
            if (_gapSinceMs < 0) { _gapSinceMs = nowMs; break; }
            if (nowMs - _gapSinceMs > _deadlineMs)
            {
                NoteDrop();
                _nextExpected = ne + 1;
                _gapSinceMs = -1;
                continue;
            }
            break;
        }
        var stale = _partials
            .Where(kv => kv.Key != _nextExpected && nowMs - kv.Value.FirstSeenMs > _deadlineMs)
            .Select(kv => kv.Key).ToList();
        foreach (var id in stale)
        {
            _partials.Remove(id);
            NoteDrop();
        }
        return outp;
    }

    private void NoteGap(long nowMs)
    {
        if (_nextExpected is not int ne || _gapSinceMs >= 0) return;
        if (_partials.ContainsKey(ne) || _completed.ContainsKey(ne)) return;
        if (_partials.Keys.Any(k => k > ne) || _completed.Keys.Any(k => k > ne)) _gapSinceMs = nowMs;
    }

    private void NoteDrop()
    {
        DroppedFrames++;
        KeyframeNeeded = true;
    }

    private void EvictOverflow()
    {
        while (_partials.Count > _maxInFlight)
        {
            int oldest = _partials.Keys.Min();
            _partials.Remove(oldest);
            NoteDrop();
            if (_nextExpected == oldest)
            {
                var rest = _partials.Keys.Concat(_completed.Keys).ToList();
                _nextExpected = rest.Count > 0 ? rest.Min() : oldest + 1;
                _gapSinceMs = -1;
            }
        }
    }
}

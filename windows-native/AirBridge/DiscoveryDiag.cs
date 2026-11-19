using System.Threading;

namespace AirBridge;

/// <summary>
/// Discovery diagnostics — C# mirror of the Android DiscoveryDiag.kt
/// (discovery hardening §5.4; the shared semantics are JVM-proven by
/// tools/TestDiag.kt — keep the verdict rules and the probe interval in
/// sync). Per-channel counters, the paired-probe schedule and the
/// plain-language verdict selection, so "nothing found" stops being
/// silent on the PC as well.
/// </summary>
public static class DiscoveryDiag
{
    public enum Channel { Broadcast, Mdns, Sweep, Ble, Direct }
    public enum Dir { Sent, Received }

    public enum Verdict
    {
        None,
        VisibleUnreachable,
        SendNoReceive,
        WrongNetwork,
        AutoPaused,
        SweepOff
    }

    /// <summary>Thread-safe per-channel sent/received counters.</summary>
    public sealed class Counters
    {
        private readonly long[] _sent = new long[Enum.GetValues<Channel>().Length];
        private readonly long[] _received = new long[Enum.GetValues<Channel>().Length];

        public void Inc(Channel channel, Dir dir, int n = 1)
        {
            if (n <= 0) return;
            if (dir == Dir.Sent) Interlocked.Add(ref _sent[(int)channel], n);
            else Interlocked.Add(ref _received[(int)channel], n);
        }

        public Snapshot TakeSnapshot() =>
            new((long[])_sent.Clone(), (long[])_received.Clone());
    }

    public sealed class Snapshot
    {
        private readonly long[] _sent, _received;
        internal Snapshot(long[] sent, long[] received) { _sent = sent; _received = received; }
        public long Sent(Channel c) => _sent[(int)c];
        public long Received(Channel c) => _received[(int)c];
        public long TotalSent => _sent.Sum();
        public long TotalReceived => _received.Sum();
    }

    /// <summary>Paired stored-IP probing cadence (§5.1): 15 s.</summary>
    public static class ProbeSchedule
    {
        public const long IntervalMs = 15_000;
        public static bool IsDue(long lastProbeMs, long nowMs, long intervalMs = IntervalMs) =>
            lastProbeMs <= 0 || nowMs - lastProbeMs >= intervalMs;
    }

    public static class VerdictPolicy
    {
        /// <summary>"Never received anything" becomes a verdict only after
        /// the engines had a fair chance to hear the 1 s beacons.</summary>
        public const long NoReceiveGraceMs = 20_000;

        /// <summary>Most specific first — mirror of DiscoveryDiag.kt:
        /// VisibleUnreachable > WrongNetwork > SendNoReceive > AutoPaused
        /// > SweepOff > None. onLanNetwork: null = unknown (only an
        /// explicit false raises WrongNetwork).</summary>
        public static Verdict Select(
            long nowMs, long startedMs,
            long totalSent, long totalReceived,
            bool hasPaired,
            bool visibleUnreachable,
            bool anyAutoPaused,
            bool? onLanNetwork,
            bool sweepGatedOff)
        {
            if (visibleUnreachable) return Verdict.VisibleUnreachable;
            if (onLanNetwork == false) return Verdict.WrongNetwork;
            if (hasPaired && totalSent > 0 && totalReceived == 0 &&
                nowMs - startedMs >= NoReceiveGraceMs)
                return Verdict.SendNoReceive;
            if (anyAutoPaused) return Verdict.AutoPaused;
            if (sweepGatedOff && hasPaired) return Verdict.SweepOff;
            return Verdict.None;
        }
    }
}

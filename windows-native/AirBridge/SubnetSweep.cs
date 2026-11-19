using System.Net;

namespace AirBridge;

/// <summary>
/// Cross-subnet discovery core (v2.2.3, FB-6) — line-for-line C# mirror of the
/// Android SubnetSweep.kt (the JVM harness tools/TestSweep.kt proves the shared
/// semantics; keep constants and ordering rules in sync with the Kotlin side).
///
/// Gray's home LAN is several ROUTED subnets (192.168.23.0/24 with cascaded
/// 192.168.88.0/24, 192.168.99.0/24): broadcast and mDNS never cross a router,
/// so a third discovery channel unicasts the existing probe packets
/// (PC → BEACON, phone → DISCOVER) to every host of a bounded candidate set,
/// ordered by value (hardening F9): paired devices' last-known-IP /24s
/// first (learned, persisted — never evictable), then local physical
/// interface /24s, then local virtual interface /24s, then user-configured
/// extra subnets. Bounds: at most MaxSubnets
/// subnets, hosts .1–.254 only, own IPs skipped, priority hosts and the
/// gateway-ish .1/.254 first, per-tick batch budget at the call site, and a
/// host that answers is not re-probed until the next cycle.
/// </summary>
public static class SubnetSweep
{
    public const int MaxSubnets = 6;
    public const int FirstHost = 1;
    public const int LastHost = 254;

    public static bool TryParseIpv4(string s, out int v)
    {
        v = 0;
        if (!IPAddress.TryParse(s.Trim(), out var ip)) return false;
        var b = ip.GetAddressBytes();
        if (b.Length != 4) return false;
        v = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3];
        return true;
    }

    public static string FormatIpv4(int v) =>
        $"{(v >> 24) & 0xFF}.{(v >> 16) & 0xFF}.{(v >> 8) & 0xFF}.{v & 0xFF}";

    public static int Subnet24(int ip) => ip & unchecked((int)0xFFFFFF00);

    /// <summary>Parse a user subnet spec ("192.168.88.0/24", "192.168.88.1",
    /// "192.168.88.0", shorthand "192.168.88") into a /24 network address.
    /// A CIDR prefix wider than /24 is rejected (too many probes).</summary>
    public static bool TryParseSubnetSpec(string spec, out int net)
    {
        net = 0;
        string s = spec.Trim();
        if (s.Length == 0) return false;
        int slash = s.IndexOf('/');
        if (slash >= 0)
        {
            if (!int.TryParse(s[(slash + 1)..].Trim(), out int prefix) || prefix < 24 || prefix > 32)
                return false;
            s = s[..slash].Trim();
        }
        if (s.Count(c => c == '.') == 2) s += ".0"; // "192.168.88" shorthand
        if (!TryParseIpv4(s, out int ip)) return false;
        net = Subnet24(ip);
        return true;
    }

    public static List<string> SplitSpecs(string raw) =>
        raw.Split(new[] { ',', ';', '\n', '\r', ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries)
           .Select(x => x.Trim()).Where(x => x.Length > 0).ToList();

    /// <summary>Ordered, deduped candidate /24 networks, capped at MaxSubnets,
    /// ordered by VALUE (discovery hardening, F9 — mirror of SubnetSweep.kt):
    /// paired devices' subnets FIRST (added before anything else, so a pile
    /// of local virtual/VPN adapters can never evict the subnet a paired
    /// phone actually lives on), then local PHYSICAL (Ethernet/WiFi)
    /// subnets, then local VIRTUAL subnets, then the user's extra subnets.
    /// Callers classify their interface addresses into the two local lists.</summary>
    public static List<int> CandidateSubnets(List<int> localPhysicalIps, List<int> localVirtualIps, List<int> pairedIps, List<string> extraSpecs)
    {
        var outp = new List<int>();
        void Add(int n) { if (!outp.Contains(n) && outp.Count < MaxSubnets) outp.Add(n); }
        foreach (var ip in pairedIps) Add(Subnet24(ip));
        foreach (var ip in localPhysicalIps) Add(Subnet24(ip));
        foreach (var ip in localVirtualIps) Add(Subnet24(ip));
        foreach (var spec in extraSpecs)
            if (TryParseSubnetSpec(spec, out int n)) Add(n);
        return outp;
    }

    /// <summary>Interface classification for the candidate ordering: is this
    /// adapter a virtual/VPN one (Hyper-V, WSL, VMware, VirtualBox, TAP/TUN,
    /// generic tunnels)? String/type-based heuristic — a false "virtual"
    /// only costs probe priority, never correctness, because paired
    /// subnets always rank first regardless.</summary>
    public static bool IsVirtualInterface(string name, string description, System.Net.NetworkInformation.NetworkInterfaceType type)
    {
        if (type is System.Net.NetworkInformation.NetworkInterfaceType.Tunnel
            or System.Net.NetworkInformation.NetworkInterfaceType.Ppp
            or System.Net.NetworkInformation.NetworkInterfaceType.Loopback)
            return true;
        string s = (name + " " + description).ToLowerInvariant();
        foreach (var hint in new[] { "hyper-v", "virtual", "vmware", "vbox", "wsl", "tap", "tun", "vpn", "docker", "loopback", "bluetooth" })
            if (s.Contains(hint)) return true;
        return false;
    }

    /// <summary>Probe order inside one /24: priority hosts in this subnet
    /// first, then gateway-ish .1 and .254, then the rest ascending.
    /// Own IPs are skipped; .0/.255 are never probed.</summary>
    public static List<int> ProbeOrder(int subnet, List<int> priorityHosts, HashSet<int> ownIps)
    {
        var outp = new List<int>(LastHost);
        var seen = new HashSet<int>();
        void Add(int ip)
        {
            if (ownIps.Contains(ip)) return;
            if (seen.Add(ip)) outp.Add(ip);
        }
        foreach (var h in priorityHosts)
            if (Subnet24(h) == subnet) Add(h);
        Add(subnet | 1);
        Add(subnet | 254);
        for (int host = FirstHost; host <= LastHost; host++) Add(subnet | host);
        return outp;
    }
}

/// <summary>Stateful sweep driver: one cycle = a full pass over every
/// candidate subnet's probe queue, interleaved round-robin. A host that
/// answers is removed from the current cycle (see NoteResponse); the next
/// cycle re-probes it. Callers own pacing and the discovery-active gate.</summary>
public sealed class SweepPlan
{
    private List<int> _subnets = new();
    private List<int> _priorityHosts = new();
    private HashSet<int> _ownIps = new();
    private readonly List<Queue<int>> _queues = new();
    private readonly object _lock = new();

    public int CycleNumber { get; private set; }
    public int SubnetCount => _subnets.Count;

    public void Reset(List<int> subnets, List<int> priorityHosts, HashSet<int> ownIps)
    {
        lock (_lock)
        {
            _subnets = subnets; _priorityHosts = priorityHosts; _ownIps = ownIps;
            BeginCycleLocked();
        }
    }

    public void BeginCycle() { lock (_lock) BeginCycleLocked(); }

    private void BeginCycleLocked()
    {
        _queues.Clear();
        foreach (var net in _subnets)
            _queues.Add(new Queue<int>(SubnetSweep.ProbeOrder(net, _priorityHosts, _ownIps)));
        CycleNumber++;
    }

    public void NoteResponse(int ip)
    {
        lock (_lock)
        {
            foreach (var q in _queues)
            {
                int n = q.Count;
                for (int i = 0; i < n; i++)
                {
                    int x = q.Dequeue();
                    if (x != ip) q.Enqueue(x);
                }
            }
        }
    }

    public bool IsCycleComplete
    {
        get { lock (_lock) return _queues.All(q => q.Count == 0); }
    }

    public int RemainingCount
    {
        get { lock (_lock) return _queues.Sum(q => q.Count); }
    }

    public List<int> NextBatch(int maxCount)
    {
        var outp = new List<int>();
        if (maxCount <= 0) return outp;
        lock (_lock)
        {
            bool progressed = true;
            while (outp.Count < maxCount && progressed)
            {
                progressed = false;
                foreach (var q in _queues)
                {
                    if (outp.Count >= maxCount) break;
                    if (q.Count > 0) { outp.Add(q.Dequeue()); progressed = true; }
                }
            }
        }
        return outp;
    }
}

package com.qi.phoneaudiobridge

/**
 * Cross-subnet discovery — PURE Kotlin core (v2.2.3, FB-6). No Android
 * imports: the subnet math, candidate ordering and sweep bounds live
 * here so the JVM harness (tools/TestSweep.kt) can prove them without
 * a device. The Windows engine carries a line-for-line C# mirror
 * (SubnetSweep.cs) — keep the constants and ordering rules in sync.
 *
 * The problem: Gray's home LAN is several ROUTED subnets
 * (192.168.23.0/24 with 192.168.88.0/24, 192.168.99.0/24, … hanging off
 * the main router). Broadcast and mDNS multicast never cross a router,
 * so the existing discovery goes blind across subnets. The fix is a
 * third discovery channel: UNICAST probes of the existing discovery
 * packets (phone → DISCOVER, PC → BEACON) to every host of a bounded
 * candidate-subnet set:
 *
 *   (a) the /24 of every local interface address,
 *   (b) the /24 of every paired device's last-known IP (learned and
 *       persisted by both ends),
 *   (c) user-configured extra subnets (Settings, both ends).
 *
 * Bounds (enforced here, not by callers): at most [MAX_SUBNETS]
 * subnets, hosts .1–.254 only (never .0/.255, never our own IPs),
 * priority hosts (paired last IPs, sighted peers) and the gateway-ish
 * .1/.254 first, a per-tick batch budget at the call site, and a host
 * that answers is not re-probed until the next cycle.
 */
object SubnetSweep {
    const val MAX_SUBNETS = 6
    const val FIRST_HOST = 1
    const val LAST_HOST = 254

    /** IPv4 <-> Int, big-endian: a.b.c.d -> (a shl 24)|(b shl 16)|(c shl 8)|d. */
    fun parseIpv4(s: String): Int? {
        val parts = s.trim().split(".")
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            if (p.isEmpty() || p.length > 3) return null
            val n = p.toIntOrNull() ?: return null
            if (n < 0 || n > 255) return null
            v = (v shl 8) or n
        }
        return v
    }

    fun formatIpv4(v: Int): String =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    /** The /24 network address containing [ip]. */
    fun subnet24(ip: Int): Int = ip and 0xFFFFFF00.toInt()

    /**
     * Parse a user subnet spec into a /24 network address. Accepts
     * "192.168.88.0/24", "192.168.88.1" (any host in the subnet),
     * "192.168.88.0" and the shorthand "192.168.88". A CIDR prefix
     * wider than /24 (e.g. /16) is rejected: sweeping it would mean
     * tens of thousands of probes.
     */
    fun parseSubnetSpec(spec: String): Int? {
        var s = spec.trim()
        if (s.isEmpty()) return null
        val slash = s.indexOf('/')
        if (slash >= 0) {
            val prefix = s.substring(slash + 1).trim().toIntOrNull() ?: return null
            if (prefix < 24 || prefix > 32) return null
            s = s.substring(0, slash).trim()
        }
        if (s.count { it == '.' } == 2) s = "$s.0" // "192.168.88" shorthand
        val ip = parseIpv4(s) ?: return null
        return subnet24(ip)
    }

    /** Split a free-form settings string into subnet specs. */
    fun splitSpecs(raw: String): List<String> =
        raw.split(',', ';', '\n', '\r', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Ordered, deduped candidate /24 networks, capped at [MAX_SUBNETS],
     * ordered by VALUE (discovery hardening, F9): paired devices' subnets
     * FIRST — they are added before anything else, so no pile of local
     * virtual/VPN adapters (Hyper-V/WSL/VMware/TAP…) can ever evict the
     * subnet a paired device actually lives on — then local PHYSICAL
     * (Ethernet/WiFi) subnets, then local VIRTUAL subnets, then the
     * user's extra subnets. Callers classify their interface addresses
     * into the two local lists.
     */
    fun candidateSubnets(
        localPhysicalIps: List<Int>,
        localVirtualIps: List<Int>,
        pairedIps: List<Int>,
        extraSpecs: List<String>
    ): List<Int> {
        val out = ArrayList<Int>()
        fun add(net: Int) {
            if (net !in out && out.size < MAX_SUBNETS) out.add(net)
        }
        for (ip in pairedIps) add(subnet24(ip))
        for (ip in localPhysicalIps) add(subnet24(ip))
        for (ip in localVirtualIps) add(subnet24(ip))
        for (spec in extraSpecs) parseSubnetSpec(spec)?.let { add(it) }
        return out
    }

    /**
     * Probe order inside one /24: [priorityHosts] that live in this
     * subnet first (paired last IPs, peers sighted this run — the
     * addresses most likely to answer), then the gateway-ish .1 and
     * .254, then the rest ascending. Own IPs are always skipped;
     * .0 and .255 are never probed.
     */
    fun probeOrder(subnet: Int, priorityHosts: List<Int>, ownIps: Set<Int>): List<Int> {
        val out = ArrayList<Int>(LAST_HOST)
        val seen = HashSet<Int>()
        fun add(ip: Int) {
            if (ip in ownIps) return
            if (seen.add(ip)) out.add(ip)
        }
        for (h in priorityHosts) if (subnet24(h) == subnet) add(h)
        add(subnet or 1)   // typical gateway / often a router-hosted peer
        add(subnet or 254) // alternate gateway convention
        for (host in FIRST_HOST..LAST_HOST) add(subnet or host)
        return out
    }
}

/**
 * Stateful sweep driver: a cycle is one full pass over every candidate
 * subnet's probe queue, interleaved round-robin so no subnet starves
 * the others. A host that answers (any packet from its IP) is removed
 * from the current cycle via [noteResponse]; the next cycle re-probes
 * it (presence expiry still needs fresh answers across subnets, where
 * beacons cannot reach us). Callers own the pacing: they pull at most
 * `budget` addresses per tick from [nextBatch] and only while
 * discovery is active.
 */
class SweepPlan {
    private var subnets: List<Int> = emptyList()
    private var priorityHosts: List<Int> = emptyList()
    private var ownIps: Set<Int> = emptySet()
    private val queues = ArrayList<ArrayDeque<Int>>()
    private var cycle = 0

    val cycleNumber: Int get() = cycle
    val subnetCount: Int get() = subnets.size

    /** (Re)build all queues from fresh candidate inputs; starts a new cycle. */
    @Synchronized
    fun reset(subnets: List<Int>, priorityHosts: List<Int>, ownIps: Set<Int>) {
        this.subnets = subnets
        this.priorityHosts = priorityHosts
        this.ownIps = ownIps
        beginCycleLocked()
    }

    /** Start the next cycle with the inputs of the last [reset]. */
    @Synchronized
    fun beginCycle() = beginCycleLocked()

    private fun beginCycleLocked() {
        queues.clear()
        for (net in subnets) {
            val q = ArrayDeque<Int>()
            q.addAll(SubnetSweep.probeOrder(net, priorityHosts, ownIps))
            queues.add(q)
        }
        cycle++
    }

    /** A packet arrived from [ip]: do not probe it again this cycle. */
    @Synchronized
    fun noteResponse(ip: Int) {
        for (q in queues) q.remove(ip)
    }

    @Synchronized
    fun isCycleComplete(): Boolean = queues.all { it.isEmpty() }

    @Synchronized
    fun remainingCount(): Int = queues.sumOf { it.size }

    /**
     * Up to [maxCount] next probe targets, round-robin across subnets
     * (one per subnet per pass) so every candidate subnet progresses
     * together instead of the first subnet eating the whole budget.
     */
    @Synchronized
    fun nextBatch(maxCount: Int): List<Int> {
        if (maxCount <= 0) return emptyList()
        val out = ArrayList<Int>(maxCount)
        var progressed = true
        while (out.size < maxCount && progressed) {
            progressed = false
            for (q in queues) {
                if (out.size >= maxCount) break
                if (q.isNotEmpty()) {
                    out.add(q.removeFirst())
                    progressed = true
                }
            }
        }
        return out
    }
}

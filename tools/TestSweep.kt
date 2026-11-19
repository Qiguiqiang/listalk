import com.qi.phoneaudiobridge.SubnetSweep
import com.qi.phoneaudiobridge.SweepPlan

/**
 * JVM tests for the SHIPPED cross-subnet sweep core (SubnetSweep.kt),
 * FB-6 (v2.2.3). The headline scenario is Gray's: routed home subnets
 * 192.168.23.0/24 + 192.168.88.0/24 + 192.168.99.0/24 — broadcast/mDNS
 * cannot cross, so discovery must unicast-probe candidate /24s in a
 * bounded, priority-first order, in BOTH directions (phone finds PC,
 * PC finds phone — the logic here is shared by both ends).
 */
fun main() {
    var checks = 0
    fun expect(cond: Boolean, label: String) {
        checks++
        check(cond) { "FAILED: $label" }
        println("SWEEP ok: $label")
    }
    fun ip(s: String): Int = SubnetSweep.parseIpv4(s)!!
    fun fmt(v: Int): String = SubnetSweep.formatIpv4(v)

    // ---- IPv4 parse / format ----
    expect(fmt(ip("192.168.23.55")) == "192.168.23.55", "ipv4 round-trip")
    expect(SubnetSweep.parseIpv4("192.168.23") == null, "3-octet rejected by parseIpv4")
    expect(SubnetSweep.parseIpv4("999.1.1.1") == null, "octet >255 rejected")
    expect(SubnetSweep.parseIpv4("abc") == null, "garbage rejected")
    expect(SubnetSweep.parseIpv4("") == null, "empty rejected")

    // ---- subnet derivation from an interface address ----
    expect(fmt(SubnetSweep.subnet24(ip("192.168.23.55"))) == "192.168.23.0", "interface ip -> its /24")
    expect(fmt(SubnetSweep.subnet24(ip("192.168.99.200"))) == "192.168.99.0", "second subnet /24")

    // ---- user spec parsing ----
    expect(SubnetSweep.parseSubnetSpec("192.168.88.0/24") == ip("192.168.88.0"), "spec CIDR /24")
    expect(SubnetSweep.parseSubnetSpec("192.168.88.137") == ip("192.168.88.0"), "spec any host -> its /24")
    expect(SubnetSweep.parseSubnetSpec("192.168.88") == ip("192.168.88.0"), "spec shorthand a.b.c")
    expect(SubnetSweep.parseSubnetSpec(" 192.168.99.0/24 ") == ip("192.168.99.0"), "spec trims whitespace")
    expect(SubnetSweep.parseSubnetSpec("192.168.0.0/16") == null, "spec wider than /24 rejected (sweep bound)")
    expect(SubnetSweep.parseSubnetSpec("10.0.0.0/8") == null, "spec /8 rejected")
    expect(SubnetSweep.parseSubnetSpec("nonsense") == null, "spec garbage rejected")
    expect(SubnetSweep.splitSpecs("192.168.88.0/24, 192.168.99.0/24\n10.0.0.0/24") ==
        listOf("192.168.88.0/24", "192.168.99.0/24", "10.0.0.0/24"), "spec splitting on comma/newline")

    // ---- candidate ordering (discovery hardening, F9): PAIRED subnets
    // first — a paired device's subnet must never be evicted by local
    // (especially virtual/VPN) adapters — then physical local subnets,
    // then virtual local subnets, then the user's extras; dedupe; cap.
    val local = listOf(ip("192.168.23.55"))
    val paired = listOf(ip("192.168.99.40"), ip("192.168.23.90")) // one remote, one local-subnet
    val extras = listOf("192.168.88.0/24", "192.168.23.0/24", "junk")
    val cands = SubnetSweep.candidateSubnets(local, emptyList(), paired, extras)
    expect(cands.map { fmt(it) } == listOf("192.168.99.0", "192.168.23.0", "192.168.88.0"),
        "candidates ordered paired>physical>extra, deduped, junk dropped: ${cands.map { fmt(it) }}")
    val virt = listOf("172.16.0.2", "172.17.0.2", "172.18.0.2", "172.19.0.2", "172.20.0.2", "192.168.56.2").map { ip(it) }
    val evict = SubnetSweep.candidateSubnets(listOf(ip("10.0.0.5")), virt, listOf(ip("192.168.99.40")), listOf("192.168.88.0/24"))
    expect(evict.first() == ip("192.168.99.0") && ip("192.168.99.0") in evict,
        "paired subnet survives a flood of virtual adapters: ${evict.map { fmt(it) }}")
    expect(evict.size == SubnetSweep.MAX_SUBNETS, "cap still holds under virtual flood (${evict.size})")
    expect(evict[1] == ip("10.0.0.0"), "physical local ranks above virtual locals: ${evict.map { fmt(it) }}")
    val many = (1..10).map { "10.0.$it.0/24" }
    expect(SubnetSweep.candidateSubnets(emptyList(), emptyList(), emptyList(), many).size == SubnetSweep.MAX_SUBNETS,
        "candidates capped at MAX_SUBNETS=${SubnetSweep.MAX_SUBNETS}")

    // ---- probe order: priority first, gateway-ish next, own IP skipped, no .0/.255 ----
    val net = ip("192.168.88.0")
    val own = setOf(ip("192.168.88.55"))
    val order = SubnetSweep.probeOrder(net, listOf(ip("192.168.88.40"), ip("192.168.23.9")), own)
    expect(order.first() == ip("192.168.88.40"), "paired last IP in this subnet probed first")
    expect(ip("192.168.23.9") !in order, "priority host from another subnet not in this queue")
    expect(order[1] == ip("192.168.88.1") && order[2] == ip("192.168.88.254"), "gateway-ish .1/.254 next")
    expect(ip("192.168.88.55") !in order, "own IP never probed")
    expect(ip("192.168.88.0") !in order && ip("192.168.88.255") !in order, "network/broadcast never probed")
    expect(order.size == 253 && order.toSet().size == 253, "254 hosts minus own IP, no duplicates (${order.size})")
    val orderNoOwn = SubnetSweep.probeOrder(net, emptyList(), emptySet())
    expect(orderNoOwn.size == 254, "full /24 = 254 probe targets")

    // ---- plan: round-robin batches respect the budget and interleave subnets ----
    val plan = SweepPlan()
    val nets = SubnetSweep.candidateSubnets(local, emptyList(), paired, extras)
    val prio = listOf(ip("192.168.99.40"))
    plan.reset(nets, prio, setOf(ip("192.168.23.55")))
    expect(plan.subnetCount == 3, "plan holds 3 candidate subnets")
    val total = plan.remainingCount()
    expect(total == 254 * 3 - 1, "total probes bounded: 3 subnets x 254 - own = $total")
    val batch = plan.nextBatch(30)
    expect(batch.size == 30, "batch respects budget (30)")
    expect(batch.first() == ip("192.168.99.40"), "round-robin starts with first (paired) subnet's priority head")
    expect(batch.contains(ip("192.168.99.40")), "paired priority host probed in the FIRST batch")
    // drain the cycle: every target probed exactly once, then complete
    val seen = HashSet<Int>(batch)
    while (!plan.isCycleComplete()) {
        val b = plan.nextBatch(64)
        check(b.isNotEmpty()) { "FAILED: batch empty before cycle complete" }
        for (h in b) check(seen.add(h)) { "FAILED: host probed twice in one cycle: ${fmt(h)}" }
    }
    expect(seen.size == total, "cycle probes every target exactly once ($total)")
    expect(plan.nextBatch(10).isEmpty(), "no probes after cycle completes")

    // ---- noteResponse: an answered host leaves the current cycle only ----
    val plan2 = SweepPlan()
    plan2.reset(listOf(ip("192.168.88.0")), emptyList(), emptySet())
    plan2.noteResponse(ip("192.168.88.40"))
    var drained = 0
    while (!plan2.isCycleComplete()) {
        val b = plan2.nextBatch(50)
        check(ip("192.168.88.40") !in b) { "FAILED: answered host re-probed within the cycle" }
        drained += b.size
    }
    expect(drained == 253, "answered host skipped this cycle (253 of 254)")
    plan2.beginCycle()
    expect(plan2.remainingCount() == 254, "next cycle re-probes everyone (presence must stay fresh)")

    // ---- reset with new candidates replaces the plan ----
    plan2.reset(listOf(ip("192.168.23.0")), emptyList(), setOf(ip("192.168.23.55")))
    expect(plan2.subnetCount == 1 && plan2.remainingCount() == 253, "reset rebuilds queues from new candidates")

    println("SWEEP TESTS ALL PASSED ($checks checks)")
}

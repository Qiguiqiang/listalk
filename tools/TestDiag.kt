import com.qi.phoneaudiobridge.DiscoveryDiag
import com.qi.phoneaudiobridge.DiscoveryDiag.Verdict

/**
 * JVM tests for the SHIPPED discovery-diagnostics core (DiscoveryDiag.kt):
 * per-channel counters, the paired-probe schedule, and the plain-language
 * verdict selection from work-v2/discovery-hardening.md §5.4. The verdict
 * rules exist so "nothing found" stops being silent — and so the phone
 * never again blames ONLY the router when the PC's firewall or its
 * auto-reconnect setting is an equally likely cause (F13).
 */
fun main() {
    var checks = 0
    fun expect(cond: Boolean, label: String) {
        checks++
        check(cond) { "FAILED: $label" }
        println("DIAG ok: $label")
    }
    val V = DiscoveryDiag.VerdictPolicy

    // ---- counters: per channel + direction, totals add up ----
    val c = DiscoveryDiag.Counters()
    c.inc(DiscoveryDiag.Channel.BROADCAST, DiscoveryDiag.Dir.SENT, 3)
    c.inc(DiscoveryDiag.Channel.BROADCAST, DiscoveryDiag.Dir.RECEIVED)
    c.inc(DiscoveryDiag.Channel.SWEEP, DiscoveryDiag.Dir.SENT, 40)
    c.inc(DiscoveryDiag.Channel.MDNS, DiscoveryDiag.Dir.RECEIVED, 2)
    c.inc(DiscoveryDiag.Channel.BLE, DiscoveryDiag.Dir.RECEIVED)
    c.inc(DiscoveryDiag.Channel.DIRECT, DiscoveryDiag.Dir.SENT)
    val snap = c.snapshot()
    expect(snap.totalSent == 44L, "total sent = 3+40+1 (${snap.totalSent})")
    expect(snap.totalReceived == 4L, "total received = 1+2+1 (${snap.totalReceived})")
    expect(snap.sent(DiscoveryDiag.Channel.SWEEP) == 40L, "per-channel sent (sweep)")
    expect(snap.received(DiscoveryDiag.Channel.BROADCAST) == 1L, "per-channel received (broadcast)")

    // ---- probe schedule: ~15 s paired stored-IP probing ----
    expect(DiscoveryDiag.ProbeSchedule.isDue(0L, 1_000L), "never probed = due immediately")
    expect(!DiscoveryDiag.ProbeSchedule.isDue(10_000L, 20_000L), "10 s after last probe = not due")
    expect(DiscoveryDiag.ProbeSchedule.isDue(10_000L, 25_000L), "15 s after last probe = due")
    expect(DiscoveryDiag.ProbeSchedule.INTERVAL_MS == 15_000L, "interval pinned at 15 s")

    // ---- verdict: baseline silence is NOT a verdict ----
    expect(V.select(nowMs = 5_000, startedMs = 0, totalSent = 10, totalReceived = 0,
        hasPaired = true, visibleUnreachable = false, anyAutoPaused = false,
        onLanNetwork = true, sweepGatedOff = false) == Verdict.NONE,
        "no-receive inside the grace window = NONE (still warming up)")
    expect(V.select(nowMs = 60_000, startedMs = 0, totalSent = 0, totalReceived = 0,
        hasPaired = true, visibleUnreachable = false, anyAutoPaused = false,
        onLanNetwork = true, sweepGatedOff = false) == Verdict.NONE,
        "nothing sent yet = NONE (no evidence)")

    // ---- verdict: send-but-never-receive (PC firewall / broadcast blocked) ----
    expect(V.select(nowMs = 60_000, startedMs = 0, totalSent = 120, totalReceived = 0,
        hasPaired = true, visibleUnreachable = false, anyAutoPaused = false,
        onLanNetwork = true, sweepGatedOff = false) == Verdict.SEND_NO_RECEIVE,
        "sent for a minute, received nothing, paired exists = SEND_NO_RECEIVE")
    expect(V.select(nowMs = 60_000, startedMs = 0, totalSent = 120, totalReceived = 0,
        hasPaired = false, visibleUnreachable = false, anyAutoPaused = false,
        onLanNetwork = true, sweepGatedOff = false) == Verdict.NONE,
        "no paired device known = silence is unremarkable, no verdict")

    // ---- verdict: wrong network beats send-no-receive (it explains it) ----
    expect(V.select(nowMs = 60_000, startedMs = 0, totalSent = 120, totalReceived = 0,
        hasPaired = true, visibleUnreachable = false, anyAutoPaused = false,
        onLanNetwork = false, sweepGatedOff = false) == Verdict.WRONG_NETWORK,
        "default network not the LAN (VPN/cellular) = WRONG_NETWORK")
    expect(V.select(nowMs = 60_000, startedMs = 0, totalSent = 120, totalReceived = 3,
        hasPaired = true, visibleUnreachable = false, anyAutoPaused = false,
        onLanNetwork = null, sweepGatedOff = false) == Verdict.NONE,
        "unknown network + traffic flowing = NONE")

    // ---- verdict: visible-but-unreachable is the most specific, wins over all ----
    expect(V.select(nowMs = 60_000, startedMs = 0, totalSent = 120, totalReceived = 0,
        hasPaired = true, visibleUnreachable = true, anyAutoPaused = false,
        onLanNetwork = false, sweepGatedOff = true) == Verdict.VISIBLE_UNREACHABLE,
        "presence fresh + HELLOs unanswered = VISIBLE_UNREACHABLE (both-causes wording at the UI)")

    // ---- verdict: the §5 fuse must be visible, and outranks the sweep gate ----
    expect(V.select(nowMs = 60_000, startedMs = 0, totalSent = 120, totalReceived = 9,
        hasPaired = true, visibleUnreachable = false, anyAutoPaused = true,
        onLanNetwork = true, sweepGatedOff = true) == Verdict.AUTO_PAUSED,
        "auto-reconnect fuse tripped = AUTO_PAUSED")
    expect(V.select(nowMs = 60_000, startedMs = 0, totalSent = 120, totalReceived = 9,
        hasPaired = true, visibleUnreachable = false, anyAutoPaused = false,
        onLanNetwork = true, sweepGatedOff = true) == Verdict.SWEEP_OFF,
        "paired exists but sweep gated off = SWEEP_OFF")
    expect(V.select(nowMs = 60_000, startedMs = 0, totalSent = 120, totalReceived = 9,
        hasPaired = true, visibleUnreachable = false, anyAutoPaused = false,
        onLanNetwork = true, sweepGatedOff = false) == Verdict.NONE,
        "traffic flowing, nothing wrong = NONE")

    println("DIAG TESTS ALL PASSED ($checks checks)")
}

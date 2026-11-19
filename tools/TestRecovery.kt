import com.qi.phoneaudiobridge.PeerDirectory
import com.qi.phoneaudiobridge.RecoveryPolicy

/**
 * JVM tests for the SHIPPED mesh-recovery core (SessionRecovery.kt),
 * defect A (v2.2). The headline scenario is Gray's: the phone roams on a
 * mesh network, the stored/session IP goes stale, a beacon for the SAME
 * deviceId arrives from a NEW IP, and the session must retarget.
 */
fun main() {
    var checks = 0
    fun expect(cond: Boolean, label: String) {
        checks++
        check(cond) { "FAILED: $label" }
        println("RECOVERY ok: $label")
    }

    // ---- PeerDirectory: sightings are keyed by deviceId, freshness-gated ----
    val dir = PeerDirectory()
    dir.note("pc-1", "192.168.1.50", 1000L)
    expect(dir.freshIp("pc-1", 2000L) == "192.168.1.50", "fresh sighting resolves")
    expect(dir.freshIp("pc-1", 1000L + PeerDirectory.FRESH_MS + 1) == null, "stale sighting (>fresh window) does not resolve")
    expect(PeerDirectory.FRESH_MS in 6000L..8000L,
        "presence freshness relaxed to ~6-8s (F16: two dropped 1s beacons must not flicker a device offline)")
    expect(dir.freshIp("unknown", 2000L) == null, "unknown device does not resolve")
    dir.note("", "192.168.1.99", 2000L)
    expect(dir.sighting("") == null, "empty deviceId ignored")

    // ---- THE mesh scenario: stored IP stale, beacon same deviceId new IP ----
    val storedIp = "192.168.1.50"          // what the registry/session still believes
    dir.note("pc-1", "192.168.1.77", 5000L) // roam: beacon from the new address
    val target = RecoveryPolicy.retargetIp(dir.freshIp("pc-1", 5100L), storedIp)
    expect(target == "192.168.1.77", "session retargets to beacon's new IP ($target)")
    expect(RecoveryPolicy.retargetIp("192.168.1.77", "192.168.1.77") == null, "no retarget when already current")
    expect(RecoveryPolicy.retargetIp(null, storedIp) == null, "no retarget without a fresh sighting")
    expect(RecoveryPolicy.retargetIp("", storedIp) == null, "no retarget to an empty address")

    // ---- HELLO backoff: exponential, capped at 15 s ----
    val seq = (0..6).map { RecoveryPolicy.helloBackoffMs(it) }
    expect(seq == listOf(1000L, 2000L, 4000L, 8000L, 15000L, 15000L, 15000L), "backoff 1/2/4/8/15(cap): $seq")
    expect(RecoveryPolicy.helloBackoffMs(30) == 15000L, "backoff stays capped for large attempts")

    // ---- Link state machine ----
    expect(
        RecoveryPolicy.linkState(silenceMs = 500, presenceFresh = true, helloAttempts = 0, resolvingForMs = 0) ==
            RecoveryPolicy.LinkState.OK, "live traffic = OK")
    expect(
        RecoveryPolicy.linkState(silenceMs = RecoveryPolicy.DEAD_PEER_MS + 500, presenceFresh = true, helloAttempts = 0, resolvingForMs = 500) ==
            RecoveryPolicy.LinkState.RESOLVING, "silence >2s = RESOLVING (dead-peer detection)")
    expect(
        RecoveryPolicy.linkState(silenceMs = 99999, presenceFresh = false, helloAttempts = 9, resolvingForMs = 60000) ==
            RecoveryPolicy.LinkState.RESOLVING, "peer not visible at all = RESOLVING, never ISOLATED")
    expect(
        RecoveryPolicy.linkState(silenceMs = 99999, presenceFresh = true, helloAttempts = 4, resolvingForMs = 60000) ==
            RecoveryPolicy.LinkState.RESOLVING, "visible but <5 attempts = RESOLVING, not yet ISOLATED")
    expect(
        RecoveryPolicy.linkState(silenceMs = 99999, presenceFresh = true, helloAttempts = 5, resolvingForMs = 10000) ==
            RecoveryPolicy.LinkState.ISOLATED, "visible + 5 attempts + 10s = ISOLATED (AP/guest isolation hint)")
    expect(
        RecoveryPolicy.linkState(silenceMs = 99999, presenceFresh = true, helloAttempts = 8, resolvingForMs = 9999) ==
            RecoveryPolicy.LinkState.RESOLVING, "visible + attempts but <10s = still RESOLVING")

    println("RECOVERY TESTS ALL PASSED ($checks checks)")
}

package com.qi.phoneaudiobridge

/**
 * Mesh-roaming / network-change session recovery — PURE Kotlin core
 * (v2.2, defect A). No Android imports: the address-resolution and
 * recovery-decision logic lives here so the JVM harness
 * (tools/TestRecovery.kt) can prove it without a device.
 *
 * The problem this solves: on a mesh WiFi the phone (or the PC) roams
 * between nodes and its IP changes. Presence beacons and mDNS sightings
 * carry the PC's deviceId, so identity survives the roam — but a session
 * keyed to the OLD IP is a dead letter. The rule enforced by this core:
 *
 *   NEVER trust a stored/session IP once a fresher sighting for the same
 *   deviceId names a different address — retarget the session to the
 *   sighting's address.
 *
 * And when a peer is VISIBLE (beacons arriving) but repeated HELLOs get
 * no answer, that is a different failure from "not found": the router is
 * most likely isolating clients (AP / guest-network isolation), and the
 * UI must say so instead of spinning forever.
 */

/** deviceId -> latest sighting (ip + when). Fed by beacons / DISCOVER_RESP / mDNS. */
class PeerDirectory {
    data class Sighting(val ip: String, val seenMs: Long)

    private val sightings = HashMap<String, Sighting>()

    @Synchronized
    fun note(deviceId: String, ip: String, nowMs: Long) {
        if (deviceId.isEmpty() || ip.isEmpty()) return
        sightings[deviceId] = Sighting(ip, nowMs)
    }

    @Synchronized
    fun sighting(deviceId: String): Sighting? = sightings[deviceId]

    /** The device's current IP, but only while the sighting is fresh. */
    @Synchronized
    fun freshIp(deviceId: String, nowMs: Long, freshMs: Long = FRESH_MS): String? {
        val s = sightings[deviceId] ?: return null
        return if (nowMs - s.seenMs <= freshMs) s.ip else null
    }

    @Synchronized
    fun isFresh(deviceId: String, nowMs: Long, freshMs: Long = FRESH_MS): Boolean =
        freshIp(deviceId, nowMs, freshMs) != null

    companion object { const val FRESH_MS = 3000L }
}

object RecoveryPolicy {
    /** Silence longer than this on a live session = dead-peer suspicion. */
    const val DEAD_PEER_MS = 2000L
    /** HELLO attempts (with presence visible) before isolation is suspected. */
    const val ISOLATION_MIN_ATTEMPTS = 5
    /** …sustained at least this long. */
    const val ISOLATION_MIN_RESOLVING_MS = 10000L

    enum class LinkState { OK, RESOLVING, ISOLATED }

    /**
     * Exponential HELLO retry backoff: 1 s, 2 s, 4 s, 8 s, then capped
     * at 15 s — a dead peer is retried patiently, never hammered.
     */
    fun helloBackoffMs(attempt: Int): Long {
        if (attempt <= 0) return 1000L
        if (attempt >= 4) return 15000L
        return 1000L shl attempt
    }

    /**
     * Decide the user-visible link state for one peer.
     * @param silenceMs      time since the last packet from the peer (connected sessions)
     * @param presenceFresh  beacons/sightings for this deviceId are arriving NOW
     * @param helloAttempts  HELLOs sent since the last HELLO_ACK
     * @param resolvingForMs how long we have been trying to (re)establish the session
     */
    fun linkState(silenceMs: Long, presenceFresh: Boolean, helloAttempts: Int, resolvingForMs: Long): LinkState {
        if (presenceFresh && helloAttempts >= ISOLATION_MIN_ATTEMPTS && resolvingForMs >= ISOLATION_MIN_RESOLVING_MS) {
            return LinkState.ISOLATED
        }
        if (silenceMs > DEAD_PEER_MS || helloAttempts > 0) return LinkState.RESOLVING
        return LinkState.OK
    }

    /**
     * The retarget decision: given the freshest directory IP for the
     * session's deviceId and the IP the session currently targets, return
     * the address the session must move to — or null when it is already
     * aimed at the freshest known address (or nothing fresher is known).
     */
    fun retargetIp(directoryIp: String?, sessionIp: String): String? =
        if (directoryIp != null && directoryIp.isNotEmpty() && directoryIp != sessionIp) directoryIp else null
}

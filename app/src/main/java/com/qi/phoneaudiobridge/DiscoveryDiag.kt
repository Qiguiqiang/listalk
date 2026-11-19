package com.qi.phoneaudiobridge

/**
 * Discovery diagnostics — PURE Kotlin core (discovery hardening §5.4).
 * No Android imports: the per-channel counters, the paired-probe
 * schedule and the plain-language verdict selection live here so the
 * JVM harness (tools/TestDiag.kt) can prove them without a device.
 * The Windows engine carries a C# mirror (DiscoveryDiag.cs) — keep the
 * verdict rules and the probe interval in sync.
 *
 * Why this exists: "nothing found" used to be silent on both ends, and
 * the one diagnostic that did exist (the isolation verdict) named only
 * the router even when the PC's firewall was the cause (F13). The
 * verdicts below are rule-based over counters the engines keep anyway,
 * and the UI wording for VISIBLE_UNREACHABLE names BOTH candidate
 * causes — never a single misattributed one.
 */
object DiscoveryDiag {

    enum class Channel { BROADCAST, MDNS, SWEEP, BLE, DIRECT }
    enum class Dir { SENT, RECEIVED }

    enum class Verdict {
        NONE,
        /** Presence fresh but HELLOs unanswered: router isolation OR the
         *  other end's firewall / auto-reconnect off — UI names both. */
        VISIBLE_UNREACHABLE,
        /** We transmit but have never received a single packet while a
         *  paired device is known to exist: inbound path blocked (PC:
         *  firewall; phone: router broadcast filtering). */
        SEND_NO_RECEIVE,
        /** The process default network is not the home LAN (VPN up, or
         *  cellular preferred): LAN discovery cannot work from here. */
        WRONG_NETWORK,
        /** The §5 auto-connect fuse tripped: auto-retry is paused and
         *  only a tap (or a fresh appearance) resets it. */
        AUTO_PAUSED,
        /** Paired devices exist but the cross-subnet sweep is gated off
         *  (e.g. PC sitting in the tray in the pre-hardening design). */
        SWEEP_OFF
    }

    /** Thread-safe per-channel sent/received counters. */
    class Counters {
        private val sent = LongArray(Channel.values().size)
        private val received = LongArray(Channel.values().size)

        @Synchronized
        fun inc(channel: Channel, dir: Dir, n: Int = 1) {
            if (n <= 0) return
            if (dir == Dir.SENT) sent[channel.ordinal] += n else received[channel.ordinal] += n
        }

        @Synchronized
        fun snapshot(): Snapshot = Snapshot(sent.copyOf(), received.copyOf())
    }

    class Snapshot(private val sent: LongArray, private val received: LongArray) {
        fun sent(channel: Channel): Long = sent[channel.ordinal]
        fun received(channel: Channel): Long = received[channel.ordinal]
        val totalSent: Long get() = sent.sum()
        val totalReceived: Long get() = received.sum()
    }

    /** Paired stored-IP probing cadence (§5.1): probing is unconditional
     *  and periodic; CONNECTING stays governed by the existing consent
     *  machinery. 15 s is slow enough to be free and fast enough that a
     *  peer coming online is picked up without any presence channel. */
    object ProbeSchedule {
        const val INTERVAL_MS = 15_000L
        fun isDue(lastProbeMs: Long, nowMs: Long, intervalMs: Long = INTERVAL_MS): Boolean =
            lastProbeMs <= 0L || nowMs - lastProbeMs >= intervalMs
    }

    object VerdictPolicy {
        /** "Never received anything" only becomes a verdict after the
         *  engines have had a fair chance to hear the 1 s beacons. */
        const val NO_RECEIVE_GRACE_MS = 20_000L

        /**
         * Pick the single most useful verdict, most specific first:
         * VISIBLE_UNREACHABLE (we can prove presence) > WRONG_NETWORK
         * (it explains a total receive blackout) > SEND_NO_RECEIVE >
         * AUTO_PAUSED > SWEEP_OFF > NONE.
         * @param onLanNetwork true/false when known, null when unknown —
         *        only an explicit false raises WRONG_NETWORK.
         */
        fun select(
            nowMs: Long, startedMs: Long,
            totalSent: Long, totalReceived: Long,
            hasPaired: Boolean,
            visibleUnreachable: Boolean,
            anyAutoPaused: Boolean,
            onLanNetwork: Boolean?,
            sweepGatedOff: Boolean
        ): Verdict {
            if (visibleUnreachable) return Verdict.VISIBLE_UNREACHABLE
            if (onLanNetwork == false) return Verdict.WRONG_NETWORK
            if (hasPaired && totalSent > 0 && totalReceived == 0L &&
                nowMs - startedMs >= NO_RECEIVE_GRACE_MS
            ) return Verdict.SEND_NO_RECEIVE
            if (anyAutoPaused) return Verdict.AUTO_PAUSED
            if (sweepGatedOff && hasPaired) return Verdict.SWEEP_OFF
            return Verdict.NONE
        }
    }
}

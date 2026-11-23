package com.qi.phoneaudiobridge

/**
 * Per-direction pause policy (board item #3).
 *
 * The bug this replaces: the wave-1 "call pause" (§3.6) was an audio
 * focus listener that mapped ANY focus loss — a real call, but equally
 * any music/video app — to ONE shared flag gating both directions.
 * Worse, after a permanent AUDIOFOCUS_LOSS our request leaves the
 * focus stack, so the matching AUDIOFOCUS_GAIN is never delivered and
 * the flag latched until the bridge was restarted: opening a media app
 * permanently killed the phone-mic uplink to the PC, invisibly.
 *
 * The policy therefore keeps the directions fully independent:
 *  - UPLINK (phone mic -> PC) is gated ONLY by [inCall], a real
 *    telephony-call state fed by the engine from AudioManager.mode
 *    (MODE_IN_CALL / MODE_RINGTONE — set by the telephony stack, never
 *    by this app, which requests MODE_IN_COMMUNICATION). Media focus
 *    events can never pause the uplink. This preserves the §3.6 intent
 *    (call audio must never leak to the PCs) without the collateral
 *    damage. Known limit: VoIP calls that run in MODE_IN_COMMUNICATION
 *    are indistinguishable from our own session mode without
 *    READ_PHONE_STATE, so they are not detected — see the report.
 *  - DOWNLINK (PC -> phone speaker) keeps media-focus behaviour:
 *    transient loss pauses (the platform returns GAIN itself), a
 *    duckable loss ducks the render volume instead of pausing, and a
 *    permanent loss pauses AND schedules bounded focus re-requests
 *    (2s/5s/15s/45s, then it stops trying) instead of latching — the
 *    engine performs the actual requestAudioFocus calls.
 *
 * Pure Kotlin (no Android imports) so the JVM harness exercises this
 * exact class (tools/TestFocus.kt). Focus events mirror the Android
 * AudioManager constants so the engine maps them 1:1.
 */
class FocusPolicy {
    enum class FocusEvent { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK }
    enum class Downlink { ACTIVE, DUCKED, PAUSED }

    var downlink: Downlink = Downlink.ACTIVE
        private set

    /** Real telephony call in progress (ringing or off-hook). */
    var inCall: Boolean = false
        private set

    /** The ONLY gate on the mic uplink: a real call. */
    val uplinkPaused: Boolean get() = inCall
    val downlinkPaused: Boolean get() = downlink == Downlink.PAUSED
    val downlinkDucked: Boolean get() = downlink == Downlink.DUCKED

    private var reRequestAtMs = -1L
    private var reRequestAttempts = 0

    fun setInCall(value: Boolean) {
        inCall = value
    }

    fun onFocusEvent(event: FocusEvent, nowMs: Long) {
        when (event) {
            FocusEvent.GAIN -> {
                downlink = Downlink.ACTIVE
                reRequestAtMs = -1L
                reRequestAttempts = 0
            }
            FocusEvent.LOSS -> {
                // Permanent loss: GAIN will not come back on its own
                // (our request left the stack) — schedule bounded
                // re-requests instead of latching the pause forever.
                downlink = Downlink.PAUSED
                reRequestAttempts = 0
                reRequestAtMs = nowMs + RE_REQUEST_DELAYS_MS[0]
            }
            FocusEvent.LOSS_TRANSIENT -> {
                downlink = Downlink.PAUSED
                reRequestAtMs = -1L
                reRequestAttempts = 0
            }
            FocusEvent.LOSS_TRANSIENT_CAN_DUCK -> {
                // A duck must never lift an existing pause.
                if (downlink != Downlink.PAUSED) downlink = Downlink.DUCKED
            }
        }
    }

    /** True when the engine should perform one focus re-request. */
    fun shouldReRequestFocus(nowMs: Long): Boolean =
        reRequestAtMs >= 0L && nowMs >= reRequestAtMs

    /** Bookkeeping after the engine performed a re-request attempt. */
    fun onFocusReRequestPerformed(nowMs: Long) {
        reRequestAttempts++
        reRequestAtMs = if (reRequestAttempts < RE_REQUEST_DELAYS_MS.size) {
            nowMs + RE_REQUEST_DELAYS_MS[reRequestAttempts]
        } else {
            -1L
        }
    }

    fun reset() {
        downlink = Downlink.ACTIVE
        inCall = false
        reRequestAtMs = -1L
        reRequestAttempts = 0
    }

    companion object {
        /** Render-volume factor applied while ducked. */
        const val DUCK_GAIN = 0.25f
        /** Backoff before/between the bounded focus re-requests. */
        val RE_REQUEST_DELAYS_MS = longArrayOf(2_000L, 5_000L, 15_000L, 45_000L)
    }
}

package com.qi.phoneaudiobridge

/**
 * Capture health watchdog (board item #3) — the capture-side sibling
 * of [PlayoutWatchdog], same shape and rationale.
 *
 * Two silent-death modes of the uplink recorder it guards against:
 *  1. SILENCED / DEAD STREAM: when another app starts capturing with
 *     higher priority, Android can mute our VOICE_COMMUNICATION
 *     recorder (AudioRecordingConfiguration.isClientSilenced); read()
 *     then returns well-formed ZERO frames forever — no error, nothing
 *     to log, the PC hears silence while every meter looks alive.
 *     Sustained near-zero frames while capture should be live (and
 *     not inhibited by a known explanation) therefore trigger a
 *     recorder rebuild — bounded, so a genuinely quiet room can cost
 *     at most [MAX_REBUILDS] brief rebuilds per engine session, at
 *     least [MIN_REBUILD_INTERVAL_MS] apart.
 *     shortcut: silence alone cannot distinguish "silenced recorder"
 *     from "very quiet room"; the bound above is the mitigation, and
 *     the engine additionally feeds the platform silenced flag as
 *     [inhibited] whenever it is known. Upgrade path: rebuild only on
 *     a confirmed silenced->unsilenced transition if field data shows
 *     quiet-room rebuilds are a problem.
 *  2. READ ERRORS: read() <= 0 used to break the frame fill and loop
 *     forever without ever reaching the recorder-rebuild branch. A
 *     streak of [READ_ERROR_THRESHOLD] consecutive errors now asks for
 *     a rebuild, rate-limited to one per
 *     [MIN_READ_REBUILD_INTERVAL_MS] so a permanently broken OEM stack
 *     cannot spin a rebuild storm.
 *
 * Pure Kotlin (no Android imports); the engine feeds it one decision
 * point per captured frame / read error and acts on a true return by
 * releasing the recorder (the capture loop's existing null branch
 * then rebuilds it). Exercised by tools/TestFocus.kt.
 */
class CaptureWatchdog {
    /** Silence-triggered rebuilds performed in this engine session. */
    var rebuildCount: Int = 0
        private set

    private var silenceStartMs = -1L
    private var nextRebuildAllowedMs = 0L
    private var readErrorStreak = 0
    private var lastReadRebuildMs = -1L

    fun reset() {
        rebuildCount = 0
        silenceStartMs = -1L
        nextRebuildAllowedMs = 0L
        readErrorStreak = 0
        lastReadRebuildMs = -1L
    }

    /** A (re)built recorder just started: silence/error accounting
     * restarts; the rebuild budget deliberately does NOT. */
    fun onRecorderStarted(nowMs: Long) {
        silenceStartMs = -1L
        readErrorStreak = 0
    }

    /**
     * Feed one captured frame. [peak] is the frame's peak amplitude
     * 0..1. [inhibited] marks frames whose silence has a known,
     * legitimate explanation (the recorder is reported silenced by
     * the platform, or a call is in progress) — they never count
     * toward a rebuild and they reset the silence clock.
     * Returns true exactly once when a rebuild is due.
     */
    fun onFrame(peak: Float, nowMs: Long, inhibited: Boolean): Boolean {
        if (inhibited || peak >= NEAR_ZERO_PEAK) {
            silenceStartMs = -1L
            return false
        }
        if (silenceStartMs < 0L) {
            silenceStartMs = nowMs
            return false
        }
        if (nowMs - silenceStartMs < SILENCE_TRIGGER_MS) return false
        // Sustained silence: consume the window either way so a blocked
        // rebuild does not re-evaluate on every subsequent frame.
        silenceStartMs = -1L
        if (rebuildCount >= MAX_REBUILDS) return false
        if (nowMs < nextRebuildAllowedMs) return false
        rebuildCount++
        nextRebuildAllowedMs = nowMs + MIN_REBUILD_INTERVAL_MS
        return true
    }

    /**
     * Feed one failed read() (return value <= 0 or exception). Returns
     * true when a rebuild is due. The streak is kept across a
     * rate-limited refusal, so the first error after the interval
     * rebuilds immediately.
     *
     * [recorderStopped] is the definitive case — a recorder in
     * RECORDSTATE_STOPPED never produces samples again, so it rebuilds
     * at once without waiting for the streak — but it shares the same
     * rate limit, so an OEM stack that stops every new recorder
     * instantly cannot spin a rebuild storm either.
     */
    fun onReadError(nowMs: Long, recorderStopped: Boolean = false): Boolean {
        if (lastReadRebuildMs >= 0L && nowMs - lastReadRebuildMs < MIN_READ_REBUILD_INTERVAL_MS) {
            // Inside the rate-limit window nothing rebuilds; a plain
            // error still counts toward the streak for later.
            if (!recorderStopped) readErrorStreak++
            return false
        }
        if (recorderStopped) {
            readErrorStreak = 0
            lastReadRebuildMs = nowMs
            return true
        }
        readErrorStreak++
        if (readErrorStreak < READ_ERROR_THRESHOLD) return false
        readErrorStreak = 0
        lastReadRebuildMs = nowMs
        return true
    }

    /** A read() returned samples again: the error streak is over. */
    fun onReadSuccess() {
        readErrorStreak = 0
    }

    companion object {
        /** Peak below this counts as a silent frame (~-56 dBFS). */
        const val NEAR_ZERO_PEAK = 0.0015f
        /** Continuous silence that justifies one recorder rebuild. */
        const val SILENCE_TRIGGER_MS = 8_000L
        /** Silence-triggered rebuilds per engine session. */
        const val MAX_REBUILDS = 3
        /** Minimum spacing between silence-triggered rebuilds. */
        const val MIN_REBUILD_INTERVAL_MS = 60_000L
        /** Consecutive read() failures that justify a rebuild. */
        const val READ_ERROR_THRESHOLD = 5
        /** Minimum spacing between read-error rebuilds (storm guard). */
        const val MIN_READ_REBUILD_INTERVAL_MS = 2_000L
    }
}

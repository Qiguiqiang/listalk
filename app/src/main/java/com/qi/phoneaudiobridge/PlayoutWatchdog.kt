package com.qi.phoneaudiobridge

/**
 * Playback health watchdog (v2.2, speaker-no-sound fix).
 *
 * The failure this guards against: on several OEM builds (MIUI in
 * particular) an AudioTrack can silently stop consuming after a
 * communication-device switch or an audio-policy reconfiguration —
 * write() keeps accepting (or failing into a discarded return value),
 * the play head never advances, and the speaker outputs nothing while
 * every level meter in the app keeps dancing. The only trustworthy
 * liveness signal is AudioTrack.getPlaybackHeadPosition(): if frames
 * are being written but the head does not advance for a full second,
 * the track is dead and must be rebuilt.
 *
 * Pure Kotlin (no Android imports) so the JVM harness exercises this
 * exact class. The engine feeds it every successful write and samples
 * the head position ~4x per second. Head positions are 32-bit and wrap;
 * deltas are computed in the unsigned domain.
 */
class PlayoutWatchdog {
    companion object {
        /** No stall verdict during the first moments after play(). */
        const val GRACE_MS = 1200L
        /** Head must not advance for this long (while writes continue). */
        const val STALL_MS = 1000L
        /** Ignore stalls until at least this many frames were written. */
        const val MIN_WRITTEN_FRAMES = 4800 // 100 ms at 48 kHz
    }

    private var startedMs = -1L
    private var lastHead = -1L // unsigned 32-bit value kept in a Long
    private var lastAdvanceMs = 0L
    private var writtenFrames = 0L
    private var stallReported = false

    /** How many stall episodes were reported (diagnostics). */
    var stallCount = 0
        private set

    fun reset() {
        startedMs = -1L
        lastHead = -1L
        lastAdvanceMs = 0L
        writtenFrames = 0L
        stallReported = false
    }

    /** A fresh AudioTrack just entered play(). */
    fun onTrackStarted(nowMs: Long) {
        reset()
        startedMs = nowMs
        lastAdvanceMs = nowMs
    }

    /** [frames] frames (track-rate samples) were accepted by write(). */
    fun onWrite(frames: Int) {
        if (frames > 0) writtenFrames += frames
    }

    /**
     * Feed one getPlaybackHeadPosition() sample. Returns true exactly
     * once per stall episode; the caller is expected to rebuild the
     * track (which re-arms the watchdog via [onTrackStarted]).
     */
    fun onHeadSample(headRaw: Int, nowMs: Long): Boolean {
        if (startedMs < 0) return false
        val head = headRaw.toLong() and 0xFFFFFFFFL
        if (lastHead >= 0) {
            val delta = (head - lastHead) and 0xFFFFFFFFL
            // Forward motion (allowing wrap). A backwards/huge jump is a
            // counter reset, not progress — do not count it as advance.
            if (delta in 1..0x7FFFFFFFL) {
                lastAdvanceMs = nowMs
                stallReported = false
            }
        }
        lastHead = head
        if (stallReported) return false
        if (nowMs - startedMs < GRACE_MS) return false
        if (writtenFrames < MIN_WRITTEN_FRAMES) return false
        if (nowMs - lastAdvanceMs >= STALL_MS) {
            stallReported = true
            stallCount++
            return true
        }
        return false
    }
}

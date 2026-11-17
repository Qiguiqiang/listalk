package com.qi.phoneaudiobridge

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Runtime loop-guard (defect 5 safety net): detects the phone's OWN mic
 * voice coming back inside the incoming PC speaker stream — which happens
 * when the PC's capture source contains the phone-mic render (e.g. the
 * mic is rendered into the same VB-CABLE whose output is being captured,
 * or a loopback/monitor path folds it back). When a loop is detected the
 * caller drops the offending frames from playback and shows a status
 * note; the primary fixes live in routing (phone mixer never takes mic
 * input; PC capture-source exclusion). This guard is the last resort,
 * not the mechanism of first defence.
 *
 * Method: both streams are box-decimated x6 (48 kHz -> 8 kHz domain),
 * which makes the correlation tolerant of the sub-millisecond alignment
 * error the PC's resampling chain introduces. A ring holds ~2.5 s of
 * sent mic; every 20 frames a coarse scan (lags 20 ms..1.5 s, step
 * ~0.5 ms) finds the best normalised correlation, and each incoming
 * frame is verified at that lag (± a small window).
 *
 * Entry decisions use a LONG window — the last 4 incoming frames
 * concatenated (320 decimated samples). This matters: with a single
 * 80-sample frame, maximising correlation over ~3000 lags has a large
 * statistical bias, and for strongly periodic programme material
 * (music) whose post-echo-chain residual also lives in the sent-mic
 * history, the biased peak can cross even a 0.80 bar and then latch
 * (the same programme keeps the tracked-lag correlation afloat) —
 * JVM-measured: 401/500 frames falsely blocked with the single-frame
 * window. The 4x window narrows the fluctuation ~2x, so the acoustic
 * case peaks ~0.6 while a true electric loop still measures ~1.00.
 * Entry bar is 0.80 on the long window (the original single-frame
 * 0.55 bar was far inside the bias). While blocked, a periodic FULL
 * rescan releases the guard as soon as the loop signature has
 * vanished at every lag, and a hard fuse ([MAX_BLOCK_FRAMES])
 * force-releases + cools down so this guard can never mute the
 * speaker indefinitely.
 *
 * Pure Kotlin (no Android imports) so the JVM harness exercises this
 * exact class. [reset] on any negotiated-rate change.
 */
class LoopGuard {
    companion object {
        private const val DECIM = 6
        /** Entry bar: a true electric loop correlates ~1.00 (JVM-measured). */
        private const val ENTER_CORR = 0.80
        /** While blocked, a full rescan peaking below this releases at once. */
        private const val RELEASE_SCAN_CORR = 0.45
        /** Hard fuse: never block longer than 400 frames (4 s) in a row. */
        private const val MAX_BLOCK_FRAMES = 400
        /** After a fuse release, stay out of the way for 300 frames (3 s). */
        private const val COOLDOWN_FRAMES = 300
    }

    private val lock = Any()
    private val hist = ShortArray(20_000) // ~2.5 s at the 8 kHz decimated rate
    private var histTotal = 0L            // decimated samples ever pushed
    private var framesSinceScan = 0
    private var bestLag = -1              // in decimated samples
    private var releaseCount = 0
    private var blockedFrames = 0
    private var cooldownFrames = 0
    /** Last few decimated incoming frames, for the long entry window. */
    private val recentIn = ArrayDeque<ShortArray>()

    @Volatile var blocked = false
        private set
    @Volatile var detectionCount = 0
        private set
    /** Times the hard fuse had to force-release a block (diagnostics). */
    @Volatile var cappedReleaseCount = 0
        private set
    @Volatile var lastCorrelation = 0.0
        private set

    fun reset() {
        synchronized(lock) {
            histTotal = 0
            framesSinceScan = 0
            bestLag = -1
            releaseCount = 0
            blockedFrames = 0
            cooldownFrames = 0
            recentIn.clear()
            blocked = false
            lastCorrelation = 0.0
        }
    }

    /** Concatenation of the last up-to-4 decimated incoming frames. */
    private fun recentConcat(): ShortArray {
        val n = recentIn.sumOf { it.size }
        val out = ShortArray(n)
        var o = 0
        for (f in recentIn) { System.arraycopy(f, 0, out, o, f.size); o += f.size }
        return out
    }

    private fun decimate(frame: ShortArray): ShortArray {
        val n = frame.size / DECIM
        val out = ShortArray(n)
        for (i in 0 until n) {
            var sum = 0
            for (k in 0 until DECIM) sum += frame[i * DECIM + k]
            out[i] = (sum / DECIM).toShort()
        }
        return out
    }

    /** Record one mic frame exactly as sent to the PCs (post-processing). */
    fun pushSentMic(frame: ShortArray) {
        val d = decimate(frame)
        synchronized(lock) {
            for (v in d) {
                hist[(histTotal % hist.size).toInt()] = v
                histTotal++
            }
        }
    }

    private fun histAt(absIndex: Long): Double {
        if (absIndex < 0 || absIndex <= histTotal - hist.size || absIndex >= histTotal) return 0.0
        return hist[(absIndex % hist.size).toInt()].toDouble()
    }

    /** Normalised correlation of [dec] against sent history ending at (histTotal - lag). */
    private fun correlate(dec: ShortArray, lag: Int): Double {
        var cross = 0.0
        var eMic = 0.0
        var eIn = 0.0
        val base = histTotal - lag - dec.size
        for (i in dec.indices) {
            val h = histAt(base + i)
            val v = dec[i].toDouble()
            cross += h * v
            eMic += h * h
            eIn += v * v
        }
        if (eMic <= 0.0 || eIn <= 0.0) return 0.0
        return abs(cross) / sqrt(eMic * eIn)
    }

    private fun rms(dec: ShortArray): Double {
        var e = 0.0
        for (v in dec) e += v.toDouble() * v
        return sqrt(e / maxOf(1, dec.size)) / 32768.0
    }

    /**
     * Check one incoming speaker frame (single session, before mixing).
     * Returns true when the frame is (part of) our own returned voice and
     * must be dropped from playback.
     */
    /** Full lag scan; returns (bestLag, bestCorr) over 20 ms..1.5 s. */
    private fun scanAll(dec: ShortArray): Pair<Int, Double> {
        var bl = -1
        var bc = 0.0
        var lag = 16 // ~20 ms at 8 kHz
        while (lag <= 12_000) { // up to ~1.5 s
            val c = correlate(dec, lag)
            if (c > bc) { bc = c; bl = lag }
            lag += 4
        }
        return bl to bc
    }

    private fun release() {
        blocked = false
        blockedFrames = 0
        releaseCount = 0
    }

    fun checkIncoming(frame: ShortArray): Boolean {
        val dec = decimate(frame)
        synchronized(lock) {
            if (histTotal < dec.size * 4L) return false
            recentIn.addLast(dec)
            while (recentIn.size > 4) recentIn.removeFirst()
            // Fuse cooldown: after a forced release the guard stays out
            // of the way, so a marginal signal can never mute the
            // speaker for more than MAX_BLOCK_FRAMES at a time.
            if (cooldownFrames > 0) { cooldownFrames--; return false }
            // Entry/scan decisions run on the LONG window (last 4
            // incoming frames concatenated); see the class doc for why
            // a single frame is not enough evidence.
            val longDec = recentConcat()
            val longReady = recentIn.size >= 4
            framesSinceScan++
            if (framesSinceScan >= 20) {
                framesSinceScan = 0
                val (bl, bc) = scanAll(longDec)
                if (blocked && bc < RELEASE_SCAN_CORR) {
                    // The loop signature is gone at EVERY lag — release
                    // immediately instead of waiting on the tracked lag.
                    release()
                    return false
                }
                bestLag = bl
            }
            if (bestLag < 0) return false
            // verify at the tracked lag with a small tolerance window
            var corr = 0.0
            for (dl in intArrayOf(0, -4, 4)) {
                val c = correlate(longDec, bestLag + dl)
                if (c > corr) corr = c
            }
            lastCorrelation = corr
            if (blocked) {
                blockedFrames++
                if (blockedFrames > MAX_BLOCK_FRAMES) {
                    // Hard fuse: no detection, however confident, may
                    // silence playback indefinitely.
                    release()
                    cooldownFrames = COOLDOWN_FRAMES
                    cappedReleaseCount++
                    return false
                }
                if (corr < 0.35) {
                    if (++releaseCount >= 50) release()
                } else releaseCount = 0
                return blocked
            }
            if (longReady && corr > ENTER_CORR && rms(dec) > 0.008) {
                blocked = true
                blockedFrames = 0
                detectionCount++
                releaseCount = 0
                return true
            }
            return false
        }
    }
}

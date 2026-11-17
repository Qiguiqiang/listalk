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
 * frame is verified at that lag (± a small window). Loop = correlation
 * > 0.55 with significant incoming energy, released after it stays
 * < 0.35 for 0.5 s.
 *
 * Pure Kotlin (no Android imports) so the JVM harness exercises this
 * exact class. [reset] on any negotiated-rate change.
 */
class LoopGuard {
    companion object {
        private const val DECIM = 6
    }

    private val lock = Any()
    private val hist = ShortArray(20_000) // ~2.5 s at the 8 kHz decimated rate
    private var histTotal = 0L            // decimated samples ever pushed
    private var framesSinceScan = 0
    private var bestLag = -1              // in decimated samples
    private var releaseCount = 0

    @Volatile var blocked = false
        private set
    @Volatile var detectionCount = 0
        private set
    @Volatile var lastCorrelation = 0.0
        private set

    fun reset() {
        synchronized(lock) {
            histTotal = 0
            framesSinceScan = 0
            bestLag = -1
            releaseCount = 0
            blocked = false
            lastCorrelation = 0.0
        }
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
    fun checkIncoming(frame: ShortArray): Boolean {
        val dec = decimate(frame)
        synchronized(lock) {
            if (histTotal < dec.size * 4L) return false
            framesSinceScan++
            if (framesSinceScan >= 20) {
                framesSinceScan = 0
                var bl = -1
                var bc = 0.0
                var lag = 16 // ~20 ms at 8 kHz
                while (lag <= 12_000) { // up to ~1.5 s
                    val c = correlate(dec, lag)
                    if (c > bc) { bc = c; bl = lag }
                    lag += 4
                }
                bestLag = bl
            }
            if (bestLag < 0) return false
            // verify at the tracked lag with a small tolerance window
            var corr = 0.0
            for (dl in intArrayOf(0, -4, 4)) {
                val c = correlate(dec, bestLag + dl)
                if (c > corr) corr = c
            }
            lastCorrelation = corr
            if (blocked) {
                if (corr < 0.35) {
                    if (++releaseCount >= 50) { blocked = false; releaseCount = 0 }
                } else releaseCount = 0
                return blocked
            }
            if (corr > 0.55 && rms(dec) > 0.008) {
                blocked = true
                detectionCount++
                releaseCount = 0
                return true
            }
            return false
        }
    }
}

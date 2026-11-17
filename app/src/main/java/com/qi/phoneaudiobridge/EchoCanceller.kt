package com.qi.phoneaudiobridge

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Layer-2 echo cancellation: NLMS adaptive echo SUBTRACTION (pure Kotlin,
 * no Android imports — the same class is exercised by the JVM test in
 * tools/ so the shipped algorithm is the tested algorithm).
 *
 * Model: the phone plays a known signal (the mixed PC audio). That signal
 * reaches the microphone again after a bulk acoustic delay (speaker ->
 * room -> mic, typically 5–150 ms) plus a short dispersive tail. This
 * canceller keeps a ring of the playback reference, estimates the bulk
 * delay by cross-correlation, then runs a normalised-LMS FIR filter
 * (default 192 taps = 4 ms of tail at 48 kHz on top of the bulk delay)
 * whose output — the estimated echo — is subtracted from each mic sample
 * BEFORE the frame is sent.
 *
 * Double-talk: when the mic frame's energy is far above what the current
 * filter can explain as echo and its correlation with the estimated echo
 * is low, adaptation freezes for that frame (subtraction still applies),
 * so near-end speech does not drag the filter away.
 *
 * All sample arithmetic is mono PCM16 at the engine's current rate; the
 * class is rate-agnostic (delays/taps are in samples).
 */
class EchoCanceller(
    val taps: Int = 192,
    private val refCapacity: Int = 16384,
    private val stepSize: Double = 0.3,
    private val maxBulkDelay: Int = 6144
) {
    private val refRing = DoubleArray(refCapacity)
    @Volatile private var refTotal = 0L // total reference samples ever pushed

    private val w = DoubleArray(taps)

    /** Estimated bulk playback->mic delay, in samples. -1 until estimated. */
    @Volatile var bulkDelay: Int = -1
        private set
    @Volatile var bulkCorrelation: Double = 0.0
        private set

    /** Smoothed echo-return-loss estimate in dB (mic echo energy vs residual). */
    @Volatile var erleDb: Double = 0.0
        private set
    /** True once a bulk delay is locked and ERLE is measurably positive. */
    @Volatile var active: Boolean = false
        private set
    /** Frames frozen by the double-talk detector (diagnostics/UI honesty). */
    @Volatile var frozenFrames: Long = 0
        private set
    /** Times the divergence guard had to reset the filter (see process()). */
    @Volatile var divergenceResets: Long = 0
        private set
    @Volatile var processedFrames: Long = 0
        private set

    private var framesSinceSearch = 0
    private var erleInAcc = 0.0
    private var erleOutAcc = 0.0
    private var erleWindows = 0

    @Synchronized
    fun reset() {
        w.fill(0.0)
        refRing.fill(0.0)
        refTotal = 0
        bulkDelay = -1
        bulkCorrelation = 0.0
        erleDb = 0.0
        active = false
        frozenFrames = 0
        divergenceResets = 0
        processedFrames = 0
        framesSinceSearch = 0
        erleInAcc = 0.0; erleOutAcc = 0.0; erleWindows = 0
    }

    /** Feed exactly what was written to the speaker, in playback order. */
    @Synchronized
    fun pushReference(frame: ShortArray) {
        for (s in frame) {
            refRing[(refTotal % refCapacity).toInt()] = s.toDouble()
            refTotal++
        }
    }

    private fun refAt(totalIndex: Long): Double {
        if (totalIndex < 0 || totalIndex <= refTotal - refCapacity || totalIndex >= refTotal) return 0.0
        return refRing[(totalIndex % refCapacity).toInt()]
    }

    /**
     * Subtract the estimated echo from [frame] in place.
     * Returns the frame's estimated echo energy fraction (0..1+) for callers
     * that want a residual-echo signal (the legacy ducker uses it).
     */
    @Synchronized
    fun process(frame: ShortArray): Double {
        processedFrames++
        val n = frame.size
        // Reference index aligned with frame[0]: the newest reference sample
        // corresponds (causally) to "now"; mic sample i echoes the reference
        // from bulkDelay..bulkDelay+taps-1 samples ago.
        val frameRefEnd = refTotal // exclusive
        if (bulkDelay < 0) {
            maybeEstimateBulkDelay(frame, frameRefEnd)
            // Until a delay is locked, pass through untouched (honest: the
            // canceller reports inactive rather than pretending).
            return 0.0
        }
        // Filter window starts a few samples BEFORE the bulk delay so
        // dispersion on both sides of the main echo tap is covered.
        val lag0 = maxOf(0, bulkDelay - 8)
        var micEnergy = 0.0
        var echoEnergy = 0.0
        var crossME = 0.0
        val out = DoubleArray(n)
        val echo = DoubleArray(n)
        for (i in 0 until n) {
            val base = frameRefEnd - n + i - lag0
            var y = 0.0
            for (k in 0 until taps) y += w[k] * refAt(base - k)
            echo[i] = y
            val m = frame[i].toDouble()
            out[i] = m - y
            micEnergy += m * m
            echoEnergy += y * y
            crossME += m * y
        }
        // Divergence guard: a working canceller can only REMOVE energy.
        // With strongly self-correlated playback (music), the block-stale
        // NLMS update can go unstable and AMPLIFY — the JVM harness showed
        // output RMS hitting the clip rail on such a signal. If a frame's
        // output energy exceeds 4x its input, reset the filter and pass
        // the frame through untouched (the ducker + howl guard downstream
        // still protect the send) instead of shipping amplified garbage.
        var outEnergyRaw = 0.0
        for (v in out) outEnergyRaw += v * v
        if (micEnergy > 0.0 && outEnergyRaw > 4.0 * micEnergy) {
            w.fill(0.0)
            divergenceResets++
            return 0.0 // frame left unchanged
        }
        // Double-talk detection (tuned on the JVM harness, see tools/):
        // freeze only once the filter already explains a real share of the
        // mic (>=5% of its energy — before that there is nothing to protect
        // and adaptation must proceed), when the mic carries substantially
        // more than the explained echo (>1.8x) and the mic/echo correlation
        // is imperfect (<0.8). Echo-only frames fail the 1.8x test and keep
        // adapting; loud double-talk frames pass all three and freeze.
        val corr = if (micEnergy > 0.0 && echoEnergy > 0.0)
            abs(crossME) / sqrt(micEnergy * echoEnergy) else 0.0
        val freeze = echoEnergy > 0.05 * micEnergy && micEnergy > 1.8 * echoEnergy && corr < 0.8
        if (freeze) {
            frozenFrames++
        } else {
            // NLMS update, sample by sample, with the error RE-COMPUTED
            // against the evolving filter (serial NLMS). Using the
            // frame-start (stale) errors for all 480 updates is unstable
            // on strongly self-correlated playback — the divergence guard
            // above kept resetting the filter on music-like signals
            // (193/400 frames in the JVM harness); fresh errors converge.
            for (i in 0 until n) {
                val base = frameRefEnd - n + i - lag0
                var norm = 1e-6
                var yFresh = 0.0
                for (k in 0 until taps) {
                    val x = refAt(base - k); norm += x * x; yFresh += w[k] * x
                }
                val e = (out[i] + echo[i]) - yFresh // original mic - current estimate
                val g = stepSize * e / norm
                for (k in 0 until taps) w[k] += g * refAt(base - k)
            }
        }
        // ERLE bookkeeping on non-frozen frames with a live reference.
        if (!freeze && echoEnergy > 100.0) {
            var outEnergy = 0.0
            for (v in out) outEnergy += v * v
            erleInAcc += micEnergy; erleOutAcc += outEnergy
            if (++erleWindows >= 25) {
                if (erleOutAcc > 0.0 && erleInAcc > 0.0) {
                    val inst = 10.0 * log10(erleInAcc / erleOutAcc)
                    erleDb = if (erleWindows == 25 && erleDb == 0.0) inst else erleDb * 0.6 + inst * 0.4
                    active = erleDb > 2.0
                }
                erleInAcc = 0.0; erleOutAcc = 0.0; erleWindows = 0
            }
        }
        for (i in 0 until n) {
            frame[i] = out[i].coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        framesSinceSearch++
        return if (micEnergy > 0.0) (echoEnergy / micEnergy).coerceIn(0.0, 1.0) else 0.0
    }

    private fun maybeEstimateBulkDelay(mic: ShortArray, frameRefEnd: Long) {
        // Need at least maxBulkDelay + frame of reference history.
        if (frameRefEnd < maxBulkDelay + mic.size) return
        val n = mic.size
        // Stage 1 (coarse): correlate 9-tap moving-averaged copies of mic and
        // reference. Smoothing widens the correlation peak to ~±4 samples —
        // essential, because with a near-white reference and a short echo
        // path the raw peak is ONE sample wide and any stepped search jumps
        // over it (found by the JVM test: raw corr 0.55 at the true lag,
        // <0.1 eight samples away).
        val winStart = frameRefEnd - maxBulkDelay - n - 8
        val winLen = maxBulkDelay + n + 16
        val refWin = DoubleArray(winLen) { refAt(winStart + it) }
        val refSm = DoubleArray(winLen)
        for (i in refWin.indices) {
            var s = 0.0; var c = 0
            for (k in -4..4) {
                val j = i + k
                if (j in refWin.indices) { s += refWin[j]; c++ }
            }
            refSm[i] = s / c
        }
        val micSm = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0; var c = 0
            for (k in -4..4) {
                val j = i + k
                if (j in mic.indices) { s += mic[j]; c++ }
            }
            micSm[i] = s / c
        }
        var micSmEnergy = 0.0
        for (v in micSm) micSmEnergy += v * v
        if (micSmEnergy <= 0.0) return
        // refWin index of mic position i at lag L: (frameRefEnd - L - n + i) - winStart
        val baseIdx = (frameRefEnd - n - winStart).toInt()
        var coarseLag = -1; var coarseBest = 0.0
        var lag = 0
        while (lag <= maxBulkDelay) {
            var cross = 0.0; var refEnergy = 0.0
            val off = baseIdx - lag
            for (i in 0 until n) {
                val r = refSm[off + i]
                cross += micSm[i] * r; refEnergy += r * r
            }
            if (refEnergy > 0.0) {
                val c = abs(cross) / sqrt(micSmEnergy * refEnergy)
                if (c > coarseBest) { coarseBest = c; coarseLag = lag }
            }
            lag += 4
        }
        if (coarseLag < 0) return
        // Stage 2 (fine): raw correlation ±8 samples around the coarse peak.
        var micEnergy = 0.0
        for (v in mic) micEnergy += v.toDouble() * v
        if (micEnergy <= 0.0) return
        fun corrAt(l: Int): Double {
            var cross = 0.0; var refEnergy = 0.0
            val end = frameRefEnd - l
            for (i in 0 until n) {
                val r = refAt(end - n + i)
                cross += mic[i] * r; refEnergy += r * r
            }
            return if (refEnergy > 0.0) abs(cross) / sqrt(micEnergy * refEnergy) else 0.0
        }
        var bestLag = coarseLag; var best = 0.0
        val lo = maxOf(0, coarseLag - 8); val hi = minOf(maxBulkDelay, coarseLag + 8)
        for (l in lo..hi) {
            val c = corrAt(l)
            if (c > best) { best = c; bestLag = l }
        }
        if (best > 0.25) {
            bulkDelay = bestLag
            bulkCorrelation = best
        }
    }
}

package com.qi.phoneaudiobridge

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The LIVE capture-side echo chain (v2.1): pure Kotlin, no Android imports,
 * so the JVM harness in tools/ exercises THIS class — the exact code the
 * AudioEngine capture loop calls per mic frame — not a copy.
 *
 * Layers (all on the PHONE):
 *  1. Platform AEC/NS/AGC — lives in AudioEngine.attachEffects (Android
 *     audiofx on the VOICE_COMMUNICATION session); not part of this class.
 *  2. NLMS adaptive echo SUBTRACTION ([EchoCanceller]) fed with the exact
 *     playback signal via [pushPlayback]. This is the primary canceller.
 *  2b. Residual-echo suppressor (fallback): driven ONLY by the ESTIMATED
 *     RESIDUAL ECHO (the projection of the post-NLMS frame onto the
 *     playback reference), never by raw mic loudness. A double-talk
 *     detector (unexplained energy = frame energy minus the echo-attributed
 *     part, with ~300 ms hangover) caps suppression at -6 dB while the
 *     user is speaking, so quiet near-end speech survives loud playback;
 *     the echo-only cap is -12 dB. (v2's ducker keyed on correlation
 *     alone and ate quiet speech during double-talk — defect 3.)
 *  3. Howling guard (always on, even when suppression is toggled off):
 *     sustained loud + highly-correlated mic energy steps the send gain
 *     down 3 dB at a time (floor -18 dB) and recovers once it clears.
 *
 * Threading: [pushPlayback] is called from the playout thread,
 * [processCapture] from the capture thread; the NLMS itself is
 * synchronized, the reference deque is guarded by its own lock.
 */
class EchoProcessor(
    val canceller: EchoCanceller = EchoCanceller()
) {
    /**
     * Headset output mode (set by AudioEngine from the confirmed route):
     * echo risk is minimal with no loudspeaker in the room, so the
     * suppressor relaxes — higher engagement threshold, depth capped at
     * about -4 dB. The NLMS itself keeps running on the same reference.
     */
    @Volatile var headsetMode = false

    /**
     * Structure-borne noise control: a two-pole high-pass (two cascaded
     * one-pole sections at 70 Hz, cascade corner ~100 Hz) at the very head
     * of the capture chain — mic path ONLY, before the NLMS. Kills
     * desk-transmitted thumps/rumble (chair taps through a tabletop are
     * almost all < 80 Hz; measured -16 dB at 30 Hz) while the voice band
     * passes essentially intact (-0.5 dB at 200 Hz). The SAME filter is
     * applied to the reference copy the canceller consumes (never to the
     * audible playback), so the NLMS still models only the acoustic path
     * — filtering just one side cost ~45 dB of ERLE in the JVM harness.
     */
    @Volatile var hpfEnabled = true
    @Volatile var sampleRateHz = 48000

    private inner class HpState {
        var x1 = 0.0; var y1 = 0.0; var x2 = 0.0; var y2 = 0.0
        fun clear() { x1 = 0.0; y1 = 0.0; x2 = 0.0; y2 = 0.0 }
    }

    private val hpfMic = HpState()
    private val hpfRef = HpState()

    private fun applyHighPass(frame: ShortArray, st: HpState) {
        if (!hpfEnabled) return
        val rc = 1.0 / (2.0 * Math.PI * 70.0)
        val dt = 1.0 / sampleRateHz
        val alpha = rc / (rc + dt)
        for (i in frame.indices) {
            val x = frame[i].toDouble()
            val y1 = alpha * (st.y1 + x - st.x1)
            st.x1 = x; st.y1 = y1
            val y2 = alpha * (st.y2 + y1 - st.x2)
            st.x2 = y1; st.y2 = y2
            frame[i] = y2.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }
    data class Result(
        /** NLMS-estimated echo energy fraction of the raw frame (0..1). */
        val nlmsEchoFraction: Double,
        /** Post-subtraction residual correlation with the playback reference. */
        val residualEcho: Double,
        /** True while the fallback suppressor is pulling the mic down. */
        val duckingActive: Boolean,
        /** Suppressor gain currently applied, dB (0 or negative). */
        val duckGainDb: Float,
        /** Double-talk detector state (near-end speech present). */
        val doubleTalk: Boolean,
        /** Howling-guard gain currently applied, dB (0 or negative). */
        val howlGainDb: Float,
        /** NLMS has a bulk-delay lock and measurably positive ERLE. */
        val nlmsActive: Boolean,
        /** NLMS bulk-delay lock state (-1 = still searching). */
        val nlmsBulkDelay: Int,
        /** Smoothed NLMS ERLE estimate in dB. */
        val nlmsErleDb: Double
    )

    // timestamped copies of exactly what was played (for the suppressor)
    private val refDeque = ArrayDeque<Pair<Long, ShortArray>>()
    private var duckGain = 1f
    private var doubleTalkHold = 0
    private var loudCorrFrames = 0
    private var lastHowlStepMs = 0L
    @Volatile var howlGainDb = 0f
        private set

    fun reset() {
        canceller.reset()
        synchronized(refDeque) { refDeque.clear() }
        duckGain = 1f
        doubleTalkHold = 0
        loudCorrFrames = 0
        howlGainDb = 0f
        hpfMic.clear()
        hpfRef.clear()
    }

    /** Feed exactly what was written to the speaker, in playback order. */
    fun pushPlayback(frame: ShortArray, nowNs: Long = System.nanoTime()) {
        // The canceller/ducker consume a COPY run through the same HPF as
        // the mic path (see hpfEnabled doc); the caller's frame — the one
        // handed to AudioTrack — is never mutated.
        val refCopy = frame.copyOf()
        applyHighPass(refCopy, hpfRef)
        canceller.pushReference(refCopy)
        synchronized(refDeque) {
            refDeque.addLast(nowNs to refCopy)
            while (refDeque.size > 60) refDeque.removeFirst()
        }
    }

    /**
     * Process one captured mic frame IN PLACE (the frame is then sent).
     * [suppressionEnabled] gates layers 2/2b; layer 3 always runs.
     */
    fun processCapture(frame: ShortArray, suppressionEnabled: Boolean, nowMs: Long = System.currentTimeMillis()): Result {
        // Stage 0: high-pass (structure-borne thump/rumble removal).
        applyHighPass(frame, hpfMic)
        // Layer 2: NLMS subtraction on the timestamped playback reference.
        val nlmsFraction = if (suppressionEnabled) canceller.process(frame) else 0.0

        // Residual estimate for the fallback ducker + howl guard.
        val refRmsOut = DoubleArray(1)
        val corr = estimateResidualEcho(frame, refRmsOut)

        var micEnergy = 0.0
        for (v in frame) micEnergy += v.toDouble() * v
        val micRms = sqrt(micEnergy / maxOf(1, frame.size)) / 32768.0

        // ---- double-talk detection (defect 3) ----
        // The echo-attributed part of the post-NLMS frame is its projection
        // onto the reference: estEchoRms = corr * micRms. Whatever energy is
        // left unexplained is near-end speech (or noise) and must survive.
        val estEchoRms = corr * micRms
        val unexplainedRms = sqrt(maxOf(0.0, micRms * micRms - estEchoRms * estEchoRms))
        if (unexplainedRms > 0.006 && corr < 0.6) doubleTalkHold = 30 // ~300 ms hangover
        else if (doubleTalkHold > 0) doubleTalkHold--
        val doubleTalk = doubleTalkHold > 0

        // Layer 2b: residual-echo suppressor. The target gain would push
        // the ESTIMATED ECHO component down to the unexplained floor —
        // speech above that floor is untouched by construction — and the
        // depth is hard-capped: -12 dB echo-only, -6 dB during double-talk.
        if (suppressionEnabled) {
            val floorGain = when {
                headsetMode -> 0.63f   // ~-4 dB cap: headset echo risk is minimal
                doubleTalk -> 0.5f     // -6 dB cap while the user speaks
                else -> 0.25f          // -12 dB cap, echo only
            }
            val engageThreshold = if (headsetMode) 0.03 else 0.01
            val target = if (estEchoRms > engageThreshold && micRms > 1e-6) {
                maxOf(floorGain, minOf(1.0, (unexplainedRms + 0.004) / micRms).toFloat())
            } else 1.0f
            val rate = if (target < duckGain) 0.6f else 0.04f
            duckGain += (target - duckGain) * rate
        } else {
            duckGain = 1f
        }
        val ducking = duckGain < 0.9f

        // Layer 3: howling guard (always on — a runaway loop must never persist).
        if (micRms > 0.45 && corr > 0.8) {
            loudCorrFrames++
        } else {
            loudCorrFrames = 0
        }
        if (loudCorrFrames >= 30 && nowMs - lastHowlStepMs > 500) {
            howlGainDb = maxOf(-18f, howlGainDb - 3f)
            lastHowlStepMs = nowMs
            loudCorrFrames = 0
        } else if (howlGainDb < 0f && corr < 0.5 && nowMs - lastHowlStepMs > 2000) {
            howlGainDb = minOf(0f, howlGainDb + 1f)
            lastHowlStepMs = nowMs
        }

        val howlLinear = Math.pow(10.0, howlGainDb / 20.0).toFloat()
        val gain = duckGain * howlLinear
        if (gain < 0.999f) {
            for (i in frame.indices) {
                frame[i] = (frame[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return Result(
            nlmsEchoFraction = nlmsFraction,
            residualEcho = corr,
            duckingActive = ducking,
            duckGainDb = (20.0 * kotlin.math.log10(maxOf(duckGain, 1e-4f).toDouble())).toFloat(),
            doubleTalk = doubleTalk,
            howlGainDb = howlGainDb,
            nlmsActive = canceller.active,
            nlmsBulkDelay = canceller.bulkDelay,
            nlmsErleDb = canceller.erleDb
        )
    }

    /**
     * Correlate the mic frame against the timestamped playback reference at
     * lags 0..5 frames (acoustic delay). Returns the best normalised
     * correlation 0..1 and the reference RMS via [refRmsOut].
     * (Moved verbatim from the v2 AudioEngine ducker.)
     */
    private fun estimateResidualEcho(mic: ShortArray, refRmsOut: DoubleArray): Double {
        val nowNs = System.nanoTime()
        val refs: List<ShortArray>
        synchronized(refDeque) {
            while (true) {
                val head = refDeque.peekFirst() ?: break
                if (nowNs - head.first > 400_000_000L) refDeque.removeFirst() else break
            }
            refs = refDeque.map { it.second }.reversed()
        }
        if (refs.isEmpty()) { refRmsOut[0] = 0.0; return 0.0 }
        var micEnergy = 0.0
        for (v in mic) micEnergy += v.toDouble() * v
        if (micEnergy <= 0.0) { refRmsOut[0] = 0.0; return 0.0 }
        var best = 0.0
        var bestRefRms = 0.0
        val maxLag = minOf(5, refs.size - 1)
        for (lag in 0..maxLag) {
            val ref = refs[lag]
            val n = minOf(mic.size, ref.size)
            var cross = 0.0
            var refEnergy = 0.0
            for (i in 0 until n) {
                cross += mic[i].toDouble() * ref[i]
                refEnergy += ref[i].toDouble() * ref[i]
            }
            if (refEnergy <= 0.0) continue
            val corr = abs(cross) / sqrt(micEnergy * refEnergy)
            if (corr > best) {
                best = corr
                bestRefRms = sqrt(refEnergy / n) / 32768.0
            }
        }
        refRmsOut[0] = bestRefRms
        return best.coerceIn(0.0, 1.0)
    }
}

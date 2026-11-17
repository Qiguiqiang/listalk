import com.qi.phoneaudiobridge.EchoProcessor
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FB-8 scenarios for the PC-side loop detector, run against the
 * line-for-line Kotlin port of LoopDetector.cs (tools/PcLoopDetector.kt)
 * — the sandbox has no Windows runtime for the C# original.
 *
 * The detector is fed exactly as BridgeEngine feeds it: every 10 ms
 * speaker frame (fed even while paused — the frame is dropped, not
 * sent) and every received mic frame, with evaluate() once per second.
 *
 *  (a) Music playout with AEC residual, modelled exactly as the
 *      documented field condition (speaker-no-sound-fix.md §2 R3:
 *      post-chain residual ~-26 dB + room noise — the construction of
 *      the shipped v2.2 phone-side acoustic scenario). Plus (a2): the
 *      same detector fed by the REAL shipped EchoProcessor chain
 *      processing music-like content end to end. Neither may pause.
 *  (b) Genuine unprocessed feedback loop (mic = the speaker stream
 *      returned electrically at 120 ms): must engage fast, stay
 *      engaged while the loop persists, and auto-resume once it ends.
 *  (c) Marginal hovering correlation while paused (above the release
 *      bar, below the entry bar): the probe-resume failsafe must end
 *      the pause — residual-level correlation may not mute output
 *      indefinitely — and must not re-engage afterwards.
 */

private const val RATE = 48000
private const val FRAME = 480 // 10 ms

private class Lcg(seed: Long) {
    private var s = seed
    fun next(): Double {
        s = (s * 1103515245L + 12345L) and 0x7fffffffL
        return s / 1073741824.0 - 1.0
    }
}

/** Strongly periodic programme material (the hard case): a chord
 *  progression changing every 5 s under a slow tremolo. */
private fun progAt(p: Int): Double {
    val t = p / RATE.toDouble()
    val roots = doubleArrayOf(196.0, 164.8, 220.0, 146.8)
    val root = roots[(p / (RATE * 5)) % roots.size]
    val trem = 0.85 + 0.15 * sin(2.0 * PI * 0.7 * t)
    return ((sin(2.0 * PI * root * t) +
        0.7 * sin(2.0 * PI * root * 4.0 / 3.0 * t) +
        0.5 * sin(2.0 * PI * root * 2.0 * t)) / 2.2 * 16000.0) * trem
}

/** Fixed chord — the exact programme material of the shipped v2.2
 *  phone-side acoustic scenario, for comparability of the numbers. */
private fun fixedChordAt(p: Int): Double {
    val t = p / RATE.toDouble()
    return (sin(2.0 * PI * 196.0 * t) +
        0.7 * sin(2.0 * PI * 262.0 * t) +
        0.5 * sin(2.0 * PI * 392.0 * t)) / 2.2 * 16000.0
}

private fun bytesOf(frame: ShortArray): ByteArray {
    val out = ByteArray(frame.size * 2)
    for (i in frame.indices) {
        out[i * 2] = (frame[i].toInt() and 0xFF).toByte()
        out[i * 2 + 1] = ((frame[i].toInt() shr 8) and 0xFF).toByte()
    }
    return out
}

fun main() {
    // ---------- (a) music + AEC residual (documented field model) ----------
    // The field condition from speaker-no-sound-fix.md §2 R3: the phone
    // mic hears the programme only acoustically and what reaches the PC
    // is the post-echo-chain residual (~-26 dB) plus independent room
    // noise. Same construction as the shipped v2.2 phone-side acoustic
    // scenario (fixed chord, residual x0.05 at 120 ms, noise x2600).
    run {
        val det = PcLoopDetector()
        val rng = Lcg(0xAEC01L)
        var noiseSm = 0.0
        var everPaused = false
        var peakCorr = 0.0
        val seconds = 40
        for (f in 0 until seconds * 100) {
            val spk = ShortArray(FRAME) { fixedChordAt(f * FRAME + it).toInt().toShort() }
            val mic = ShortArray(FRAME) { i ->
                val p = f * FRAME + i
                noiseSm = noiseSm * 0.8 + rng.next() * 0.2
                (fixedChordAt(p - 5760) * 0.05 + noiseSm * 2600.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
            }
            det.feedSpeakerFrame(bytesOf(spk))
            det.feedMicFrame(bytesOf(mic))
            if (f % 100 == 99) {
                det.evaluate()
                if (det.loopDetected) everPaused = true
                if (det.lastCorrelation > peakCorr) peakCorr = det.lastCorrelation
            }
        }
        println("JVM pcloop-residual: everPaused=$everPaused peakCorr=${"%.2f".format(peakCorr)} over ${seconds}s (entry bar 0.85 x3 / fast path 0.97)")
        check(!everPaused) { "PC loop detector paused output on music + AEC residual (peakCorr=$peakCorr)" }
    }

    // ---------- (a2) music-like content through the REAL EchoProcessor ----------
    // End-to-end: the shipped phone chain (NLMS + suppressor) processes
    // an acoustic echo of music-like content (chord + broadband body,
    // TestMain-style dispersed echo path); its processed output is the
    // uplink the PC detector sees. Must never pause.
    run {
        val det = PcLoopDetector()
        val proc = EchoProcessor()
        val bodyGen = Lcg(0xB0D1L)
        var bodySm = 0.0
        val total = FRAME * 4000
        val body = DoubleArray(total)
        for (i in 0 until total) { bodySm = bodySm * 0.86 + bodyGen.next() * 0.14; body[i] = bodySm * 3.2 }
        fun musicAt(p: Int): Double = if (p in 0 until total) progAt(p) * 0.75 + body[p] * 9000.0 else 0.0
        fun echoAt(p: Int): Double {
            fun r(idx: Int) = musicAt(idx)
            val d = p - 300
            return 0.55 * (0.25 * r(d - 1) + 0.5 * r(d) + 0.25 * r(d + 1))
        }
        val rng = Lcg(0xAEC02L)
        var noiseSm = 0.0
        var everPaused = false
        var peakCorr = 0.0
        for (f in 0 until 4000) {
            val spk = ShortArray(FRAME) { musicAt(f * FRAME + it).toInt().toShort() }
            val mic = ShortArray(FRAME) { i ->
                val p = f * FRAME + i
                noiseSm = noiseSm * 0.8 + rng.next() * 0.2
                (echoAt(p) + noiseSm * 2600.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
            }
            proc.pushPlayback(spk)
            proc.processCapture(mic, suppressionEnabled = true, nowMs = f * 10L)
            det.feedSpeakerFrame(bytesOf(spk))
            det.feedMicFrame(bytesOf(mic))
            if (f % 100 == 99) {
                det.evaluate()
                if (det.loopDetected) everPaused = true
                if (det.lastCorrelation > peakCorr) peakCorr = det.lastCorrelation
            }
        }
        println("JVM pcloop-livechain: everPaused=$everPaused peakCorr=${"%.2f".format(peakCorr)} over 40s (NLMS erle=${"%.1f".format(proc.canceller.erleDb)} dB, bulkDelay=${proc.canceller.bulkDelay})")
        check(!everPaused) { "PC loop detector paused output on the live AEC chain output (peakCorr=$peakCorr)" }
    }

    // ---------- (b) genuine electric loop -> engage, hold, auto-resume ----------
    run {
        val det = PcLoopDetector()
        val rng = Lcg(0xB0B5L)
        var noiseSm = 0.0
        fun noise(): Double {
            noiseSm = noiseSm * 0.8 + rng.next() * 0.2
            return noiseSm * 2600.0
        }
        val delay = 5760 // 120 ms
        var engageEval = -1
        var releaseEval = -1
        var heldThroughout = true
        var evalIdx = -1
        val totalFrames = 3000 // 30 s
        for (f in 0 until totalFrames) {
            val spk = ShortArray(FRAME) { progAt(f * FRAME + it).toInt().toShort() }
            val mic = ShortArray(FRAME) { i ->
                val p = f * FRAME + i
                val v = when {
                    f < 500 -> noise()                                   // no loop
                    f < 2000 -> (if (p - delay >= 0) progAt(p - delay) * 0.9 else 0.0) + noise() * 0.3
                    else -> noise()                                      // loop removed
                }
                v.coerceIn(-32768.0, 32767.0).toInt().toShort()
            }
            det.feedSpeakerFrame(bytesOf(spk))
            det.feedMicFrame(bytesOf(mic))
            if (f % 100 == 99) {
                evalIdx++
                det.evaluate()
                if (det.loopDetected && engageEval < 0 && f >= 500) engageEval = evalIdx
                if (f >= 2000 && !det.loopDetected && releaseEval < 0) releaseEval = evalIdx
                if (f in 700..1999 && engageEval >= 0 && !det.loopDetected) heldThroughout = false
            }
        }
        // evals: phase2 starts at eval 5 (f=500..599), ends at eval 19; phase3 from eval 20
        println("JVM pcloop-loop: engageEval=$engageEval (phase2 first eval=5) releaseEval=$releaseEval (phase3 first eval=20) held=$heldThroughout logs=${det.logs}")
        check(engageEval in 5..6) { "genuine loop not engaged fast: engageEval=$engageEval" }
        check(heldThroughout) { "genuine loop pause did not hold while the loop persisted" }
        check(releaseEval in 20..23) { "no auto-resume after loop ended: releaseEval=$releaseEval" }
    }

    // ---------- (c) hovering marginal correlation -> probe-resume ----------
    run {
        val det = PcLoopDetector()
        val rng = Lcg(0xC0DEL)
        var noiseSm = 0.0
        fun noise(scale: Double): Double {
            noiseSm = noiseSm * 0.8 + rng.next() * 0.2
            return noiseSm * scale
        }
        val delay = 5760
        var engageEval = -1
        var probeReleaseEval = -1
        var evalIdx = -1
        var hoverCorrSum = 0.0
        var hoverCorrN = 0
        val totalFrames = 4500 // 45 s: 5 s hard loop, then 40 s hover
        for (f in 0 until totalFrames) {
            val spk = ShortArray(FRAME) { progAt(f * FRAME + it).toInt().toShort() }
            val mic = ShortArray(FRAME) { i ->
                val p = f * FRAME + i
                val v = if (f < 500) {
                    (if (p - delay >= 0) progAt(p - delay) * 0.9 else 0.0) + noise(800.0)
                } else {
                    // hover: coherent return well under the entry bar but
                    // above the release bar (target corr ~0.65)
                    (if (p - delay >= 0) progAt(p - delay) * 0.30 else 0.0) + noise(12400.0)
                }
                v.coerceIn(-32768.0, 32767.0).toInt().toShort()
            }
            det.feedSpeakerFrame(bytesOf(spk))
            det.feedMicFrame(bytesOf(mic))
            if (f % 100 == 99) {
                evalIdx++
                val wasDetected = det.loopDetected
                det.evaluate()
                if (det.loopDetected && engageEval < 0) engageEval = evalIdx
                if (f >= 500 && det.loopDetected) { hoverCorrSum += det.lastCorrelation; hoverCorrN++ }
                if (wasDetected && !det.loopDetected && det.probeResumeCount > 0 && probeReleaseEval < 0) {
                    probeReleaseEval = evalIdx
                }
            }
        }
        val hoverAvg = if (hoverCorrN > 0) hoverCorrSum / hoverCorrN else 0.0
        println("JVM pcloop-probe: engageEval=$engageEval probeReleaseEval=$probeReleaseEval probeResumes=${det.probeResumeCount} hoverAvgCorr=${"%.2f".format(hoverAvg)} endDetected=${det.loopDetected}")
        check(engageEval in 0..6) { "probe scenario never engaged on the hard loop: engageEval=$engageEval" }
        check(probeReleaseEval > 0) { "probe-resume never fired under hovering correlation" }
        check(probeReleaseEval - 5 <= 22) { "probe-resume took too long: released at eval $probeReleaseEval" }
        check(!det.loopDetected) { "detector re-engaged / stayed paused after probe-resume under sub-entry correlation" }
    }

    println("PC LOOP OK")
}

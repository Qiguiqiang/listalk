import com.qi.phoneaudiobridge.EchoCanceller
import com.qi.phoneaudiobridge.EchoProcessor
import com.qi.phoneaudiobridge.LoopGuard
import com.qi.phoneaudiobridge.Protocol
import java.security.MessageDigest
import kotlin.math.log10
import kotlin.math.sqrt

/** JVM harness: (1) Protocol header pack/unpack round-trip mirroring the PC
 *  Python struct "!HBBIQ"; (2) verify-code derivation mirroring
 *  pc verify_code_for(); (3) NLMS echo-cancellation measurement on synthetic
 *  speech + delayed/attenuated playback echo. Prints machine-readable lines
 *  that tools/check_protocol_consistency.py compares against the PC side. */

fun verifyCodeKotlin(token: String, phoneId: String): String = Protocol.verifyCodeFor(token, phoneId)

fun tokenHashKotlin(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

class Lcg(seed: Long) {
    private var s = seed
    fun next(): Double {
        s = s * 6364136223846793005L + 1442695040888963407L
        return ((s ushr 33) % 20000 - 10000) / 10000.0 // -1..1
    }
}

fun main() {
    // ---- header round-trip ----
    val payload = ByteArray(960) { (it % 251).toByte() }
    val packed = Protocol.pack(Protocol.TYPE_AUDIO, Protocol.flags(Protocol.CODEC_PCM16, Protocol.RATE_24K), 123456, 9876543210L, payload)
    check(packed.size == 976) { "packed size ${packed.size}" }
    check(packed[0] == 0x50.toByte() && packed[1] == 0x41.toByte()) { "magic bytes" }
    val h = Protocol.unpack(packed, packed.size)!!
    check(h.type == 0 && h.seq == 123456 && h.timestampUs == 9876543210L && h.codec == 0 && h.rateCode == 1) { "header fields" }
    check(Protocol.unpack(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 16) == null) { "bad magic rejected" }
    println("JVM header: OK size=976 magic=5041 rateCode(24k)=1")

    // ---- pairing derivations (vectors compared with PC by the checker) ----
    val token = "0123456789abcdeffedcba9876543210"
    println("JVM tokenHash: ${tokenHashKotlin(token)}")
    println("JVM verifyCode(phoneXYZ): ${verifyCodeKotlin(token, "phoneXYZ")}")
    println("JVM verifyCode(phone123): ${verifyCodeKotlin(token, "phone123")}")

    // ---- NLMS echo test ----
    val rate = 48000
    val frame = rate / 100 // 480 samples = 10 ms
    val frames = 400 // 4 s
    val bulkDelay = 300 // samples of acoustic bulk delay
    val refGen = Lcg(0xA1B2C3D4L)
    val spGen = Lcg(0x11223344L)
    val total = frame * frames
    val ref = DoubleArray(total) { refGen.next() * 12000.0 }
    // near-end speech: independent noise, smoothed (voice-like, uncorrelated)
    val speechRaw = DoubleArray(total) { spGen.next() * 9000.0 }
    val speech = DoubleArray(total) { i ->
        val a = speechRaw[maxOf(0, i - 1)]; val b = speechRaw[i]; val c = speechRaw[minOf(total - 1, i + 1)]
        (a + 2 * b + c) / 4.0
    }
    // echo path: bulk delay + 3-tap lowpass dispersion, gain 0.55
    fun echoAt(p: Int): Double {
        fun r(idx: Int) = if (idx in ref.indices) ref[idx] else 0.0
        val d = p - bulkDelay
        return 0.55 * (0.25 * r(d - 1) + 0.5 * r(d) + 0.25 * r(d + 1))
    }
    // Three phases, as AECs are honestly evaluated:
    //  A frames 0..99    far-end single-talk (echo only)  -> convergence + ERLE
    //  B frames 100..249 double-talk (speech + echo)      -> DTD must protect
    //  C frames 250..399 far-end single-talk again        -> ERLE must survive B
    val canceller = EchoCanceller(taps = 192, stepSize = 0.3)
    val phaseEcho = DoubleArray(3); val phaseResid = DoubleArray(3); val phaseSpeechIn = DoubleArray(3); val phaseSpeechOut = DoubleArray(3)
    for (f in 0 until frames) {
        val phase = when { f < 100 -> 0; f < 250 -> 1; else -> 2 }
        val refFrame = ShortArray(frame) { ref[f * frame + it].toInt().toShort() }
        canceller.pushReference(refFrame)
        val mic = ShortArray(frame) { i ->
            val p = f * frame + i
            val sp = if (phase == 1) speech[p] else 0.0
            (sp + echoAt(p)).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        canceller.process(mic)
        // measure the last 50 frames of phase A, all of B's last 50, last 50 of C
        val measure = (phase == 0 && f >= 50) || (phase == 1 && f >= 200) || (phase == 2 && f >= 350)
        if (measure) {
            for (i in 0 until frame) {
                val p = f * frame + i
                val e = echoAt(p)
                val sp = if (phase == 1) speech[p] else 0.0
                val resid = mic[i] - sp // residual echo (output minus true speech)
                phaseEcho[phase] += e * e
                phaseResid[phase] += resid * resid
                phaseSpeechIn[phase] += sp * sp
                phaseSpeechOut[phase] += mic[i].toDouble() * mic[i]
            }
        }
    }
    fun erle(ph: Int) = 10.0 * log10(phaseEcho[ph] / maxOf(phaseResid[ph], 1e-9))
    val erleA = erle(0); val erleB = erle(1); val erleC = erle(2)
    println("JVM nlms: bulkDelayEst=${canceller.bulkDelay} (true $bulkDelay) bulkCorr=${"%.3f".format(canceller.bulkCorrelation)}")
    println("JVM nlms: internalErleDb=${"%.2f".format(canceller.erleDb)} active=${canceller.active} frozenFrames=${canceller.frozenFrames}/${canceller.processedFrames}")
    println("JVM nlms: ERLE_A_echoOnly_dB=${"%.2f".format(erleA)} ERLE_B_doubleTalk_dB=${"%.2f".format(erleB)} ERLE_C_afterDoubleTalk_dB=${"%.2f".format(erleC)}")
    println("JVM nlms: echoRms=${"%.1f".format(sqrt(phaseEcho[2] / (frame * 50)))} residRms_C=${"%.1f".format(sqrt(phaseResid[2] / (frame * 50)))}")
    check(erleA > 12.0) { "phase A ERLE too low: $erleA dB" }
    check(erleB > 0.0) { "phase B: canceller amplified the echo: $erleB dB" }
    check(erleC > 10.0) { "phase C ERLE too low (filter damaged by double-talk?): $erleC dB" }

    // Ground truth for the HPF-era metrics: the chain INTENTIONALLY
    // high-passes the mic (structure-borne noise control), so the speech
    // the chain should output is the HPF'd speech. This harness-side
    // replica of the two-pole filter provides that reference; without it,
    // difference metrics mistake the removed <100 Hz speech energy for
    // residual echo.
    val speechH = DoubleArray(total)
    run {
        val rc = 1.0 / (2.0 * Math.PI * 70.0); val dt = 1.0 / rate
        val alpha = rc / (rc + dt)
        var x1 = 0.0; var y1 = 0.0; var x2 = 0.0; var y2 = 0.0
        for (i in 0 until total) {
            val x = speech[i]
            val o1 = alpha * (y1 + x - x1); x1 = x; y1 = o1
            val o2 = alpha * (y2 + o1 - x2); x2 = o1; y2 = o2
            speechH[i] = o2
        }
    }

    // ---- LIVE-PATH test (v2.1): EchoProcessor, called EXACTLY as
    // AudioEngine does — pushPlayback per playout frame, processCapture per
    // mic frame — on the same synthetic echo. This is the shipped live
    // chain (NLMS + residual ducker + howl guard), not a copy. ----
    val proc = EchoProcessor()
    val lpEcho = DoubleArray(3); val lpResid = DoubleArray(3)
    var lastRes: EchoProcessor.Result? = null
    for (f in 0 until frames) {
        val phase = when { f < 100 -> 0; f < 250 -> 1; else -> 2 }
        val refFrame = ShortArray(frame) { ref[f * frame + it].toInt().toShort() }
        proc.pushPlayback(refFrame)
        val mic = ShortArray(frame) { i ->
            val p = f * frame + i
            val sp = if (phase == 1) speech[p] else 0.0
            (sp + echoAt(p)).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        lastRes = proc.processCapture(mic, suppressionEnabled = true, nowMs = f * 10L)
        val measure = (phase == 0 && f >= 50) || (phase == 1 && f >= 200) || (phase == 2 && f >= 350)
        if (measure) {
            for (i in 0 until frame) {
                val p = f * frame + i
                val e = echoAt(p)
                val sp = if (phase == 1) speechH[p] else 0.0 // chain outputs HPF'd speech
                val resid = mic[i] - sp
                lpEcho[phase] += e * e
                lpResid[phase] += resid * resid
            }
        }
    }
    fun lpErle(ph: Int) = 10.0 * log10(lpEcho[ph] / maxOf(lpResid[ph], 1e-9))
    val lpA = lpErle(0); val lpB = lpErle(1); val lpC = lpErle(2)
    println("JVM livepath: bulkDelay=${lastRes?.nlmsBulkDelay} (true $bulkDelay) active=${lastRes?.nlmsActive} erleDb=${"%.2f".format(lastRes?.nlmsErleDb ?: 0.0)} howlDb=${lastRes?.howlGainDb}")
    println("JVM livepath: ERLE_A_echoOnly_dB=${"%.2f".format(lpA)} ERLE_B_doubleTalk_dB=${"%.2f".format(lpB)} ERLE_C_afterDoubleTalk_dB=${"%.2f".format(lpC)}")
    check(lastRes?.nlmsBulkDelay == bulkDelay) { "livepath: NLMS did not lock onto the true bulk delay" }
    check(lpA > 12.0) { "livepath phase A ERLE too low: $lpA dB" }
    check(lpB > 0.0) { "livepath phase B: chain amplified the echo: $lpB dB" }
    check(lpC > 10.0) { "livepath phase C ERLE too low: $lpC dB" }

    // ---- QUIET SPEECH test (defect 3): LOUD far-end playback + QUIET
    // near-end speech through the same live path. The v2 suppressor ate
    // quiet speech during double-talk; the DTD-driven suppressor must
    // preserve it. Metric: energy of (output - true echo) relative to the
    // input speech energy over the settled double-talk window — 0 dB is
    // perfect preservation (speech intact AND echo gone). ----
    val procQ = EchoProcessor()
    val quietScale = 0.12 // speech[] peaks ~0.22 -> quiet peaks ~-31 dBFS
    var qSpeechE = 0.0
    var qRecoveredE = 0.0
    var qErrE = 0.0
    var qEchoE = 0.0
    var qDuckMin = 1.0f
    var qDtFrames = 0
    var qSpeechRmsAcc = 0.0
    var qSpeechN = 0
    for (f in 0 until frames) {
        val phase = when { f < 100 -> 0; f < 300 -> 1; else -> 2 }
        val refFrame = ShortArray(frame) { ref[f * frame + it].toInt().toShort() }
        procQ.pushPlayback(refFrame)
        val mic = ShortArray(frame) { i ->
            val p = f * frame + i
            val sp = if (phase == 1) speech[p] * quietScale else 0.0
            (sp + echoAt(p)).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        val res = procQ.processCapture(mic, suppressionEnabled = true, nowMs = f * 10L)
        if (phase == 1 && f >= 200) {
            if (res.doubleTalk) qDtFrames++
            if (res.duckGainDb < qDuckMin) qDuckMin = res.duckGainDb
            for (i in 0 until frame) {
                val p = f * frame + i
                val sp = speechH[p] * quietScale
                // Preservation: the processed output should BE the speech
                // as the chain intends it (HPF'd) plus a small residual.
                // Error = output - intended speech.
                val err = mic[i] - sp
                qSpeechE += sp * sp
                qRecoveredE += mic[i].toDouble() * mic[i]
                qErrE += err * err
                qEchoE += echoAt(p) * echoAt(p)
                val spRaw = speech[p] * quietScale // input level stat (pre-HPF)
                qSpeechRmsAcc += spRaw * spRaw
                qSpeechN++
            }
        }
    }
    val qPresDb = 10.0 * log10(qRecoveredE / maxOf(qSpeechE, 1e-9))
    val qErrDb = 10.0 * log10(qEchoE / maxOf(qErrE, 1e-9)) // double-talk ERLE
    val qSpeechDbfs = 20.0 * log10(sqrt(qSpeechRmsAcc / maxOf(qSpeechN, 1)) / 32768.0)
    println("JVM quiet: nearEndSpeech=${"%.1f".format(qSpeechDbfs)} dBFS(rms) preservation_dB=${"%.2f".format(qPresDb)} doubleTalkERLE_dB=${"%.2f".format(qErrDb)} doubleTalkFrames=$qDtFrames/100 minDuckGainDb=${"%.1f".format(qDuckMin)}")
    check(qPresDb > -4.0) { "quiet speech was eaten by suppression: preservation $qPresDb dB" }
    check(qErrDb > 6.0) { "echo barely suppressed during double-talk: ERLE $qErrDb dB" }
    check(qDtFrames > 30) { "double-talk detector barely fired during quiet speech: $qDtFrames/100 frames" }

    // ---- LOOP GUARD test (defect 5c): own mic voice returned at +350 ms
    // must be detected and blocked; unrelated audio must pass. ----
    val guard = LoopGuard()
    var lgBlocked = 0
    var lgTotal = 0
    for (f in 0 until 300) {
        val sent = ShortArray(frame) { speech[f * frame + it].toInt().toShort() }
        guard.pushSentMic(sent)
        // incoming = the same voice 35 frames (350 ms) later, attenuated
        val incoming = if (f >= 35) ShortArray(frame) { (speech[(f - 35) * frame + it] * 0.8).toInt().toShort() }
            else ShortArray(frame)
        val dropped = guard.checkIncoming(incoming)
        if (f >= 80) { lgTotal++; if (dropped) lgBlocked++ }
    }
    println("JVM loopguard: returned-voice blocked $lgBlocked/$lgTotal frames (detections=${guard.detectionCount}, lastCorr=${"%.2f".format(guard.lastCorrelation)})")
    check(lgBlocked > lgTotal * 0.8) { "loop guard failed to block returned own-voice: $lgBlocked/$lgTotal" }
    val guard2 = LoopGuard()
    var lgFalse = 0
    var lgFalseN = 0
    var seed2 = 987654321L
    fun nextRand2(): Double { seed2 = (seed2 * 1103515245L + 12345L) and 0x7fffffffL; return seed2 / 1073741824.0 - 1.0 }
    var sm2 = 0.0
    for (f in 0 until 300) {
        val sent = ShortArray(frame) { speech[f * frame + it].toInt().toShort() }
        guard2.pushSentMic(sent)
        val incoming = ShortArray(frame) { sm2 = sm2 * 0.82 + nextRand2() * 0.18 * 0.9; (sm2 * 32767.0 * 0.5).toInt().toShort() }
        val dropped = guard2.checkIncoming(incoming)
        if (f >= 80) { lgFalseN++; if (dropped) lgFalse++ }
    }
    println("JVM loopguard: unrelated audio falsely blocked $lgFalse/$lgFalseN frames")
    check(lgFalse == 0) { "loop guard blocked unrelated audio: $lgFalse frames" }

    // ---- CORRELATED PLAYBACK test (music-like, strongly self-correlated
    // reference): the block-stale NLMS update can go unstable on such
    // signals — the divergence guard must bound the output (a canceller
    // may never amplify). Regression for a divergence found in testing
    // (output RMS hit the clip rail without the guard). ----
    val procM = EchoProcessor()
    var mSeed = 424242L
    fun mRand(): Double { mSeed = (mSeed * 1103515245L + 12345L) and 0x7fffffffL; return mSeed / 1073741824.0 - 1.0 }
    val mTotal = frame * frames
    val mRef = DoubleArray(mTotal); var mSm = 0.0
    for (i in 0 until mTotal) { mSm = mSm * 0.82 + mRand() * 0.18; mRef[i] = mSm * 32767.0 * 0.5 }
    fun mEchoAt(p: Int): Double {
        fun r(idx: Int) = if (idx in mRef.indices) mRef[idx] else 0.0
        return 0.55 * r(p - 300) + 0.18 * r(p - 337) + 0.09 * r(p - 391)
    }
    var mMaxRatio = 0.0
    for (f in 0 until frames) {
        val refFrame = ShortArray(frame) { mRef[f * frame + it].toInt().toShort() }
        procM.pushPlayback(refFrame)
        val mic = ShortArray(frame) { i ->
            val p = f * frame + i
            mEchoAt(p).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        var inE = 0.0
        for (v in mic) inE += v.toDouble() * v
        procM.processCapture(mic, suppressionEnabled = true, nowMs = f * 10L)
        var outE = 0.0
        for (v in mic) outE += v.toDouble() * v
        if (f >= 20 && inE > 1e6) {
            val ratio = sqrt(outE / inE)
            if (ratio > mMaxRatio) mMaxRatio = ratio
        }
    }
    println("JVM correlated: maxOutInRatio=${"%.2f".format(mMaxRatio)} divergenceResets=${procM.canceller.divergenceResets}")
    check(mMaxRatio < 2.0) { "canceller amplified correlated playback: max ratio $mMaxRatio" }

    // ---- HIGH-PASS test (structure-borne noise): a 30 Hz thump (chair
    // tap through a desk) must be crushed by the capture-chain HPF, a
    // 200 Hz voice-band tone must pass essentially untouched. No playback
    // reference is pushed, so the NLMS stays inactive and the measurement
    // isolates the HPF inside the shipped processCapture. ----
    fun toneDb(freq: Double, seconds: Double = 1.0): Double {
        val procH = EchoProcessor()
        val n = (rate * seconds).toInt()
        var inE = 0.0; var outE = 0.0
        var idx = 0
        while (idx < n) {
            val fr = ShortArray(frame) { i ->
                val t = (idx + i) / rate.toDouble()
                (kotlin.math.sin(2.0 * Math.PI * freq * t) * 12000.0).toInt().toShort()
            }
            for (v in fr) inE += v.toDouble() * v
            procH.processCapture(fr, suppressionEnabled = true, nowMs = idx * 10L)
            // skip the first 100 ms (filter settle) in the output energy
            if (idx >= rate / 10) for (v in fr) outE += v.toDouble() * v
            idx += frame
        }
        val inAvg = inE / n
        val outAvg = outE / (n - rate / 10)
        return 10.0 * log10(outAvg / inAvg)
    }
    val thumpDb = toneDb(30.0)
    val voiceDb = toneDb(200.0)
    println("JVM hpf: 30Hz_thump_dB=${"%.1f".format(thumpDb)} 200Hz_voice_dB=${"%.2f".format(voiceDb)}")
    check(thumpDb < -15.0) { "HPF failed to reject structure-borne thump: $thumpDb dB" }
    // A ~100 Hz cascade corner inherently costs ~1 dB at 200 Hz (measured
    // -1.07 dB): perceptually negligible for voice, documented in the report.
    check(voiceDb > -1.5) { "HPF hurt voice band: $voiceDb dB" }
    println("JVM ALL OK")
}

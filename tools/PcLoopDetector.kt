import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Line-for-line Kotlin port of the PC-side loop detector
 * (windows-native/AirBridge/LoopDetector.cs, FB-8 graded handling) so
 * the JVM harness can exercise the PC-side guard's exact algorithm —
 * the sandbox has no Windows runtime for the C# original. Constants,
 * windowing and the state machine are identical; keep them in sync
 * when editing either side (the C# file carries the design rationale).
 *
 * API mirrors the C#: feed PCM16-LE frames (feedMicFrame /
 * feedSpeakerFrame), call evaluate() ~1/s, read loopDetected /
 * lastCorrelation / probeResumeCount. Log lines are collected in
 * [logs] instead of an event.
 */
class PcLoopDetector {
    companion object {
        private const val DECIM = 6
        private const val HIST_LEN = 20000
        private const val FRAME_LEN = 320
        private const val ENTER_CORR = 0.85
        private const val HOWL_CORR = 0.97
        private const val ENTER_HITS = 3
        private const val RELEASE_CORR = 0.50
        private const val RELEASE_HITS = 2
        private const val MIN_HOLD_EVALS = 2
        private const val PROBE_RESUME_EVALS = 20
        private const val PROBE_RESUME_MAX_CORR = 0.85
    }

    private val micHist = ShortArray(HIST_LEN)
    private var micTotal = 0L
    private val spkHist = ShortArray(HIST_LEN)
    private var spkTotal = 0L
    private var hits = 0
    private var pausedEvals = 0
    private var lowEvals = 0
    private val pausedCorrs = ArrayDeque<Double>()

    var loopDetected = false
        private set
    var lastCorrelation = 0.0
        private set
    var probeResumeCount = 0
        private set
    val logs = mutableListOf<String>()

    fun reset() {
        micTotal = 0
        spkTotal = 0
        hits = 0
        pausedEvals = 0
        lowEvals = 0
        pausedCorrs.clear()
        loopDetected = false
        lastCorrelation = 0.0
    }

    private fun decimate(pcm16: ByteArray): ShortArray {
        val n = pcm16.size / 2 / DECIM
        val out = ShortArray(n)
        for (i in 0 until n) {
            var sum = 0
            for (k in 0 until DECIM) {
                val idx = (i * DECIM + k) * 2
                // C# bytes are unsigned: reconstruct the LE Int16 exactly
                // as the C# (short)(lo | (hi << 8)) does.
                val s = ((pcm16[idx].toInt() and 0xFF) or
                    ((pcm16[idx + 1].toInt() and 0xFF) shl 8)).toShort()
                sum += s
            }
            out[i] = (sum / DECIM).toShort()
        }
        return out
    }

    fun feedMicFrame(pcm16: ByteArray) {
        val d = decimate(pcm16)
        for (v in d) {
            micHist[(micTotal % micHist.size).toInt()] = v
            micTotal++
        }
    }

    fun feedSpeakerFrame(pcm16: ByteArray) {
        val d = decimate(pcm16)
        for (v in d) {
            spkHist[(spkTotal % spkHist.size).toInt()] = v
            spkTotal++
        }
    }

    private fun micAt(absIndex: Long): Double {
        if (absIndex < 0 || absIndex <= micTotal - micHist.size || absIndex >= micTotal) return 0.0
        return micHist[(absIndex % micHist.size).toInt()].toDouble()
    }

    private fun spkAt(absIndex: Long): Double {
        if (absIndex < 0 || absIndex <= spkTotal - spkHist.size || absIndex >= spkTotal) return 0.0
        return spkHist[(absIndex % spkHist.size).toInt()].toDouble()
    }

    private fun correlate(frame: ShortArray, lag: Int): Double {
        var cross = 0.0
        var eMic = 0.0
        var eIn = 0.0
        val baseIdx = micTotal - lag - frame.size
        for (i in frame.indices) {
            val h = micAt(baseIdx + i)
            val v = frame[i].toDouble()
            cross += h * v
            eMic += h * h
            eIn += v * v
        }
        if (eMic <= 0.0 || eIn <= 0.0) return 0.0
        return abs(cross) / sqrt(eMic * eIn)
    }

    private fun engage(corr: Double) {
        loopDetected = true
        hits = 0
        pausedEvals = 0
        lowEvals = 0
        pausedCorrs.clear()
        logs.add("[loop] engaged corr=${"%.2f".format(corr)}")
    }

    private fun release(probe: Boolean) {
        loopDetected = false
        hits = 0
        pausedEvals = 0
        lowEvals = 0
        pausedCorrs.clear()
        if (probe) {
            probeResumeCount++
            logs.add("[loop] released via probe-resume")
        } else {
            logs.add("[loop] released (return gone)")
        }
    }

    fun evaluate() {
        if (micTotal < FRAME_LEN + 960 || spkTotal < FRAME_LEN) return
        val frame = ShortArray(FRAME_LEN) { spkAt(spkTotal - FRAME_LEN + it).toInt().toShort() }
        var inE = 0.0
        for (v in frame) inE += v.toDouble() * v
        val rms = sqrt(inE / frame.size) / 32768.0
        val silent = rms <= 0.008
        var best = 0.0
        if (!silent) {
            var lag = 16
            while (lag <= 9600) {
                val c = correlate(frame, lag)
                if (c > best) best = c
                lag += 4
            }
            lastCorrelation = best
        }
        if (silent && !loopDetected) {
            hits = 0
            return
        }
        if (!loopDetected) {
            if (best >= HOWL_CORR) {
                engage(best)
            } else if (best >= ENTER_CORR) {
                hits++
                if (hits >= ENTER_HITS) engage(best)
            } else {
                hits = 0
            }
            return
        }
        pausedEvals++
        pausedCorrs.addLast(best)
        while (pausedCorrs.size > 5) pausedCorrs.removeFirst()
        if (best < RELEASE_CORR) lowEvals++ else lowEvals = 0
        if (pausedEvals >= MIN_HOLD_EVALS && lowEvals >= RELEASE_HITS) {
            release(probe = false)
        } else if (pausedEvals >= PROBE_RESUME_EVALS && pausedCorrs.size >= 5 &&
            (pausedCorrs.maxOrNull() ?: 0.0) < PROBE_RESUME_MAX_CORR
        ) {
            release(probe = true)
        }
    }
}

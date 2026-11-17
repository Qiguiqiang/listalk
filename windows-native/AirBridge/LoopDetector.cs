namespace AirBridge;

/// <summary>
/// PC-side loop detector (defect 5b runtime net): correlates the speaker
/// stream we SEND to the phone against the mic stream the phone sent us.
/// If the PC's capture source contains the phone-mic render (same-cable
/// twin, loopback of the mic device, a monitor/mix path), the phone's own
/// voice comes back with a network delay and the correlation at that lag
/// spikes. While detected, BridgeEngine drops outgoing speaker frames
/// and the UI warns — the structural fix is the routing exclusion in
/// AudioService.RoutingConflict; this detector catches the rest.
///
/// FB-8 graded handling (v2.2.3): the v2.2.2 detector used a single
/// 80-sample window, a 0.6 entry bar and a 0.35 release bar. On real
/// programme material that is a false-pause machine: the phone's AEC
/// (NLMS on the phone) attenuates its own speaker echo by tens of dB,
/// but normalised correlation is LEVEL-BLIND — a coherent −26 dB
/// residual of strongly periodic music still correlates, the max over
/// ~2400 lags inflates it further, the pause engages, and the residual
/// then hovers at 0.4–0.5, never under the 0.35 release bar, so PC →
/// phone sound stays muted (Gray's field report: paused at corr 0.40).
/// The phone-side LoopGuard learned this lesson in v2.2 (long window);
/// this detector now applies the same cure plus graded state handling:
///
///  * LONG WINDOW: correlation runs over the last 320 decimated speaker
///    samples (40 ms at 8 kHz), not 80 — fluctuation narrows ~2×, so
///    AEC-residual peaks land far below a true electric loop (~1.00).
///  * SUSTAINED ENTRY: engage after 3 consecutive evaluations ≥ 0.85,
///    or immediately on a single evaluation ≥ 0.97 (a genuine electric
///    loop correlates ~1.00; howling protection stays instant — and
///    the phone's own howl guard is a second, always-on layer).
///  * HYSTERESIS: release needs correlation &lt; 0.50 on 2 consecutive
///    evaluations, with a minimum 2-evaluation hold — the wide
///    0.50…0.85 dead band stops pause/resume flapping.
///  * AUTO-RESUME PROBING: feeding and evaluating CONTINUE while
///    paused (BridgeEngine feeds before the pause check), so a pause
///    is self-probing — the moment the phone's mic stops returning our
///    content, the correlation collapses and we resume. As a failsafe,
///    a pause held for 20 evaluations whose recent correlation never
///    reached the hard-loop band (max of last 5 &lt; 0.85) is released
///    anyway: residual-level correlation may never mute output
///    indefinitely. A genuine loop re-engages via the fast path within
///    one evaluation of resuming.
///
/// The JVM harness exercises a line-for-line Kotlin port of this class
/// (tools/PcLoopDetector.kt + tools/TestLoopPc.kt) with the FB-8
/// scenarios; keep constants and control flow in sync when editing.
/// </summary>
public sealed class LoopDetector
{
    private const int Decim = 6;
    private const int HistLen = 20000;      // ~2.5 s at 8 kHz
    private const int FrameLen = 320;       // long window: 40 ms at 8 kHz
    private const double EnterCorr = 0.85;  // sustained-entry bar (long window)
    private const double HowlCorr = 0.97;   // single-eval fast path (true electric loop)
    private const int EnterHits = 3;        // consecutive evals at/above EnterCorr
    private const double ReleaseCorr = 0.50;
    private const int ReleaseHits = 2;      // consecutive evals below ReleaseCorr
    private const int MinHoldEvals = 2;     // anti-flap minimum pause length
    private const int ProbeResumeEvals = 20;
    private const double ProbeResumeMaxCorr = 0.85;

    private readonly object _lock = new();
    private readonly short[] _micHist = new short[HistLen];
    private long _micTotal;
    private readonly short[] _spkHist = new short[HistLen];
    private long _spkTotal;
    private int _hits;
    private int _pausedEvals;
    private int _lowEvals;
    private readonly Queue<double> _pausedCorrs = new();

    public bool LoopDetected { get; private set; }
    public double LastCorrelation { get; private set; }
    /** Times the probe-resume failsafe released a long pause (diagnostics). */
    public int ProbeResumeCount { get; private set; }
    public event Action<string>? Log;

    public void Reset()
    {
        lock (_lock)
        {
            _micTotal = 0;
            _spkTotal = 0;
            _hits = 0;
            _pausedEvals = 0;
            _lowEvals = 0;
            _pausedCorrs.Clear();
            LoopDetected = false;
            LastCorrelation = 0;
        }
    }

    private static short[] Decimate(byte[] pcm16)
    {
        int n = pcm16.Length / 2 / Decim;
        var outp = new short[n];
        for (int i = 0; i < n; i++)
        {
            int sum = 0;
            for (int k = 0; k < Decim; k++)
            {
                int idx = (i * Decim + k) * 2;
                sum += (short)(pcm16[idx] | (pcm16[idx + 1] << 8));
            }
            outp[i] = (short)(sum / Decim);
        }
        return outp;
    }

    /// <summary>Feed one received phone-mic frame (PCM16, engine rate).</summary>
    public void FeedMicFrame(byte[] pcm16)
    {
        var d = Decimate(pcm16);
        lock (_lock)
        {
            foreach (var v in d)
            {
                _micHist[_micTotal % _micHist.Length] = v;
                _micTotal++;
            }
        }
    }

    /// <summary>Feed one speaker frame about to be sent to the phone.
    /// Called even while paused (the frame is dropped, not sent), which
    /// is what makes a pause self-probing.</summary>
    public void FeedSpeakerFrame(byte[] pcm16)
    {
        var d = Decimate(pcm16);
        lock (_lock)
        {
            foreach (var v in d)
            {
                _spkHist[_spkTotal % _spkHist.Length] = v;
                _spkTotal++;
            }
        }
    }

    private double MicAt(long absIndex)
    {
        if (absIndex < 0 || absIndex <= _micTotal - _micHist.Length || absIndex >= _micTotal) return 0;
        return _micHist[absIndex % _micHist.Length];
    }

    private double SpkAt(long absIndex)
    {
        if (absIndex < 0 || absIndex <= _spkTotal - _spkHist.Length || absIndex >= _spkTotal) return 0;
        return _spkHist[absIndex % _spkHist.Length];
    }

    private double Correlate(short[] frame, int lag)
    {
        double cross = 0, eMic = 0, eIn = 0;
        long baseIdx = _micTotal - lag - frame.Length;
        for (int i = 0; i < frame.Length; i++)
        {
            double h = MicAt(baseIdx + i);
            double v = frame[i];
            cross += h * v;
            eMic += h * h;
            eIn += v * v;
        }
        if (eMic <= 0 || eIn <= 0) return 0;
        return Math.Abs(cross) / Math.Sqrt(eMic * eIn);
    }

    private void Engage(double corr)
    {
        LoopDetected = true;
        _hits = 0;
        _pausedEvals = 0;
        _lowEvals = 0;
        _pausedCorrs.Clear();
        Log?.Invoke($"[环路] 检测到手机麦克风声音被电脑采集源回传（相关度 {corr:F2}），已暂停向手机发送电脑声音，请改选采集设备");
    }

    private void Release(bool probe)
    {
        LoopDetected = false;
        _hits = 0;
        _pausedEvals = 0;
        _lowEvals = 0;
        _pausedCorrs.Clear();
        if (probe)
        {
            ProbeResumeCount++;
            Log?.Invoke("[环路] 长时间暂停后回传相关度未达硬环路水平，自动恢复发送电脑声音（若仍有回传将再次暂停）");
        }
        else
        {
            Log?.Invoke("[环路] 回传已消失，恢复发送电脑声音");
        }
    }

    /// <summary>Called ~1/s from the engine adapt loop.</summary>
    public void Evaluate()
    {
        lock (_lock)
        {
            if (_micTotal < FrameLen + 960 || _spkTotal < FrameLen) return;
            var frame = new short[FrameLen];
            for (int i = 0; i < FrameLen; i++) frame[i] = (short)SpkAt(_spkTotal - FrameLen + i);
            double inE = 0;
            foreach (var v in frame) inE += (double)v * v;
            double rms = Math.Sqrt(inE / frame.Length) / 32768.0;
            bool silent = rms <= 0.008;
            double best = 0;
            if (!silent)
            {
                for (int lag = 16; lag <= 9600; lag += 4)
                {
                    double c = Correlate(frame, lag);
                    if (c > best) best = c;
                }
                LastCorrelation = best;
            }
            if (silent && !LoopDetected)
            {
                // Near-silence builds no entry evidence.
                _hits = 0;
                return;
            }
            // While paused, near-silence counts as a low evaluation:
            // nothing is being sent, so nothing can be coming back —
            // the pause must not latch on a stale verdict.
            if (!LoopDetected)
            {
                if (best >= HowlCorr)
                {
                    Engage(best);
                }
                else if (best >= EnterCorr)
                {
                    _hits++;
                    if (_hits >= EnterHits) Engage(best);
                }
                else
                {
                    _hits = 0;
                }
                return;
            }
            // Paused: keep scoring the (unsent) stream against the mic —
            // the pause is the probe.
            _pausedEvals++;
            _pausedCorrs.Enqueue(best);
            while (_pausedCorrs.Count > 5) _pausedCorrs.Dequeue();
            if (best < ReleaseCorr) _lowEvals++; else _lowEvals = 0;
            if (_pausedEvals >= MinHoldEvals && _lowEvals >= ReleaseHits)
            {
                Release(probe: false);
            }
            else if (_pausedEvals >= ProbeResumeEvals && _pausedCorrs.Count >= 5 && _pausedCorrs.Max() < ProbeResumeMaxCorr)
            {
                Release(probe: true);
            }
        }
    }
}

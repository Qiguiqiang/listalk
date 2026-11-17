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
/// Same method as the phone's LoopGuard: both streams box-decimated x6
/// (tolerant of resampling alignment error), coarse lag scan 20 ms..1.2 s
/// evaluated once per second, two consecutive hits to engage, release
/// when the correlation stays low.
/// </summary>
public sealed class LoopDetector
{
    private const int Decim = 6;
    private readonly object _lock = new();
    private readonly short[] _micHist = new short[20000]; // ~2.5 s at 8 kHz
    private long _micTotal;
    private readonly List<short> _spkPending = new();
    private int _hits;

    public bool LoopDetected { get; private set; }
    public double LastCorrelation { get; private set; }
    public event Action<string>? Log;

    public void Reset()
    {
        lock (_lock)
        {
            _micTotal = 0;
            _spkPending.Clear();
            _hits = 0;
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

    /// <summary>Feed one speaker frame about to be sent to the phone.</summary>
    public void FeedSpeakerFrame(byte[] pcm16)
    {
        var d = Decimate(pcm16);
        lock (_lock)
        {
            _spkPending.AddRange(d);
            while (_spkPending.Count > 160) _spkPending.RemoveAt(0); // keep last ~20 ms x10
        }
    }

    private double MicAt(long absIndex)
    {
        if (absIndex < 0 || absIndex <= _micTotal - _micHist.Length || absIndex >= _micTotal) return 0;
        return _micHist[absIndex % _micHist.Length];
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

    /// <summary>Called ~1/s from the engine adapt loop.</summary>
    public void Evaluate()
    {
        lock (_lock)
        {
            if (_micTotal < 400 || _spkPending.Count < 80) return;
            var frame = _spkPending.GetRange(_spkPending.Count - 80, 80).ToArray();
            double inE = 0;
            foreach (var v in frame) inE += (double)v * v;
            double rms = Math.Sqrt(inE / frame.Length) / 32768.0;
            double best = 0;
            for (int lag = 16; lag <= 9600; lag += 4)
            {
                double c = Correlate(frame, lag);
                if (c > best) best = c;
            }
            LastCorrelation = best;
            if (best > 0.6 && rms > 0.008)
            {
                _hits++;
                if (_hits >= 2 && !LoopDetected)
                {
                    LoopDetected = true;
                    Log?.Invoke($"[环路] 检测到手机麦克风声音被电脑采集源回传（相关度 {best:F2}），已暂停向手机发送电脑声音，请改选采集设备");
                }
            }
            else if (best < 0.35)
            {
                _hits = 0;
                if (LoopDetected)
                {
                    LoopDetected = false;
                    Log?.Invoke("[环路] 回传已消失，恢复发送电脑声音");
                }
            }
        }
    }
}

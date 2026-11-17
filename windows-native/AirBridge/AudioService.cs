using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace AirBridge;

public sealed class AudioDeviceInfo
{
    public required string Id { get; init; }          // endpoint id; loopback entries are "loopback:<renderId>"
    public required string Name { get; init; }
    public required bool IsLoopback { get; init; }
    public int Score { get; init; }
    public bool IsDefault { get; init; }
    public override string ToString() => Name;
}

/// <summary>Continuous linear resampler: mono float in at inRate -> PCM16 frames out at outRate.</summary>
public sealed class StreamResampler
{
    private readonly List<float> _buf = new();
    private double _readPos; // fractional index into _buf
    private readonly object _lock = new();
    public int InRate { get; set; }
    public int OutRate { get; set; }

    public StreamResampler(int inRate, int outRate) { InRate = inRate; OutRate = outRate; }
    public void Clear() { lock (_lock) { _buf.Clear(); _readPos = 0; } }

    public void Push(ReadOnlySpan<float> mono)
    {
        lock (_lock)
        {
            foreach (var f in mono) _buf.Add(f);
            // keep buffer bounded (~500 ms)
            int max = InRate / 2;
            if (_buf.Count > max)
            {
                int drop = _buf.Count - max;
                _buf.RemoveRange(0, drop);
                _readPos = Math.Max(0, _readPos - drop);
            }
        }
    }

    /// <summary>One 10 ms PCM16 frame, or null if not enough input yet.</summary>
    public byte[]? ReadFrame()
    {
        lock (_lock)
        {
            int outSamples = OutRate / 100;
            double ratio = (double)InRate / OutRate;
            double need = _readPos + (outSamples - 1) * ratio + 1;
            if (_buf.Count < need) return null;
            var bytes = new byte[outSamples * 2];
            for (int i = 0; i < outSamples; i++)
            {
                double pos = _readPos + i * ratio;
                int i0 = (int)pos;
                double frac = pos - i0;
                float a = _buf[i0];
                float b = i0 + 1 < _buf.Count ? _buf[i0 + 1] : a;
                float v = (float)(a + (b - a) * frac);
                short s = (short)Math.Clamp(v * 32767f, short.MinValue, short.MaxValue);
                bytes[i * 2] = (byte)(s & 0xFF);
                bytes[i * 2 + 1] = (byte)((s >> 8) & 0xFF);
            }
            _readPos += outSamples * ratio;
            int whole = (int)_readPos;
            if (whole > 0) { _buf.RemoveRange(0, Math.Min(whole, _buf.Count)); _readPos -= whole; }
            return bytes;
        }
    }
}

/// <summary>IWaveProvider that pulls phone-mic frames from the jitter buffer (silence on underrun).</summary>
public sealed class JitterWaveProvider : IWaveProvider
{
    private readonly BridgeEngine _engine;
    public WaveFormat WaveFormat { get; }
    private byte[]? _cur;
    private int _curOff;

    public JitterWaveProvider(BridgeEngine engine, int rateCode)
    {
        _engine = engine;
        WaveFormat = new WaveFormat(Protocol.RateHz(rateCode), 16, 1);
    }

    public int Read(byte[] buffer, int offset, int count)
    {
        int written = 0;
        while (written < count)
        {
            if (_cur is null || _curOff >= _cur.Length)
            {
                _cur = (_engine.Connected && !_engine.Paused && _engine.Settings.MicToPc)
                    ? _engine.Jb.Pop() : null;
                _curOff = 0;
                if (_cur is not null) UpdateLevel(_cur);
            }
            if (_cur is null)
            {
                // conceal with silence
                int n = count - written;
                Array.Clear(buffer, offset + written, n);
                written += n;
                break;
            }
            int take = Math.Min(_cur.Length - _curOff, count - written);
            Array.Copy(_cur, _curOff, buffer, offset + written, take);
            _curOff += take; written += take;
        }
        return count;
    }

    private void UpdateLevel(byte[] frame)
    {
        double sum = 0; int n = frame.Length / 2;
        for (int i = 0; i < n; i++)
        {
            short s = (short)(frame[i * 2] | (frame[i * 2 + 1] << 8));
            sum += (double)s * s;
        }
        double rms = n > 0 ? Math.Sqrt(sum / n) / 32768.0 : 0;
        var st = _engine.Stats;
        st.LevelOut = Math.Max(Math.Min(1.0, rms * 3.0), st.LevelOut * 0.72);
    }
}

/// <summary>
/// WASAPI audio via NAudio — the native replacement for the Python sounddevice layer.
///  - Render: phone mic -> selected render endpoint (VB-CABLE Input preferred, Python scoring).
///  - Capture: selected capture endpoint, or WASAPI loopback of a render endpoint
///    (VB-CABLE Output / system output) -> phone.
/// </summary>
public sealed class AudioService : IDisposable
{
    private readonly BridgeEngine _engine;
    private IWaveIn? _capture;
    private WasapiOut? _playout;
    private StreamResampler? _resampler;
    private bool _running;
    private int _builtRate = -1;

    public AudioDeviceInfo? CurrentInput { get; private set; }
    public AudioDeviceInfo? CurrentOutput { get; private set; }
    public string Status { get; private set; } = "未启动";
    public event Action<string>? Log;

    /// <summary>True when the selected capture source structurally contains
    /// the phone-mic render: capture runs but NOTHING is sent to the phone
    /// until the user picks a clean source (defect 5b).</summary>
    public bool CaptureBlocked { get; private set; }
    public string? RoutingWarning { get; private set; }

    /// <summary>
    /// Structural loop check (defect 5b): does capturing from [input]
    /// necessarily include what we render (the phone mic) into [output]?
    /// Two hard cases: (1) loopback OF the very render endpoint the mic
    /// plays into; (2) the two ends of the SAME single cable — with one
    /// VB-CABLE, the mic renders into "CABLE Input" and everything on it
    /// reappears at "CABLE Output", which the default scoring prefers as
    /// the system-sound capture. That default pairing WAS the v2 loop.
    /// </summary>
    public static string? RoutingConflict(AudioDeviceInfo input, AudioDeviceInfo output)
    {
        if (input.IsLoopback && input.Id == "loopback:" + output.Id)
            return Loc.Tr("采集源是手机麦克风输出设备的环回——手机的声音会被原样发回手机");
        string inp = input.Name.Replace("（环回）", "").Replace(" (Loopback)", "").ToLowerInvariant();
        string outp = output.Name.ToLowerInvariant();
        bool inCable = inp.Contains("cable output");
        bool outCable = outp.Contains("cable input");
        if (inCable && outCable)
            return Loc.Tr("采集源与手机麦克风共用同一条虚拟线缆（CABLE Output ← CABLE Input）——手机麦克风会混入电脑声音被发回");
        return null;
    }

    public AudioService(BridgeEngine engine)
    {
        _engine = engine;
        _engine.RateChanged += _ => { if (_running) Task.Run(Restart); };
    }

    public (List<AudioDeviceInfo> inputs, List<AudioDeviceInfo> outputs) Enumerate()
    {
        var inputs = new List<AudioDeviceInfo>();
        var outputs = new List<AudioDeviceInfo>();
        using var en = new MMDeviceEnumerator();
        string defIn = "", defOut = "";
        try { defIn = en.GetDefaultAudioEndpoint(DataFlow.Capture, Role.Multimedia).ID; } catch { }
        try { defOut = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia).ID; } catch { }
        foreach (var d in en.EnumerateAudioEndPoints(DataFlow.Capture, DeviceState.Active))
            inputs.Add(new AudioDeviceInfo { Id = d.ID, Name = d.FriendlyName, IsLoopback = false, Score = DeviceScoring.InputScore(d.FriendlyName), IsDefault = d.ID == defIn });
        foreach (var d in en.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
        {
            outputs.Add(new AudioDeviceInfo { Id = d.ID, Name = d.FriendlyName, IsLoopback = false, Score = DeviceScoring.OutputScore(d.FriendlyName), IsDefault = d.ID == defOut });
            inputs.Add(new AudioDeviceInfo { Id = "loopback:" + d.ID, Name = d.FriendlyName + Loc.T("（环回）", " (Loopback)"), IsLoopback = true, Score = DeviceScoring.InputScore(d.FriendlyName + " loopback"), IsDefault = false });
        }
        return (inputs, outputs);
    }

    public AudioDeviceInfo PickInput(List<AudioDeviceInfo> inputs, string? savedId)
        => PickInput(inputs, savedId, null);

    /// <summary>Auto-pick that never lands on the capture twin of the chosen
    /// mic-render device (a saved user choice is still honoured — Start()
    /// guards it with a warning + suppression instead).</summary>
    public AudioDeviceInfo PickInput(List<AudioDeviceInfo> inputs, string? savedId, AudioDeviceInfo? avoidTwinOf)
    {
        if (savedId is not null && inputs.FirstOrDefault(d => d.Id == savedId) is { } saved) return saved;
        var pool = avoidTwinOf is null ? inputs
            : inputs.Where(d => RoutingConflict(d, avoidTwinOf) is null).ToList();
        if (pool.Count == 0) pool = inputs;
        var scored = pool.Where(d => d.Score > 0).OrderByDescending(d => d.Score).FirstOrDefault();
        return scored ?? pool.FirstOrDefault(d => d.IsDefault) ?? pool[0];
    }
    public AudioDeviceInfo PickOutput(List<AudioDeviceInfo> outputs, string? savedId)
    {
        if (savedId is not null && outputs.FirstOrDefault(d => d.Id == savedId) is { } saved) return saved;
        var scored = outputs.Where(d => d.Score > 0).OrderByDescending(d => d.Score).FirstOrDefault();
        return scored ?? outputs.FirstOrDefault(d => d.IsDefault) ?? outputs[0];
    }

    private static MMDevice FindDevice(string id)
    {
        using var en = new MMDeviceEnumerator();
        return en.GetDevice(id);
    }

    public void Start(AudioDeviceInfo input, AudioDeviceInfo output)
    {
        StopInternal();
        CurrentInput = input; CurrentOutput = output;
        // Defect 5b enforcement: a capture source that contains the mic
        // render is blocked from sending (with a loud warning), never
        // silently looped back to the phone.
        RoutingWarning = RoutingConflict(input, output);
        CaptureBlocked = RoutingWarning is not null;
        if (CaptureBlocked)
            Log?.Invoke($"[路由警告] {RoutingWarning}。已阻止把该采集源发给手机，请改选采集设备（例如扬声器环回）。");
        int rateCode = _engine.RateCode;
        // --- capture (PC sound -> phone) ---
        string capId = input.IsLoopback ? input.Id["loopback:".Length..] : input.Id;
        var capDevice = FindDevice(capId);
        _capture = input.IsLoopback ? new WasapiLoopbackCapture(capDevice) : new WasapiCapture(capDevice, true, 20);
        var fmt = _capture.WaveFormat;
        _resampler = new StreamResampler(fmt.SampleRate, Protocol.RateHz(rateCode));
        _capture.DataAvailable += OnCaptureData;
        _capture.RecordingStopped += (_, e) => { if (e.Exception is not null) Log?.Invoke($"[采集] 停止：{e.Exception.Message}"); };
        _capture.StartRecording();
        // --- playout (phone mic -> PC output) ---
        var outDevice = FindDevice(output.Id);
        _playout = new WasapiOut(outDevice, AudioClientShareMode.Shared, true, 60);
        _playout.Init(new JitterWaveProvider(_engine, rateCode));
        _playout.Play();
        _builtRate = rateCode;
        _running = true;
        Status = $"采集中：{input.Name} / 播放：{output.Name}";
        Log?.Invoke($"[设备] 电脑声音采集（发给手机）: {input.Name}{(input.Score > 0 ? $"（自动识别虚拟设备 score={input.Score}）" : "")}");
        Log?.Invoke($"[设备] 手机麦克风播放（给其他应用当麦）: {output.Name}{(output.Score > 0 ? $"（自动识别虚拟设备 score={output.Score}）" : "")}");
    }

    private void OnCaptureData(object? sender, WaveInEventArgs e)
    {
        if (_resampler is null || e.BytesRecorded == 0) return;
        if (CaptureBlocked)
        { _resampler.Clear(); return; } // contaminated source: never sent (5b)
        if (!_engine.Connected || _engine.Paused || !_engine.Settings.PcToPhone)
        { _resampler.Clear(); return; }
        var fmt = _capture!.WaveFormat;
        var mono = DecodeMono(e.Buffer, e.BytesRecorded, fmt);
        if (mono.Length == 0) return;
        // level (pre-resample, like Python's per-block RMS)
        double sum = 0; foreach (var f in mono) sum += (double)f * f;
        double rms = Math.Sqrt(sum / mono.Length);
        var st = _engine.Stats;
        st.LevelIn = Math.Max(Math.Min(1.0, rms * 3.0), st.LevelIn * 0.72);
        _resampler.Push(mono);
        while (_resampler.ReadFrame() is { } frame)
            _engine.SendAudioFrame(frame);
    }

    private static float[] DecodeMono(byte[] buf, int bytes, WaveFormat fmt)
    {
        int ch = Math.Max(1, fmt.Channels);
        var outList = new List<float>();
        if (fmt.Encoding == WaveFormatEncoding.IeeeFloat && fmt.BitsPerSample == 32)
        {
            int frames = bytes / 4 / ch;
            for (int i = 0; i < frames; i++)
            {
                float acc = 0;
                for (int c = 0; c < ch; c++) acc += BitConverter.ToSingle(buf, (i * ch + c) * 4);
                outList.Add(acc / ch);
            }
        }
        else if (fmt.BitsPerSample == 16)
        {
            int frames = bytes / 2 / ch;
            for (int i = 0; i < frames; i++)
            {
                float acc = 0;
                for (int c = 0; c < ch; c++) acc += (short)(buf[(i * ch + c) * 2] | (buf[(i * ch + c) * 2 + 1] << 8)) / 32768f;
                outList.Add(acc / ch);
            }
        }
        else if (fmt.BitsPerSample == 24)
        {
            int frames = bytes / 3 / ch;
            for (int i = 0; i < frames; i++)
            {
                float acc = 0;
                for (int c = 0; c < ch; c++)
                {
                    int o = (i * ch + c) * 3;
                    int v = buf[o] | (buf[o + 1] << 8) | (buf[o + 2] << 16);
                    if ((v & 0x800000) != 0) v |= unchecked((int)0xFF000000);
                    acc += v / 8388608f;
                }
                outList.Add(acc / ch);
            }
        }
        else if (fmt.BitsPerSample == 32) // PCM int32
        {
            int frames = bytes / 4 / ch;
            for (int i = 0; i < frames; i++)
            {
                float acc = 0;
                for (int c = 0; c < ch; c++) acc += BitConverter.ToInt32(buf, (i * ch + c) * 4) / 2147483648f;
                outList.Add(acc / ch);
            }
        }
        return outList.ToArray();
    }

    private void Restart()
    {
        if (CurrentInput is null || CurrentOutput is null) return;
        try { Start(CurrentInput, CurrentOutput); }
        catch (Exception ex) { Log?.Invoke($"[音频] 采样率切换重启失败：{ex.Message}"); }
    }

    private void StopInternal()
    {
        try { _capture?.StopRecording(); } catch { }
        try { _capture?.Dispose(); } catch { }
        _capture = null;
        try { _playout?.Stop(); } catch { }
        try { _playout?.Dispose(); } catch { }
        _playout = null;
        _running = false;
    }

    public void Stop() { StopInternal(); Status = "已停止"; }
    public void Dispose() => StopInternal();
}

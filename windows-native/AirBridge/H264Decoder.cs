using System.Diagnostics;

namespace AirBridge;

/// <summary>
/// H.264 decoder for the camera-relay preview (wave 4, stage 1): an
/// ffmpeg SUBPROCESS fed Annex-B access units on stdin, emitting raw
/// BGRA frames on stdout.
///
/// Why a subprocess (the stage-1 decode-path decision):
///  - Zero new dependencies and no native linking: the app stays
///    Apache-2.0 clean; ffmpeg is a separate program (aggregation, not
///    a derivative work), located via the SOUNDRELAY_FFMPEG env var or
///    PATH. In this Linux sandbox the system ffmpeg 8.1.2 proves the
///    path end to end.
///  - Weight/licensing for a future Windows package: an LGPL-built
///    ffmpeg.exe (e.g. the BtbN LGPL variant) is on the order of tens
///    of MB — a packaging decision for stage 2, NOT taken here. (The
///    widely mirrored "essentials" builds are GPL and must not ship.)
///  - The production plan (§B.4) decodes with Media Foundation on
///    Windows for the virtual-camera path; MF does not exist in this
///    sandbox, so it cannot be the stage-1 path — stage 2 swaps the
///    decoder behind this same interface (Start/Write/FrameDecoded).
///
/// Frames are delivered on the decoder's reader thread; consumers must
/// copy if they retain a frame. If ffmpeg is missing or dies, the
/// decoder reports via LastError and simply produces no frames — the
/// audio path is never affected.
/// </summary>
public sealed class H264Decoder : IDisposable
{
    /// <summary>Raised per decoded frame: (bgra bytes, width, height, frame index).</summary>
    public event Action<byte[], int, int, long>? FrameDecoded;

    public long FramesDecoded { get; private set; }
    public int Width { get; private set; }
    public int Height { get; private set; }
    public bool Running { get; private set; }
    public string? LastError { get; private set; }
    public string? FfmpegPath { get; }

    private Process? _proc;
    private Thread? _reader;
    private readonly object _writeLock = new();
    private volatile bool _stopping;

    public H264Decoder()
    {
        FfmpegPath = FindFfmpeg();
    }

    public static string? FindFfmpeg()
    {
        var env = Environment.GetEnvironmentVariable("SOUNDRELAY_FFMPEG");
        if (!string.IsNullOrEmpty(env) && File.Exists(env)) return env;
        string exe = OperatingSystem.IsWindows() ? "ffmpeg.exe" : "ffmpeg";
        var pathVar = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (var dir in pathVar.Split(Path.PathSeparator))
        {
            if (string.IsNullOrEmpty(dir)) continue;
            var candidate = Path.Combine(dir, exe);
            if (File.Exists(candidate)) return candidate;
        }
        return null;
    }

    /// <summary>Start decoding a stream of the given size. [csdAnnexB]
    /// (SPS+PPS from VIDEO_CONFIG) is written before any access unit.</summary>
    public bool Start(int width, int height, byte[]? csdAnnexB)
    {
        Stop();
        if (FfmpegPath is null)
        {
            LastError = "ffmpeg not found (set SOUNDRELAY_FFMPEG or install ffmpeg)";
            return false;
        }
        if (width <= 0 || height <= 0) { LastError = "invalid frame size"; return false; }
        Width = width; Height = height;
        _stopping = false;
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = FfmpegPath,
                UseShellExecute = false,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
            };
            psi.ArgumentList.Add("-loglevel"); psi.ArgumentList.Add("error");
            // NB: do NOT add "-fflags nobuffer" here — with a raw H.264
            // elementary stream on a pipe, ffmpeg 8.x then discards the
            // parser's buffered data and decodes ZERO frames (verified
            // empirically in the SmokeTest video stage). The format is
            // forced (-f h264), so probing cost is already minimal, and
            // end-to-end latency is governed by the encoder side
            // (no B-frames, 1 s IDR), not by this demuxer buffer.
            psi.ArgumentList.Add("-f"); psi.ArgumentList.Add("h264");
            psi.ArgumentList.Add("-i"); psi.ArgumentList.Add("pipe:0");
            psi.ArgumentList.Add("-pix_fmt"); psi.ArgumentList.Add("bgra");
            psi.ArgumentList.Add("-f"); psi.ArgumentList.Add("rawvideo");
            psi.ArgumentList.Add("pipe:1");
            _proc = Process.Start(psi);
            if (_proc is null) { LastError = "ffmpeg failed to start"; return false; }
            // Drain stderr so the pipe can never block the decoder.
            var proc = _proc;
            _ = Task.Run(() =>
            {
                try
                {
                    string? line;
                    while ((line = proc.StandardError.ReadLine()) is not null)
                        if (line.Length > 0) LastError = line;
                }
                catch { }
            });
            Running = true;
            _reader = new Thread(() => ReadLoop(proc)) { IsBackground = true, Name = "sr-h264dec" };
            _reader.Start();
            if (csdAnnexB is { Length: > 0 }) WriteRaw(csdAnnexB);
            return true;
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
            Running = false;
            return false;
        }
    }

    private void ReadLoop(Process proc)
    {
        int frameBytes = Width * Height * 4;
        var stream = proc.StandardOutput.BaseStream;
        try
        {
            while (!_stopping)
            {
                var frame = new byte[frameBytes];
                int filled = 0;
                while (filled < frameBytes)
                {
                    int n = stream.Read(frame, filled, frameBytes - filled);
                    if (n <= 0) return; // EOF: ffmpeg exited / stdin closed
                    filled += n;
                }
                FramesDecoded++;
                FrameDecoded?.Invoke(frame, Width, Height, FramesDecoded);
            }
        }
        catch { /* pipe torn down during Stop */ }
    }

    public void WriteAccessUnit(byte[] au)
    {
        if (!Running) return;
        WriteRaw(au);
    }

    private void WriteRaw(byte[] bytes)
    {
        var proc = _proc;
        if (proc is null) return;
        lock (_writeLock)
        {
            try
            {
                proc.StandardInput.BaseStream.Write(bytes, 0, bytes.Length);
                proc.StandardInput.BaseStream.Flush();
            }
            catch (Exception ex)
            {
                LastError = ex.Message;
                Running = false;
            }
        }
    }

    /// <summary>Close stdin (ffmpeg flushes its delayed frames), give the
    /// reader a moment to drain, then tear the process down.</summary>
    public void Stop()
    {
        var proc = _proc;
        _proc = null;
        if (proc is null) { Running = false; return; }
        try { proc.StandardInput.Close(); } catch { }
        try { _reader?.Join(1500); } catch { }
        _stopping = true;
        try { if (!proc.HasExited) proc.Kill(); } catch { }
        try { proc.Dispose(); } catch { }
        Running = false;
    }

    public void Dispose() => Stop();
}

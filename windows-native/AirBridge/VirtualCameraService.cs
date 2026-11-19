using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace AirBridge;

/// <summary>
/// Wave-4 stage-2: Media Foundation virtual camera, .NET side.
///
/// Lifecycle: BridgeEngine.StartVideo/StopVideo start/stop this
/// service (the engine's VirtualCamera property is assigned by
/// MainWindow; headless contexts leave it null and stay preview-only).
/// Start publishes decoded BGRA frames into the shared frame channel
/// (VirtualCameraChannel) and asks the native media-source DLL to
/// register the camera via MFCreateVirtualCamera (session lifetime,
/// current user — no elevation for the call itself; the DLL's COM
/// registration is a one-time machine step done by the installer, see
/// the stage-2 report). Stop unregisters the camera and closes the
/// channel. Process exit removes a session-lifetime camera even if
/// Stop is never reached.
///
/// Failure policy (FB-2 principle: degrade, never die): every failure
/// — not Windows 11, DLL missing/unloadable, media source not
/// registered, MFCreateVirtualCamera error — lands in LastError, is
/// written to the engine log, and leaves the audio path untouched.
/// This class never throws out of Start/Stop.
/// </summary>
public sealed class VirtualCameraService : IDisposable
{
    /// <summary>Media-source CLSID; the sourceId MFCreateVirtualCamera
    /// activates and the registry key the installer registers. Mirrors
    /// srvcam::kMediaSourceClsid in SharedFrameChannel.h. Internal
    /// identifier (KEEP-compat), not brand surface.</summary>
    public const string MediaSourceClsid = "{7AD7CD52-9DC5-45A4-A3C6-F06F6206F8DA}";
    public const string DllFileName = "SoundRelayVirtualCamera.dll";
    /// <summary>Name other apps list; the MF pipeline appends
    /// "Windows Virtual Camera" to it (MFCreateVirtualCamera docs).</summary>
    public const string CameraFriendlyName = "LisTalk 摄像头";

    private readonly BridgeEngine _engine;
    private readonly object _lock = new();
    private VirtualCameraChannel? _channel;
    private Thread? _pump;
    private volatile bool _pumpStop;
    private bool _started;
    private VirtualCameraFormat _lastFormat;
    private readonly AutoResetEvent _frameSignal = new(false);

    public bool Active { get; private set; }
    public string? LastError { get; private set; }
    public long FramesPublished { get; private set; }

    public VirtualCameraService(BridgeEngine engine)
    {
        _engine = engine; // inert: no native or OS calls until Start()
    }

    public void Start()
    {
        lock (_lock)
        {
            if (_started) return;
            _started = true; // reset by Stop(); blocks re-entry while starting
        }
        try
        {
            StartCore();
        }
        catch (Exception ex)
        {
            Fail($"虚拟摄像头启动异常：{ex.Message}");
        }
    }

    private void StartCore()
    {
        if (!OperatingSystem.IsWindows())
        {
            Fail("虚拟摄像头需要 Windows 11（本机非 Windows，跳过）");
            return;
        }
        string dllPath = Path.Combine(AppContext.BaseDirectory, DllFileName);
        if (!File.Exists(dllPath))
        {
            Fail($"虚拟摄像头组件缺失：{DllFileName} 不在程序目录");
            return;
        }
        if (!IsMediaSourceRegistered())
        {
            Fail("虚拟摄像头组件未注册（媒体源 CLSID 不在 HKLM）——请通过安装器修复/重装");
            return;
        }
        var channel = new VirtualCameraChannel();
        if (!channel.Open())
        {
            Fail($"帧通道打开失败：{channel.LastError}");
            channel.Dispose();
            return;
        }
        _channel = channel;
        _lastFormat = FormatFrom(_engine.CurrentVideoConfig);
        _channel.WriteFormat(_lastFormat, active: true);

        _pumpStop = false;
        _engine.VideoFrameArrived += OnFrameSignal;
        _engine.StateChanged += OnFrameSignal;
        _pump = new Thread(PumpLoop) { IsBackground = true, Name = "sr-vcam-feed" };
        _pump.Start();

        int hr;
        try
        {
            hr = Native.SrVirtualCameraStart(CameraFriendlyName);
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException
                                       or BadImageFormatException)
        {
            // Includes "mfsensorgroup.dll missing" on Windows 10: the
            // native DLL itself then fails to load. Degrade quietly.
            TeardownFeed();
            Fail($"虚拟摄像头组件加载失败（需要 Windows 11）：{ex.Message}");
            return;
        }
        if (hr != 0)
        {
            TeardownFeed();
            Fail($"MFCreateVirtualCamera/Start 失败：0x{hr:X8}");
            return;
        }
        Active = true;
        LastError = null;
        Log($"[视频] 虚拟摄像头已启用：{CameraFriendlyName}");
    }

    public void Stop()
    {
        lock (_lock)
        {
            if (!_started) return;
            _started = false;
        }
        try
        {
            _channel?.SetActive(false); // DLL serves black while unregistering
            try { Native.SrVirtualCameraStop(); } catch { /* already gone */ }
            TeardownFeed();
            if (Active) Log("[视频] 虚拟摄像头已停用");
            Active = false;
        }
        catch { /* Stop must never throw into engine teardown */ }
    }

    private void TeardownFeed()
    {
        _engine.VideoFrameArrived -= OnFrameSignal;
        _engine.StateChanged -= OnFrameSignal;
        _pumpStop = true;
        _frameSignal.Set();
        try { _pump?.Join(500); } catch { }
        _pump = null;
        _channel?.Dispose();
        _channel = null;
    }

    private void Fail(string message)
    {
        LastError = message;
        Active = false;
        lock (_lock) { _started = false; }
        Log($"[视频] {message}");
    }

    private static void Log(string line) => EngineLog.Write(line);

    private static bool IsMediaSourceRegistered()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(
                $@"Software\Classes\CLSID\{MediaSourceClsid}\InprocServer32");
            return key?.GetValue(null) is string path && path.Length > 0;
        }
        catch { return false; }
    }

    private static VirtualCameraFormat FormatFrom(VideoStreamConfig? cfg) =>
        cfg is null
            ? VirtualCameraFormat.Default
            : new VirtualCameraFormat(cfg.Width, cfg.Height, cfg.Fps, cfg.Rotation, cfg.Mirrored);

    private void OnFrameSignal() => _frameSignal.Set();

    /// <summary>Feeder pump: on each decoded frame, copy the engine's
    /// latest-frame slot into the shared channel — that copy is the
    /// only one between the decoder buffer and shared memory. Also
    /// refreshes the channel's format header when VIDEO_CONFIG changes
    /// ahead of the next frame.</summary>
    private void PumpLoop()
    {
        long lastPublished = -1;
        while (!_pumpStop)
        {
            _frameSignal.WaitOne(100);
            if (_pumpStop) break;
            var channel = _channel;
            if (channel is null) break;
            var cfg = _engine.CurrentVideoConfig;
            var fmt = FormatFrom(cfg);
            while (_engine.TryGetLatestVideoFrame(lastPublished, out var bgra,
                                                  out var w, out var h, out var idx))
            {
                long seq = channel.Publish(bgra!, w, h, fmt.Fps, fmt.Rotation,
                                           fmt.Mirrored, TimestampUs());
                if (seq > 0)
                {
                    FramesPublished++;
                    _lastFormat = new VirtualCameraFormat(w, h, fmt.Fps, fmt.Rotation, fmt.Mirrored);
                }
                lastPublished = idx;
            }
            if (fmt != _lastFormat && _engine.VideoStreaming)
            {
                channel.WriteFormat(fmt, active: true);
                _lastFormat = fmt;
            }
        }
    }

    private static long TimestampUs() =>
        Stopwatch.GetTimestamp() * 1_000_000L / Stopwatch.Frequency;

    public void Dispose() => Stop();

    private static class Native
    {
        [DllImport(DllFileName, CharSet = CharSet.Unicode, ExactSpelling = true)]
        public static extern int SrVirtualCameraStart(string friendlyName);

        [DllImport(DllFileName, ExactSpelling = true)]
        public static extern int SrVirtualCameraStop();
    }
}

using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Threading;

namespace AirBridge;

/// <summary>
/// Camera-relay preview (wave 4, stage 1): renders the engine's decoded
/// frames (see H264Decoder) into a WriteableBitmap. Deliberately a
/// SEPARATE window that MainWindow does not reference — stage 2 wires
/// the entry point into the main UI; here it is constructed directly
/// (by the SmokeTest video stage in this sandbox).
///
/// Frames arrive sensor-raw: rotation/mirroring from VIDEO_CONFIG are
/// NOT applied in this stage-1 preview (the plan assigns the transform
/// to the stage-2 virtual-camera source; the preview showing the raw
/// orientation is a known, documented gap, not a silent one — the
/// status line reports the configured rotation).
/// </summary>
public partial class VideoPreviewWindow : Window
{
    private readonly BridgeEngine _engine;
    private readonly DispatcherTimer _timer;
    private WriteableBitmap? _bitmap;
    private long _shownIndex = -1;

    /// <summary>Parameterless ctor for the XAML runtime loader / designer;
    /// production code uses the engine-taking ctor below.</summary>
    public VideoPreviewWindow() : this(new BridgeEngine(AppConfig.Load(), AppSettings.Load()))
    {
    }

    public VideoPreviewWindow(BridgeEngine engine)
    {
        _engine = engine;
        InitializeComponent();
        _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(33) };
        _timer.Tick += (_, _) => RefreshFrame();
        Opened += (_, _) => _timer.Start();
        Closed += (_, _) => _timer.Stop();
    }

    /// <summary>Pull the engine's newest decoded frame into the bitmap.
    /// Called by the 33 ms timer; also callable directly (tests).</summary>
    public void RefreshFrame()
    {
        var img = this.FindControl<Image>("img");
        var placeholder = this.FindControl<TextBlock>("placeholder");
        var status = this.FindControl<TextBlock>("status");
        if (_engine.TryGetLatestVideoFrame(_shownIndex, out var bgra, out int w, out int h, out long index)
            && bgra is not null)
        {
            _shownIndex = index;
            if (_bitmap is null || _bitmap.PixelSize.Width != w || _bitmap.PixelSize.Height != h)
            {
                _bitmap?.Dispose();
                _bitmap = new WriteableBitmap(new PixelSize(w, h), new Vector(96, 96),
                    PixelFormat.Bgra8888, AlphaFormat.Opaque);
                if (img is not null) img.Source = _bitmap;
            }
            using (var fb = _bitmap.Lock())
            {
                int rowBytes = w * 4;
                for (int y = 0; y < h; y++)
                {
                    Marshal.Copy(bgra, y * rowBytes, fb.Address + y * fb.RowBytes, rowBytes);
                }
            }
            img?.InvalidateVisual(); // WriteableBitmap has no Invalidate of its own
            if (placeholder is not null) placeholder.IsVisible = false;
        }
        if (status is not null)
        {
            var cfg = _engine.CurrentVideoConfig;
            status.Text = cfg is null
                ? Loc.T("尚未收到视频配置", "No video config received yet")
                : Loc.F("解码 {0} 帧 · {1}x{2}@{3} · 旋转 {4}°{5} · 丢帧 {6}",
                        "Decoded {0} frames · {1}x{2}@{3} · rotation {4}°{5} · dropped {6}",
                        _engine.VideoFramesDecoded, cfg.Width, cfg.Height, cfg.Fps, cfg.Rotation,
                        cfg.Mirrored ? Loc.T(" · 已镜像", " · mirrored") : "", _engine.VideoFramesDropped);
        }
    }
}

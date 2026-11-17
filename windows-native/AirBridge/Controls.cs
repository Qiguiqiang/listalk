using Avalonia;
using Avalonia.Animation;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;

namespace AirBridge;

/// <summary>Token brush lookup — custom-drawn controls resolve the same
/// ResourceDictionary keys as XAML (UI_SPEC_DESKTOP §2: no hard-coded colours).</summary>
internal static class Tokens
{
    private static readonly Dictionary<string, IBrush> Cache = new();
    // FB-9 root cause (v2.2.3): this lookup used to be
    // Application.Current.Resources[key] — the ResourceDictionary INDEXER,
    // which does NOT traverse merged dictionaries in Avalonia 11.2. Since
    // the FB-2 fix moved the tokens into Tokens.axaml (merged via
    // ResourceInclude), every lookup missed, the fallback below was
    // cached, and ALL code-drawn controls (switches, meters, sparkline,
    // segmented controls, device-list art) rendered in the fallback
    // colour — switch ON and OFF mixed the same fallback with itself,
    // making the states literally identical (Gray's FB-9 report).
    // TryFindResource performs the real host lookup (merged dictionaries
    // included); the SmokeTest FB-9 stage guards this path.
    private static readonly Color Fallback = Color.Parse("#808080");
    public static IBrush B(string key)
    {
        if (Cache.TryGetValue(key, out var b)) return b;
        b = Application.Current is { } app && app.TryFindResource(key, out var v) && v is IBrush found
            ? found
            : new SolidColorBrush(Fallback);
        Cache[key] = b;
        return b;
    }
    public static Color C(string key) => B(key) is ISolidColorBrush s ? s.Color : Fallback;
    public static Color Mix(Color a, Color b, double t) => Color.FromArgb(
        (byte)(a.A + (b.A - a.A) * t), (byte)(a.R + (b.R - a.R) * t),
        (byte)(a.G + (b.G - a.G) * t), (byte)(a.B + (b.B - a.B) * t));
}

/// <summary>28-segment level meter (spec §3.6): segments light in the channel
/// colour up to 72%, warn to 90%, error above; 100 ms repaint from the owner.</summary>
public sealed class SegmentMeter : Control
{
    public static readonly StyledProperty<double> LevelProperty =
        AvaloniaProperty.Register<SegmentMeter, double>(nameof(Level));
    public static readonly StyledProperty<IBrush?> ChannelBrushProperty =
        AvaloniaProperty.Register<SegmentMeter, IBrush?>(nameof(ChannelBrush));

    static SegmentMeter() => AffectsRender<SegmentMeter>(LevelProperty, ChannelBrushProperty);

    public double Level { get => GetValue(LevelProperty); set => SetValue(LevelProperty, value); }
    public IBrush? ChannelBrush { get => GetValue(ChannelBrushProperty); set => SetValue(ChannelBrushProperty, value); }

    public override void Render(DrawingContext ctx)
    {
        const int n = 28;
        const double gap = 2.5;
        double w = Bounds.Width, h = Bounds.Height;
        if (w <= 0 || h <= 0) return;
        double segW = (w - gap * (n - 1)) / n;
        int lit = (int)Math.Round(Math.Clamp(Level, 0, 1) * n);
        var off = Tokens.B("SegOff");
        var warn = Tokens.B("Warn");
        var error = Tokens.B("Error");
        var channel = ChannelBrush ?? Tokens.B("Accent");
        for (int i = 0; i < n; i++)
        {
            IBrush fill = off;
            if (i < lit)
            {
                double r = (double)i / n;
                fill = r > 0.90 ? error : r > 0.72 ? warn : channel;
            }
            var rect = new Rect(i * (segW + gap), 0, segW, h);
            ctx.DrawRectangle(fill, null, new RoundedRect(rect, 2.5));
        }
    }
}

/// <summary>Latency sparkline (spec §3.5): last-60 s polyline, accent stroke
/// 1.6 px with a 9% alpha fill underneath. Points are pushed by the owner.</summary>
public sealed class Sparkline : Control
{
    private IReadOnlyList<double> _points = Array.Empty<double>();
    public void SetPoints(IReadOnlyList<double> points) { _points = points; InvalidateVisual(); }

    public override void Render(DrawingContext ctx)
    {
        double w = Bounds.Width, h = Bounds.Height;
        if (w <= 0 || h <= 0 || _points.Count < 2) return;
        double maxv = Math.Max(50.0, _points.Max() * 1.2);
        var pts = new Point[_points.Count];
        for (int i = 0; i < _points.Count; i++)
        {
            double x = 2 + i * (w - 4) / 59.0;
            double y = h - 3 - (_points[i] / maxv) * (h - 6);
            pts[i] = new Point(x, Math.Clamp(y, 1, h - 1));
        }
        var line = new StreamGeometry();
        using (var g = line.Open())
        {
            g.BeginFigure(pts[0], false);
            for (int i = 1; i < pts.Length; i++) g.LineTo(pts[i]);
            g.EndFigure(false);
        }
        var fillGeo = new StreamGeometry();
        using (var g = fillGeo.Open())
        {
            g.BeginFigure(pts[0], true);
            for (int i = 1; i < pts.Length; i++) g.LineTo(pts[i]);
            g.LineTo(new Point(pts[^1].X, h));
            g.LineTo(new Point(pts[0].X, h));
            g.EndFigure(true);
        }
        var accent = Tokens.C("Accent");
        ctx.DrawGeometry(new SolidColorBrush(Color.FromArgb(23, accent.R, accent.G, accent.B)), null, fillGeo);
        ctx.DrawGeometry(null, new Pen(Tokens.B("Accent"), 1.6), line);
    }
}

/// <summary>Breathing status dot (spec §3.1/§3.2): expanding ring while active,
/// a static grey dot while inactive.</summary>
public sealed class PulseDot : Control
{
    public static readonly StyledProperty<bool> IsActiveProperty =
        AvaloniaProperty.Register<PulseDot, bool>(nameof(IsActive), true);
    public bool IsActive { get => GetValue(IsActiveProperty); set => SetValue(IsActiveProperty, value); }

    private DispatcherTimer? _timer;
    private double _phase;
    static PulseDot() => AffectsRender<PulseDot>(IsActiveProperty);

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(60) };
        _timer.Tick += (_, _) => { _phase = (_phase + 0.045) % 1.0; InvalidateVisual(); };
        _timer.Start();
    }
    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        _timer?.Stop(); _timer = null;
        base.OnDetachedFromVisualTree(e);
    }

    public override void Render(DrawingContext ctx)
    {
        double d = Math.Min(Bounds.Width, Bounds.Height);
        if (d <= 0) return;
        var center = new Point(Bounds.Width / 2, Bounds.Height / 2);
        if (!IsActive)
        {
            ctx.DrawEllipse(Tokens.B("Text3"), null, center, d / 4, d / 4);
            return;
        }
        var accent = Tokens.C("Accent");
        double ringR = d / 4 + _phase * d / 4;
        byte alpha = (byte)(130 * (1 - _phase));
        ctx.DrawEllipse(null, new Pen(new SolidColorBrush(Color.FromArgb(alpha, accent.R, accent.G, accent.B)), 1.5),
            center, ringR, ringR);
        ctx.DrawEllipse(Tokens.B("Accent"), null, center, d / 4, d / 4);
    }
}

/// <summary>Scanning ring for the discovery radar bar (spec §3.9): a rotating
/// accent arc over a dim full ring.</summary>
public sealed class Spinner : Control
{
    private DispatcherTimer? _timer;
    private double _angle;

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(50) };
        _timer.Tick += (_, _) => { _angle = (_angle + 14) % 360; InvalidateVisual(); };
        _timer.Start();
    }
    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        _timer?.Stop(); _timer = null;
        base.OnDetachedFromVisualTree(e);
    }

    public override void Render(DrawingContext ctx)
    {
        double d = Math.Min(Bounds.Width, Bounds.Height);
        if (d <= 4) return;
        double r = d / 2 - 1.5;
        var center = new Point(Bounds.Width / 2, Bounds.Height / 2);
        ctx.DrawEllipse(null, new Pen(Tokens.B("AccentDim"), 2), center, r, r);
        double a0 = _angle * Math.PI / 180.0, sweep = 270 * Math.PI / 180.0;
        var start = new Point(center.X + r * Math.Cos(a0), center.Y + r * Math.Sin(a0));
        var end = new Point(center.X + r * Math.Cos(a0 + sweep), center.Y + r * Math.Sin(a0 + sweep));
        var geo = new StreamGeometry();
        using (var g = geo.Open())
        {
            g.BeginFigure(start, false);
            g.ArcTo(end, new Size(r, r), 0, true, SweepDirection.Clockwise);
            g.EndFigure(false);
        }
        ctx.DrawGeometry(null, new Pen(Tokens.B("Accent"), 2) { LineCap = PenLineCap.Round }, geo);
    }
}

public enum SwitchVariant { Accent, Sky }

/// <summary>Spec §3.8 switch: 42×24 (small 38×22), off = #2A3648 track /
/// #8B98AB knob, on = channel-dim track / channel knob, 150 ms slide.</summary>
public sealed class SwitchControl : Control
{
    public static readonly StyledProperty<bool> IsCheckedProperty =
        AvaloniaProperty.Register<SwitchControl, bool>(nameof(IsChecked));
    public static readonly StyledProperty<SwitchVariant> VariantProperty =
        AvaloniaProperty.Register<SwitchControl, SwitchVariant>(nameof(Variant));
    public static readonly StyledProperty<bool> SmallProperty =
        AvaloniaProperty.Register<SwitchControl, bool>(nameof(Small));
    private static readonly StyledProperty<double> AnimProgressProperty =
        AvaloniaProperty.Register<SwitchControl, double>("AnimProgress");

    public event EventHandler<bool>? CheckedChanged;

    public bool IsChecked { get => GetValue(IsCheckedProperty); set => SetValue(IsCheckedProperty, value); }
    public SwitchVariant Variant { get => GetValue(VariantProperty); set => SetValue(VariantProperty, value); }
    public bool Small { get => GetValue(SmallProperty); set => SetValue(SmallProperty, value); }

    public SwitchControl()
    {
        Focusable = true;
        Cursor = new Cursor(StandardCursorType.Hand);
        Transitions = new Transitions { new DoubleTransition { Property = AnimProgressProperty, Duration = TimeSpan.FromMilliseconds(150) } };
    }

    static SwitchControl() => AffectsRender<SwitchControl>(IsCheckedProperty, VariantProperty, AnimProgressProperty);

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == IsCheckedProperty)
            SetValue(AnimProgressProperty, IsChecked ? 1.0 : 0.0);
    }

    public void Toggle()
    {
        IsChecked = !IsChecked;
        CheckedChanged?.Invoke(this, IsChecked);
    }

    protected override void OnPointerReleased(PointerReleasedEventArgs e)
    {
        base.OnPointerReleased(e);
        if (new Rect(Bounds.Size).Contains(e.GetPosition(this))) { Focus(); Toggle(); }
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        base.OnKeyDown(e);
        if (e.Key is Key.Space or Key.Enter) { Toggle(); e.Handled = true; }
    }

    public override void Render(DrawingContext ctx)
    {
        double w = Bounds.Width, h = Bounds.Height;
        if (w <= 0 || h <= 0) return;
        double p = GetValue(AnimProgressProperty);
        bool sky = Variant == SwitchVariant.Sky;
        // FB-9: ON must be unmistakable — the track fills with the FULL
        // accent colour (the old code filled it with the DIM variant, a
        // near-black green barely different from the OFF track) and the
        // knob goes white. OFF stays TrackOff + KnobOff. The two states
        // now differ in track colour, track brightness, knob colour and
        // knob position.
        var track = Tokens.Mix(Tokens.C("TrackOff"), Tokens.C(sky ? "Sky" : "Accent"), p);
        var knob = Tokens.Mix(Tokens.C("KnobOff"), Colors.White, p);
        ctx.DrawRectangle(new SolidColorBrush(track), null, new RoundedRect(new Rect(0, 0, w, h), h / 2));
        double d = h - 6;
        double x = 3 + p * (w - d - 6);
        ctx.DrawEllipse(new SolidColorBrush(knob), null, new Point(x + d / 2, h / 2), d / 2, d / 2);
        if (IsFocused)
            ctx.DrawRectangle(null, new Pen(Tokens.B("StrokeHi"), 1), new RoundedRect(new Rect(0.5, 0.5, w - 1, h - 1), h / 2));
    }
}

/// <summary>Segmented control (spec §3.10): SurfaceIn tray, selected item
/// SurfaceHi + semibold. Selection is code-managed (no engine binding inside).</summary>
public sealed class SegmentedControl : Border
{
    private readonly StackPanel _row = new() { Orientation = Orientation.Horizontal, Spacing = 2 };
    private readonly List<Button> _buttons = new();
    public event EventHandler<int>? SelectionChanged;
    public int SelectedIndex { get; private set; } = -1;

    public SegmentedControl()
    {
        Background = Tokens.B("SurfaceIn");
        BorderBrush = Tokens.B("Stroke");
        BorderThickness = new Thickness(1);
        CornerRadius = new CornerRadius(10);
        Padding = new Thickness(3);
        Child = _row;
    }

    public void SetItems(IReadOnlyList<string> items, int selected)
    {
        _buttons.Clear();
        _row.Children.Clear();
        for (int i = 0; i < items.Count; i++)
        {
            int idx = i;
            var b = new Button
            {
                Content = items[i],
                FontSize = 12,
                Padding = new Thickness(13, 6),
                CornerRadius = new CornerRadius(7),
                BorderThickness = new Thickness(0),
            };
            b.Click += (_, _) => Select(idx, raise: true);
            _buttons.Add(b);
            _row.Children.Add(b);
        }
        Select(selected, raise: false);
    }

    public void Select(int idx, bool raise)
    {
        if (idx < 0 || idx >= _buttons.Count) return;
        SelectedIndex = idx;
        for (int i = 0; i < _buttons.Count; i++)
        {
            bool on = i == idx;
            _buttons[i].Background = on ? Tokens.B("SurfaceHi") : Brushes.Transparent;
            _buttons[i].Foreground = on ? Tokens.B("Text1") : Tokens.B("Text2");
            _buttons[i].FontWeight = on ? FontWeight.SemiBold : FontWeight.Normal;
        }
        if (raise) SelectionChanged?.Invoke(this, idx);
    }
}

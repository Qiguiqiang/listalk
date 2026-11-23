using System.Diagnostics;
using System.Net;
using System.Reflection;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using Avalonia.VisualTree;
using Avalonia.LogicalTree;
using AirBridge;

// Headless smoke harness: the judge for the 'Text1/Text3 not found' startup
// crash. Stage 1 = App.Initialize() (loads App.axaml; StyleInclude pulls in
// Styles.axaml whose StaticResource setters evaluate during Populate).
// Stage 2 = MainWindow construction (its own XAML + styles applied).
// Stage 3 (v2.2.3, FB-5) = render the custom title bar at 1x and 2x (plus
// maximized at 2x for the restore glyph) and the full window at 1x, so the
// window-control icons can be inspected for doubling/crispness.
// Usage: SmokeTest [output-dir]   (default: ./renders)
string outDir = args.Length > 0 ? args[0] : "renders";
Directory.CreateDirectory(outDir);

try
{
    var builder = AppBuilder.Configure<App>()
        .UseSkia()
        .UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false });
    builder.SetupWithoutStarting();
    Console.WriteLine("SMOKE: APP INITIALIZED OK");
}
catch (Exception ex)
{
    Console.WriteLine("SMOKE: APP INITIALIZE FAILED");
    Console.WriteLine(ex.ToString());
    return 1;
}

MainWindow window;
try
{
    window = new MainWindow();
    Console.WriteLine("SMOKE: MAINWINDOW CONSTRUCTED OK");
    window.Show();
    Console.WriteLine("SMOKE: MAINWINDOW SHOWN OK");
}
catch (Exception ex)
{
    Console.WriteLine("SMOKE: MAINWINDOW FAILED");
    Console.WriteLine(ex.ToString());
    return 2;
}

try
{
    Dispatcher.UIThread.RunJobs();
    var titleBar = window.FindControl<Border>("titleBar")
        ?? throw new InvalidOperationException("titleBar not found");
    SaveRender(titleBar, Path.Combine(outDir, "titlebar-1x.png"), 96);
    SaveRender(titleBar, Path.Combine(outDir, "titlebar-2x.png"), 192);
    SaveRender(window, Path.Combine(outDir, "window-1x.png"), 96);

    // ---- Title-bar glyph pixel assertions (board item #1) ----
    // The minimize glyph once shipped as a zero-height stroke geometry
    // (M2,6 L10,6 under Stretch=Uniform) and rasterized to literally 0
    // pixels while every other check passed. Count non-background
    // pixels inside each button's glyph box so an invisible or
    // near-invisible glyph fails HERE instead of on a user's screen.
    int glyphFails = 0;
    void GAssert(bool cond, string what)
    {
        Console.WriteLine($"SMOKE: glyph-assert {(cond ? "ok" : "FAILED")}: {what}");
        if (!cond) glyphFails++;
    }
    int GlyphPixels(Control glyph, double dpi)
    {
        using var bmp = RenderBitmap(titleBar, dpi);
        var (px, _, _, stride) = ReadPixels(bmp);
        var tl = glyph.TranslatePoint(new Point(0, 0), titleBar)
            ?? throw new InvalidOperationException("glyph is not inside titleBar");
        double scale = dpi / 96.0;
        var rect = new PixelRect(
            Math.Max(0, (int)Math.Floor(tl.X * scale)), Math.Max(0, (int)Math.Floor(tl.Y * scale)),
            Math.Max(1, (int)Math.Ceiling(glyph.Bounds.Width * scale)),
            Math.Max(1, (int)Math.Ceiling(glyph.Bounds.Height * scale)));
        return CountNonBackground(px, stride, rect);
    }
    var btnMin = window.FindControl<Button>("btnMin")
        ?? throw new InvalidOperationException("btnMin not found");
    var btnCloseWin = window.FindControl<Button>("btnCloseWin")
        ?? throw new InvalidOperationException("btnCloseWin not found");
    var pathMin = btnMin.GetVisualDescendants().OfType<Avalonia.Controls.Shapes.Path>().First();
    var pathClose = btnCloseWin.GetVisualDescendants().OfType<Avalonia.Controls.Shapes.Path>().First();
    var pathMax = window.FindControl<Avalonia.Controls.Shapes.Path>("pathMaxIcon")
        ?? throw new InvalidOperationException("pathMaxIcon not found");
    int min1 = GlyphPixels(pathMin, 96), max1 = GlyphPixels(pathMax, 96), close1 = GlyphPixels(pathClose, 96);
    Console.WriteLine($"SMOKE: glyph pixels @1x: btnMin={min1} btnMax={max1} btnClose={close1}");
    GAssert(min1 > 12, $"btnMin glyph visible @1x ({min1} px)");
    GAssert(max1 > 12, $"btnMax glyph visible @1x ({max1} px)");
    GAssert(close1 > 12, $"btnClose glyph visible @1x ({close1} px)");
    int min2 = GlyphPixels(pathMin, 192), max2 = GlyphPixels(pathMax, 192), close2 = GlyphPixels(pathClose, 192);
    Console.WriteLine($"SMOKE: glyph pixels @2x: btnMin={min2} btnMax={max2} btnClose={close2}");
    GAssert(min2 > 30, $"btnMin glyph visible @2x ({min2} px)");
    GAssert(max2 > 30, $"btnMax glyph visible @2x ({max2} px)");
    GAssert(close2 > 30, $"btnClose glyph visible @2x ({close2} px)");

    window.WindowState = WindowState.Maximized;
    Dispatcher.UIThread.RunJobs();
    SaveRender(titleBar, Path.Combine(outDir, "titlebar-max-2x.png"), 192);
    var pathRestore = window.FindControl<Avalonia.Controls.Shapes.Path>("pathRestoreIcon")
        ?? throw new InvalidOperationException("pathRestoreIcon not found");
    int restore2 = GlyphPixels(pathRestore, 192);
    Console.WriteLine($"SMOKE: glyph pixels @2x maximized: btnRestore={restore2}");
    GAssert(restore2 > 30, $"btnRestore glyph visible @2x maximized ({restore2} px)");
    window.WindowState = WindowState.Normal;
    Dispatcher.UIThread.RunJobs();
    if (glyphFails > 0) throw new InvalidOperationException($"{glyphFails} title-bar glyph assertion(s) failed");
    Console.WriteLine("SMOKE: RENDERS OK -> " + Path.GetFullPath(outDir));
}
catch (Exception ex)
{
    Console.WriteLine("SMOKE: RENDER FAILED");
    Console.WriteLine(ex.ToString());
    return 3;
}

try
{
    // ---- Stage 4 (v2.2.3, FB-9): token resolution diagnostics + channel
    // toggle ON/OFF renders. Tokens.B() (Controls.cs) resolves design
    // tokens via Application.Current.Resources[key] and falls back to
    // MAGENTA on a miss — if that path misses at runtime, code-drawn
    // controls (switches, meters) render magenta and switch ON/OFF
    // become indistinguishable (Mix of magenta with magenta). Print
    // what each lookup path actually returns for the switch tokens.
    Console.WriteLine("SMOKE: --- FB-9 token diagnostics ---");
    foreach (var key in new[] { "Accent", "AccentDim", "AccentEnd", "OnAccent", "Sky", "SkyDim", "TrackOff", "KnobOff" })
    {
        object? viaApp = null;
        try { viaApp = Application.Current?.Resources[key]; } catch (Exception ex) { viaApp = "THREW " + ex.GetType().Name; }
        var foundHost = window.TryFindResource(key, out var viaHost);
        // The path Tokens.B() uses in production (FB-9 fix): TryFindResource
        // on the Application. The raw Resources[key] indexer does NOT
        // traverse merged dictionaries (printed for the record).
        object? viaAppFind = null;
        var foundApp = Application.Current is { } appCur && appCur.TryFindResource(key, out viaAppFind);
        Console.WriteLine($"SMOKE: token {key}: appIndexer={DescribeBrush(viaApp)} appTryFind={(foundApp ? DescribeBrush(viaAppFind) : "MISS")} hostLookup={(foundHost ? DescribeBrush(viaHost) : "MISS")}");
        if (!foundApp) throw new InvalidOperationException($"token {key} not resolvable via Application.TryFindResource — Tokens.B would fall back");
    }

    var swMic = window.FindControl<SwitchControl>("swMic")
        ?? throw new InvalidOperationException("swMic not found");
    var swSpk = window.FindControl<SwitchControl>("swSpk")
        ?? throw new InvalidOperationException("swSpk not found");
    // The channel card that hosts the mic switch (walk up to the card Border).
    Control card = swMic;
    while (card.Parent is Control p && card is not Border) card = p;

    swMic.IsChecked = true; swSpk.IsChecked = true;
    Dispatcher.UIThread.RunJobs();
    SaveRender(card, Path.Combine(outDir, "toggles-on.png"), 192);
    SaveRender(window, Path.Combine(outDir, "session-on.png"), 192);
    swMic.IsChecked = false; swSpk.IsChecked = false;
    Dispatcher.UIThread.RunJobs();
    SaveRender(card, Path.Combine(outDir, "toggles-off.png"), 192);
    SaveRender(window, Path.Combine(outDir, "session-off.png"), 192);
    Console.WriteLine("SMOKE: FB-9 RENDERS OK");
}
catch (Exception ex)
{
    Console.WriteLine("SMOKE: FB-9 STAGE FAILED");
    Console.WriteLine(ex.ToString());
    return 4;
}

try
{
    // ---- Stage 5 (wave 2): state-fed renders + assertions. The window's
    // engine is a REAL BridgeEngine; we drive it into the connected /
    // paused / reconnecting states by writing its session properties
    // through reflection (private setters) and invoking the window's
    // private Poll() — the same code path the 500 ms timer drives in
    // production. Assertions read the actual control texts, so a
    // state-display regression of the B-03/B-06/B-07 class fails here
    // instead of reaching a real machine.
    Console.WriteLine("SMOKE: --- state-fed stage ---");
    int stateFails = 0;
    void Assert(bool cond, string what)
    {
        Console.WriteLine($"SMOKE: state-assert {(cond ? "ok" : "FAILED")}: {what}");
        if (!cond) stateFails++;
    }
    static void SetBacking(object obj, string prop, object? value)
    {
        var t = obj.GetType();
        var p = t.GetProperty(prop) ?? throw new InvalidOperationException($"property {prop} not found on {t.Name}");
        var setter = p.GetSetMethod(true);
        if (setter is not null) { setter.Invoke(obj, new[] { value }); return; }
        var f = t.GetField($"<{prop}>k__BackingField", BindingFlags.NonPublic | BindingFlags.Instance)
            ?? throw new InvalidOperationException($"backing field for {prop} not found on {t.Name}");
        f.SetValue(obj, value);
    }
    static void SetField(object obj, string name, object? value)
    {
        var f = obj.GetType().GetField(name, BindingFlags.NonPublic | BindingFlags.Instance)
            ?? throw new InvalidOperationException($"field {name} not found on {obj.GetType().Name}");
        f.SetValue(obj, value);
    }
    static object? GetField(object obj, string name) =>
        obj.GetType().GetField(name, BindingFlags.NonPublic | BindingFlags.Instance)?.GetValue(obj);
    static void Invoke(object obj, string method) =>
        (obj.GetType().GetMethod(method, BindingFlags.NonPublic | BindingFlags.Instance)
            ?? throw new InvalidOperationException($"method {method} not found on {obj.GetType().Name}")).Invoke(obj, null);
    string Text(string name) => window.FindControl<TextBlock>(name)?.Text ?? "<missing>";

    var engine = (BridgeEngine)(GetField(window, "_engine")
        ?? throw new InvalidOperationException("_engine not found"));

    // The toggle path below persists settings; keep the developer's
    // settings file byte-identical afterwards.
    string settingsPath = AppSettings.FilePath;
    byte[]? backup = File.Exists(settingsPath) ? File.ReadAllBytes(settingsPath) : null;
    void RestoreSettings()
    {
        if (backup is not null) File.WriteAllBytes(settingsPath, backup);
        else if (File.Exists(settingsPath)) File.Delete(settingsPath);
    }

    // -- board item #1: the vdev-hint classifier is pure logic — pin
    // all three branches directly (no audio hardware exists headless).
    Assert(DeviceScoring.HintForOutput(null) == DeviceScoring.VdevHint.NoVirtual, "vdev hint: no selection -> NoVirtual");
    Assert(DeviceScoring.HintForOutput(0) == DeviceScoring.VdevHint.NoVirtual, "vdev hint: real speaker (score 0) -> NoVirtual");
    Assert(DeviceScoring.HintForOutput(85) == DeviceScoring.VdevHint.CableFallback, "vdev hint: VB-CABLE (score 85) -> CableFallback");
    Assert(DeviceScoring.HintForOutput(100) == DeviceScoring.VdevHint.CableFallback, "vdev hint: cable input (score 100) -> CableFallback");
    Assert(DeviceScoring.HintForOutput(110) == DeviceScoring.VdevHint.None, "vdev hint: own driver (score 110) -> None");
    Assert(DeviceScoring.HintForOutput(120) == DeviceScoring.VdevHint.None, "vdev hint: above own-driver threshold -> None");

    // -- fed state: connected & streaming --
    SetBacking(engine, "Connected", true);
    SetBacking(engine, "Peer", new IPEndPoint(IPAddress.Loopback, Protocol.Port));
    SetBacking(engine, "PeerName", "Smoke Phone");
    SetBacking(engine, "PhoneAppVersion", Protocol.AppVersion);
    SetBacking(engine, "PhoneProtoVersion", Protocol.ProtoVersion);
    SetField(engine, "_lastRxUtc", DateTime.UtcNow); // keep the 5 s link-death check quiet
    engine.Stats.RttMs = 12;
    engine.Stats.Rx = 2000; engine.Stats.Tx = 1800;
    Invoke(window, "Poll");
    Dispatcher.UIThread.RunJobs();
    Assert(window.FindControl<Border>("hero")?.IsVisible == true, "connected: hero visible");
    Assert(Text("devStatusWord") == Loc.Tr("推流中"), "connected: status word 推流中");
    Assert(Text("heroEyebrow") == Loc.Tr("● LIVE · 已连接手机"), "connected: hero eyebrow LIVE");
    Assert(Text("statLoss") != "--", "connected: stats strip live");
    Assert(Text("sideConnTitle").Contains("Smoke Phone"), "connected: sidebar names the peer");
    // board item #1: headless there are no audio devices, so the mic
    // channel has no virtual device -> the NoVirtual hint must show,
    // with its driver-folder action.
    Assert(window.FindControl<Border>("bannerVdev")?.IsVisible == true, "connected: vdev hint banner visible (no virtual device headless)");
    Assert(Text("tvVdevWarn") == Loc.Tr("未检测到虚拟音频设备：手机麦克风的声音正送到电脑的真实扬声器，其他软件用不了手机麦克风。打开驱动文件夹、重新运行一键安装.bat 装好虚拟音频驱动即可解决。"), "connected: vdev hint is the NoVirtual text");
    Assert(window.FindControl<Button>("btnOpenDriverDir")?.IsVisible == true, "connected: vdev hint offers the driver-folder action");
    SaveRender(window, Path.Combine(outDir, "state-connected.png"), 96);

    // -- fed state: paused (through the real TogglePause path) --
    SetField(engine, "_lastRxUtc", DateTime.UtcNow);
    Invoke(window, "TogglePause");
    Dispatcher.UIThread.RunJobs();
    Assert(Text("devStatusWord") == Loc.Tr("已暂停"), "paused: status word 已暂停");
    Assert(Text("heroEyebrow") == Loc.Tr("已暂停 · 已连接手机"), "paused: hero eyebrow not LIVE (B-06)");
    var btnPause = window.FindControl<Button>("btnPause")
        ?? throw new InvalidOperationException("btnPause not found");
    Assert((btnPause.Content as string) == Loc.Tr("恢复桥接"), "paused: pause button offers resume (B-03)");
    Assert(Text("sideConnTitle").Contains(Loc.Tr("已暂停")), "paused: sidebar says 已暂停 (B-03)");
    SaveRender(window, Path.Combine(outDir, "state-paused.png"), 96);
    SetField(engine, "_lastRxUtc", DateTime.UtcNow);
    Invoke(window, "TogglePause"); // resume for the next state
    Dispatcher.UIThread.RunJobs();

    // -- fed state: reconnecting (link death: Connected drops, Peer stays) --
    SetBacking(engine, "Connected", false);
    Invoke(window, "Poll");
    Dispatcher.UIThread.RunJobs();
    Assert(Text("devStatusWord") == Loc.Tr("重连中"), "reconnecting: status word 重连中");
    Assert(Text("heroEyebrow") == Loc.Tr("重连中 · 连接中断"), "reconnecting: hero eyebrow not LIVE (B-06)");
    Assert(Text("heroSub") == Loc.Tr("连接中断 · 正在重连，手机回来会自动续连"), "reconnecting: hero sub not stale (B-06)");
    Assert(Text("heroName") == "Smoke Phone", "reconnecting: hero keeps the last peer name");
    Assert(Text("statLoss") == "--" && Text("statJitter") == "--" && Text("statBuffer") == "--"
        && Text("statUp") == "--" && Text("statDown") == "--", "reconnecting: stats strip cleared (B-07)");
    Assert(window.FindControl<Border>("bannerVdev")?.IsVisible == false, "reconnecting: vdev hint hidden while not connected");
    SaveRender(window, Path.Combine(outDir, "state-reconnecting.png"), 96);

    RestoreSettings();

    // -- B-03 restart case: persisted Paused=true must label the buttons
    // correctly in a FRESH window, before any toggle happens. Seed the
    // real settings file, construct, assert, restore the file verbatim.
    try
    {
        var seeded = AppSettings.Load();
        seeded.Paused = true;
        seeded.AutoCheckUpdates = false; // keep the second window off the network
        seeded.Save();
        var w2 = new MainWindow();
        w2.Show();
        Dispatcher.UIThread.RunJobs();
        var btn2 = w2.FindControl<Button>("btnPause")
            ?? throw new InvalidOperationException("btnPause not found in second window");
        Assert((btn2.Content as string) == Loc.Tr("恢复桥接"), "restart(Paused=true): pause button offers resume (B-03)");
        var trayPause = GetField(w2, "_trayPause") as NativeMenuItem;
        Assert(trayPause?.Header == Loc.Tr("恢复推流"), "restart(Paused=true): tray item offers resume (B-03)");
        w2.Hide();
    }
    finally { RestoreSettings(); }

    if (stateFails > 0) throw new InvalidOperationException($"{stateFails} state assertion(s) failed");
    Console.WriteLine("SMOKE: STATE STAGE OK");
}
catch (Exception ex)
{
    Console.WriteLine("SMOKE: STATE STAGE FAILED");
    Console.WriteLine(ex.ToString());
    return 5;
}

try
{
    // ---- Stage 6 (wave 4): camera-relay pipeline proof. A real H.264
    // stream is generated with ffmpeg, split into access units, pushed
    // through the PRODUCTION packetizer + reassembler (with one whole
    // frame dropped and fragments shuffled), decoded by the PRODUCTION
    // ffmpeg-pipe decoder, and rendered by the real preview window.
    // ffprobe verifies both the reassembled elementary stream and an
    // mp4 written from the decoded frames.
    Console.WriteLine("SMOKE: --- video stage ---");
    int videoFails = 0;
    void VAssert(bool cond, string what)
    {
        Console.WriteLine($"SMOKE: video-assert {(cond ? "ok" : "FAILED")}: {what}");
        if (!cond) videoFails++;
    }
    static void VSetField(object obj, string name, object? value)
    {
        var f = obj.GetType().GetField(name, BindingFlags.NonPublic | BindingFlags.Instance)
            ?? throw new InvalidOperationException($"field {name} not found on {obj.GetType().Name}");
        f.SetValue(obj, value);
    }
    static string RunCapture(string exe, params string[] argv)
    {
        var psi = new ProcessStartInfo { FileName = exe, RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false };
        foreach (var a in argv) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi) ?? throw new InvalidOperationException($"cannot start {exe}");
        string stdout = p.StandardOutput.ReadToEnd();
        p.WaitForExit();
        if (p.ExitCode != 0) throw new InvalidOperationException($"{exe} exited {p.ExitCode}: {p.StandardError.ReadToEnd()}");
        return stdout;
    }
    // Annex-B access-unit split: a new AU starts at a VCL NAL when the
    // current AU already holds one (valid for the no-B-frame,
    // one-slice-per-frame streams both ffmpeg -bf 0 and MediaCodec make).
    static List<byte[]> SplitAccessUnits(byte[] es)
    {
        var nals = new List<(int type, int start, int len)>();
        int i = 0;
        while (i + 3 < es.Length)
        {
            int scLen = 0;
            if (es[i] == 0 && es[i + 1] == 0 && es[i + 2] == 1) scLen = 3;
            else if (i + 4 < es.Length && es[i] == 0 && es[i + 1] == 0 && es[i + 2] == 0 && es[i + 3] == 1) scLen = 4;
            if (scLen == 0) { i++; continue; }
            int nalStart = i;
            int j = i + scLen;
            int next = es.Length;
            for (int k = j; k + 3 < es.Length; k++)
            {
                if (es[k] == 0 && es[k + 1] == 0 && (es[k + 2] == 1 || (k + 4 < es.Length && es[k + 2] == 0 && es[k + 3] == 1))) { next = k; break; }
            }
            nals.Add((es[j] & 0x1F, nalStart, next - nalStart));
            i = next;
            if (next >= es.Length) break;
        }
        var aus = new List<byte[]>();
        using var cur = new MemoryStream();
        bool curHasVcl = false;
        foreach (var (type, start, len) in nals)
        {
            bool isVcl = type == 1 || type == 5;
            if (isVcl && curHasVcl)
            {
                aus.Add(cur.ToArray());
                cur.SetLength(0);
                curHasVcl = false;
            }
            cur.Write(es, start, len);
            if (isVcl) curHasVcl = true;
        }
        if (cur.Length > 0) aus.Add(cur.ToArray());
        return aus;
    }

    // (a) the C# subheader layout must equal the JVM-pinned golden bytes
    var golden = VideoPackets.PackSubheader(0x01020304, 5, 9, true, true, 0);
    VAssert(golden.SequenceEqual(new byte[] { 1, 2, 3, 4, 0, 5, 0, 9, 3, 0 }),
        "subheader golden bytes match the JVM-pinned layout");

    // (b) generate a real H.264 stream: testsrc2 320x240@15, 2 s, no B-frames
    string vidDir = Path.Combine(outDir, "video");
    Directory.CreateDirectory(vidDir);
    string esPath = Path.Combine(vidDir, "testsrc.h264");
    RunCapture("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi",
        "-i", "testsrc2=size=320x240:rate=15:duration=2",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-bf", "0", "-threads", "1", "-g", "15",
        "-f", "h264", esPath);
    var aus = SplitAccessUnits(File.ReadAllBytes(esPath));
    VAssert(aus.Count >= 25, $"test stream split into {aus.Count} access units (expect ~30)");

    // (c) packetize -> drop frame 5 whole -> shuffle frags -> reassemble
    var reasm = new VideoReassembler();
    var emitted = new List<byte[]>();
    long nowMs = 1000;
    uint seq = 0;
    var rnd = new Random(42);
    for (int f = 0; f < aus.Count; f++)
    {
        if (f == 5) continue; // whole frame lost on the wire
        var pkts = VideoPackets.Packetize(f, aus[f], keyframe: f % 15 == 0, timestampUs: (ulong)(f * 66666), firstSeq: seq);
        seq += (uint)pkts.Count;
        var frags = pkts.Select(p =>
        {
            VideoPackets.TryParseFrag(p.AsSpan(Protocol.HeaderSize), out var fh, out var d);
            return (fh, d);
        }).OrderBy(_ => rnd.Next()).ToList();
        foreach (var (fh, d) in frags)
            emitted.AddRange(reasm.Offer(fh.FrameId, fh.FragIndex, fh.FragCount, fh.Keyframe, d, nowMs));
        nowMs += 10;
    }
    emitted.AddRange(reasm.Expire(nowMs + 500)); // let the frame-5 gap expire
    VAssert(emitted.Count == aus.Count - 1, $"reassembled {emitted.Count}/{aus.Count} AUs (frame 5 lost)");
    VAssert(reasm.DroppedFrames == 1 && reasm.KeyframeNeeded, "loss counted once, keyframe requested");
    var expectAus = aus.Where((_, idx) => idx != 5).ToList();
    VAssert(emitted.Zip(expectAus).All(pr => pr.First.SequenceEqual(pr.Second)),
        "reassembled AUs byte-identical to the originals and in order");

    // (d) ffprobe verifies the reassembled elementary stream
    string rePath = Path.Combine(vidDir, "reassembled.h264");
    File.WriteAllBytes(rePath, emitted.SelectMany(a => a).ToArray());
    string probe1 = RunCapture("ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0", rePath);
    VAssert(probe1.Contains("h264") && probe1.Contains("320") && probe1.Contains("240"),
        $"ffprobe sees h264 320x240 in the reassembled stream [{probe1.Trim()}]");

    // (e) decode with the production decoder; CSD (SPS/PPS) is fed
    // separately exactly like production (VIDEO_CONFIG.csd), so strip
    // those NALs from the first AU's decode copy.
    byte[] firstAu = emitted[0];
    int csdLen = 0;
    {
        // SPS (7) + PPS (8) lead the first AU; find where the IDR (5) starts.
        int pos = 0;
        while (pos + 4 < firstAu.Length)
        {
            int sc = (firstAu[pos + 2] == 1) ? 3 : 4;
            int nalType = firstAu[pos + sc] & 0x1F;
            if (nalType == 5) { csdLen = pos; break; }
            int nxt = pos + sc + 1;
            bool found = false;
            for (int k = nxt; k + 3 < firstAu.Length; k++)
                if (firstAu[k] == 0 && firstAu[k + 1] == 0 && (firstAu[k + 2] == 1 || firstAu[k + 3] == 1)) { pos = k; found = true; break; }
            if (!found) break;
        }
    }
    byte[] csd = firstAu.Take(csdLen).ToArray();
    VAssert(csdLen > 0, $"extracted SPS/PPS as CSD ({csdLen} B) like VIDEO_CONFIG carries");
    var dec = new H264Decoder();
    VAssert(dec.FfmpegPath is not null, $"ffmpeg located: {dec.FfmpegPath}");
    var frames = new List<byte[]>();
    dec.FrameDecoded += (bgra, w, h, _) => { lock (frames) frames.Add(bgra); };
    VAssert(dec.Start(320, 240, csd), "production decoder started (ffmpeg pipe)");
    for (int f = 0; f < emitted.Count; f++)
        dec.WriteAccessUnit(f == 0 ? emitted[0].Skip(csdLen).ToArray() : emitted[f]);
    dec.Stop(); // closes stdin: ffmpeg flushes its delayed frames
    int decodedCount;
    lock (frames) decodedCount = frames.Count;
    VAssert(decodedCount >= 25, $"decoded {decodedCount} frames from {emitted.Count} AUs (expect >= 25)");
    bool colourful = false, moving = false;
    lock (frames)
    {
        if (frames.Count >= 2)
        {
            var distinct = new HashSet<byte>();
            for (int s = 0; s < frames[0].Length; s += 997) distinct.Add(frames[0][s]);
            colourful = distinct.Count > 16;
            moving = !frames[0].SequenceEqual(frames[^1]);
        }
    }
    VAssert(colourful, "decoded frame 0 is real picture content (not a blank frame)");
    VAssert(moving, "decoded frames change over time (testsrc2 motion)");

    // (f) an mp4 written from the decoded frames verifies with ffprobe
    string mp4 = Path.Combine(vidDir, "decoded.mp4");
    {
        var psi = new ProcessStartInfo { FileName = "ffmpeg", RedirectStandardInput = true, RedirectStandardError = true, UseShellExecute = false };
        foreach (var a in new[] { "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgra",
                                  "-s", "320x240", "-r", "15", "-i", "pipe:0",
                                  "-c:v", "libx264", "-pix_fmt", "yuv420p", mp4 })
            psi.ArgumentList.Add(a);
        using var p = Process.Start(psi)!;
        lock (frames) foreach (var fr in frames) p.StandardInput.BaseStream.Write(fr, 0, fr.Length);
        p.StandardInput.Close();
        p.WaitForExit();
        VAssert(p.ExitCode == 0, "ffmpeg wrote decoded.mp4 from the decoded frames");
    }
    string probe2 = RunCapture("ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
        "-show_entries", "stream=codec_name,width,height,nb_read_frames", "-of", "csv=p=0", mp4);
    VAssert(probe2.Contains("h264") && probe2.Contains("320") && probe2.Contains("240"),
        $"ffprobe verifies decoded.mp4 [{probe2.Trim()}]");

    // (g) the real preview window renders a real decoded frame
    var vengine = new BridgeEngine(AppConfig.Load(), AppSettings.Load());
    byte[] frame0;
    lock (frames) frame0 = frames[0];
    VSetField(vengine, "_latestFrame", frame0);
    VSetField(vengine, "_latestFrameW", 320);
    VSetField(vengine, "_latestFrameH", 240);
    VSetField(vengine, "_latestFrameIndex", 1L);
    var preview = new VideoPreviewWindow(vengine);
    preview.Show();
    Dispatcher.UIThread.RunJobs();
    preview.RefreshFrame();
    Dispatcher.UIThread.RunJobs();
    SaveRender(preview, Path.Combine(vidDir, "preview-window.png"), 96);
    var imgCtrl = preview.FindControl<Avalonia.Controls.Image>("img");
    VAssert(imgCtrl?.Source is Avalonia.Media.Imaging.WriteableBitmap,
        "preview window displays the decoded frame as a WriteableBitmap");
    var phCtrl = preview.FindControl<TextBlock>("placeholder");
    VAssert(phCtrl?.IsVisible == false, "preview placeholder hidden once a frame is shown");
    preview.Close();

    if (videoFails > 0) throw new InvalidOperationException($"{videoFails} video assertion(s) failed");
    Console.WriteLine("SMOKE: VIDEO STAGE OK");
}
catch (Exception ex)
{
    Console.WriteLine("SMOKE: VIDEO STAGE FAILED");
    Console.WriteLine(ex.ToString());
    return 6;
}

try
{
    // ---- Stage 7 (board item #2): bilingual coverage. Seed the real
    // settings file with each Language value ("" / "zh" / "en") and
    // construct a fresh MainWindow — the ctor applies the persisted
    // override through the production path (AppSettings -> Loc). Walk
    // every visible string in the tree (all pages are in the tree even
    // when not shown) plus the tray headers:
    //   EN mode: no CJK anywhere outside the brand/device whitelist.
    //   ZH mode: no collected string equals an English Map value.
    // Live switching is exercised both ways via the private SetLanguage
    // (the Settings segmented control's handler). Settings + config
    // files are restored byte-identical afterwards (Stage 5 discipline).
    Console.WriteLine("SMOKE: --- language stage ---");
    int langFails = 0;
    void LAssert(bool cond, string what)
    {
        Console.WriteLine($"SMOKE: lang-assert {(cond ? "ok" : "FAILED")}: {what}");
        if (!cond) langFails++;
    }
    static object? LGetField(object obj, string name) =>
        obj.GetType().GetField(name, BindingFlags.NonPublic | BindingFlags.Instance)?.GetValue(obj);
    static void LInvoke(object obj, string method, params object?[] args) =>
        (obj.GetType().GetMethod(method, BindingFlags.NonPublic | BindingFlags.Instance)
            ?? throw new InvalidOperationException($"method {method} not found on {obj.GetType().Name}")).Invoke(obj, args);

    string[] cjkWhitelist = { "听说", "声桥", "中文" }; // brand, shipped device name, language option labels (own-script by design)
    static bool HasCjk(string s) => s.Any(c => c is >= '一' and <= '鿿');
    var enValues = new HashSet<string>(Loc.Map.Values
        .Where(v => v.Count(c => c is >= 'a' and <= 'z' or >= 'A' and <= 'Z') >= 2)
        .Where(v => v != "English")); // the language option label is "English" in both modes by design

    List<string> CollectTexts(Control root, MainWindow w)
    {
        var texts = new List<string>();
        // Logical tree, same as Loc.ApplyTo: hidden pages and unscrolled
        // ScrollViewer content have no visual descendants — a visual walk
        // would share ApplyTo's old blind spot and pass vacuously.
        foreach (var v in root.GetLogicalDescendants())
        {
            if (v is TextBlock tb && !string.IsNullOrEmpty(tb.Text)) texts.Add(tb.Text!);
            if (v is Button b && b.Content is string bs && bs.Length > 0) texts.Add(bs);
            if (v is Control c && ToolTip.GetTip(c) is string ts && ts.Length > 0) texts.Add(ts);
        }
        foreach (var f in new[] { "_trayStatus", "_trayPause", "_trayMic", "_traySpk", "_trayDisc", "_trayOpen", "_traySettings", "_trayExit" })
            if (LGetField(w, f) is NativeMenuItem item && !string.IsNullOrEmpty(item.Header)) texts.Add(item.Header!);
        return texts;
    }
    void AssertNoCjk(MainWindow w, string label)
    {
        var texts = CollectTexts(w, w);
        var bad = texts.Where(t => HasCjk(t) && !cjkWhitelist.Any(x => t.Contains(x))).Distinct().ToList();
        foreach (var b in bad) Console.WriteLine($"SMOKE: lang-offender [{label}]: {b}");
        LAssert(bad.Count == 0, $"{label}: no CJK in {texts.Count} walked texts");
    }
    void AssertNoEnglish(MainWindow w, string label)
    {
        var texts = CollectTexts(w, w);
        var bad = texts.Where(t => enValues.Contains(t)).Distinct().ToList();
        foreach (var b in bad) Console.WriteLine($"SMOKE: lang-offender [{label}]: {b}");
        LAssert(bad.Count == 0, $"{label}: no English Map values in {texts.Count} walked texts");
    }
    void ShowSettingsPage(MainWindow w)
    {
        foreach (var name in new[] { "pageDevice", "pageDiscover", "pageAbout" })
            if (w.FindControl<Grid>(name) is { } g) g.IsVisible = false;
        if (w.FindControl<Grid>("pageSettings") is { } s) s.IsVisible = true;
        Dispatcher.UIThread.RunJobs();
    }
    void ScrollSettingsToBottom(MainWindow w)
    {
        if (w.FindControl<Grid>("pageSettings") is { } s
            && s.GetLogicalDescendants().OfType<ScrollViewer>().FirstOrDefault() is { } sv)
        {
            sv.Offset = new Vector(0, double.MaxValue);
            Dispatcher.UIThread.RunJobs();
        }
    }

    string settingsPath2 = AppSettings.FilePath;
    byte[]? settingsBackup = File.Exists(settingsPath2) ? File.ReadAllBytes(settingsPath2) : null;
    string configPath = AppConfig.FilePath;
    byte[]? configBackup = File.Exists(configPath) ? File.ReadAllBytes(configPath) : null;
    void RestoreFiles()
    {
        if (settingsBackup is not null) File.WriteAllBytes(settingsPath2, settingsBackup);
        else if (File.Exists(settingsPath2)) File.Delete(settingsPath2);
        if (configBackup is not null) File.WriteAllBytes(configPath, configBackup);
        else if (File.Exists(configPath)) File.Delete(configPath);
    }
    try
    {
        File.WriteAllText(configPath, "{}"); // clean identity: no paired phones with user-data names in the tree
        void SeedLang(string lang)
        {
            var s = AppSettings.Load();
            s.Language = lang;
            s.AutoCheckUpdates = false; // keep stage windows off the network
            s.Save();
        }

        // -- persisted EN: fresh window renders English end to end --
        SeedLang("en");
        var wEn = new MainWindow();
        wEn.Show();
        Dispatcher.UIThread.RunJobs();
        LAssert(!Loc.IsZh, "seeded Language=en: Loc.IsZh == false after ctor");
        LAssert(AppSettings.Load().Language == "en", "seeded Language=en survives the ctor (new field not dropped)");
        LAssert(wEn.FindControl<SegmentedControl>("segLanguage") is not null, "settings page has the segLanguage control");
        AssertNoCjk(wEn, "en-fresh");
        SaveRender(wEn, Path.Combine(outDir, "window-en-1x.png"), 96);
        ShowSettingsPage(wEn);
        SaveRender(wEn, Path.Combine(outDir, "settings-en-1x.png"), 96);
        ScrollSettingsToBottom(wEn);
        SaveRender(wEn, Path.Combine(outDir, "settings-en-bottom-1x.png"), 96);

        // -- live switch EN -> ZH on the same window --
        LInvoke(wEn, "SetLanguage", "zh");
        Dispatcher.UIThread.RunJobs();
        LAssert(Loc.IsZh, "live switch to zh: Loc.IsZh == true");
        LAssert(AppSettings.Load().Language == "zh", "live switch to zh persisted");
        AssertNoEnglish(wEn, "en-window-live-zh");

        // -- persisted ZH: fresh window renders Chinese end to end --
        SeedLang("zh");
        var wZh = new MainWindow();
        wZh.Show();
        Dispatcher.UIThread.RunJobs();
        LAssert(Loc.IsZh, "seeded Language=zh: Loc.IsZh == true after ctor");
        AssertNoEnglish(wZh, "zh-fresh");
        SaveRender(wZh, Path.Combine(outDir, "window-zh-1x.png"), 96);
        ShowSettingsPage(wZh);
        SaveRender(wZh, Path.Combine(outDir, "settings-zh-1x.png"), 96);
        ScrollSettingsToBottom(wZh);
        SaveRender(wZh, Path.Combine(outDir, "settings-zh-bottom-1x.png"), 96);

        // -- live switch ZH -> EN, then the preview window in EN --
        LInvoke(wZh, "SetLanguage", "en");
        Dispatcher.UIThread.RunJobs();
        AssertNoCjk(wZh, "zh-window-live-en");
        var vengine2 = new BridgeEngine(AppConfig.Load(), AppSettings.Load());
        var preview2 = new VideoPreviewWindow(vengine2);
        preview2.Show();
        Dispatcher.UIThread.RunJobs();
        var ph2 = preview2.FindControl<TextBlock>("placeholder");
        LAssert(ph2?.Text == "Waiting for the phone camera…", "preview window placeholder renders in English");
        preview2.Close();
        wEn.Hide();
        wZh.Hide();
    }
    finally
    {
        RestoreFiles();
        Loc.Override = null;
    }

    if (langFails > 0) throw new InvalidOperationException($"{langFails} language assertion(s) failed");
    Console.WriteLine("SMOKE: LANGUAGE STAGE OK");
}
catch (Exception ex)
{
    Console.WriteLine("SMOKE: LANGUAGE STAGE FAILED");
    Console.WriteLine(ex.ToString());
    return 7;
}

Console.WriteLine("SMOKE: PASS");
return 0;

static string DescribeBrush(object? o) => o switch
{
    null => "null",
    Avalonia.Media.ISolidColorBrush s => "solid " + s.Color.ToString(),
    Avalonia.Media.IBrush b => b.GetType().Name,
    _ => o.GetType().Name,
};

static RenderTargetBitmap RenderBitmap(Control control, double dpi)
{
    Dispatcher.UIThread.RunJobs();
    double scale = dpi / 96.0;
    var size = control.Bounds.Size;
    var pixels = new PixelSize(
        Math.Max(1, (int)Math.Ceiling(size.Width * scale)),
        Math.Max(1, (int)Math.Ceiling(size.Height * scale)));
    var bitmap = new RenderTargetBitmap(pixels, new Vector(dpi, dpi));
    bitmap.Render(control);
    return bitmap;
}

static void SaveRender(Control control, string path, double dpi)
{
    using var bitmap = RenderBitmap(control, dpi);
    bitmap.Save(path);
    Console.WriteLine($"SMOKE: saved {path} ({bitmap.PixelSize.Width}x{bitmap.PixelSize.Height})");
}

static (byte[] px, int w, int h, int stride) ReadPixels(RenderTargetBitmap bmp)
{
    var size = bmp.PixelSize;
    int stride = size.Width * 4; // Bgra8888
    var buf = new byte[stride * size.Height];
    var handle = System.Runtime.InteropServices.GCHandle.Alloc(buf, System.Runtime.InteropServices.GCHandleType.Pinned);
    try { bmp.CopyPixels(new PixelRect(0, 0, size.Width, size.Height), handle.AddrOfPinnedObject(), buf.Length, stride); }
    finally { handle.Free(); }
    return (buf, size.Width, size.Height, stride);
}

/// <summary>Pixels inside <paramref name="rect"/> whose colour differs
/// from the title-bar background (sampled at pixel (4,4), bare BgTop) by
/// a summed-channel distance &gt; 60 — the same metric used to quantify
/// the invisible-minimize bug offline (baseline: btnMin = 0).</summary>
static int CountNonBackground(byte[] px, int stride, PixelRect rect)
{
    int bgOff = 4 * stride + 4 * 4;
    int bb = px[bgOff], bg = px[bgOff + 1], br = px[bgOff + 2];
    int n = 0;
    for (int y = rect.Y; y < rect.Y + rect.Height; y++)
        for (int x = rect.X; x < rect.X + rect.Width; x++)
        {
            int o = y * stride + x * 4;
            int d = Math.Abs(px[o] - bb) + Math.Abs(px[o + 1] - bg) + Math.Abs(px[o + 2] - br);
            if (d > 60) n++;
        }
    return n;
}

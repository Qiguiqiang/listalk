using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Threading;

namespace AirBridge;

/// <summary>
/// Online update (v2 requirement). The app checks a fixed update manifest on
/// startup (background, non-blocking) and via 设置 → 检查更新：
///   GET {UpdateManifestUrl} -> { "windows": { "version", "url", "sha256", "notes" } }
/// If the manifest version is newer than CurrentVersion, the user sees the
/// release notes; on confirm the NSIS installer is downloaded to %TEMP%, its
/// SHA-256 is verified against the manifest, and ONLY a verified file is
/// launched (it upgrades in place); the app then exits. A hash mismatch
/// aborts with an error dialog and the downloaded file is deleted — an
/// unverified file is never executed.
/// </summary>
public static class UpdateChecker
{
    public const string UpdateManifestUrl = "https://muse.ai/s/airbridge-update/update.json";
    public const string CurrentVersion = "2.1.0";

    public sealed record UpdateInfo(string Version, string Url, string Sha256, string Notes);
    public sealed record ManifestInfo(UpdateInfo? Windows, UpdateInfo? Android);

    private static UpdateInfo? ParseEntry(JsonElement root, string key)
    {
        if (!root.TryGetProperty(key, out var w)) return null;
        string version = w.TryGetProperty("version", out var v) ? v.GetString() ?? "" : "";
        string url = w.TryGetProperty("url", out var u) ? u.GetString() ?? "" : "";
        string sha = w.TryGetProperty("sha256", out var s) ? s.GetString() ?? "" : "";
        string notes = w.TryGetProperty("notes", out var n) ? n.GetString() ?? "" : "";
        if (version.Length == 0 || url.Length == 0) return null;
        return new UpdateInfo(version, url, sha, notes);
    }

    /// <summary>Fetches the manifest and parses BOTH the "windows" and the "android" entries.</summary>
    public static async Task<ManifestInfo?> FetchAsync(string manifestUrl)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("AirBridge/2.1.0");
        string json = await http.GetStringAsync(manifestUrl);
        using var doc = JsonDocument.Parse(json);
        return new ManifestInfo(ParseEntry(doc.RootElement, "windows"), ParseEntry(doc.RootElement, "android"));
    }

    /// <summary>
    /// Full check report (v2.2 defect B): never throws, never silent —
    /// returns the URL tried, the HTTP status, the parsed manifest, and a
    /// plain-language error. The UI renders exactly one outcome from it:
    /// 已是最新版本 / 发现新版本 x.y.z / 检查失败：&lt;reason&gt;.
    /// </summary>
    public sealed record FetchReport(string Url, int? HttpStatus, ManifestInfo? Manifest, string? Error)
    {
        /// <summary>One-line diagnostics summary (URL + HTTP status + manifest versions).</summary>
        public string Summary => Error is not null
            ? $"GET {Url} → 失败：{Error}"
            : $"GET {Url} → HTTP {HttpStatus} · windows={Manifest?.Windows?.Version ?? "无"} · android={Manifest?.Android?.Version ?? "无"}";
    }

    public static async Task<FetchReport> FetchReportAsync(string manifestUrl)
    {
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("AirBridge/2.1.0");
            using var resp = await http.GetAsync(manifestUrl);
            int code = (int)resp.StatusCode;
            if (!resp.IsSuccessStatusCode)
                return new FetchReport(manifestUrl, code, null, $"服务器返回 HTTP {code}");
            string json = await resp.Content.ReadAsStringAsync();
            try
            {
                using var doc = JsonDocument.Parse(json);
                var manifest = new ManifestInfo(ParseEntry(doc.RootElement, "windows"), ParseEntry(doc.RootElement, "android"));
                if (manifest.Windows is null && manifest.Android is null)
                    return new FetchReport(manifestUrl, code, null, "更新清单无效（内容无法解析）");
                return new FetchReport(manifestUrl, code, manifest, null);
            }
            catch (JsonException)
            {
                return new FetchReport(manifestUrl, code, null, "更新清单无效（内容无法解析）");
            }
        }
        catch (Exception ex)
        {
            return new FetchReport(manifestUrl, null, null, $"网络不可达：{ex.Message}");
        }
    }

    public static bool IsNewerThan(string remoteVersion, string baseline) =>
        Version.TryParse(remoteVersion, out var rv) &&
        Version.TryParse(baseline, out var cv) &&
        rv > cv;

    public static bool IsNewer(string remoteVersion) =>
        Version.TryParse(remoteVersion, out var rv) &&
        Version.TryParse(CurrentVersion, out var cv) &&
        rv > cv;

    public static async Task<string> DownloadAsync(UpdateInfo info, IProgress<double>? progress = null)
    {
        string dest = Path.Combine(Path.GetTempPath(), $"AirBridge-Setup-{info.Version}.exe");
        using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("AirBridge/2.1.0");
        using var resp = await http.GetAsync(info.Url, HttpCompletionOption.ResponseHeadersRead);
        resp.EnsureSuccessStatusCode();
        long total = resp.Content.Headers.ContentLength ?? -1;
        await using var src = await resp.Content.ReadAsStreamAsync();
        await using var dst = File.Create(dest);
        var buf = new byte[81920];
        long done = 0; int read;
        while ((read = await src.ReadAsync(buf)) > 0)
        {
            await dst.WriteAsync(buf.AsMemory(0, read));
            done += read;
            if (total > 0) progress?.Report(done * 100.0 / total);
        }
        return dest;
    }

    /// <summary>SHA-256 gate. Returns true only when the file hash matches the manifest value.</summary>
    public static async Task<bool> VerifySha256Async(string filePath, string expectedHex)
    {
        if (string.IsNullOrWhiteSpace(expectedHex)) return false;
        await using var fs = File.OpenRead(filePath);
        byte[] hash = await SHA256.HashDataAsync(fs);
        string actual = Convert.ToHexString(hash).ToLowerInvariant();
        return string.Equals(actual, expectedHex.Trim().ToLowerInvariant(), StringComparison.Ordinal);
    }
}

/// <summary>Update dialog: text plus an optional QR code (used for the partner/Android
/// update — the QR encodes the android entry's url from the manifest so the user can
/// scan it with the phone and open the update page).</summary>
public static class UpdateDialog
{
    public static Task<bool> ShowAsync(Window owner, string title, string text, string? qrPayload, string okText, string? cancelText)
    {
        var tcs = new TaskCompletionSource<bool>();
        Dispatcher.UIThread.Post(() =>
        {
            var fg1 = new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse("#F2F5F9"));
            var fg2 = new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse("#9AA7B8"));
            var win = new Window
            {
                Title = title, Width = 460, Height = qrPayload is null ? 300 : 520,
                WindowStartupLocation = WindowStartupLocation.CenterOwner,
                Background = new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse("#0B0E13")),
            };
            var panel = new StackPanel { Margin = new Thickness(20), Spacing = 14 };
            panel.Children.Add(new TextBlock { Text = title, FontSize = 16, FontWeight = Avalonia.Media.FontWeight.Bold, Foreground = fg1 });
            panel.Children.Add(new TextBlock { Text = text, TextWrapping = Avalonia.Media.TextWrapping.Wrap, Foreground = fg2 });
            if (qrPayload is not null)
            {
                try
                {
                    var gen = new QRCoder.QRCodeGenerator();
                    var data = gen.CreateQrCode(qrPayload, QRCoder.QRCodeGenerator.ECCLevel.M);
                    var png = new QRCoder.PngByteQRCode(data).GetGraphic(8);
                    panel.Children.Add(new Image { Source = new Avalonia.Media.Imaging.Bitmap(new MemoryStream(png)), Width = 180, Height = 180, HorizontalAlignment = HorizontalAlignment.Center });
                    panel.Children.Add(new TextBlock { Text = "用手机扫码打开手机端更新页", Foreground = fg2, HorizontalAlignment = HorizontalAlignment.Center });
                }
                catch { }
            }
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, HorizontalAlignment = HorizontalAlignment.Right };
            var ok = new Button { Content = okText };
            ok.Click += (_, _) => { tcs.TrySetResult(true); win.Close(); };
            if (cancelText is not null)
            {
                var cancel = new Button { Content = cancelText };
                cancel.Click += (_, _) => { tcs.TrySetResult(false); win.Close(); };
                row.Children.Add(cancel);
            }
            row.Children.Add(ok);
            win.Closed += (_, _) => tcs.TrySetResult(false);
            panel.Children.Add(row);
            win.Content = panel;
            win.ShowDialog(owner);
        });
        return tcs.Task;
    }
}

/// <summary>Minimal in-app dialogs (Avalonia has no built-in MessageBox).</summary>
public static class UiDialogs
{
    public static Task<bool> ConfirmAsync(Window owner, string title, string text, string okText, string cancelText)
    {
        var tcs = new TaskCompletionSource<bool>();
        Dispatcher.UIThread.Post(() =>
        {
            var win = new Window
            {
                Title = title, Width = 440, Height = 300,
                WindowStartupLocation = WindowStartupLocation.CenterOwner,
                Background = new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse("#0B0E13")),
            };
            var panel = new StackPanel { Margin = new Thickness(20), Spacing = 14 };
            panel.Children.Add(new TextBlock { Text = title, FontSize = 16, FontWeight = Avalonia.Media.FontWeight.Bold, Foreground = new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse("#F2F5F9")) });
            panel.Children.Add(new TextBlock { Text = text, TextWrapping = Avalonia.Media.TextWrapping.Wrap, Foreground = new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse("#9AA7B8")) });
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, HorizontalAlignment = HorizontalAlignment.Right };
            var ok = new Button { Content = okText };
            var cancel = new Button { Content = cancelText };
            ok.Click += (_, _) => { tcs.TrySetResult(true); win.Close(); };
            cancel.Click += (_, _) => { tcs.TrySetResult(false); win.Close(); };
            win.Closed += (_, _) => tcs.TrySetResult(false);
            row.Children.Add(cancel); row.Children.Add(ok);
            panel.Children.Add(row);
            win.Content = panel;
            win.ShowDialog(owner);
        });
        return tcs.Task;
    }

    public static Task InfoAsync(Window owner, string title, string text)
    {
        var tcs = new TaskCompletionSource<bool>();
        Dispatcher.UIThread.Post(() =>
        {
            var win = new Window
            {
                Title = title, Width = 440, Height = 260,
                WindowStartupLocation = WindowStartupLocation.CenterOwner,
                Background = new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse("#0B0E13")),
            };
            var panel = new StackPanel { Margin = new Thickness(20), Spacing = 14 };
            panel.Children.Add(new TextBlock { Text = title, FontSize = 16, FontWeight = Avalonia.Media.FontWeight.Bold, Foreground = new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse("#F2F5F9")) });
            panel.Children.Add(new TextBlock { Text = text, TextWrapping = Avalonia.Media.TextWrapping.Wrap, Foreground = new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse("#9AA7B8")) });
            var ok = new Button { Content = "知道了", HorizontalAlignment = HorizontalAlignment.Right };
            ok.Click += (_, _) => { tcs.TrySetResult(true); win.Close(); };
            win.Closed += (_, _) => tcs.TrySetResult(true);
            panel.Children.Add(ok);
            win.Content = panel;
            win.ShowDialog(owner);
        });
        return tcs.Task;
    }
}

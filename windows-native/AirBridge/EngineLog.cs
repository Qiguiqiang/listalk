using System.Text;

namespace AirBridge;

/// <summary>
/// Persistent engine log (discovery hardening §5.4/§5.8): the bridge
/// engine's log lines used to live only in an in-memory, 200-line UI
/// list — gone on restart, not exportable, and "nothing found" produced
/// zero lines anywhere. This is a ring-buffer FILE
/// (%LOCALAPPDATA%\AirBridge\logs\engine.log): append-only until it
/// grows past ~512 KB, then trimmed back to the newest 1200 lines.
/// Fail-safe like StartupLog: logging must never take the app down.
/// </summary>
public static class EngineLog
{
    private static readonly object Lock = new();
    private const long MaxBytes = 512 * 1024;
    private const int KeepLines = 1200;
    private static int _sinceTrim;

    public static string FilePath => Path.Combine(StartupLog.DirPath, "engine.log");

    public static void Write(string line)
    {
        try
        {
            lock (Lock)
            {
                Directory.CreateDirectory(StartupLog.DirPath);
                File.AppendAllText(FilePath,
                    $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] {line}{Environment.NewLine}",
                    Encoding.UTF8);
                if (++_sinceTrim >= 200)
                {
                    _sinceTrim = 0;
                    TrimLocked();
                }
            }
        }
        catch { /* never throw from logging */ }
    }

    private static void TrimLocked()
    {
        try
        {
            var info = new FileInfo(FilePath);
            if (!info.Exists || info.Length <= MaxBytes) return;
            var lines = File.ReadAllLines(FilePath);
            if (lines.Length <= KeepLines) return;
            File.WriteAllLines(FilePath, lines[^KeepLines..], Encoding.UTF8);
        }
        catch { }
    }

    /// <summary>Newest log lines for a one-tap diagnostic export (§5.4).</summary>
    public static string[] Tail(int maxLines = 200)
    {
        try
        {
            lock (Lock)
            {
                if (!File.Exists(FilePath)) return Array.Empty<string>();
                var lines = File.ReadAllLines(FilePath);
                return lines.Length <= maxLines ? lines : lines[^maxLines..];
            }
        }
        catch { return Array.Empty<string>(); }
    }
}

using System.Runtime.InteropServices;
using System.Text;

namespace AirBridge;

/// <summary>
/// Startup diagnostics (v2.2.1, FB-2): staged checkpoints + fatal-exception
/// capture in %LOCALAPPDATA%\AirBridge\logs\startup.log.
/// Before this existed, ANY exception during startup (config, XAML load,
/// tray, device enumeration, socket bind…) killed the process with no
/// window, no dialog and no trace — the field report was simply "打不开".
/// Now every launch leaves a stage trail; a fatal failure shows a native
/// dialog naming this log file. Every method is fail-safe: logging must
/// never take the app down.
/// </summary>
public static class StartupLog
{
    private static readonly object Lock = new();

    public static string DirPath => Path.Combine(AppConfig.DirPath, "logs");
    public static string FilePath => Path.Combine(DirPath, "startup.log");

    /// <summary>Set true at the very end of the MainWindow constructor.
    /// Dispatcher exceptions after this point are logged and swallowed so a
    /// stray UI error cannot kill the tray-resident app; before it they are
    /// left to crash into Program.Main's catch, which shows the dialog.</summary>
    public static volatile bool StartupCompleted;

    public static void Stage(string stage) => Write($"[stage] {stage}");
    public static void Info(string msg) => Write($"[info] {msg}");
    public static void Error(string where, Exception ex) => Write($"[ERROR] {where}: {ex}");

    private static void Write(string line)
    {
        try
        {
            lock (Lock)
            {
                Directory.CreateDirectory(DirPath);
                File.AppendAllText(FilePath,
                    $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] [pid {Environment.ProcessId}] {line}{Environment.NewLine}",
                    Encoding.UTF8);
            }
        }
        catch { /* never throw from logging */ }
    }

    /// <summary>Last-resort native dialog — works even when Avalonia never
    /// came up. Text follows the same culture rule as Loc (zh → Chinese,
    /// everything else → English); the log path is appended.</summary>
    public static void ShowFatalDialog(string zh, string en) => ShowDialog(zh, en, 0x10); // MB_ICONERROR
    public static void ShowInfoDialog(string zh, string en) => ShowDialog(zh, en, 0x40);  // MB_ICONINFORMATION

    private static void ShowDialog(string zh, string en, uint icon)
    {
        try
        {
            string text = (Loc.IsZh ? zh : en) + "\n\n" + FilePath;
            string caption = Loc.IsZh ? "听说 LisTalk" : "LisTalk";
            _ = MessageBoxW(IntPtr.Zero, text, caption, icon);
        }
        catch { }
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int MessageBoxW(IntPtr hWnd, string text, string caption, uint type);
}

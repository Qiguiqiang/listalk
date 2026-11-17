using Avalonia;

namespace AirBridge;

internal static class Program
{
    // Held for the process lifetime: the single-instance guard (FB-2).
    private static Mutex? _singleInstance;

    [STAThread]
    public static int Main(string[] args)
    {
        StartupLog.Stage($"process start (v{UpdateChecker.CurrentVersion}), args=[{string.Join(" ", args)}]");

        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            if (e.ExceptionObject is Exception ex)
                StartupLog.Error("AppDomain.UnhandledException", ex);
        };
        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            StartupLog.Error("UnobservedTaskException", e.Exception);
            e.SetObserved();
        };

        // Single instance (FB-2 hardening). The app lives in the tray and can
        // be autostarted, so a second launch (installer finish page, desktop
        // shortcut while the tray instance runs, …) previously started a
        // full second copy fighting the first over the UDP port / audio
        // devices — or died trying, looking exactly like "打不开". Now the
        // second launch says so and exits cleanly.
        bool createdNew;
        try
        {
            _singleInstance = new Mutex(initiallyOwned: true, "SoundRelay-AirBridge-SingleInstance", out createdNew);
        }
        catch (AbandonedMutexException)
        {
            createdNew = true; // previous instance died without releasing — we own it now
        }
        if (!createdNew)
        {
            StartupLog.Info("another instance is already running — this launch exits");
            StartupLog.ShowInfoDialog(
                "声音接力 SoundRelay 已经在运行（请看任务栏右下角托盘图标）。\n双击托盘图标即可打开主窗口。本次启动将退出。",
                "SoundRelay is already running (see the tray icon in the taskbar corner).\nDouble-click the tray icon to open the main window. This launch will exit.");
            return 0;
        }

        try
        {
            BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
            StartupLog.Stage("process exit (clean)");
            return 0;
        }
        catch (Exception ex)
        {
            // The dialog is the whole point of FB-2's fix: a failed launch
            // must never again be a silent no-show.
            StartupLog.Error("fatal: StartWithClassicDesktopLifetime", ex);
            StartupLog.ShowFatalDialog(
                $"声音接力 SoundRelay 启动失败：\n{ex.GetType().Name}: {ex.Message}\n\n启动日志已写入下面这个文件，把它发给开发者即可定位原因：",
                $"SoundRelay failed to start:\n{ex.GetType().Name}: {ex.Message}\n\nA startup log was written to the file below — send it to the developer to pinpoint the cause:");
            return 1;
        }
    }

    public static AppBuilder BuildAvaloniaApp() => AppBuilder.Configure<App>()
        .UsePlatformDetect()
        .LogToTrace();
}

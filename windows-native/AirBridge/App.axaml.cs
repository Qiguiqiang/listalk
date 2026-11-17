using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Avalonia.Threading;

namespace AirBridge;

public partial class App : Application
{
    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
        // FB-2: UI-thread exceptions must at least leave evidence. Before
        // startup completes they are NOT marked handled — they crash into
        // Program.Main's catch and the user gets the fatal dialog + log
        // path. After startup, the tray app stays alive and just logs.
        Dispatcher.UIThread.UnhandledException += (_, e) =>
        {
            StartupLog.Error("Dispatcher.UIThread.UnhandledException", e.Exception);
            if (StartupLog.StartupCompleted) e.Handled = true;
        };
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var window = new MainWindow();
            desktop.MainWindow = window;
            // The app lives in the tray: lifetime ends only via tray 退出 / MainWindow.RequestExit.
            desktop.ShutdownMode = Avalonia.Controls.ShutdownMode.OnExplicitShutdown;
            MainWindow.ExitAction = () => desktop.Shutdown();
        }
        base.OnFrameworkInitializationCompleted();
    }
}

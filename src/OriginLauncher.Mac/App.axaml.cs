using System.Reflection;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Layout;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac;

public sealed class App : Application
{
    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
        UI.Motion.ApplyTo(this);
        UI.ToolTipRules.Apply(); // hover labels: the Windows launcher's timing and placement
        // The two logo files are the only logos (DECISIONS, Look): loaded once as bitmaps, used by key, like the WPF
        // Theme/Brand.xaml.
        Resources["Brand.Logo"] = LoadBitmap("avares://OriginLauncher/Assets/Brand/origin_logo.png");
        Resources["Brand.Wordmark"] = LoadBitmap("avares://OriginLauncher/Assets/Brand/wordmark.png");
    }

    private static Bitmap LoadBitmap(string uri)
    {
        using var stream = AssetLoader.Open(new Uri(uri));
        return new Bitmap(stream);
    }

    public override void OnFrameworkInitializationCompleted()
    {
        // The headless render-shots run builds its own windows (UI/RenderShots.cs); only a real start opens one here.
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.MainWindow = SignInProbe.Dir != null ? SignInProbe.Start(desktop) : new MainWindow();
            OriginLauncher.App.Core.Updates.UpdateService.Shutdown = () => QuitForUpdate(desktop);
        }
        base.OnFrameworkInitializationCompleted();
    }

    // The self-update's quit (UpdateService.Mac.cs): the swap script waits for this process to be gone, so it must go.
    // On the UI thread (the update's last step may resume on a pool thread), and a watchdog ends the process if anything
    // held the app open, so the update always installs and the script always reopens the launcher. A background thread:
    // a clean exit never waits for it.
    private static void QuitForUpdate(IClassicDesktopStyleApplicationLifetime desktop)
    {
        new Thread(() =>
        {
            Thread.Sleep(TimeSpan.FromSeconds(10));
            Environment.Exit(0);
        }) { IsBackground = true, Name = "update-quit-watchdog" }.Start();
        if (Avalonia.Threading.Dispatcher.UIThread.CheckAccess()) desktop.Shutdown();
        else Avalonia.Threading.Dispatcher.UIThread.Post(() => desktop.Shutdown());
    }

    internal static string Version =>
        typeof(App).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0]
        ?? typeof(App).Assembly.GetName().Version?.ToString(3) ?? "";

    private Window? _about;

    // App menu > About Origin Launcher: a small fixed window with the logo, the name and the version.
    private void About_Click(object? sender, EventArgs e)
    {
        if (_about != null) { _about.Activate(); return; }
        _about = new Window
        {
            Title = "About Origin Launcher",
            Width = 320,
            Height = 240,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterScreen,
            Background = Find<IBrush>("Brush.Panel"),
            Content = new StackPanel
            {
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Spacing = 8,
                Children =
                {
                    new BrandLogo { Width = 64, Height = 64, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 0, 0, 8) },
                    new TextBlock { Text = "Origin Launcher", Theme = Find<Avalonia.Styling.ControlTheme>("Text.Title"), HorizontalAlignment = HorizontalAlignment.Center },
                    new TextBlock { Text = "Version " + Version, Theme = Find<Avalonia.Styling.ControlTheme>("Text.Caption"), HorizontalAlignment = HorizontalAlignment.Center },
                    new TextBlock { Text = "originclient.org", Theme = Find<Avalonia.Styling.ControlTheme>("Text.CaptionDim"), HorizontalAlignment = HorizontalAlignment.Center },
                },
            },
        };
        _about.Closed += (_, _) => _about = null;
        _about.Show();
    }

    // App menu > Settings... (Cmd+,): the Settings page, the way its tab opens it.
    private void Settings_Click(object? sender, EventArgs e)
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime { MainWindow: MainWindow window })
            window.OpenSettingsFromMenu();
    }

    private T? Find<T>(string key) where T : class => TryGetResource(key, null, out var v) ? v as T : null;
}

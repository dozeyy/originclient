using Avalonia.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Pages;

// STUB: replaced by the Settings page port.
public sealed class SettingsPage : Panel, IPage
{
    private readonly MainWindow _window;

    public SettingsPage(MainWindow window)
    {
        _window = window;
        Children.Add(Ui.Heading("Settings").Margin(32, 32, 32, 32));
    }

    public void Shown() => _ = _window;
}

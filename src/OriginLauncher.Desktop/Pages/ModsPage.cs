using Avalonia.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Pages;

// STUB: replaced by the Mods page port.
public sealed class ModsPage : Panel, IPage
{
    private readonly MainWindow _window;

    public ModsPage(MainWindow window)
    {
        _window = window;
        Children.Add(Ui.Heading("Mods").Margin(32, 32, 32, 32));
    }

    public void Shown() => _ = _window;
}

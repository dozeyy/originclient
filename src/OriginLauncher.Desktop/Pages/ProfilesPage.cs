using Avalonia.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Pages;

// STUB: replaced by the Profiles page port.
public sealed class ProfilesPage : Panel, IPage
{
    private readonly MainWindow _window;

    public ProfilesPage(MainWindow window)
    {
        _window = window;
        Children.Add(Ui.Heading("Profiles").Margin(32, 32, 32, 32));
    }

    public void Shown() => _ = _window;
}

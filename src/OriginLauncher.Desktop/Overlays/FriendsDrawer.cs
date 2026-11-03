using Avalonia.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Overlays;

// STUB: replaced by the friends / social port.
public sealed class FriendsDrawer : Panel
{
    public FriendsDrawer(MainWindow window)
    {
        Children.Add(Ui.Title("Friends").Margin(24, 24, 24, 24));
        _ = window;
    }

    /// <summary>Starts the social connection once the window is up (real runs only).</summary>
    public static void StartSocial(MainWindow window) => _ = window;
}

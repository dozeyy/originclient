using Avalonia.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Overlays;

// STUB: replaced by the accounts + Microsoft sign-in port.
public sealed class AccountDrawer : Panel
{
    public AccountDrawer(MainWindow window)
    {
        Children.Add(Ui.Title("Accounts").Margin(24, 24, 24, 24));
        _ = window;
    }
}

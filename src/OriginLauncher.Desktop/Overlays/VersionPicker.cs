using Avalonia.Controls;
using OriginLauncher.Desktop.Kit;

namespace OriginLauncher.Desktop.Overlays;

// STUB: replaced by the version picker port (DESIGN-SYSTEM.md 11.3).
public sealed class VersionPicker : Panel
{
    public event Action<string>? Picked;
    public event Action? Dismissed;

    public VersionPicker(string selected)
    {
        Children.Add(Ui.Heading("Versions " + selected));
        PointerPressed += (_, _) => Dismissed?.Invoke();
        Picked?.Invoke(selected);
    }
}

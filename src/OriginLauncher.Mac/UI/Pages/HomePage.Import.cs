using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac.UI.Pages;

// Home's bring-over button (twin of the WPF HomePage's BringOver): it sits on Home, so it shows only while Home does.
// Built on first use and added on top of Home's own layers, so Home's axaml stays untouched and a launcher that never
// offers it never builds it.
public sealed partial class HomePage
{
    private BringOverButton? _bringOver;

    /// <summary>The button when it has been built (the run-state refusals reach it only then; Offer builds it).</summary>
    internal BringOverButton? BringOverIfBuilt => _bringOver;

    internal BringOverButton BringOver
    {
        get
        {
            if (_bringOver != null) return _bringOver;
            _bringOver = new BringOverButton();
            BringOverBuilt?.Invoke(_bringOver);
            // Under the version picker and the Fix prompt, like the WPF Home's order: an overlay always covers it.
            HomeRoot.Children.Insert(HomeRoot.Children.IndexOf(VersionOverlay), _bringOver);
            return _bringOver;
        }
    }

    /// <summary>The button was just built: the window hooks its presses once (MainWindow.Import.cs).</summary>
    internal event Action<BringOverButton>? BringOverBuilt;
}

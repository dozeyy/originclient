using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac.UI.Pages;

// Home's import slot (twin of the WPF HomePage's ImportCard): the one-time welcome card sits over Home, so it shows
// only while Home does. Built on first use and added on top of Home's own layers, so Home's axaml stays untouched and
// a launcher that never shows the card never builds it.
public sealed partial class HomePage
{
    private ImportPromptCard? _importCard;

    /// <summary>The card when it has been built (the run-state refusals reach it only then; ShowQuestion sets it too).</summary>
    internal ImportPromptCard? ImportCardIfBuilt => _importCard;

    internal ImportPromptCard ImportCard
    {
        get
        {
            if (_importCard != null) return _importCard;
            _importCard = new ImportPromptCard();
            HomeRoot.Children.Add(_importCard);
            return _importCard;
        }
    }
}

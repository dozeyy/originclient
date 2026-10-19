using System.ComponentModel;
using System.Windows.Media;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;

namespace OriginLauncher.App.UI.Controls;

// Row models for the Import drawer (ImportPanel). Like the Friends drawer's, they are plain objects the
// virtualized list draws with the DataTemplates in ImportPanel.xaml. Only SetupRowVm and ChooseRowVm change
// after they are shown (a plan arrives, a tick box is pressed), so only they raise PropertyChanged: the rest
// are replaced when the page they belong to is rebuilt.

/// <summary>A launcher's heading: "PRISM LAUNCHER - 4", with an Import button for that launcher, or a folding heading.</summary>
public sealed class ImportSectionVm : RowVm
{
    public required string Id { get; init; }
    public required string Title { get; init; }
    public int Count { get; init; }

    /// <summary>"IMPORT": brings in everything from this launcher (null: no button).</summary>
    public string? ActionText { get; init; }
    public string? ActionTip { get; init; }
    public SourceKind? Launcher { get; init; }
    public bool Collapsible { get; init; }
    public bool Collapsed { get; init; }

    /// <summary>A heading about things that need attention: gold.</summary>
    public bool Gold { get; init; }

    public string Heading => Count > 0 ? $"{Title} - {Count}" : Title;
    public bool HasAction => ActionText != null;
    public override string Key => $"S|{Id}";
    protected override string Body => $"{Heading}|{Collapsed}|{ActionText}";
}

/// <summary>Big plain text at the top of a page (Done: "Done: 7 setups brought over").</summary>
public sealed class ImportTitleVm : RowVm
{
    public required string Title { get; init; }
    public string? Sub { get; init; }
    public bool HasSub => !string.IsNullOrEmpty(Sub);
    public override string Key => "T";
    protected override string Body => $"{Title}|{Sub}";
}

/// <summary>One setup found by the scan: a square launcher initial, its name, one quiet line, and only a problem gets more words.</summary>
public sealed class SetupRowVm : CardRowVm, INotifyPropertyChanged
{
    private string _caption = "";
    private string? _warning;
    private bool _ticked = true;
    private bool _canTick = true;
    private bool _dimmed;
    private string? _choiceVersion;

    public required DetectedInstance Setup { get; init; }

    public string Id => Setup.BulkId();
    public string Name => Setup.Name;

    /// <summary>The launcher's first letter (the section above says which launcher).</summary>
    public string Initial => Setup.SourceLabel.Length > 0 ? Setup.SourceLabel[..1].ToUpperInvariant() : "?";

    /// <summary>"1.21.1 - Fabric - 12 mods".</summary>
    public string Caption { get => _caption; set => Set(ref _caption, value, nameof(Caption)); }

    /// <summary>The gold lines under the row (one sentence per line), or null when nothing is wrong.</summary>
    public string? Warning
    {
        get => _warning;
        set
        {
            if (!Set(ref _warning, value, nameof(Warning))) return;
            Raise(nameof(HasWarning));
        }
    }

    public bool HasWarning => !string.IsNullOrEmpty(_warning);

    /// <summary>Included in "Import all". Unticked setups stay out.</summary>
    public bool Ticked { get => _ticked; set => Set(ref _ticked, value, nameof(Ticked)); }

    /// <summary>False for a setup that cannot come over at all (no tick box).</summary>
    public bool CanTick
    {
        get => _canTick;
        set
        {
            if (Set(ref _canTick, value, nameof(CanTick))) Raise(nameof(ShowTick));
        }
    }

    public bool ShowTick => _canTick;

    /// <summary>Drawn quieter: this setup can't come over.</summary>
    public bool Dimmed { get => _dimmed; set => Set(ref _dimmed, value, nameof(Dimmed)); }

    /// <summary>Set on the settings winner of a version whose winner is unclear: shows the Change button.</summary>
    public string? ChoiceVersion
    {
        get => _choiceVersion;
        set
        {
            if (Set(ref _choiceVersion, value, nameof(ChoiceVersion))) Raise(nameof(HasChoice));
        }
    }

    public bool HasChoice => _choiceVersion != null;

    public string AutomationName => $"{Name}, {Caption}";
    public string RowTip => CanTick ? "Review this setup on its own" : "This setup can't be imported as it is. Press to pick a version.";

    public override string Key => $"U|{Id}";
    protected override string Body => $"{Id}|{Caption}|{Warning}|{Ticked}|{CanTick}|{ChoiceVersion}";

    public event PropertyChangedEventHandler? PropertyChanged;

    private bool Set<T>(ref T field, T value, string name)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        Raise(name);
        return true;
    }

    private void Raise(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

/// <summary>One choice in "whose settings to use": the setup, how much it was played, and a check on the one picked.</summary>
public sealed class ChooseRowVm : CardRowVm, INotifyPropertyChanged
{
    private bool _selected;

    public required DetectedInstance Setup { get; init; }
    public required string Caption { get; init; }

    /// <summary>The picker's highlighted choice.</summary>
    public bool Suggested { get; init; }

    public string Id => Setup.BulkId();
    public string Name => Setup.Name;
    public string Initial => Setup.SourceLabel.Length > 0 ? Setup.SourceLabel[..1].ToUpperInvariant() : "?";

    public bool Selected
    {
        get => _selected;
        set
        {
            if (_selected == value) return;
            _selected = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Selected)));
        }
    }

    public override string Key => $"C|{Id}";
    protected override string Body => $"{Id}|{Caption}|{Selected}";

    public event PropertyChangedEventHandler? PropertyChanged;
}

/// <summary>A switch in Details ("Copy my single-player worlds").</summary>
public sealed class ImportOptionVm : CardRowVm
{
    public required string Id { get; init; }
    public required string Label { get; init; }
    public required string Caption { get; init; }
    public bool IsOn { get; set; }
    public override string Key => $"O|{Id}";
    protected override string Body => $"{Id}|{IsOn}";
}

/// <summary>A label and a number ("Mods   11").</summary>
public sealed class ImportStatVm : CardRowVm
{
    public required string Id { get; init; }
    public required string Label { get; init; }
    public required string Value { get; init; }
    public override string Key => $"N|{Id}";
    protected override string Body => $"{Id}|{Label}|{Value}";
}

/// <summary>A static row: a title, a quiet line, optionally a gold line and a status on the right.</summary>
public sealed class ImportLineVm : CardRowVm
{
    public required string Id { get; init; }
    public required string Title { get; init; }
    public string? Caption { get; init; }

    /// <summary>A gold line (a problem, a reason it didn't come over).</summary>
    public string? Warning { get; init; }

    /// <summary>Right-aligned status ("Imported", "Skipped: built in") in <see cref="StatusBrush"/>.</summary>
    public string? Status { get; init; }
    public Brush? StatusBrush { get; init; }

    public bool HasCaption => !string.IsNullOrEmpty(Caption);
    public bool HasWarning => !string.IsNullOrEmpty(Warning);
    public bool HasStatus => !string.IsNullOrEmpty(Status);

    public override string Key => $"L|{Id}";
    protected override string Body => $"{Id}|{Title}|{Caption}|{Warning}|{Status}";
}

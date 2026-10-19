using System.ComponentModel;
using System.Runtime.CompilerServices;
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
    public string? Title { get; init; }
    public string? Sub { get; init; }
    public bool HasTitle => !string.IsNullOrEmpty(Title);
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

    /// <summary>The setup's own first letter (like a friend's initial).</summary>
    public string Initial => ImportLogic.Initial(Setup.Name);

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

    public bool ShowTick => _canTick && !NoTick;

    /// <summary>A row that only reports (the Review page): no tick box, whatever <see cref="CanTick"/> says.</summary>
    public bool NoTick { get; init; }

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
    public string TickName => $"Include {Setup.SourceLabel}: {Name}";
    public string ChangeName => $"Pick whose settings to use for {Name}";
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
    public string Initial => ImportLogic.Initial(Setup.Name);

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


// ---------------------------------------------------------------- the launcher -> versions -> version flow
// These rows stay on screen while the player ticks and switches, so they change in place (PropertyChanged) instead of
// the list being rebuilt under the pointer: a hover, the focus and the scroll position all survive a toggle.

/// <summary>A card row whose words change while it is shown.</summary>
public abstract class LiveCardRowVm : CardRowVm, INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected bool Set<T>(ref T field, T value, [CallerMemberName] string name = "")
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        Raise(name);
        return true;
    }

    protected void Raise(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

/// <summary>One launcher on the first page: "Lunar Client / 4 versions, 12 worlds", or greyed with why ("Not installed").</summary>
public sealed class LauncherTileVm : LiveCardRowVm
{
    private string _caption = "";
    private bool _enabled;
    private string _tip = "";

    public required SourceKind Kind { get; init; }
    public required string Title { get; init; }
    public string Initial => ImportLogic.Initial(Title);

    public string Caption
    {
        get => _caption;
        set { if (Set(ref _caption, value)) Raise(nameof(AutomationName)); }
    }

    /// <summary>Something to import: the tile opens the launcher's versions. Off: greyed, the tooltip says why.</summary>
    public bool Enabled { get => _enabled; set => Set(ref _enabled, value); }

    public string Tip { get => _tip; set => Set(ref _tip, value); }

    public string AutomationName => $"{Title}, {Caption}";
    public override string Key => $"W|{Kind}";
    protected override string Body => $"{Kind}|{Caption}|{Enabled}";
}

/// <summary>"Import from all versions": one tick box over every version Origin can take (mixed when some are ticked).</summary>
public sealed class SelectAllVm : LiveCardRowVm
{
    private bool? _state = true;
    private string _caption = "";
    private bool _enabled = true;

    public bool? State { get => _state; set => Set(ref _state, value); }
    public string Caption { get => _caption; set => Set(ref _caption, value); }
    public bool Enabled { get => _enabled; set => Set(ref _enabled, value); }

    /// <summary>The tick box's binding keeps its own toggled value after a click; this puts the model's answer back.</summary>
    public void Resync() => Raise(nameof(State));

    public override string Key => "A";
    protected override string Body => $"{State}|{Caption}|{Enabled}";
}

/// <summary>One Minecraft version: its setups in a line, what they hold, whose settings win; a tick box and a way in.</summary>
public sealed class VersionRowVm : LiveCardRowVm
{
    private string _caption = "";
    private string _contents = "";
    private string? _warning;
    private bool _ticked;

    public required string VersionKey { get; init; }
    public required string Title { get; init; }

    /// <summary>False for a version Origin can't take: no tick box, drawn quieter, the reason in gold.</summary>
    public bool CanTick { get; init; }
    public bool Dimmed => !CanTick;

    public string Caption { get => _caption; set { if (Set(ref _caption, value)) Raise(nameof(AutomationName)); } }

    /// <summary>"12 mods, 2 worlds, 3 packs" (empty until the folders are counted).</summary>
    public string Contents { get => _contents; set { if (Set(ref _contents, value)) Raise(nameof(HasContents)); } }
    public bool HasContents => !string.IsNullOrEmpty(_contents);

    public string? Warning { get => _warning; set { if (Set(ref _warning, value)) Raise(nameof(HasWarning)); } }
    public bool HasWarning => !string.IsNullOrEmpty(_warning);

    public bool Ticked { get => _ticked; set => Set(ref _ticked, value); }

    public void Resync() => Raise(nameof(Ticked));

    public string AutomationName => $"Minecraft {Title}, {Caption}";
    public string TickName => $"Import Minecraft {Title}";
    public string RowTip => CanTick ? $"Choose what {Title} brings" : $"See the setups on {Title}";
    public override string Key => $"V|{VersionKey}";
    protected override string Body => $"{VersionKey}|{Caption}|{Contents}|{Warning}|{Ticked}";
}

/// <summary>"What to bring": a row of switches, one per kind of thing (for every version, or for one).</summary>
public sealed class CategoryBarVm : CardRowVm
{
    public required string Id { get; init; }
    public required IReadOnlyList<CategoryChipVm> Chips { get; init; }
    public override string Key => $"B|{Id}";
    protected override string Body => Id + "|" + string.Join(",", Chips.Select(c => $"{c.IsOn}{c.Count}{c.Enabled}"));
}

/// <summary>One "what to bring" switch: "Worlds 4". On, off, or mixed (some versions bring it).</summary>
public sealed class CategoryChipVm : INotifyPropertyChanged
{
    private bool? _isOn;
    private string _count = "";
    private bool _enabled = true;

    public required ImportCategories Category { get; init; }
    public required string Label { get; init; }

    public bool? IsOn { get => _isOn; set { if (Set(ref _isOn, value)) { Raise(nameof(AutomationName)); Raise(nameof(Tip)); } } }

    /// <summary>"12", or empty for the yes/no kinds and before counting.</summary>
    public string Count { get => _count; set { if (Set(ref _count, value)) { Raise(nameof(Text)); Raise(nameof(AutomationName)); } } }

    /// <summary>Off when the setups have none of it (nothing to switch).</summary>
    public bool Enabled { get => _enabled; set { if (Set(ref _enabled, value)) Raise(nameof(Tip)); } }

    public string Text => string.IsNullOrEmpty(_count) ? Label : $"{Label} {_count}";

    public string AutomationName => $"Bring {Label}" + (string.IsNullOrEmpty(_count) ? "" : $", {_count}")
        + (_isOn == true ? ", on" : _isOn == false ? ", off" : ", on for some versions");

    public string Tip => !_enabled ? $"No {Label.ToLowerInvariant()} to bring"
        : _isOn == null ? $"Some versions bring {Label.ToLowerInvariant()}. Press to bring them for all."
        : _isOn == true ? $"Bringing {Label.ToLowerInvariant()}. Press to leave them out."
        : $"Leaving {Label.ToLowerInvariant()} out. Press to bring them.";

    /// <summary>The switch keeps its own toggled value after a click; this puts the model's answer back.</summary>
    public void Resync() => Raise(nameof(IsOn));

    public event PropertyChangedEventHandler? PropertyChanged;

    private bool Set<T>(ref T field, T value, [CallerMemberName] string name = "")
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        Raise(name);
        return true;
    }

    private void Raise(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

/// <summary>A quiet line inside a card whose words change in place ("Settings from Hypixel. Mods and packs from every ticked setup.").</summary>
public sealed class ImportNoteVm : LiveCardRowVm
{
    private string _text = "";

    public required string Id { get; init; }
    public string Text { get => _text; set => Set(ref _text, value); }
    public override string Key => $"Q|{Id}";
    protected override string Body => Text;
}

using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
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

    /// <summary>
    /// The copy this row stands for (Review): the row is named "Lunar 1.8.9 -> 1.21.1", the exact source and Origin versions
    /// of ONE copy of the plan (ImportManifest). Null on the scan lists, which name the setup itself.
    /// </summary>
    public ImportMapping? Mapping { get; init; }

    public string Id => Setup.BulkId();
    public string Name => Mapping?.Title ?? Setup.Name;

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

    // A setup that lends its settings to another version is two rows (two copies): the key follows the copy.
    public override string Key => $"U|{Mapping?.Id ?? Id}";
    protected override string Body => $"{Id}|{Name}|{Caption}|{Warning}|{Ticked}|{CanTick}|{ChoiceVersion}";

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

/// <summary>
/// One launcher found on this PC (the Import page's first screen): its real logo, name, folder, three counts, and a tick
/// that takes all of its setups in or out of "Import everything". Changes in place while the scan and count land.
/// </summary>
public sealed class LauncherTileVm : LiveCardRowVm
{
    private string _caption = "";
    private bool _enabled;
    private string _tip = "";
    private bool _notFound;
    private string _pathText = "";
    private string _setupsText = "";
    private string _worldsText = "";
    private string _modsText = "";
    private bool? _ticked = true;
    private bool _canTick;

    public required SourceKind Kind { get; init; }
    public required string Title { get; init; }
    public string Initial => ImportLogic.Initial(Title);

    /// <summary>The launcher's real logo (Assets/Logos, sources in Assets/SOURCES.md); null = show the initial.</summary>
    public ImageSource? Logo => LauncherLogos.For(Kind);
    public bool HasLogo => Logo != null;

    /// <summary>The faint wash in the tile's corner and the hairline round its logo, in the launcher's own colour.</summary>
    public Brush TintEdge => LauncherLogos.Edge(Kind);

    /// <summary>Its folder isn't on this PC (the page lists it as a slim "Locate folder" row instead of a tile).</summary>
    public bool NotFound { get => _notFound; set => Set(ref _notFound, value); }

    public string Caption
    {
        get => _caption;
        set { if (Set(ref _caption, value)) Raise(nameof(AutomationName)); }
    }

    /// <summary>Something to import.</summary>
    public bool Enabled { get => _enabled; set => Set(ref _enabled, value); }

    public string Tip { get => _tip; set => Set(ref _tip, value); }

    /// <summary>Where its setups live, shortened ("%AppData%\PrismLauncher\instances"); empty until there are setups.</summary>
    public string PathText { get => _pathText; set => Set(ref _pathText, value); }

    /// <summary>The three counts: setups found, worlds and mods once counted ("..." until then).</summary>
    public string SetupsText { get => _setupsText; set { if (Set(ref _setupsText, value)) Raise(nameof(SetupsLabel)); } }

    /// <summary>
    /// The label under the setups count. Launchers that call them instances (CurseForge, Modrinth, Prism, the official
    /// launcher) say INSTANCES; the rest say SETUPS. Singular for 1.
    /// </summary>
    public string SetupsLabel
    {
        get
        {
            var word = Kind is SourceKind.CurseForge or SourceKind.Modrinth or SourceKind.Prism or SourceKind.OfficialLauncher
                ? "INSTANCE" : "SETUP";
            return _setupsText == "1" ? word : word + "S";
        }
    }
    public string WorldsText { get => _worldsText; set => Set(ref _worldsText, value); }
    public string ModsText { get => _modsText; set => Set(ref _modsText, value); }

    /// <summary>In "Import everything": true when every setup Origin can take is going, false when none, null in between.</summary>
    public bool? Ticked { get => _ticked; set => Set(ref _ticked, value); }

    /// <summary>False when none of its setups can come over (nothing to tick).</summary>
    public bool CanTick { get => _canTick; set => Set(ref _canTick, value); }


    /// <summary>The tick box keeps its own toggled value after a click; this puts the model's answer back.</summary>
    public void Resync() => Raise(nameof(Ticked));

    public string AutomationName => $"{Title}, {Caption}";
    public string TickName => $"Import from {Title}";
    public override string Key => $"W|{Kind}";
    protected override string Body => $"{Kind}|{Caption}|{Enabled}|{NotFound}|{PathText}|{SetupsText}|{WorldsText}|{ModsText}|{Ticked}";
}

/// <summary>A launcher that has nothing to bring: its small logo in the slim "not found" row.</summary>
public sealed class MissingLauncherVm : CardRowVm
{
    public required SourceKind Kind { get; init; }
    public required string Title { get; init; }
    public string Message { get; init; } = "";
    public string Initial => ImportLogic.Initial(Title);
    public ImageSource? Logo => LauncherLogos.For(Kind);
    public bool HasLogo => Logo != null;
    public string AutomationName => $"{Title} {Message}";
    public override string Key => $"M|{Kind}";
    protected override string Body => $"{Kind}|{Message}";
}

/// <summary>A launcher's heading in "Choose what to import": logo, name, "5 of 5 selected, 17 worlds, 244 mods", one tick for all.</summary>
public sealed class PickGroupVm : LiveCardRowVm
{
    private string _caption = "";
    private bool? _state = true;
    private bool _canTick = true;

    public required SourceKind Kind { get; init; }
    public required string Title { get; init; }
    public string Initial => ImportLogic.Initial(Title);
    public ImageSource? Logo => LauncherLogos.For(Kind);
    public bool HasLogo => Logo != null;
    public Brush TintEdge => LauncherLogos.Edge(Kind);

    /// <summary>The space above a second and later group.</summary>
    public Thickness Gap { get; init; }

    public string Caption { get => _caption; set => Set(ref _caption, value); }
    public bool? State { get => _state; set => Set(ref _state, value); }
    public bool CanTick { get => _canTick; set => Set(ref _canTick, value); }
    public void Resync() => Raise(nameof(State));

    public string TickName => $"Import everything from {Title}";
    public override string Key => $"PG|{Kind}";
    protected override string Body => $"{Kind}|{Caption}|{State}|{CanTick}";
}

/// <summary>One setup in "Choose what to import": tick, name, "23 mods, 3 worlds", and its Minecraft version.</summary>
public sealed class PickRowVm : LiveCardRowVm
{
    private string _caption = "";
    private bool _ticked;

    public required DetectedInstance Setup { get; init; }
    public string Id => Setup.BulkId();
    public string Name => Setup.Name;
    public string Badge => Setup.McVersion ?? "?";

    /// <summary>False when Origin can't take its version: no tick, drawn quieter, the reason as the caption.</summary>
    public bool CanTick { get; init; }
    public bool Dimmed => !CanTick;

    public string Caption { get => _caption; set { if (Set(ref _caption, value)) Raise(nameof(AutomationName)); } }
    public bool Ticked { get => _ticked; set => Set(ref _ticked, value); }
    public void Resync() => Raise(nameof(Ticked));

    public string AutomationName => $"{Name}, {Caption}";
    public string TickName => $"Import {Setup.SourceLabel}: {Name}";
    public string ReviewName => $"Review {Name}";
    public string RowTip => CanTick ? "Tick or untick this setup" : "Origin can't run this version yet. Press to pick another version for it.";
    public override string Key => $"PR|{Id}";
    protected override string Body => $"{Id}|{Caption}|{Ticked}|{CanTick}";
}

/// <summary>One setup on the Importing screen: Waiting, the one being copied (a small bar and the step), or Done.</summary>
public sealed class ProgressRowVm : LiveCardRowVm
{
    public enum Phase { Waiting, Active, Done }

    private Phase _state;
    private string _status = "Waiting";
    private double _fraction;

    public required DetectedInstance Setup { get; init; }

    /// <summary>The copy this row reports on (ImportManifest): the row is named "Lunar 1.8.9 -> 1.21.1", like the Review row it came from.</summary>
    public ImportMapping? Mapping { get; init; }

    public string Name => Mapping?.Title ?? Setup.Name;
    public string Launcher => ImportLogic.GroupTitle(Setup.SourceLabel);
    public string Initial => ImportLogic.Initial(Setup.Name);
    public ImageSource? Logo => LauncherLogos.For(Setup.Kind);
    public bool HasLogo => Logo != null;
    public Brush TintEdge => LauncherLogos.Edge(Setup.Kind);

    public Phase State
    {
        get => _state;
        set
        {
            if (!Set(ref _state, value)) return;
            Raise(nameof(IsActive));
            Raise(nameof(IsDone));
            Raise(nameof(IsWaiting));
        }
    }

    public bool IsActive => _state == Phase.Active;
    public bool IsDone => _state == Phase.Done;
    public bool IsWaiting => _state == Phase.Waiting;

    public string Status { get => _status; set => Set(ref _status, value); }

    /// <summary>0..1 of the small bar (the active row only).</summary>
    public double Fraction { get => _fraction; set { if (Set(ref _fraction, value)) Raise(nameof(BarWidth)); } }

    /// <summary>The small bar's track in px.</summary>
    public const double BarTrack = 160;

    /// <summary>The small bar's fill in px.</summary>
    public double BarWidth => Math.Round(BarTrack * Math.Clamp(_fraction, 0, 1));

    public override string Key => $"PP|{Mapping?.Id ?? Setup.BulkId()}";
    protected override string Body => $"{Setup.BulkId()}|{Name}|{State}|{Status}|{Fraction:0.00}";
}

/// <summary>
/// A Minecraft version that got something on the Done screen: the logo of the launcher its settings came from, "Minecraft
/// 1.21.1" and "From Prism, Lunar": the version, ready on Home (each setup's mods are in its own profile).
/// </summary>
public sealed class VersionCardVm : CardRowVm
{
    public required string Name { get; init; }
    public required string Caption { get; init; }
    public SourceKind Kind { get; init; }
    public string Initial => ImportLogic.Initial(Name);
    public ImageSource? Logo => LauncherLogos.For(Kind);
    public bool HasLogo => Logo != null;
    public Brush TintEdge => LauncherLogos.Edge(Kind);

    /// <summary>The "+3 more" card: no logo, no action.</summary>
    public bool IsMore { get; init; }
    public override string Key => $"VC|{Name}|{Caption}";
    protected override string Body => $"{Name}|{Caption}";
}

// ---------------------------------------------------------------- "Choose what to import": the grid (ImportGridModel)

/// <summary>A version tab above the grid ("All versions", "1.21.1"); the shown one is drawn selected.</summary>
public sealed class GridTabVm : INotifyPropertyChanged
{
    private bool _selected;

    public event PropertyChangedEventHandler? PropertyChanged;

    public required string Tab { get; init; }
    public required string Title { get; init; }
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
    public string AutomationName => "Show " + Title;

    /// <summary>The toggle keeps its own pressed value after a click; this puts the model's answer back.</summary>
    public void Resync() => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Selected)));
}

/// <summary>One tick of the grid (a launcher's row, a column): on, off or mixed; greyed with the reason when nothing can come.</summary>
public sealed class GridCellVm : INotifyPropertyChanged
{
    private bool? _on;
    private bool _enabled;
    private string _tip = "";
    private string _name = "";

    public event PropertyChangedEventHandler? PropertyChanged;

    public required SourceKind Kind { get; init; }
    public required GridColumn Column { get; init; }

    public bool? On { get => _on; set => Set(ref _on, value); }
    public bool Enabled { get => _enabled; set => Set(ref _enabled, value); }
    public string Tip { get => _tip; set => Set(ref _tip, value); }

    /// <summary>What a screen reader says: "Lunar Client: Mods".</summary>
    public string Name { get => _name; set => Set(ref _name, value); }

    /// <summary>The tick box keeps its own toggled value after a click; this puts the model's answer back.</summary>
    public void Resync() => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(On)));

    private void Set<T>(ref T field, T value, [CallerMemberName] string name = "")
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}

/// <summary>A launcher's row in the grid: logo, name, "3 setups - 1.21.1, 1.20.1", then a tick per column.</summary>
public sealed class GridRowVm : LiveCardRowVm
{
    private string _caption = "";

    public required SourceKind Kind { get; init; }
    public required string Title { get; init; }
    public required IReadOnlyList<GridCellVm> Cells { get; init; }
    public string Initial => ImportLogic.Initial(Title);
    public ImageSource? Logo => LauncherLogos.For(Kind);
    public bool HasLogo => Logo != null;
    public Brush TintEdge => LauncherLogos.Edge(Kind);

    public string Caption { get => _caption; set => Set(ref _caption, value); }

    public override string Key => $"GR|{Kind}";
    protected override string Body => $"{Kind}|{Caption}";
}

/// <summary>
/// The launcher logos, decoded once at tile size (2x for crisp scaling) and frozen so any thread may read them.
/// Prism and MultiMC share one source kind and one tile ("Prism / MultiMC"), so the tile wears Prism's logo.
/// </summary>
internal static class LauncherLogos
{
    private static readonly Dictionary<SourceKind, ImageSource?> Cache = new();

    /// <summary>
    /// Each launcher's own colour, the one its logo wears (white logos: the colour its brand is known by). Used only as an
    /// accent: a hairline round the logo, never a fill or a wash.
    /// </summary>
    private static Color ColourOf(SourceKind kind) => ColorToken(kind switch
    {
        SourceKind.OfficialLauncher => "Color.Hue.Emerald",
        SourceKind.CurseForge => "Color.Hue.Coral",
        SourceKind.Modrinth => "Color.Hue.Gold",
        SourceKind.Prism => "Color.Hue.Lilac",
        SourceKind.Lunar => "Color.Hue.Sky",
        SourceKind.Feather => "Color.Hue.Sky",
        _ => "Color.TextDim",
    });

    // Colours are tokens (Theme/Colors.xaml); the five hues keep a launcher's tile recognisable without a brand-colour table.
    private static Color ColorToken(string key) =>
        Application.Current?.TryFindResource(key) is Color c ? c : Colors.Transparent;

    private static readonly Dictionary<SourceKind, Brush> Edges = new();

    /// <summary>The hairline round a logo: the launcher's colour at about 40 %.</summary>
    public static Brush Edge(SourceKind kind)
    {
        lock (Edges)
        {
            if (Edges.TryGetValue(kind, out var hit)) return hit;
            var c = ColourOf(kind);
            var brush = new SolidColorBrush(Color.FromArgb(0x66, c.R, c.G, c.B));
            brush.Freeze();
            return Edges[kind] = brush;
        }
    }

    public static ImageSource? For(SourceKind kind)
    {
        lock (Cache)
        {
            if (Cache.TryGetValue(kind, out var hit)) return hit;
            var name = kind switch
            {
                SourceKind.OfficialLauncher => "minecraft",
                SourceKind.Prism => "prism",
                SourceKind.Modrinth => "modrinth",
                SourceKind.CurseForge => "curseforge",
                SourceKind.Lunar => "lunar",
                SourceKind.Feather => "feather",
                _ => null,
            };
            ImageSource? image = null;
            if (name != null)
            {
                try
                {
                    var bmp = new BitmapImage();
                    bmp.BeginInit();
                    bmp.UriSource = new Uri($"pack://application:,,,/Assets/Logos/{name}.png", UriKind.Absolute);
                    bmp.DecodePixelWidth = 72;
                    bmp.CacheOption = BitmapCacheOption.OnLoad;
                    bmp.EndInit();
                    bmp.Freeze();
                    image = bmp;
                }
                catch
                {
                    image = null; // a missing logo falls back to the initial, never breaks the drawer
                }
            }
            Cache[kind] = image;
            return image;
        }
    }
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

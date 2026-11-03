using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.UI;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Overlays;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Pages;

// Home (DESIGN-SYSTEM.md 13): the numeral on the art (ArtStage) and the strip 32 in from the left, right and
// bottom (24 below 640 high). Left: the version card (opens the version picker) and the status line. Right,
// every item centred on one 64 line: MY MODS + switch, Join (only when the profile has a server), Play.
// Why Play is off is words on the status line, never a hover label. The launch itself is LaunchController's.
public sealed class HomePage : Panel, IPage
{
    private readonly MainWindow _window;
    private readonly Grid _strip;
    private readonly Pressable _versionCard;
    private readonly Image _thumb = new() { Width = Sizes.StripThumb, Height = Sizes.StripThumb, Stretch = Stretch.UniformToFill };
    private readonly TextBlock _versionText = Ui.Heading("");
    private readonly TextBlock _familyText = Ui.Caption("");
    private readonly Avalonia.Controls.Shapes.Ellipse _statusDot = Ui.Dot(Palette.TextMuted, 8);
    private readonly TextBlock _statusText = Ui.Caption("");
    private readonly Toggle _myMods = new("My mods", home: true);
    private readonly Pressable _join;
    private readonly TextBlock _joinServer = Ui.BodyStrong("Server");
    private readonly StackPanel _joinWords;
    private readonly Pressable _play;
    private readonly StackPanel _playFace;
    private readonly TextBlock _runningLabel;
    private readonly LoadingSpinner _spinner = new(20) { IsVisible = false };
    private (string Text, Tone Tone)? _statusOverride;

    public LaunchController Launches { get; }

    // Over Home, under the bar's layers: a centred card (the first-run import prompt, ImportView) or a Home-only
    // prompt (Fix Compatibility over a refused Play). One at a time; null clears it.
    private readonly Panel _cardSlot = new() { IsVisible = false };

    public void ShowCard(Control? card)
    {
        _cardSlot.Children.Clear();
        if (card != null) _cardSlot.Children.Add(card);
        _cardSlot.IsVisible = card != null;
    }

    public Control? Card => _cardSlot.Children.Count > 0 ? _cardSlot.Children[0] : null;

    public HomePage(MainWindow window)
    {
        _window = window;
        Launches = new LaunchController(window, this);

        // Version card: radius lg, 16 right / 8 left padding, 48 thumb (radius md), 12, version heading over the
        // family caption, then the chevron.
        var thumbBox = new Border { CornerRadius = new CornerRadius(Radius.Md), ClipToBounds = true, Child = _thumb };
        RenderOptions.SetBitmapInterpolationMode(_thumb, BitmapInterpolationMode.HighQuality);
        _versionText.LineHeight = 26;
        var words = Ui.Column(0, _versionText, _familyText);
        words.VerticalAlignment = VerticalAlignment.Center;
        var cardRow = Ui.Row(12, thumbBox, words, Ui.Icon(Icons.ChevronDown, Sizes.Icon, Palette.TextDim).Margin(12, 0, 0, 0));
        _versionCard = new Pressable(Look.Card, cardRow, "Choose a version")
        {
            Height = Sizes.Strip, Padding = new Thickness(8, 0, 16, 0), CornerRadius = new CornerRadius(Radius.Lg),
            HorizontalAlignment = HorizontalAlignment.Left,
        };
        _versionCard.Click += (_, _) => { OpenPicker(); };

        // The status line wraps to two rows at most (a refusal names up to three mods); the dot sits on the first row.
        _statusText.TextWrapping = TextWrapping.Wrap;
        _statusText.MaxWidth = Sizes.StatusWidth;
        _statusText.LineHeight = Sizes.StatusLine;
        _statusDot.VerticalAlignment = VerticalAlignment.Top;
        _statusDot.Margin = new Thickness(0, (Sizes.StatusLine - 8) / 2, 0, 0);
        var status = Ui.Row(8, _statusDot, _statusText);
        status.Margin = new Thickness(4, 10, 0, 0);
        var left = Ui.Column(0, _versionCard, status);
        left.VerticalAlignment = VerticalAlignment.Bottom;

        // MY MODS + switch.
        var myModsLabel = Ui.Eyebrow("My mods", Palette.TextMuted, TypeScale.NavTracking);
        _myMods.Toggled += on => LauncherState.Write(s => s.PlayWithExternalMods = on);
        ToolTip.SetTip(_myMods, "Load the mods you added yourself");
        var myMods = Ui.Row(10, myModsLabel, _myMods);

        // Join: a strip card, server icon + "Join" over the server name.
        _joinWords = Ui.Column(0, Ui.Caption("Join", Palette.TextDim), _joinServer);
        _joinWords.VerticalAlignment = VerticalAlignment.Center;
        _join = new Pressable(Look.Card, Ui.Row(10, Ui.Icon(Icons.Server, Sizes.IconLg), _joinWords), "Join server")
        {
            Height = Sizes.Strip, Padding = new Thickness(14, 0), CornerRadius = new CornerRadius(Radius.Lg), IsVisible = false,
        };
        _join.Click += (_, _) =>
        {
            if (CurrentProfile() is { HasServer: true } p) _ = Launches.PlayAsync(p.Version, p.ServerAddress);
        };

        // Play: accent card 208 x 64, radius lg, play triangle + "Play" in action type; Running; spinner; disabled.
        _playFace = Ui.Row(10, Ui.Icon(Icons.Play, 20, null, filled: true), Ui.Text("Play", TypeScale.Action, FontWeight.Bold, null, 0.02));
        _playFace.HorizontalAlignment = HorizontalAlignment.Center;
        _runningLabel = Ui.Text("Running", TypeScale.Action, FontWeight.Bold, null, 0.02);
        _runningLabel.HorizontalAlignment = HorizontalAlignment.Center;
        _runningLabel.IsVisible = false;
        var playContent = new Panel();
        playContent.Children.Add(_playFace);
        playContent.Children.Add(_runningLabel);
        playContent.Children.Add(_spinner);
        _play = new Pressable(Look.Primary, playContent, "Play Minecraft")
        {
            Width = Sizes.PlayWidth, Height = Sizes.Strip, CornerRadius = new CornerRadius(Radius.Lg),
        };
        _play.Center();
        _play.Click += (_, _) => _ = Launches.PlayOrFocusAsync(LauncherState.SelectedVersion);

        var right = Ui.Row(12, myMods, _join, _play);
        right.VerticalAlignment = VerticalAlignment.Bottom;
        right.HorizontalAlignment = HorizontalAlignment.Right;
        // Bottom-aligned with the status line: the status line sits 10 + 18 under the 64 line.
        right.Margin = new Thickness(0, 0, 0, 28);

        _strip = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), VerticalAlignment = VerticalAlignment.Bottom };
        _strip.Children.Add(left.At(0));
        _strip.Children.Add(right.At(2));
        Children.Add(_strip);
        Children.Add(_cardSlot);

        SizeChanged += (_, _) => Layout();
        LauncherState.SettingsChanged += Refresh;
        LauncherState.AccountsChanged += Refresh;
        Launches.Changed += RefreshPlayState;
        Refresh();
    }

    public void Shown()
    {
        Refresh();
        Layout();
    }

    /// <summary>The strip's top in window coordinates feeds the numeral band (bar bottom to strip top).</summary>
    private void Layout()
    {
        var inset = Bounds.Height + Sizes.Bar < 640 ? Space.S6 : Space.S8;
        _strip.Margin = new Thickness(inset, 0, inset, inset);
        var stripTop = Sizes.Bar + Bounds.Height - inset - 28 - Sizes.Strip; // the 64 line's top
        _window.Art.SetBand(Sizes.Bar, stripTop);
    }

    public void Refresh()
    {
        var version = LauncherState.SelectedVersion;
        ShowVersionCard(version);
        _myMods.IsOn = LauncherState.Settings.PlayWithExternalMods;
        var profile = CurrentProfile();
        _join.IsVisible = profile is { HasServer: true } && profile.Version == version;
        if (_join.IsVisible) _joinServer.Text = profile!.ServerName ?? profile.ServerAddress ?? "Server";
        RefreshPlayState();
    }

    private static ModProfile? CurrentProfile()
    {
        var s = LauncherState.Settings;
        return s.SelectedProfileId == null ? null : s.ModProfiles.FirstOrDefault(p => p.Id == s.SelectedProfileId);
    }

    private void ShowVersionCard(string version)
    {
        _versionText.Text = version;
        var group = FamilyTheme.GroupOf(version);
        _familyText.Text = group.Tagline;
        _thumb.Source = Assets.Thumb(group.Key);
        if (_window.Page == PageKey.Home) _window.Art.Show(version);
    }

    /// <summary>The selected account's folder key; null when nobody can play (signed out, no offline test).</summary>
    public static string? SelectedKey() =>
        LauncherState.Account != null || LauncherState.Settings.OfflineTestMode ? LauncherState.AccountKey : null;

    public void RefreshPlayState()
    {
        var key = SelectedKey();
        var phase = key != null ? Launches.Book.PhaseOf(key) : GamePhase.Idle;
        var view = AccountLaunchBook.ButtonFor(phase, canPlay: key != null, hasVersion: true);
        var blocked = Launches.Blocked || FixCompatibility.Running || ImportRunState.IsApplying;
        if (blocked) view = view with { Enabled = false };
        _play.IsEnabled = view.Enabled;
        _spinner.IsVisible = view.Spinner;
        _playFace.Opacity = !view.Spinner && !view.ShowsRunning ? 1 : 0;
        _runningLabel.IsVisible = !view.Spinner && view.ShowsRunning;
        Avalonia.Automation.AutomationProperties.SetName(_play, view.ShowsRunning ? "Switch to Minecraft" : "Play Minecraft");
        _join.IsEnabled = key != null && phase == GamePhase.Idle && !blocked;

        if (_statusOverride is { } o) SetStatusLine(o.Text, o.Tone);
        else if (Launches.BlockedText is { } why) SetStatusLine(why, Tone.Warning);
        else if (key != null && phase != GamePhase.Idle && Launches.Book.NoteOf(key) is { } note)
            SetStatusLine(note.Text, ToneOf(note.Tone));
        // An idle account keeps its last note (a refusal, a failed launch, "Made ...") for the version it was about.
        else if (key != null && Launches.IdleNote(key, LauncherState.SelectedVersion) is { } idle)
            SetStatusLine(idle.Text, ToneOf(idle.Tone));
        else if (LauncherState.Account != null) SetStatusLine("Ready to play", Tone.Success);
        else if (key != null) SetStatusLine("Ready to play offline", Tone.Warning);
        else SetStatusLine("Sign in to play", Tone.Info);
    }

    /// <summary>A status line that wins over the computed one until cleared (null).</summary>
    public void OverrideStatus(string? text, Tone tone = Tone.Info)
    {
        _statusOverride = text == null ? null : (text, tone);
        RefreshPlayState();
    }

    private static Tone ToneOf(LaunchTone t) => t switch
    {
        LaunchTone.Success => Tone.Success,
        LaunchTone.Warning => Tone.Warning,
        LaunchTone.Error => Tone.Danger,
        _ => Tone.Info,
    };

    // The dot: accent while ready, warning, danger; neutral (textMuted) otherwise.
    private void SetStatusLine(string text, Tone tone)
    {
        _statusText.Text = text;
        _statusDot.Fill = tone switch
        {
            Tone.Success => Palette.Accent,
            Tone.Warning => Palette.Warning,
            Tone.Danger => Palette.Danger,
            _ => Palette.TextMuted,
        };
    }

    /// <summary>The version picker over Home. A pick saves the version and Home follows; the picker stays up until
    /// Esc, a click outside or Close (a one-release family closes it itself). installs: render runs' made-up states.</summary>
    public VersionPicker OpenPicker(InstalledVersions? installs = null)
    {
        var picker = installs == null
            ? new VersionPicker(LauncherState.SelectedVersion)
            : new VersionPicker(LauncherState.SelectedVersion, installs);
        picker.Picked += v => LauncherState.Write(s => s.SelectedVersion = v);
        picker.Dismissed += () => _window.CloseSheet();
        _window.OpenSheet(picker);
        return picker;
    }

    /// <summary>The status line's words (the launch override reads the refusal it shows).</summary>
    public string StatusText => _statusText.Text ?? "";

    // ---- Render runs ----

    public Pressable PlayButton => _play;
    public Pressable VersionCard => _versionCard;
    public Toggle MyModsSwitch => _myMods;
    public Pressable JoinButton => _join;
}

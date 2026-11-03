using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using Avalonia.VisualTree;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.GameFolders.Launcher;
using OriginLauncher.App.Core.Install;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Performance;
using OriginLauncher.App.Core.Updates;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI.Pages;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Pages;

// Settings (port of WPF UI/Pages/SettingsPage.xaml(.cs)). Sections in the Windows order: Settings Sync, Performance,
// Game, JVM arguments, Origin Client, Startup, Developer (dev builds only). The rail on the left lists them and
// tracks the scroll (the shared SettingsRailSpy decides which item is lit).
//
// Rules this page keeps:
// - Every write goes through LauncherState.Write the moment the player changes something (RAM after 250 ms of
//   slider quiet, or on leaving the page; text boxes when they lose focus, like Windows).
// - One control, one job: each handler writes only its own setting. Values set from code never write (Toggle and
//   SettingsSlider only raise for the player's own input; the JVM editor has its own loading guard).
// - Nothing OS-specific here: start-at-login goes through Core's InstallRegistry seam (Linux XDG autostart,
//   macOS LaunchAgent), folders through Avalonia's StorageProvider.
public sealed class SettingsPage : Panel, IPage
{
    private const string RamAutoText = "Auto: Origin picks the right amount for each version.";
    private const string RamManualText = "Fixed at your choice for every version.";

    private readonly MainWindow _window;
    private readonly ScrollViewer _scroller;
    private readonly Panel _content = new();
    private readonly StackPanel _rail = new() { VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, Space.S6, Space.S6, 0) };
    private readonly TranslateTransform _railShift = new();
    private readonly List<(SettingsRailItem Item, Control Section)> _sections = [];
    private readonly SettingsRailSpy _spy = new();

    // Settings Sync
    private readonly Toggle _sync = new("Sync settings across versions");
    private readonly Border _syncState;
    private readonly TextBlock _syncStateText;

    // Performance
    private readonly int _totalRamMb;
    private readonly SettingsSlider _ram = new("RAM allocation");
    private readonly TextBlock _ramValue = Ui.Text("Auto", TypeScale.Title, FontWeight.ExtraBold, Palette.Text);
    private readonly TextBlock _ramWhy;
    private readonly Pressable _ramAuto = Ui.Button("Back to Auto", Look.Secondary);
    private readonly DispatcherTimer _ramSave = new() { Interval = TimeSpan.FromMilliseconds(250) };
    private int? _pendingRamMb;
    private readonly Toggle _shaderCache = new("Shader cache");
    private readonly Pressable _preset = Ui.Button("Apply", Look.Secondary).Named("Apply recommended video settings");
    private readonly TextBlock _presetStatus;

    // Game
    private readonly TextBox _installPath = SettingsUi.Box("Install folder");
    private readonly TextBlock _installError = SettingsUi.ErrorText();
    private readonly TextBox _width = SettingsUi.Box("Width", Sizes.FieldNarrow, centred: true);
    private readonly TextBox _height = SettingsUi.Box("Height", Sizes.FieldNarrow, centred: true);
    private readonly TextBlock _resolutionError = SettingsUi.ErrorText();

    // JVM arguments (per version: the lineup spans Java 8 to 25, so a flag one accepts can stop another starting)
    private readonly Dropdown _jvmVersions = new(Sizes.ControlLg) { MinWidth = 140 };
    private readonly TextBox _jvmArgs = SettingsUi.Box("Arguments");
    private readonly TextBlock _jvmStatus;
    private readonly Grid _jvmUndoRow;
    private readonly TextBlock _jvmUndoText;
    private readonly Pressable _jvmUndo = Ui.Button("Undo", Look.Secondary).Named("Undo Java arguments reset");
    private readonly Toggle _jvmReplace = new("Replace defaults");
    private readonly TextBlock _jvmCustom;
    private bool _jvmLoading = true;
    private string? _jvmVersion;
    // True once the player typed in the arguments box since it was loaded. Only then is the box written back: a box
    // that merely shows what was saved (possibly stale after the crash window reset it) never overwrites the reset.
    private bool _jvmDirty;

    // Origin Client, Startup, Developer
    private readonly Toggle _originUi = new("Origin UI");
    private readonly Toggle _startAtLogin = new("Open Origin when you log in");
    private readonly Toggle _offline = new("Offline test mode");

    /// <summary>Developer is for dev builds (local, not stamped by CI). Render runs show the public layout, like Windows.</summary>
    private static bool DeveloperVisible => UpdateService.IsDevBuild && !Shots.ShotRunner.Active;

    public SettingsPage(MainWindow window)
    {
        _window = window;
        var s = LauncherState.Settings;

        // ---- Settings Sync ----
        _syncStateText = Ui.Eyebrow("OFF", Palette.TextDim);
        _syncStateText.HorizontalAlignment = HorizontalAlignment.Center;
        _syncState = Ui.Capsule(new Border
        {
            Height = Sizes.Badge, MinWidth = Sizes.Badge, Padding = new Thickness(6, 0), Child = _syncStateText,
            Margin = new Thickness(0, 0, Space.S3, 0), VerticalAlignment = VerticalAlignment.Center,
        }.Named("Settings Sync state"));
        var syncRow = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto,Auto") };
        syncRow.Children.Add(SettingsUi.TitleWhy("Sync settings across versions",
            "Your keybinds, video settings, mod settings, HUD positions and server list follow you to every version.")
            .Margin(0, 0, Space.S4, 0).At(0));
        syncRow.Children.Add(_syncState.At(1));
        syncRow.Children.Add(_sync.At(2));
        _sync.Toggled += OnSyncToggled;

        // ---- Performance ----
        _totalRamMb = SystemInfo.GetTotalPhysicalMemoryMb();
        _ram.Minimum = RamAdvice.SliderMinimumMb;
        _ram.Maximum = RamAdvice.MaximumMb(_totalRamMb);
        _ram.Step = RamAdvice.StepMb;
        _ram.LargeStep = 1024;
        _ram.ValueChanged += OnRamMoved;
        _ramSave.Tick += (_, _) => PersistPendingRam();
        _ramAuto.Click += (_, _) => OnRamAuto();
        var ramHead = SettingsUi.Row(SettingsUi.TitleWhy("RAM allocation", RamAutoText, out var ramWhy), _ramAuto);
        _ramWhy = ramWhy!;
        _ramValue.MinWidth = 80;
        _ramValue.TextAlignment = TextAlignment.Right;
        _ramValue.Margin = new Thickness(Space.S6, 0, 0, 0);
        var ramLine = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Margin = new Thickness(0, 14, 0, 0) };
        ramLine.Children.Add(_ram.At(0));
        ramLine.Children.Add(_ramValue.At(1));
        _ram.VerticalAlignment = VerticalAlignment.Center;
        ShowRam(RamPolicy.IsManual(s));

        _shaderCache.IsOn = s.ShaderCacheNvidia || s.ShaderCacheAmd;
        _shaderCache.Toggled += on => LauncherState.Write(x => { x.ShaderCacheNvidia = on; x.ShaderCacheAmd = on; });

        var presetText = SettingsUi.TitleWhy("Recommended for this PC",
            "Picks render distance, particles, clouds and graphics to match your PC. Changes video settings in every version you have played.");
        _presetStatus = SettingsUi.Why("");
        _presetStatus.Margin = new Thickness(0, 6, 0, 0);
        _presetStatus.IsVisible = false; // an empty line still took space under the card's text
        presetText.Children.Add(_presetStatus);
        _preset.Click += async (_, _) => await ApplyPresetAsync();

        // ---- Game ----
        _installPath.Text = s.InstallPath;
        _installPath.LostFocus += (_, _) => SaveInstallPath();
        _installPath.TextChanged += (_, _) =>
        {
            // Once a problem shows, it re-checks as the player types, so fixing the text clears it.
            if (SettingsUi.IsInvalid(_installPath)) ShowInstallProblem(FieldRules.InstallFolderProblem(_installPath.Text));
        };
        var browse = Ui.Button("Browse", Look.Secondary);
        browse.Click += async (_, _) => await BrowseAsync();
        var pathLine = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), ColumnSpacing = Space.S2, Margin = new Thickness(0, Space.S3, 0, 0) };
        pathLine.Children.Add(_installPath.At(0));
        pathLine.Children.Add(browse.At(1));

        _width.Text = s.ResolutionWidth.ToString(System.Globalization.CultureInfo.InvariantCulture);
        _height.Text = s.ResolutionHeight.ToString(System.Globalization.CultureInfo.InvariantCulture);
        _width.LostFocus += (_, _) => SaveResolution();
        _height.LostFocus += (_, _) => SaveResolution();
        var resolutionBoxes = Ui.Row(0, _width, Ui.Text("x", TypeScale.Body, FontWeight.Normal, Palette.TextDim).Margin(10, 0, 10, 0), _height);
        var resolution = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), RowDefinitions = new RowDefinitions("Auto,Auto") };
        resolution.Children.Add(SettingsUi.TitleWhy("Resolution", "Window size when the game opens.").Margin(0, 0, Space.S4, 0).At(0));
        resolution.Children.Add(resolutionBoxes.At(1));
        Grid.SetColumnSpan(_resolutionError, 2);
        resolution.Children.Add(_resolutionError.At(0, 1));

        // ---- JVM arguments ----
        _jvmVersions.Items = VersionCatalog.SupportedVersions.Select(v => new DropItem(v, v)).ToList();
        _jvmVersions.Trigger.Named("Version");
        _jvmVersions.Selected += OnJvmVersionPicked;
        _jvmArgs.AcceptsReturn = true;
        _jvmArgs.TextWrapping = TextWrapping.Wrap;
        _jvmArgs.Height = Sizes.FieldTall;
        _jvmArgs.VerticalContentAlignment = VerticalAlignment.Top;
        _jvmArgs.Padding = new Thickness(Space.S3, Space.S2);
        _jvmArgs.FontFamily = Assets.Mono;
        _jvmArgs.FontSize = TypeScale.Mono;
        _jvmArgs.Margin = new Thickness(0, 14, 0, 0);
        _jvmArgs.TextChanged += (_, _) =>
        {
            if (_jvmLoading) return;
            _jvmDirty = true;
            UpdateJvmStatus();
        };
        _jvmArgs.LostFocus += (_, _) =>
        {
            if (_jvmLoading) return;
            SaveJvmOptions();
            UpdateJvmCustomizedList();
            UpdateJvmUndo();
        };
        _jvmStatus = SettingsUi.Why("");
        _jvmStatus.Margin = new Thickness(0, 6, 0, 0);
        _jvmUndoRow = SettingsUi.Row(SettingsUi.TitleWhy("Reset after a crash", "", out var undoText), _jvmUndo);
        _jvmUndoText = undoText!;
        _jvmUndoRow.Margin = new Thickness(0, 14, 0, 0);
        _jvmUndoRow.IsVisible = false;
        _jvmUndo.Click += (_, _) => OnJvmUndo();
        _jvmReplace.Toggled += _ =>
        {
            if (_jvmLoading) return;
            _jvmDirty = true;
            SaveJvmOptions();
            UpdateJvmStatus();
            UpdateJvmCustomizedList();
            UpdateJvmUndo();
        };
        _jvmCustom = SettingsUi.Why("");
        _jvmCustom.Margin = new Thickness(0, 10, 0, 0);
        // Open on the version the player is set to play: the common case needs no dropdown trip.
        var versions = VersionCatalog.SupportedVersions;
        _jvmVersions.SetValue(VersionCatalog.IsSupported(s.SelectedVersion) ? s.SelectedVersion : versions.FirstOrDefault());
        LoadJvmOptionsForSelectedVersion();

        // ---- Origin Client, Startup, Developer ----
        _originUi.IsOn = OriginClientConfigBridge.IsOriginUiEnabled();
        _originUi.Toggled += on => OriginClientConfigBridge.SetOriginUiEnabled(on);
        // Not a launcher setting: the OS's own start-at-login entry, read and written through Core's seam (Linux: an
        // XDG autostart .desktop file; macOS: a LaunchAgent), so there is no settings.json copy to drift from it.
        _startAtLogin.IsOn = SafeIsStartAtLogin();
        _startAtLogin.Toggled += OnStartAtLoginToggled;
        _offline.IsOn = s.OfflineTestMode;
        _offline.Toggled += on => LauncherState.Write(x => x.OfflineTestMode = on);

        _sync.IsOn = s.SyncSettingsAcrossVersions;
        PaintSyncState();

        // ---- The page ----
        var sections = new StackPanel();
        Control Title(string name, string text, bool first = false)
        {
            var t = SettingsUi.SectionTitle(text, first);
            t.Name = name;
            sections.Children.Add(t);
            return t;
        }

        var secSync = Title("SecSync", "SETTINGS SYNC", first: true);
        sections.Children.Add(SettingsUi.Card(syncRow));
        var secPerformance = Title("SecPerformance", "PERFORMANCE");
        sections.Children.Add(SettingsUi.Card(Ui.Column(0, ramHead, ramLine)));
        sections.Children.Add(SettingsUi.Card(SettingsUi.Row(SettingsUi.TitleWhy("Shader cache", "Reduces stutter while shaders compile."), _shaderCache)));
        sections.Children.Add(SettingsUi.Card(SettingsUi.Row(presetText, _preset)));
        var secGame = Title("SecGame", "GAME");
        sections.Children.Add(SettingsUi.Card(Ui.Column(0,
            SettingsUi.TitleWhy("Install folder", "Where Minecraft's game files are kept."), pathLine, _installError)));
        sections.Children.Add(SettingsUi.Card(resolution));
        var secJvm = Title("SecJvm", "JVM ARGUMENTS");
        sections.Children.Add(SettingsUi.Card(Ui.Column(0,
            SettingsUi.Row(SettingsUi.TitleWhy("Arguments for",
                "Each version keeps its own: a flag one Java accepts can stop another from starting."), _jvmVersions),
            _jvmArgs, _jvmStatus, _jvmUndoRow)));
        sections.Children.Add(SettingsUi.Card(Ui.Column(0,
            SettingsUi.Row(SettingsUi.TitleWhy("Replace defaults", "Use only your arguments, without Origin's tuned ones."), _jvmReplace),
            _jvmCustom)));
        var secClient = Title("SecClient", "ORIGIN CLIENT");
        sections.Children.Add(SettingsUi.Card(SettingsUi.Row(SettingsUi.TitleWhy("Origin UI", "Origin's menus and loading screens in game."), _originUi)));
        var secStartup = Title("SecStartup", "STARTUP");
        sections.Children.Add(SettingsUi.Card(SettingsUi.Row(SettingsUi.TitleWhy("Open Origin when you log in"), _startAtLogin)));
        Control? secDeveloper = null;
        if (DeveloperVisible)
        {
            secDeveloper = Title("SecDeveloper", "DEVELOPER");
            sections.Children.Add(SettingsUi.Card(SettingsUi.Row(
                SettingsUi.TitleWhy("Offline test mode", "Play without a Microsoft sign-in, for testing."), _offline)));
        }
        // Which build this is: what a bug report needs, and the one place the launcher states it.
        sections.Children.Add(Ui.Caption(UpdateService.IsDevBuild
                ? "Origin Launcher - dev build"
                : "Origin Launcher " + UpdateService.CurrentVersion.ToString(3))
            .Align(HorizontalAlignment.Center).Margin(0, Space.S8, 0, Space.S2));

        AddRail("SETTINGS SYNC", Icons.Sync, Palette.HueSky, secSync);
        AddRail("PERFORMANCE", Icons.Settings, Palette.HueEmerald, secPerformance);
        AddRail("GAME", Icons.Folder, Palette.HueSky, secGame);
        AddRail("JVM ARGUMENTS", Icons.Server, Palette.HueLilac, secJvm);
        AddRail("ORIGIN CLIENT", Icons.Mods, Palette.Accent, secClient);
        AddRail("STARTUP", Icons.Home, Palette.HueGold, secStartup);
        if (secDeveloper != null) AddRail("DEVELOPER", Icons.Edit, Palette.HueCoral, secDeveloper);
        _rail.RenderTransform = _railShift;

        var column = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions($"{Fmt.N(Sizes.SettingsRail)},*"),
            RowDefinitions = new RowDefinitions("Auto,*"),
            MaxWidth = Sizes.PageMaxWidth,
            HorizontalAlignment = HorizontalAlignment.Left,
            Margin = new Thickness(Space.S8, Sizes.PageTop, Space.S8, Space.S8),
        };
        var heading = Ui.Heading("Settings").Align(HorizontalAlignment.Left, VerticalAlignment.Top);
        Grid.SetColumnSpan(heading, 2);
        column.Children.Add(heading.At(0));
        column.Children.Add(_rail.At(0, 1));
        column.Children.Add(sections.At(1, 1));
        _content.Children.Add(column);

        // The bar is hidden; the wheel, keys and touch still scroll (Windows: VerticalScrollBarVisibility="Hidden").
        _scroller = new ScrollViewer
        {
            Content = _content,
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Hidden,
            HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
        };
        _scroller.ScrollChanged += (_, _) =>
        {
            _railShift.Y = _scroller.Offset.Y;
            RelightRail();
        };
        _scroller.AddHandler(PointerWheelChangedEvent, (_, _) => TakeOverFromClick(), RoutingStrategies.Tunnel);
        _scroller.AddHandler(KeyDownEvent, OnScrollerKey, RoutingStrategies.Tunnel);
        Children.Add(_scroller);
        LightRailItem(0);
    }

    public void Shown()
    {
        ShowFromTop();
        ReloadJvmFromStore(); // the crash window may have reset (or Undo restored) a version's arguments meanwhile
    }

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        // Pages stay attached and are hidden on a tab switch, so leaving Settings is a hide.
        if (change.Property == IsVisibleProperty && !IsVisible) PersistPendingRam();
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        PersistPendingRam();
        base.OnDetachedFromVisualTree(e);
    }

    // ---- Settings Sync ----

    // Turning it on also builds its store from every installed version (newest file wins per setting, so nothing the
    // player set is lost), on a worker thread since it reads every instance. Off only stops syncing.
    private void OnSyncToggled(bool on)
    {
        LauncherState.Write(x => x.SyncSettingsAcrossVersions = on);
        PaintSyncState();
        if (on) _ = Task.Run(SettingsSync.Seed);
    }

    /// <summary>The ON / OFF word reads the switch beside it, so the two can never disagree.</summary>
    private void PaintSyncState()
    {
        var on = _sync.IsOn;
        _syncStateText.Text = on ? "ON" : "OFF";
        _syncStateText.Foreground = on ? Palette.AccentHover : Palette.TextDim;
        _syncState.Background = on ? Palette.AccentSoft : Palette.CardHover;
    }

    // ---- RAM: Auto until the player moves the slider (RamPolicy). The slider only DISPLAYS the stored number
    // clamped to this PC's range; nothing here writes it back, so a Manual value is never changed by the launcher.

    private void OnRamMoved(int ramMb)
    {
        _ramValue.Text = FormatRam(ramMb);
        _ramWhy.Text = RamManualText;
        _ramAuto.IsVisible = true;
        _pendingRamMb = ramMb;
        _ramSave.Stop();
        _ramSave.Start();
    }

    private void PersistPendingRam()
    {
        _ramSave.Stop();
        if (_pendingRamMb is not { } ramMb) return;
        _pendingRamMb = null;
        LauncherState.Write(x => RamPolicy.SetManual(x, ramMb));
    }

    // "Back to Auto": the only way out of Manual.
    private void OnRamAuto()
    {
        _ramSave.Stop();
        _pendingRamMb = null;
        var total = _totalRamMb;
        LauncherState.Write(x => RamPolicy.SetAuto(x, total));
        ShowRam(manual: false);
    }

    private void ShowRam(bool manual)
    {
        // Manual: the same number the launch uses (RamAdvice.ManualMb), so what this shows is what the game gets.
        var shown = manual ? RamAdvice.ManualMb(LauncherState.Settings.RamMb, _totalRamMb) : RamAdvice.AutoMb(_totalRamMb, legacy: false, heavyPack: false);
        _ram.Value = shown;
        _ramValue.Text = manual ? FormatRam((int)_ram.Value) : "Auto";
        _ramWhy.Text = manual ? RamManualText : RamAutoText;
        _ramAuto.IsVisible = manual;
    }

    private static string FormatRam(int ramMb) => Fmt.N(ramMb / 1024d, "0.##") + " GB";

    // "Recommended for this PC": the player's explicit click, so it MAY change options that already exist, but only
    // the preset's own keys and only in folders whose game is not running. Detection and file work run off the UI thread.
    private async Task ApplyPresetAsync()
    {
        _preset.IsEnabled = false;
        ShowPresetStatus("Checking this PC...");
        try
        {
            var (summary, applied, total) = await Task.Run(() =>
            {
                var hardware = HardwareProbe.Current;
                var tier = TierPicker.Pick(hardware);
                int done = 0, count = 0;
                foreach (var version in VersionManager.OriginSupportedVersions)
                    foreach (var (_, dir) in AccountFolders.GameDirs(version))
                    {
                        if (!File.Exists(Path.Combine(dir, "options.txt"))) continue;
                        count++;
                        if (SettingsSync.IsGameRunning(dir)) continue;
                        if (PresetWriter.ApplyNow(dir, version, tier) == PresetOutcome.Applied) done++;
                    }
                return ($"{tier} - {HardwareProbe.Describe(hardware)}", done, count);
            });
            ShowPresetStatus(total == 0
                ? $"{summary}. New versions start with these settings."
                : $"{summary}. Applied to {applied} of {total} game folder{(total == 1 ? "" : "s")}.");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            ShowPresetStatus("Couldn't change the video settings - a game file is in use. Try again in a moment.");
        }
        finally
        {
            _preset.IsEnabled = true;
        }
    }

    private void ShowPresetStatus(string text)
    {
        _presetStatus.Text = text;
        _presetStatus.IsVisible = text.Length > 0;
    }

    // ---- Game folder and resolution: a rejected value is NOT saved and not silently put back. The box keeps what
    // was typed, shows the Danger edge and says what is wrong under it (FieldRules).

    private void SaveInstallPath()
    {
        var path = (_installPath.Text ?? "").Trim();
        if (ShowInstallProblem(FieldRules.InstallFolderProblem(path))) return;
        if (path == LauncherState.Settings.InstallPath) return;
        LauncherState.Write(x => x.InstallPath = path);
    }

    private bool ShowInstallProblem(string? problem)
    {
        SettingsUi.ShowError(_installError, problem);
        SettingsUi.SetInvalid(_installPath, problem != null);
        return problem != null;
    }

    private async Task BrowseAsync()
    {
        if (TopLevel.GetTopLevel(this)?.StorageProvider is not { CanPickFolder: true } storage) return;
        IStorageFolder? start = null;
        try
        {
            if (!string.IsNullOrWhiteSpace(_installPath.Text) && Directory.Exists(_installPath.Text))
                start = await storage.TryGetFolderFromPathAsync(_installPath.Text);
        }
        catch (Exception) { /* no start folder: the picker opens where it likes */ }
        var picked = await storage.OpenFolderPickerAsync(new FolderPickerOpenOptions
        {
            Title = "Install folder", AllowMultiple = false, SuggestedStartLocation = start,
        });
        if (picked.Count == 0 || picked[0].TryGetLocalPath() is not { } folder) return;
        _installPath.Text = folder;
        ShowInstallProblem(null);
        LauncherState.Write(x => x.InstallPath = folder);
    }

    private void SaveResolution()
    {
        var widthProblem = FieldRules.ResolutionProblem(_width.Text, FieldRules.MinWidth, "Width");
        var heightProblem = FieldRules.ResolutionProblem(_height.Text, FieldRules.MinHeight, "Height");
        SettingsUi.SetInvalid(_width, widthProblem != null);
        SettingsUi.SetInvalid(_height, heightProblem != null);
        SettingsUi.ShowError(_resolutionError, widthProblem ?? heightProblem);
        if (widthProblem != null || heightProblem != null) return;
        var width = int.Parse(_width.Text!.Trim(), System.Globalization.CultureInfo.InvariantCulture);
        var height = int.Parse(_height.Text!.Trim(), System.Globalization.CultureInfo.InvariantCulture);
        var s = LauncherState.Settings;
        if (s.ResolutionWidth == width && s.ResolutionHeight == height) return;
        LauncherState.Write(x => { x.ResolutionWidth = width; x.ResolutionHeight = height; });
    }

    // ---- Start at login (Core/Install/InstallRegistry.<os>.cs) ----

    private static bool SafeIsStartAtLogin()
    {
        try { return InstallRegistry.IsStartWithWindows(); }
        catch (Exception) { return false; }
    }

    private void OnStartAtLoginToggled(bool on)
    {
        var exe = Environment.ProcessPath ?? Path.Combine(AppContext.BaseDirectory, "OriginLauncher");
        try
        {
            InstallRegistry.SetStartWithWindows(on, exe);
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or IOException or System.Security.SecurityException)
        {
            _startAtLogin.IsOn = SafeIsStartAtLogin(); // say what the OS really does
            _window.Toast("Couldn't change start at login: " + ex.Message, Tone.Warning);
        }
    }

    // ---- JVM arguments ----

    private void OnJvmVersionPicked(string version)
    {
        if (_jvmLoading) return;
        // Commit the box to the version being navigated AWAY from before repointing the editor, so switching versions
        // never loses a pending edit (the box saves on focus loss, which the dropdown steals).
        SaveJvmOptions();
        LoadJvmOptionsForSelectedVersion();
    }

    private void LoadJvmOptionsForSelectedVersion()
    {
        _jvmLoading = true;
        _jvmVersion = _jvmVersions.Value;
        var opts = _jvmVersion != null && LauncherState.Settings.JvmArgsByVersion.TryGetValue(_jvmVersion, out var stored)
            ? stored
            : new VersionJvmOptions();
        _jvmArgs.Text = opts.Arguments;
        _jvmReplace.IsOn = opts.ReplacePresets;
        _jvmDirty = false;
        UpdateJvmStatus();
        UpdateJvmCustomizedList();
        UpdateJvmUndo();
        _jvmLoading = false;
    }

    // Picks up Java-argument changes made outside this page (the crash window's reset). Never while the player has an
    // unsaved edit in the box: theirs stays.
    private void ReloadJvmFromStore()
    {
        if (_jvmDirty || _jvmLoading) return;
        LauncherState.ReloadSettings();
        LoadJvmOptionsForSelectedVersion();
    }

    // "Reset after a crash" + Undo: only while a reset is on record and the version has no arguments of its own.
    private void UpdateJvmUndo()
    {
        var s = LauncherState.Settings;
        var show = RepairExecutor.CanUndoJavaArgsReset(s, _jvmVersion)
                   && string.IsNullOrWhiteSpace(_jvmArgs.Text) && !_jvmReplace.IsOn;
        _jvmUndoRow.IsVisible = show;
        if (!show) return;
        var args = JvmArgLine.Preview(s.JvmArgsBeforeReset[_jvmVersion!].Arguments);
        _jvmUndoText.Text = args.Length == 0
            ? "Java wouldn't start with your settings here, so Origin went back to its own."
            : $"Java wouldn't start with your arguments, so Origin went back to its own. Yours were: {args}";
    }

    // Puts back the arguments the crash window reset. One control, one job: only this version's Java arguments.
    private void OnJvmUndo()
    {
        var version = _jvmVersion;
        if (version == null) return;
        JavaArgsUndoOutcome outcome;
        try
        {
            outcome = RepairExecutor.UndoJavaArgsReset(version, LauncherState.Write);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // settings.json held by another program (antivirus, a sync tool): nothing changed, say so and stay put.
            _jvmStatus.Text = "Couldn't put your arguments back - the settings file is in use. Try again in a moment.";
            return;
        }
        LauncherState.ReloadSettings();
        LoadJvmOptionsForSelectedVersion();
        if (outcome == JavaArgsUndoOutcome.ChangedSince)
            _jvmStatus.Text = "You've set new arguments since, so they were kept.";
    }

    // Writes the editor to _jvmVersion (the version the editor shows), NOT the dropdown's current pick: those differ
    // for exactly one call, when the pick has already moved.
    private void SaveJvmOptions()
    {
        var version = _jvmVersion;
        if (version == null || !_jvmDirty) return;
        _jvmDirty = false;
        var text = _jvmArgs.Text ?? "";
        var replace = _jvmReplace.IsOn;
        if (new VersionJvmOptions { Arguments = text, ReplacePresets = replace }.IsEmpty)
        {
            LauncherState.Write(x => x.JvmArgsByVersion.Remove(version));
            return;
        }
        // Arguments of the player's own replace the reset ones for good: the Undo record goes in the same write, so
        // clearing the box later never brings back an old "Reset after a crash" offer.
        LauncherState.Write(x =>
        {
            x.JvmArgsByVersion[version] = new VersionJvmOptions { Arguments = text, ReplacePresets = replace };
            x.JvmArgsBeforeReset.Remove(version);
        });
    }

    private void UpdateJvmStatus()
    {
        var tokens = JvmArgLine.Split(_jvmArgs.Text);
        var replace = _jvmReplace.IsOn;
        // The one mistake that stops the game booting outright: a collector while Origin's G1 preset is still in
        // play (the JVM aborts with "Multiple garbage collectors selected"). Name the switch that fixes it.
        if (!replace && JvmArgLine.ConflictsWithPresetCollector(tokens))
        {
            _jvmStatus.Text = $"{JvmArgLine.SelectedCollector(tokens)} conflicts with Origin defaults. Turn on Replace defaults.";
            return;
        }
        if (JvmArgLine.Warn(tokens) is { } warning)
        {
            _jvmStatus.Text = warning;
            return;
        }
        var count = $"{tokens.Count} argument{(tokens.Count == 1 ? "" : "s")}";
        _jvmStatus.Text = replace
            ? tokens.Count == 0 ? "No custom arguments" : $"{count}, defaults off"
            : tokens.Count == 0 ? "Using Origin defaults" : $"{count}, Origin defaults on";
    }

    private void UpdateJvmCustomizedList()
    {
        var s = LauncherState.Settings;
        var customized = VersionCatalog.SupportedVersions.Where(v => s.JvmArgsFor(v) != null).ToList();
        _jvmCustom.Text = customized.Count == 0 ? "No custom versions" : $"Custom: {string.Join(", ", customized)}";
    }

    // ---- Section rail: lives inside the scrolled column (so it lines up at every width) and is shifted by the
    // scroll offset so it stays put. A click jumps to its section and stays lit (the last sections are shorter than
    // the window, so they never reach the top); scrolling by hand lights the section under the top edge.

    private void AddRail(string label, string glyph, IBrush hue, Control section)
    {
        var item = new SettingsRailItem(label, glyph, hue, section.Name!);
        var index = _sections.Count;
        item.Click += (_, _) => GoToSection(index);
        _sections.Add((item, section));
        _rail.Children.Add(item);
    }

    private double SectionTop(Control section) => section.TranslatePoint(default, _content)?.Y ?? double.MaxValue;

    private double ScrollableHeight => Math.Max(0, _scroller.Extent.Height - _scroller.Viewport.Height);

    private void LightRailItem(int index)
    {
        for (var i = 0; i < _sections.Count; i++) _sections[i].Item.IsSelected = i == index;
    }

    private void GoToSection(int index)
    {
        if (index < 0 || index >= _sections.Count) return;
        _spy.Click(index);
        LightRailItem(index);
        var target = SettingsRailSpy.TargetOffset(SectionTop(_sections[index].Section), ScrollableHeight);
        _scroller.Offset = new Vector(0, target);
    }

    // Ends a click's hold and lights what is in view right now. The page may not move at all (End while already at
    // the end), and then no scroll event comes to do it.
    private void TakeOverFromClick()
    {
        if (_spy.Pinned == null) return;
        _spy.UserScrolled();
        RelightRail();
    }

    private void OnScrollerKey(object? sender, KeyEventArgs e)
    {
        // Typing in a field (or nudging the slider) is not scrolling the page.
        if (e.Source is SettingsSlider || (e.Source as Visual)?.FindAncestorOfType<TextBox>(includeSelf: true) != null) return;
        if (e.Key is Key.PageUp or Key.PageDown or Key.Home or Key.End or Key.Up or Key.Down or Key.Space) TakeOverFromClick();
    }

    private void RelightRail()
    {
        if (_sections.Count == 0) return;
        var tops = _sections.Select(x => SectionTop(x.Section)).ToList();
        LightRailItem(_spy.Resolve(tops, _scroller.Offset.Y, ScrollableHeight));
    }

    // ---- What the screenshot run drives (Shots/SettingsShots.cs) ----

    /// <summary>Settings was opened (again): the first section, from the top, nothing remembered.</summary>
    public void ShowFromTop()
    {
        _spy.Reset();
        _scroller.Offset = default;
        LightRailItem(0);
    }

    /// <summary>Press the rail item for a section ("SecJvm") the way a click does. False when the page has no such section.</summary>
    public bool ClickRail(string section)
    {
        var item = _sections.FirstOrDefault(x => x.Item.Section == section).Item;
        if (item == null) return false;
        item.RaiseClick();
        return true;
    }

    /// <summary>Scroll to a fraction of the page (0 top, 1 bottom), as a wheel would.</summary>
    public void ScrollToFraction(double fraction)
    {
        TakeOverFromClick();
        _scroller.Offset = new Vector(0, ScrollableHeight * Math.Clamp(fraction, 0, 1));
    }

    /// <summary>The Settings Sync row a little below the top of the page, whole (the row's own cut-off check).</summary>
    public void ScrollToSync()
    {
        var top = _sync.TranslatePoint(default, _content)?.Y ?? 0;
        _scroller.Offset = new Vector(0, Math.Max(0, top - 160));
    }

    /// <summary>End, the way a player presses it: the key clears a rail click, then the page goes to its end.</summary>
    public void PressEnd()
    {
        TakeOverFromClick();
        _scroller.ScrollToEnd();
    }

    public TextBox InstallPathBox => _installPath;
    public TextBox WidthBox => _width;
    public Dropdown JvmVersions => _jvmVersions;

    /// <summary>The two error states the shots show: a path that is not a full folder path, a width that is too small.</summary>
    public void ShowFieldErrors()
    {
        _installPath.Text = "games";
        ShowInstallProblem(FieldRules.InstallFolderProblem(_installPath.Text));
        _width.Text = "100";
        var problem = FieldRules.ResolutionProblem("100", FieldRules.MinWidth, "Width");
        SettingsUi.SetInvalid(_width, problem != null);
        SettingsUi.ShowError(_resolutionError, problem);
    }

    /// <summary>Show what is saved now (a shot that seeds a reset record calls this).</summary>
    public void ReloadJvm() => ReloadJvmFromStore();
}

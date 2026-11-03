using Avalonia;
using Avalonia.Automation;
using Avalonia.VisualTree;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Controls.Shapes;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Mods.Icons;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac.UI.Pages;

// Twin of the WPF UI/Pages/ModsPage.xaml.cs, through the same Core calls (ModManager, ProfileRules, SettingsStore):
// the per-version manager of the player's own jars in instances/<version>/mods (or the selected profile's folder),
// shared by every account. A dashed drop zone that behaves like drag and drop should, the player's mods as rows, then
// Origin's built-in mods as cards; one status strip under the zone.
public sealed partial class ModsPage : UserControl
{
    private string? _version;
    private ModProfile? _profile;      // the selected mod profile when it belongs to _version; else null (normal mods)
    private string? _pendingRemove;    // filename awaiting the inline "Remove?" confirm
    private readonly List<ReplaceOffer> _pendingReplace = new();
    private bool _suppressToggle;      // guards programmatic IsChecked changes
    private bool _dragOver;
    private bool _adding;              // an import is copying (off the UI thread)
    private IReadOnlyList<ModEntry>? _demo; // render shots only: made-up rows instead of the disk
    private string? _builtKey;
    private List<ModEntry>? _builtFrom;

    private readonly ScaleTransform _jarScale = new();
    private readonly TranslateTransform _jarLift = new();

    internal string? ShownVersion => _version;

    public ModsPage()
    {
        InitializeComponent();
        JarIcon.RenderTransform = new TransformGroup { Children = { _jarScale, _jarLift } };
        JarIcon.RenderTransformOrigin = RelativePoint.Center;
        YourModsSwitch.IsCheckedChanged += YourModsSwitch_Changed;
        OpenFolderButton.Click += OpenFolder_Click;
        DropZone.PointerEntered += (_, _) => { if (!_dragOver && _version != null) SetZone(over: false, hover: true, count: 0); };
        DropZone.PointerExited += (_, _) => { if (!_dragOver) SetZone(over: false, hover: false, count: 0); };
        DropZone.PointerReleased += (_, e) => { if (e.InitialPressMouseButton == MouseButton.Left) Browse(); };
        DropZone.KeyDown += DropZone_KeyDown;
        DropZone.AddHandler(DragDrop.DragEnterEvent, DropZone_DragEnter);
        DropZone.AddHandler(DragDrop.DragOverEvent, DropZone_DragEnter);
        DropZone.AddHandler(DragDrop.DragLeaveEvent, DropZone_DragLeave);
        DropZone.AddHandler(DragDrop.DropEvent, DropZone_Drop);
        if (!Motion.Off)
        {
            // The idle and "Release to add" lines cross-fade (120 ms), as on Windows.
            foreach (var t in new Control[] { IdleText, OverText })
                t.Transitions = [new Avalonia.Animation.DoubleTransition { Property = OpacityProperty, Duration = TimeSpan.FromMilliseconds(120) }];
        }
    }

    /// <summary>Point the page at a version and rebuild. Null = nothing selectable.</summary>
    public void ShowVersion(string? version)
    {
        _version = version;
        _pendingRemove = null;
        _pendingReplace.Clear();
        _demo = null;
        ResolveProfile();
        VersionChipText.Text = version ?? "No version";
        VersionChip.IsVisible = version != null;
        DropZone.IsEnabled = version != null;
        DropZone.Opacity = version != null ? 1.0 : 0.5;
        SyncYourModsSwitch();
        HideStatus();
        SetZone(over: false, hover: false, count: 0);
        Refresh();
    }

    private void SyncYourModsSwitch()
    {
        var on = SettingsStore.Load().PlayWithExternalMods;
        _suppressToggle = true;
        YourModsSwitch.IsChecked = on;
        _suppressToggle = false;
    }

    // One control, one job: the switch writes only its own setting.
    private void YourModsSwitch_Changed(object? sender, RoutedEventArgs e)
    {
        if (_suppressToggle || _demo != null || OriginPaths.IsScratchRoot && _version == null) return;
        var on = YourModsSwitch.IsChecked == true;
        SettingsStore.Update(s => s.PlayWithExternalMods = on);
    }

    // "built-in" with a non-breaking hyphen (U+2011, written as an escape so the source stays ASCII): Avalonia breaks a
    // line after a plain hyphen (UAX #14), WPF doesn't, so the intro wrapped as "built-" / "in" on the Mac.
    private const string BuiltIn = "built\u2011in";

    private void ResolveProfile()
    {
        var settings = SettingsStore.Load();
        _profile = ProfileRules.Active(settings.ModProfiles, settings.SelectedProfileId, _version);
        var selected = settings.ModProfiles.FirstOrDefault(p => p.Id == settings.SelectedProfileId && ProfileRules.IsUsable(p));

        if (_profile != null)
            IntroText.Text = $"Mods for your profile \"{_profile.Name}\". Only this profile loads them; Origin's {BuiltIn} mods stay up to date on their own.";
        else if (selected != null && _version != null)
            IntroText.Text = $"Your profile \"{selected.Name}\" is for {selected.Version}, so these are your normal mods for this version, shared by all your accounts.";
        else
            IntroText.Text = $"Your own .jar mods for this version, shared by all your accounts. Origin's {BuiltIn} mods stay up to date on their own.";
    }

    private void Refresh(ICollection<string>? landed = null)
    {
        if (_version == null)
        {
            _builtKey = null;
            _builtFrom = null;
            UserModsPanel.Children.Clear();
            BuiltInPanel.Children.Clear();
            UserEmptyText.Text = "Choose a version first";
            UserEmptyState.IsVisible = true;
            UserCount.Text = "0";
            UserHint.IsVisible = false;
            OpenFolderButton.IsEnabled = false;
            BuiltInSection.IsVisible = false;
            return;
        }

        var mods = _demo ?? ModManager.Enumerate(_version, _profile?.Id);
        var plain = landed == null && _pendingRemove == null && _pendingReplace.Count == 0 && _demo == null;
        var key = $"{_version}|{_profile?.Id}|{_profile?.Name}";
        if (plain && key == _builtKey && _builtFrom != null && mods.SequenceEqual(_builtFrom)) return; // same rows
        _builtKey = plain ? key : null;
        _builtFrom = plain ? mods.ToList() : null;
        UserModsPanel.Children.Clear();
        BuiltInPanel.Children.Clear();
        var user = mods.Where(m => !m.IsManaged).ToList();
        var managed = _profile != null ? new List<ModEntry>() : mods.Where(m => m.IsManaged).ToList();

        if (_pendingReplace.Count > 0) UserModsPanel.Children.Add(BuildReplaceRow(_pendingReplace[0]));

        var delay = 0;
        foreach (var mod in user)
        {
            var isLanded = landed != null && landed.Contains(mod.FileName);
            var row = mod.FileName == _pendingRemove ? BuildConfirmRow(mod) : BuildUserRow(mod, isLanded);
            UserModsPanel.Children.Add(row);
            if (isLanded)
            {
                Land(row, delay);
                delay += 40;
            }
        }

        OpenFolderButton.IsEnabled = true;
        UserCount.Text = user.Count.ToString();
        UserEmptyText.Text = "No mods yet. Drop a jar above to add one.";
        UserEmptyState.IsVisible = user.Count == 0;
        UserHint.IsVisible = user.Count > 0;

        foreach (var mod in managed)
            BuiltInPanel.Children.Add(BuildManagedTile(mod));
        BuiltInCount.Text = managed.Count.ToString();
        BuiltInSection.IsVisible = managed.Count > 0;
    }

    // A just-added row drops in from above and settles (Back ease), staggered.
    private static void Land(Control row, int delayMs)
    {
        if (Motion.Off) return;
        var shift = new TranslateTransform();
        row.RenderTransform = shift;
        MotionFx.Run(row, OpacityProperty, 0, 1, 220, MotionFx.Out, delayMs);
        MotionFx.Run(shift, TranslateTransform.YProperty, -14, 0, 420, MotionFx.Back, delayMs);
    }

    // ---- row builders -----------------------------------------------------

    private static Border RowShell() => new() { Theme = Res.Theme("Surface.Row"), CornerRadius = Res.Radius("Radius.Md") };

    // The square well that leads every row and tile: the mod's own icon, else its tinted glyph, else the jar.
    private Border IconWell(bool dim, ModEntry? mod = null)
    {
        var well = new Border
        {
            Width = Res.D("Size.TileLg"),
            Height = Res.D("Size.TileLg"),
            Background = Res.Brush("Brush.Bg"),
            CornerRadius = Res.Radius("Radius.Sm"),
            VerticalAlignment = VerticalAlignment.Center,
        };
        var glyph = new IconPath
        {
            Data = (mod != null ? ModIcons.For(mod) : null) ?? Res.Geo("Icon.Jar"),
            Theme = Res.Theme("Icon.Base"),
            Stroke = Res.Brush(dim ? "Brush.TextMuted" : "Brush.TextDim"),
            Width = Res.D("Icon.SizeLarge"),
            Height = Res.D("Icon.SizeLarge"),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        var host = new Panel();
        host.Children.Add(glyph);
        // Origin Client's own entry wears the official logo image, never a drawn glyph.
        if (mod != null && ModIcons.IsOrigin(mod))
        {
            glyph.IsVisible = false;
            host.Children.Add(new BrandLogo
            {
                Width = Res.D("Size.Logo.Bar"),
                Height = Res.D("Size.Logo.Bar"),
                Opacity = dim ? 0.55 : 1,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            });
        }
        well.Child = host;
        if (mod != null && ModIcons.Hue(mod) is { } hue)
        {
            well.Background = new SolidColorBrush(Color.FromArgb(0x26, hue.R, hue.G, hue.B));
            glyph.Stroke = new SolidColorBrush(hue);
            glyph.Opacity = dim ? 0.55 : 1;
        }
        if (mod != null) LoadRealIcon(mod, well, glyph, dim);
        return well;
    }

    // ---- real mod icons -------------------------------------------------------

    // Decoded icons by jar (path + size + mtime), loaded once and shared across rebuilds.
    private static readonly IconMemo<Bitmap> Icons = new();

    // Icons come only from inside the jar. Origin Client's own jar keeps the Origin mark.
    private void LoadRealIcon(ModEntry mod, Border well, IconPath glyph, bool dim)
    {
        if (mod.FileName.StartsWith("originclient", StringComparison.OrdinalIgnoreCase)) return;
        Task<Bitmap?> task;
        if (_demo != null)
        {
            var fileName = mod.FileName;
            task = Icons.GetByKeyAsync("demo:" + fileName, () =>
                ModIconSource.DemoIcons != null && ModIconSource.DemoIcons.TryGetValue(fileName, out var d) ? DecodeIcon(d) : null);
        }
        else
        {
            if (_version == null) return;
            var name = mod.Enabled || mod.IsManaged ? mod.FileName : mod.FileName + ".disabled";
            var jar = System.IO.Path.Combine(ModManager.ModsFolder(_version, _profile?.Id), name);
            task = Icons.GetAsync(jar, path => JarIconReader.ReadIcon(path) is { } bytes ? DecodeIcon(bytes) : null);
        }

        if (task.IsCompletedSuccessfully)
        {
            if (task.Result is { } ready) ShowRealIcon(well, glyph, ready, dim, fade: false);
            return;
        }
        _ = task.ContinueWith(t =>
        {
            if (t.IsCompletedSuccessfully && t.Result is { } image)
                Dispatcher.UIThread.Post(() => ShowRealIcon(well, glyph, image, dim, fade: true));
        }, TaskScheduler.Default);
    }

    // Decode off the UI thread; anything that is not a sane image is "no icon". Never keeps more than 128 px.
    private static Bitmap? DecodeIcon(byte[] bytes)
    {
        try
        {
            using var full = new Bitmap(new MemoryStream(bytes));
            int w = full.PixelSize.Width, h = full.PixelSize.Height;
            if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null;
            if (w >= h && w > 128) return Bitmap.DecodeToWidth(new MemoryStream(bytes), 128);
            if (h > w && h > 128) return Bitmap.DecodeToHeight(new MemoryStream(bytes), 128);
            return new Bitmap(new MemoryStream(bytes));
        }
        catch
        {
            return null;
        }
    }

    // The real icon over the tinted placeholder: nearest-neighbour at a whole-number multiple when small (16 -> 32,
    // 32 -> 32, 20 -> 40), high quality otherwise, clipped to the tile's corner radius either way.
    private static void ShowRealIcon(Border well, IconPath glyph, Bitmap source, bool dim, bool fade)
    {
        if (well.Child is not Panel host || host.Children.Count > 1) return;
        var longest = Math.Max(source.PixelSize.Width, source.PixelSize.Height);
        var wholeMultiple = longest <= 40;
        double side = wholeMultiple ? Math.Floor(40.0 / longest) * longest : 40;

        var image = new Image { Source = source, Stretch = Stretch.Uniform, Width = side, Height = side, IsHitTestVisible = false };
        RenderOptions.SetBitmapInterpolationMode(image, wholeMultiple ? BitmapInterpolationMode.None : BitmapInterpolationMode.HighQuality);
        var clip = new Border
        {
            Width = side,
            Height = side,
            CornerRadius = Res.Radius("Radius.Sm"),
            ClipToBounds = true,
            Child = image,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            IsHitTestVisible = false,
        };
        host.Children.Add(clip);

        var rest = dim ? 0.6 : 1.0;
        glyph.IsVisible = false;
        well.Background = Res.Brush("Brush.Bg");
        if (!fade) clip.Opacity = rest;
        else MotionFx.Run(clip, OpacityProperty, 0, rest, 200);
    }

    private static TextBlock NameText(string text, bool dim) => new()
    {
        Text = text,
        FontFamily = Res.Font,
        FontSize = Res.D("FontSize.Body"),
        FontWeight = FontWeight.SemiBold,
        Foreground = Res.Brush(dim ? "Brush.TextDim" : "Brush.Text"),
        TextTrimming = TextTrimming.CharacterEllipsis,
    };

    private static TextBlock FileText(string text) => new()
    {
        Text = text,
        FontFamily = Res.Font,
        FontSize = Res.D("FontSize.Caption"),
        Margin = new Thickness(0, 4, 0, 0),
        Foreground = Res.Brush("Brush.TextMuted"),
        TextTrimming = TextTrimming.CharacterEllipsis,
    };

    private Control BuildUserRow(ModEntry mod, bool added = false)
    {
        var border = RowShell();
        var grid = Res.Columns("Auto", "*", "Auto", "Auto");
        grid.Children.Add(IconWell(!mod.Enabled, mod));

        // The name column starts Space.3 after the icon well; the Replace and Remove prompts use the same gap.
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(Res.D("Space.3"), 0, 12, 0) };
        if (added)
        {
            var nameLine = new StackPanel { Orientation = Orientation.Horizontal };
            nameLine.Children.Add(NameText(mod.DisplayName, !mod.Enabled));
            nameLine.Children.Add(AddedTag());
            text.Children.Add(nameLine);
        }
        else text.Children.Add(NameText(mod.DisplayName, !mod.Enabled));
        text.Children.Add(FileText(mod.FileName));
        grid.Children.Add(text.At(1));

        var toggle = new ToggleButton
        {
            Theme = Res.Theme("Toggle.Slider"),
            IsChecked = mod.Enabled,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 12, 0),
        };
        AutomationProperties.SetName(toggle, $"Enable {mod.DisplayName}");
        toggle.IsCheckedChanged += (s, _) => OnToggle(mod.FileName, toggle.IsChecked == true, toggle);
        grid.Children.Add(toggle.At(2));

        var remove = IconButton("Icon.Trash", $"Remove {mod.DisplayName}");
        remove.Click += (_, _) => { _pendingRemove = mod.FileName; Refresh(); };
        grid.Children.Add(remove.At(3));

        border.Child = grid;
        if (added) AddLandingLight(grid);
        return border;
    }

    // "ADDED": a capsule badge, a 6 px accent dot and the word, on the soft accent.
    private static Border AddedTag()
    {
        var label = new TextBlock
        {
            Text = "ADDED",
            FontFamily = Res.Font,
            FontSize = Res.D("FontSize.Label"),
            FontWeight = FontWeight.Bold,
            Foreground = Res.Brush("Brush.Text"),
            VerticalAlignment = VerticalAlignment.Center,
        };
        var dot = new Border
        {
            Width = Res.D("Size.Dot"),
            Height = Res.D("Size.Dot"),
            Margin = new Thickness(0, 0, 6, 0),
            VerticalAlignment = VerticalAlignment.Center,
            Background = Res.Brush("Brush.Accent"),
        };
        Capsule.SetOn(dot, true);
        var line = new StackPanel { Orientation = Orientation.Horizontal };
        line.Children.Add(dot);
        line.Children.Add(label);
        var tag = new Border
        {
            Padding = Res.Thick("Padding.Capsule.Sm"),
            Margin = new Thickness(8, 0, 0, 0),
            VerticalAlignment = VerticalAlignment.Center,
            Background = Res.Brush("Brush.AccentSoft"),
            Child = line,
        };
        Capsule.SetOn(tag, true);
        return tag;
    }

    // The flash a landed row gives once: the soft accent over the row, fading out (opacity only; ignores the mouse).
    private static void AddLandingLight(Grid grid)
    {
        var radius = Res.Radius("Radius.Sm").TopLeft + 2;
        var tint = new Rectangle
        {
            IsHitTestVisible = false,
            Margin = new Thickness(-13, -11, -13, -11),
            RadiusX = radius,
            RadiusY = radius,
            Fill = Res.Brush("Brush.AccentSoft"),
        };
        Grid.SetColumnSpan(tint, 4);
        grid.Children.Insert(0, tint);
        if (Motion.Off)
        {
            tint.Opacity = 0;
            return;
        }
        MotionFx.Run(tint, OpacityProperty, 1, 0, 1100, MotionFx.Out, 300);
    }

    // Inline destructive-action confirm: the row flips to "Remove <name>?" with explicit Remove / Cancel.
    private Control BuildConfirmRow(ModEntry mod)
    {
        var border = RowShell();
        border.BorderBrush = Res.Brush("Brush.Danger");
        var grid = Res.Columns("Auto", "*", "Auto", "Auto");
        grid.Children.Add(IconWell(true, mod));

        var prompt = NameText($"Remove {mod.DisplayName}?", false);
        prompt.VerticalAlignment = VerticalAlignment.Center;
        prompt.Margin = new Thickness(Res.D("Space.3"), 0, Res.D("Space.2"), 0);
        grid.Children.Add(prompt.At(1));

        var confirm = new Button { Content = "Remove", Theme = Res.Theme("Button.Danger.Inline"), Margin = new Thickness(0, 0, 8, 0), VerticalAlignment = VerticalAlignment.Center };
        confirm.Click += (_, _) =>
        {
            if (_demo != null) return;
            var result = ModManager.Remove(_version!, mod.FileName, _profile?.Id);
            _pendingRemove = null;
            if (!result.Ok) ShowStatus(result.Error, error: true);
            else HideStatus();
            Refresh();
        };
        grid.Children.Add(confirm.At(2));

        var cancel = new Button { Content = "Cancel", Theme = Res.Theme("Button.Secondary.Inline"), VerticalAlignment = VerticalAlignment.Center };
        cancel.Click += (_, _) => { _pendingRemove = null; Refresh(); };
        grid.Children.Add(cancel.At(3));

        border.Child = grid;
        return border;
    }

    // A built-in mod: a read-only card (Origin installs and updates it), as tall as a row.
    private Control BuildManagedTile(ModEntry mod)
    {
        var tile = new Border
        {
            Theme = Res.Theme("Surface.Card"),
            Margin = new Thickness(0, 0, 4, 4),
            Padding = new Thickness(12, 10, 12, 10),
        };
        var grid = Res.Columns("Auto", "*");
        grid.Children.Add(IconWell(false, mod));
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(Res.D("Space.3"), 0, 0, 0) };
        text.Children.Add(NameText(mod.DisplayName, false));
        text.Children.Add(FileText(ModIcons.Blurb(mod)));
        grid.Children.Add(text.At(1));
        tile.Child = grid;
        AutomationProperties.SetName(tile, $"{mod.DisplayName}, built in");
        return tile;
    }

    // An icon sitting directly in a button, no box; the automation name carries the meaning.
    internal static Button IconButton(string iconKey, string automationName)
    {
        var button = new Button { Theme = Res.Theme("Social.Icon") };
        var path = new IconPath { Data = Res.Geo(iconKey), Theme = Res.Theme("Icon.Small") };
        // The glyph follows the button's own foreground (dim at rest, bright on hover).
        path.Bind(IconPath.StrokeProperty, button.GetObservable(ForegroundProperty));
        button.Content = path;
        AutomationProperties.SetName(button, automationName);
        return button;
    }

    // ---- actions ----------------------------------------------------------

    private void OnToggle(string fileName, bool enable, ToggleButton toggle)
    {
        if (_suppressToggle || _version == null || _demo != null) return;
        var result = ModManager.SetEnabled(_version, fileName, enable, _profile?.Id);
        if (result.Ok)
        {
            HideStatus();
            return;
        }
        // Revert the switch to disk truth without re-firing this handler.
        _suppressToggle = true;
        toggle.IsChecked = !enable;
        _suppressToggle = false;
        ShowStatus(result.Error, error: true);
    }

    private void OpenFolder_Click(object? sender, RoutedEventArgs e)
    {
        if (_version == null || _demo != null) return;
        var result = ModManager.OpenFolder(_version, _profile?.Id);
        if (!result.Ok) ShowStatus(result.Error, error: true);
    }

    // Browse: the same add path as a drop, through the Mac file picker.
    private async void Browse()
    {
        if (_version == null || _adding || _demo != null) return;
        if (TopLevel.GetTopLevel(this)?.StorageProvider is not { CanOpen: true } storage) return;
        var picked = await storage.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "Add mod jars",
            AllowMultiple = true,
            FileTypeFilter = [new FilePickerFileType("Mod jars") { Patterns = ["*.jar"] }],
        });
        var files = picked.Select(f => f.TryGetLocalPath()).OfType<string>().ToArray();
        if (files.Length > 0) AddFiles(files);
    }

    // Copy the jars in off the UI thread, then land the new rows. A second add waits its turn.
    private async void AddFiles(string[] files)
    {
        if (_version == null || _adding) return;
        _adding = true;
        var version = _version;
        var profileId = _profile?.Id;
        var before = ModManager.Enumerate(version, profileId).Select(m => m.FileName).ToHashSet(StringComparer.OrdinalIgnoreCase);
        ShowStatus(files.Length == 1 ? "Adding 1 mod..." : $"Adding {files.Length} mods...", error: false);

        var report = await Task.Run(() => ModManager.Import(version, files, profileId));
        _adding = false;
        if (_version != version) return; // the page moved on to another version meanwhile
        ApplyImport(report, before, version, profileId);
    }

    private void ApplyImport(ImportReport report, HashSet<string> before, string version, string? profileId)
    {
        _pendingReplace.AddRange(report.NeedsReplace);
        if (!report.Ok)
        {
            ShowStatus(report.Error, error: true);
            Refresh();
            return;
        }
        var after = ModManager.Enumerate(version, profileId);
        var landed = after.Where(m => !before.Contains(m.FileName)).Select(m => m.FileName).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var message = report.Message;
        if (message.Length > 0) ShowStatus(message, error: false); else HideStatus();
        Refresh(landed);
        if (report.Added > 0) Pop();
    }

    // The player answered a "Replace?" row. Yes copies that one jar in (the old file stays, turned off); no drops it.
    private async void AnswerReplace(ReplaceOffer offer, bool replace)
    {
        _pendingReplace.Remove(offer);
        if (!replace || _version == null || _adding)
        {
            Refresh();
            return;
        }
        _adding = true;
        var version = _version;
        var profileId = _profile?.Id;
        var before = ModManager.Enumerate(version, profileId).Select(m => m.FileName).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var confirmed = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { offer.FileName };
        var report = await Task.Run(() => ModManager.Import(version, new[] { offer.SourcePath }, profileId, confirmed));
        _adding = false;
        if (_version != version) return;
        ApplyImport(report, before, version, profileId);
    }

    // "Sodium 0.5.0 is already installed. Replace it with 0.6.13?" with explicit Replace / Keep old.
    private Control BuildReplaceRow(ReplaceOffer offer)
    {
        var border = RowShell();
        border.BorderBrush = Res.Brush("Brush.Accent");
        var grid = Res.Columns("Auto", "*", "Auto", "Auto");
        grid.Children.Add(IconWell(true, new ModEntry(offer.ExistingFileName, offer.ModName, true, false)));

        var prompt = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(Res.D("Space.3"), 0, Res.D("Space.2"), 0) };
        prompt.Children.Add(NameText($"{offer.ModName} is already installed", false));
        var from = offer.ExistingVersion ?? "this version";
        var versions = FileText(offer.NewVersion == null ? $"Replace {from} with this file?" : $"Replace {from} -> {offer.NewVersion}");
        versions.Margin = new Thickness(0, Res.D("Space.1"), 0, 0);
        prompt.Children.Add(versions);
        grid.Children.Add(prompt.At(1));

        var replace = new Button { Content = "Replace", Theme = Res.Theme("Button.Primary.Inline"), Margin = new Thickness(0, 0, Res.D("Space.2"), 0), VerticalAlignment = VerticalAlignment.Center };
        replace.Click += (_, _) => AnswerReplace(offer, true);
        grid.Children.Add(replace.At(2));

        var keep = new Button { Content = "Keep old", Theme = Res.Theme("Button.Secondary.Inline"), VerticalAlignment = VerticalAlignment.Center };
        keep.Click += (_, _) => AnswerReplace(offer, false);
        grid.Children.Add(keep.At(3));

        border.Child = grid;
        return border;
    }

    // The arrow's "landed" pop: a quick overshoot back to rest.
    private void Pop()
    {
        if (Motion.Off) return;
        MotionFx.Run(_jarScale, ScaleTransform.ScaleXProperty, 1.14, 1, 420, MotionFx.Back);
        MotionFx.Run(_jarScale, ScaleTransform.ScaleYProperty, 1.14, 1, 420, MotionFx.Back);
    }

    // ---- the drop zone's states -----------------------------------------

    // idle -> hover (a little brighter) -> over (jars carried in): the card surface, accent dashes, the arrow lifts and
    // the line reads "Release to add 2 mod jars". No grow, no glow.
    private void SetZone(bool over, bool hover, int count)
    {
        _dragOver = over;
        DropZone.Background = Res.Brush(over || hover ? "Brush.Card" : "Brush.Panel");
        DropDash.Stroke = Res.Brush(over ? "Brush.Accent" : hover ? "Brush.TextMuted" : "Brush.Stroke");
        JarIcon.Stroke = Res.Brush(over || hover ? "Brush.Text" : "Brush.TextDim");
        var lift = over ? -3 : 0;
        if (Math.Abs(_jarLift.Y - lift) > 0.01) MotionFx.Run(_jarLift, TranslateTransform.YProperty, _jarLift.Y, lift, 260, MotionFx.Back);
        IdleText.Opacity = over ? 0 : 1;
        OverText.Opacity = over ? 1 : 0;
        if (over) OverText.Text = count == 1 ? "Release to add 1 mod jar" : $"Release to add {count} mod jars";
    }

    private void DropZone_KeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key is not (Key.Enter or Key.Space)) return;
        Browse();
        e.Handled = true;
    }

    private void DropZone_DragEnter(object? sender, DragEventArgs e)
    {
        var jars = _version != null && !_adding ? Jars(e).Length : 0;
        e.DragEffects = jars > 0 ? DragDropEffects.Copy : DragDropEffects.None;
        if (jars > 0 && !_dragOver) SetZone(over: true, hover: false, count: jars);
        e.Handled = true;
    }

    private void DropZone_DragLeave(object? sender, DragEventArgs e)
    {
        // DragLeave also fires when the pointer crosses onto a child: only leave for real outside the zone.
        var p = e.GetPosition(DropZone);
        if (p.X > 0 && p.Y > 0 && p.X < DropZone.Bounds.Width && p.Y < DropZone.Bounds.Height) return;
        SetZone(over: false, hover: false, count: 0);
    }

    private void DropZone_Drop(object? sender, DragEventArgs e)
    {
        SetZone(over: false, hover: true, count: 0);
        var jars = Jars(e);
        if (_version == null || jars.Length == 0) return;
        AddFiles(jars);
        e.Handled = true;
    }

    private static string[] Jars(DragEventArgs e) =>
        (e.DataTransfer.TryGetFiles() ?? [])
            .Select(f => f.TryGetLocalPath())
            .OfType<string>()
            .Where(f => f.EndsWith(ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase))
            .ToArray();

    // One strip under the zone: a failure in the danger colour (dot + text), a note in the normal text with the accent dot.
    private void ShowStatus(string? message, bool error)
    {
        StatusText.Text = message ?? "Something went wrong.";
        StatusText.Foreground = Res.Brush(error ? "Brush.DangerText" : "Brush.Text");
        StatusDot.Background = Res.Brush(error ? "Brush.Danger" : "Brush.Accent");
        StatusBox.Background = Res.Brush(error ? "Brush.DangerSoft" : "Brush.Card");
        StatusBox.IsVisible = true;
    }

    private void HideStatus() => StatusBox.IsVisible = false;

    // ---- render shots only (UI/Shots/AreaBShots.cs); each refuses outside the scratch root -------------------------

    internal void TourShowDemo(IReadOnlyList<ModEntry> mods, int carrying)
    {
        if (!OriginPaths.IsScratchRoot) return;
        _demo = mods;
        HideStatus();
        // carrying == -1: the "just dropped" shot, the first of the player's mods lands.
        var first = carrying < 0 ? mods.FirstOrDefault(m => !m.IsManaged) : null;
        Refresh(first == null ? null : new HashSet<string> { first.FileName });
        SetZone(over: carrying > 0, hover: false, count: Math.Max(carrying, 0));
    }

    internal void TourShowStatus(string message, bool error)
    {
        if (!OriginPaths.IsScratchRoot) return;
        ShowStatus(message, error);
    }

    internal void TourConfirmRemove(string fileName)
    {
        if (!OriginPaths.IsScratchRoot) return;
        _pendingRemove = fileName;
        Refresh();
    }

    internal void TourNoVersion()
    {
        if (!OriginPaths.IsScratchRoot) return;
        ShowVersion(null);
    }

    internal void TourScroll(double y) => Scroller.Offset = new Vector(0, y);
    internal Button TourOpenFolderButton => OpenFolderButton;
    // The row's first plain button (Remove), as on Windows: an Avalonia ToggleButton is a Button too, and the switch
    // before it is not the control this shot pictures.
    internal Control? TourFirstRowButton => UserModsPanel.GetVisualDescendants().OfType<Button>()
        .FirstOrDefault(b => b is not Avalonia.Controls.Primitives.ToggleButton);
}

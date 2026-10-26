using OriginLauncher.App.Core.Mods.Icons;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.App.UI.Pages;

// Per-version external-mod manager. Reads/writes the version's
// /instances/{version}/mods/ folder - one per version, shared by every account
// (Core/GameFolders/SharedMods.cs) - the same folder VersionManager provisions
// and every account's game loads, so a mod toggled here is exactly what the
// game does (or doesn't) load, whichever account plays. The version
// is pushed in by MainWindow from HomePage's live selection (see ShowVersion),
// so switching versions on Home swaps this whole list to match.
//
// Workbench look: a dashed drop zone that behaves like drag and drop should (it
// lights the dashes and says "Release to add N mod jars" while a jar is over it,
// and the new rows land below), then your mods as rows, then Origin's built-in
// mods as cards. Status (an add's result or a refusal) is one strip under the zone.
public partial class ModsPage : UserControl
{
    private string? _version;
    private ModProfile? _profile;      // the selected mod profile when it belongs to _version; else null (normal mods)
    private string? _pendingRemove;   // filename awaiting the inline "Remove?" confirm
    private readonly List<ReplaceOffer> _pendingReplace = new(); // same-mod jars awaiting "Replace?" (Core/Compat/ModImporter)
    private bool _suppressToggle;      // guards programmatic IsChecked changes
    private bool _dragOver;
    private bool _adding;              // an import is copying (off the UI thread)
    private IReadOnlyList<ModEntry>? _demo; // render shots only: made-up rows instead of the disk
    // What the rows on screen were last built from (plain rows only, no confirm/replace/landing): a re-show with the
    // same version, profile and jars keeps them instead of rebuilding every card (a tab switch back to Mods).
    private string? _builtKey;
    private List<ModEntry>? _builtFrom;

    /// <summary>The version the page last showed (MainWindow: a switch to another version rebuilds before showing).</summary>
    internal string? ShownVersion => _version;

    public ModsPage()
    {
        InitializeComponent();
    }

    /// <summary>Point the page at a version and rebuild. Null = nothing
    /// selectable (version load failed / not chosen yet).</summary>
    public void ShowVersion(string? version)
    {
        _version = version;
        _pendingRemove = null;
        _pendingReplace.Clear();
        _demo = null;
        ResolveProfile();
        VersionChipText.Text = version ?? "No version";
        VersionChip.Visibility = version != null ? Visibility.Visible : Visibility.Collapsed;
        DropZone.IsEnabled = version != null;
        DropZone.Opacity = version != null ? 1.0 : 0.5;
        SyncYourModsSwitch();
        HideStatus();
        SetZone(over: false, hover: false, count: 0);
        Refresh();
    }

    // The YOUR MODS switch mirrors the saved setting; the launch reads the same
    // setting fresh (HomePage.Launch), so flipping it here applies next Play.
    private void SyncYourModsSwitch()
    {
        var on = SettingsStore.Load().PlayWithExternalMods;
        _suppressToggle = true;
        YourModsSwitch.IsChecked = on;
        _suppressToggle = false;
    }

    private void YourModsSwitch_Changed(object sender, RoutedEventArgs e)
    {
        if (_suppressToggle) return;
        var on = YourModsSwitch.IsChecked == true;
        SettingsStore.Update(s => s.PlayWithExternalMods = on);
    }

    // A selected mod profile (Profiles page) owns the mods list for ITS version:
    // adds, toggles and removals then go to that profile's own folder. On any
    // other version the page keeps managing the version's normal mods.
    private void ResolveProfile()
    {
        var settings = SettingsStore.Load();
        _profile = ProfileRules.Active(settings.ModProfiles, settings.SelectedProfileId, _version);
        var selected = settings.ModProfiles.FirstOrDefault(p => p.Id == settings.SelectedProfileId && ProfileRules.IsUsable(p));

        if (_profile != null)
            IntroText.Text = $"Mods for your profile \"{_profile.Name}\". Only this profile loads them; Origin's built-in mods stay up to date on their own.";
        else if (selected != null && _version != null)
            IntroText.Text = $"Your profile \"{selected.Name}\" is for {selected.Version}, so these are your normal mods for this version, shared by all your accounts.";
        else
            IntroText.Text = "Your own .jar mods for this version, shared by all your accounts. Origin's built-in mods stay up to date on their own.";
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
            UserEmptyState.Visibility = Visibility.Visible;
            UserCount.Text = "0";
            UserHint.Visibility = Visibility.Collapsed;
            OpenFolderButton.IsEnabled = false;
            BuiltInSection.Visibility = Visibility.Collapsed;
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
        // A profile folder holds only the player's own mods; Origin's built-in
        // ones come from the version's shared folder and aren't part of it.
        var managed = _profile != null ? new List<ModEntry>() : mods.Where(m => m.IsManaged).ToList();

        // A dropped jar that is the same mod as an installed one asks first, above the list.
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
        UserEmptyState.Visibility = user.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        UserHint.Visibility = user.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

        foreach (var mod in managed)
            BuiltInPanel.Children.Add(BuildManagedTile(mod));
        BuiltInCount.Text = managed.Count.ToString();
        BuiltInSection.Visibility = managed.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    // A just-added row drops in from above and settles (Back ease), staggered.
    private static void Land(FrameworkElement row, int delayMs)
    {
        if (Motion.Off) return;
        var shift = new TranslateTransform();
        row.RenderTransform = shift;
        row.Opacity = 0;
        row.BeginAnimation(OpacityProperty, Motion.FromTo(0, 1, 220, Motion.Out, delayMs));
        shift.BeginAnimation(TranslateTransform.YProperty, Motion.FromTo(-14, 0, 420, Motion.Back, delayMs));
    }

    // ---- row builders -----------------------------------------------------

    private Border RowShell() => new() { Style = (Style)FindResource("Row.Mod") };

    // The square well with the jar glyph that leads every row and tile.
    private Border IconWell(bool dim, ModEntry? mod = null)
    {
        var well = new Border { Style = (Style)FindResource("Mods.IconWell"), VerticalAlignment = VerticalAlignment.Center };
        var glyph = new Path
        {
            Data = (mod != null ? ModIcons.For(mod) : null) ?? (Geometry)FindResource("Icon.Jar"),
            Style = (Style)FindResource("Icon.Base"),
            Stroke = (Brush)FindResource(dim ? "Brush.TextMuted" : "Brush.TextDim"),
            Width = (double)FindResource("Icon.SizeLarge"),
            Height = (double)FindResource("Icon.SizeLarge"),
        };
        // The generated icon is the instant placeholder; the mod's real icon (if any) fades in over it.
        var host = new Grid();
        host.Children.Add(glyph);
        // Origin Client's own entry wears the official logo image, never a drawn glyph (design system 1.5).
        if (mod != null && ModIcons.IsOrigin(mod))
        {
            glyph.Visibility = Visibility.Collapsed;
            host.Children.Add(new Controls.BrandLogo
            {
                Width = (double)FindResource("Size.Logo.Bar"),
                Height = (double)FindResource("Size.Logo.Bar"),
                Opacity = dim ? 0.55 : 1,
            });
        }
        well.Child = host;
        // Accent only: a soft tint behind the glyph and the glyph in the same hue
        // (dimmer when the mod is switched off). Unknown jars stay neutral.
        if (mod != null && ModIcons.Hue(mod) is { } hue)
        {
            var wash = new SolidColorBrush(Color.FromArgb(0x26, hue.R, hue.G, hue.B));
            wash.Freeze();
            well.Background = wash;
            var ink = new SolidColorBrush(hue);
            ink.Freeze();
            glyph.Stroke = ink;
            glyph.Opacity = dim ? 0.55 : 1;
        }
        if (mod != null) LoadRealIcon(mod, well, glyph, dim);
        return well;
    }

    // ---- real mod icons -------------------------------------------------------

    // Decoded icons by jar (path + size + mtime), loaded once and shared: a Refresh() that rebuilds the rows
    // gets the finished icon at once (no second fade) and never reads the same jar twice.
    private static readonly IconMemo<BitmapSource> Icons = new();

    // Icons come only from inside the jar. A jar with no icon keeps the generated tinted one.
    // Origin Client's own jar ships a placeholder icon, so it keeps the Origin mark.
    private void LoadRealIcon(ModEntry mod, Border well, Path glyph, bool dim)
    {
        if (mod.FileName.StartsWith("originclient", StringComparison.OrdinalIgnoreCase)) return;
        Task<BitmapSource?> task;
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
                well.Dispatcher.InvokeAsync(() => ShowRealIcon(well, glyph, image, dim, fade: true));
        }, TaskScheduler.Default);
    }

    // Decode off the UI thread into a frozen bitmap. Anything that is not a sane image is "no icon".
    private static BitmapSource? DecodeIcon(byte[] bytes)
    {
        try
        {
            int w, h;
            using (var probe = new System.IO.MemoryStream(bytes))
            {
                var frame = BitmapDecoder.Create(probe, BitmapCreateOptions.DelayCreation, BitmapCacheOption.None).Frames[0];
                w = frame.PixelWidth;
                h = frame.PixelHeight;
            }
            if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null;

            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.StreamSource = new System.IO.MemoryStream(bytes);
            // A tile shows 40 px (80 physical at 200 %); never keep a 2000 px icon in memory.
            if (w >= h && w > 128) bitmap.DecodePixelWidth = 128;
            else if (h > w && h > 128) bitmap.DecodePixelHeight = 128;
            bitmap.EndInit();
            bitmap.Freeze();
            return bitmap;
        }
        catch
        {
            return null;
        }
    }

    // Fades the real icon in over the tinted placeholder (opacity only), then retires the placeholder.
    // Small icons are drawn at a whole-number multiple with nearest-neighbour so they stay crisp;
    // everything else uses high-quality scaling. Clipped to the tile's corner radius either way.
    private static void ShowRealIcon(Border well, Path glyph, BitmapSource source, bool dim, bool fade)
    {
        if (well.Child is not Grid host || host.Children.Count > 1) return;
        var longest = Math.Max(source.PixelWidth, source.PixelHeight);
        // Nearest-neighbour only at a whole-number multiple of the source (16 -> 32, 32 -> 32, 20 -> 40);
        // any other size is scaled to the 40 px tile with high-quality filtering.
        var wholeMultiple = longest <= 40;
        double side = wholeMultiple ? 40 / longest * longest : 40;

        var image = new Image
        {
            Source = source,
            Stretch = Stretch.Uniform,
            Width = side,
            Height = side,
            IsHitTestVisible = false,
            Clip = new RectangleGeometry(new Rect(0, 0, side, side), TileRadius, TileRadius),
        };
        RenderOptions.SetBitmapScalingMode(image, wholeMultiple ? BitmapScalingMode.NearestNeighbor : BitmapScalingMode.HighQuality);
        host.Children.Add(image);

        var rest = dim ? 0.6 : 1.0;
        void Settle()
        {
            glyph.Visibility = Visibility.Collapsed;
            well.SetResourceReference(Border.BackgroundProperty, "Brush.Bg");
        }
        if (!fade || Motion.Off)
        {
            image.Opacity = rest;
            Settle();
            return;
        }
        image.Opacity = 0;
        var fadeIn = Motion.FromTo(0, rest, 200, Motion.Out);
        fadeIn.Completed += (_, _) => Settle();
        image.BeginAnimation(OpacityProperty, fadeIn);
    }

    // The tile's corner radius (token Radius.Sm) as a number, for the icon clip.
    private static double TileRadius => ((CornerRadius)Application.Current.FindResource("Radius.Sm")).TopLeft;

    private static TextBlock NameText(string text, bool dim) => new()
    {
        Text = text,
        FontSize = (double)Application.Current.FindResource("FontSize.Body"),
        FontWeight = FontWeights.SemiBold,
        Foreground = (Brush)Application.Current.FindResource(dim ? "Brush.TextDim" : "Brush.Text"),
        TextTrimming = TextTrimming.CharacterEllipsis,
    };

    private static TextBlock FileText(string text) => new()
    {
        Text = text,
        FontSize = (double)Application.Current.FindResource("FontSize.Caption"),
        Margin = new Thickness(0, 4, 0, 0),
        Foreground = (Brush)Application.Current.FindResource("Brush.TextMuted"),
        TextTrimming = TextTrimming.CharacterEllipsis,
    };

    private FrameworkElement BuildUserRow(ModEntry mod, bool added = false)
    {
        var border = RowShell();
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        grid.Children.Add(IconWell(!mod.Enabled, mod));

        // The name column starts Space.3 after the icon well; the Replace and Remove prompts use the same gap, so the
        // question lines up with the mod names above and below it.
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(Space("Space.3"), 0, 12, 0) };
        if (added)
        {
            // Just dropped in: the name carries a small ADDED tag.
            var nameLine = new StackPanel { Orientation = Orientation.Horizontal };
            nameLine.Children.Add(NameText(mod.DisplayName, !mod.Enabled));
            nameLine.Children.Add(AddedTag());
            text.Children.Add(nameLine);
        }
        else text.Children.Add(NameText(mod.DisplayName, !mod.Enabled));
        text.Children.Add(FileText(mod.FileName));
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);

        var toggle = new ToggleButton
        {
            Style = (Style)FindResource("Toggle.Slider"),
            IsChecked = mod.Enabled,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 12, 0),
        };
        System.Windows.Automation.AutomationProperties.SetName(toggle, $"Enable {mod.DisplayName}");
        toggle.Checked += (s, _) => OnToggle(mod.FileName, true, (ToggleButton)s);
        toggle.Unchecked += (s, _) => OnToggle(mod.FileName, false, (ToggleButton)s);
        Grid.SetColumn(toggle, 2);
        grid.Children.Add(toggle);

        var remove = IconButton("Icon.Trash", $"Remove {mod.DisplayName}");
        remove.Click += (_, _) => { _pendingRemove = mod.FileName; Refresh(); };
        Grid.SetColumn(remove, 3);
        grid.Children.Add(remove);

        border.Child = grid;
        if (added) AddLandingLight(grid);
        return border;
    }

    // "ADDED": a capsule badge (Capsule.On, Padding.Capsule.Sm): a 6 px accent dot and the word, on the soft accent.
    private Border AddedTag()
    {
        var label = new TextBlock
        {
            Text = "ADDED",
            FontSize = (double)FindResource("FontSize.Label"),
            FontWeight = FontWeights.Bold,
            Foreground = (Brush)FindResource("Brush.Text"),
            VerticalAlignment = VerticalAlignment.Center,
        };
        var dot = new Border
        {
            Width = (double)FindResource("Size.Dot"),
            Height = (double)FindResource("Size.Dot"),
            Margin = new Thickness(0, 0, 6, 0),
            VerticalAlignment = VerticalAlignment.Center,
        };
        UI.Capsule.SetOn(dot, true);
        dot.SetResourceReference(Border.BackgroundProperty, "Brush.Accent");
        var line = new StackPanel { Orientation = Orientation.Horizontal };
        line.Children.Add(dot);
        line.Children.Add(label);
        var tag = new Border
        {
            Padding = (Thickness)FindResource("Padding.Capsule.Sm"),
            Margin = new Thickness(8, 0, 0, 0),
            VerticalAlignment = VerticalAlignment.Center,
            Child = line,
        };
        UI.Capsule.SetOn(tag, true);
        tag.SetResourceReference(Border.BackgroundProperty, "Brush.AccentSoft");
        return tag;
    }

    // The flash a landed row gives once: the soft accent over the row, fading out (opacity only; ignores the
    // mouse). No edge, no glow (spec 1).
    private static void AddLandingLight(Grid grid)
    {
        var tint = new Rectangle
        {
            IsHitTestVisible = false,
            Margin = new Thickness(-13, -11, -13, -11),
            RadiusX = TileRadius + 2,
            RadiusY = TileRadius + 2,
            Opacity = 1,
        };
        tint.SetResourceReference(Shape.FillProperty, "Brush.AccentSoft");
        Grid.SetColumnSpan(tint, 4);
        grid.Children.Insert(0, tint);
        if (Motion.Off)
        {
            tint.Opacity = 0;
            return;
        }
        tint.BeginAnimation(OpacityProperty, Motion.FromTo(1, 0, 1100, Motion.Out, 300));
    }

    // Inline destructive-action confirm (nothing lost by accident): the row
    // flips to "Remove <name>?" with explicit Remove / Cancel, rather than a
    // native dialog that would break the chromeless look.
    private FrameworkElement BuildConfirmRow(ModEntry mod)
    {
        var border = RowShell();
        border.BorderBrush = (Brush)FindResource("Brush.Danger");

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        grid.Children.Add(IconWell(true, mod));

        var prompt = NameText($"Remove {mod.DisplayName}?", false);
        prompt.VerticalAlignment = VerticalAlignment.Center;
        prompt.Margin = new Thickness(Space("Space.3"), 0, Space("Space.2"), 0);
        Grid.SetColumn(prompt, 1);
        grid.Children.Add(prompt);

        var confirm = new Button
        {
            Content = "Remove",
            Style = (Style)FindResource("Button.Danger.Inline"),
            Margin = new Thickness(0, 0, 8, 0),
            VerticalAlignment = VerticalAlignment.Center
        };
        confirm.Click += (_, _) =>
        {
            var result = ModManager.Remove(_version!, mod.FileName, _profile?.Id);
            _pendingRemove = null;
            if (!result.Ok) ShowStatus(result.Error, error: true);
            else HideStatus();
            Refresh();
        };
        Grid.SetColumn(confirm, 2);
        grid.Children.Add(confirm);

        var cancel = new Button
        {
            Content = "Cancel",
            Style = (Style)FindResource("Button.Secondary.Inline"),
            VerticalAlignment = VerticalAlignment.Center
        };
        cancel.Click += (_, _) => { _pendingRemove = null; Refresh(); };
        Grid.SetColumn(cancel, 3);
        grid.Children.Add(cancel);

        border.Child = grid;
        return border;
    }

    // A built-in mod: a read-only card (Origin installs and updates it). The tile leads, the name and what it does
    // sit beside it, so a card is as tall as a row and the grid stays dense.
    private FrameworkElement BuildManagedTile(ModEntry mod)
    {
        var tile = new Border
        {
            Style = (Style)FindResource("Surface.Card"),
            Margin = new Thickness(0, 0, 4, 4),
            Padding = new Thickness(12, 10, 12, 10),
        };
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.Children.Add(IconWell(false, mod));
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(Space("Space.3"), 0, 0, 0) };
        text.Children.Add(NameText(mod.DisplayName, false));
        text.Children.Add(FileText(ModIcons.Blurb(mod)));
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        tile.Child = grid;
        System.Windows.Automation.AutomationProperties.SetName(tile, $"{mod.DisplayName}, built in");
        return tile;
    }

    // An icon sitting directly in a button, no box; no hover label (spec 9), the automation name carries the meaning.
    private Button IconButton(string iconKey, string automationName)
    {
        var button = new Button
        {
            Style = (Style)FindResource("Social.Icon"),
        };
        var path = new Path
        {
            Data = (Geometry)FindResource(iconKey),
            Style = (Style)FindResource("Icon.Small"),
        };
        // The glyph follows the button's own foreground (dim at rest, bright on hover).
        path.SetBinding(Shape.StrokeProperty, new System.Windows.Data.Binding(nameof(Foreground)) { Source = button });
        button.Content = path;
        System.Windows.Automation.AutomationProperties.SetName(button, automationName);
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
        }
        else
        {
            // Revert the switch to disk truth without re-firing this handler.
            _suppressToggle = true;
            toggle.IsChecked = !enable;
            _suppressToggle = false;
            ShowStatus(result.Error, error: true);
        }
    }

    private void OpenFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_version == null) return;
        var result = ModManager.OpenFolder(_version, _profile?.Id);
        if (!result.Ok) ShowStatus(result.Error, error: true);
    }

    // Browse: the same add path as a drop, through the system file picker.
    private void Browse()
    {
        if (_version == null || _adding) return;
        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Title = "Add mod jars",
            Filter = "Mod jars (*.jar)|*.jar",
            Multiselect = true,
            CheckFileExists = true,
        };
        if (dialog.ShowDialog(Window.GetWindow(this)) == true)
            AddFiles(dialog.FileNames);
    }

    // Copy the jars in off the UI thread (a big pack must not freeze the window),
    // then land the new rows. The newest add wins; a second one waits its turn.
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

    // Shows what an add did: the new rows land, a refused jar says why in plain words, and a jar that is the same
    // mod as an installed one waits for the player's yes (never a silent overwrite).
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
        if (report.Added > 0) Pop(JarScale);
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

    // A spacing token (Theme/Spacing.xaml, the 8 px grid) as a number for code-built layout.
    private double Space(string key) => (double)FindResource(key);

    // "Sodium 0.5.0 is already installed. Replace it with 0.6.13?" with explicit Replace / Keep old; the same inline
    // pattern as the Remove confirm, so nothing is overwritten by accident.
    private FrameworkElement BuildReplaceRow(ReplaceOffer offer)
    {
        var border = RowShell();
        border.BorderBrush = (Brush)FindResource("Brush.Accent");

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        grid.Children.Add(IconWell(true, new ModEntry(offer.ExistingFileName, offer.ModName, true, false)));

        // Line 1 is the mod, line 2 the versions as one trimmed caption ("1.38.0 -> 1.39.2"), never wrapped mid-token;
        // a long name trims.
        var prompt = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        prompt.Margin = new Thickness(Space("Space.3"), 0, Space("Space.2"), 0);
        prompt.Children.Add(NameText($"{offer.ModName} is already installed", false));
        var from = offer.ExistingVersion ?? "this version";
        var versions = FileText(offer.NewVersion == null ? $"Replace {from} with this file?" : $"Replace {from} -> {offer.NewVersion}");
        versions.Margin = new Thickness(0, Space("Space.1"), 0, 0);
        prompt.Children.Add(versions);
        Grid.SetColumn(prompt, 1);
        grid.Children.Add(prompt);

        var replace = new Button
        {
            Content = "Replace",
            Style = (Style)FindResource("Button.Primary.Inline"),
            Margin = new Thickness(0, 0, Space("Space.2"), 0),
            VerticalAlignment = VerticalAlignment.Center
        };
        replace.Click += (_, _) => AnswerReplace(offer, true);
        Grid.SetColumn(replace, 2);
        grid.Children.Add(replace);

        var keep = new Button
        {
            Content = "Keep old",
            Style = (Style)FindResource("Button.Secondary.Inline"),
            VerticalAlignment = VerticalAlignment.Center
        };
        keep.Click += (_, _) => AnswerReplace(offer, false);
        Grid.SetColumn(keep, 3);
        grid.Children.Add(keep);

        border.Child = grid;
        return border;
    }

    // The jar well's "landed" pop: a quick overshoot back to rest.
    private static void Pop(ScaleTransform scale)
    {
        if (Motion.Off) return;
        scale.BeginAnimation(ScaleTransform.ScaleXProperty, Motion.FromTo(1.14, 1, 420, Motion.Back));
        scale.BeginAnimation(ScaleTransform.ScaleYProperty, Motion.FromTo(1.14, 1, 420, Motion.Back));
    }

    // ---- the drop zone's states -----------------------------------------

    // idle -> hover (a little brighter) -> over (a jar is being carried in): the zone lights in the card
    // surface, the dashes take the accent, the arrow lifts and the line reads "Release to add 2 mod jars".
    // No grow, no glow (spec 1).
    private void SetZone(bool over, bool hover, int count)
    {
        _dragOver = over;
        DropZone.Background = (Brush)FindResource(over || hover ? "Brush.Card" : "Brush.Panel");
        if (over) DropDash.SetResourceReference(Shape.StrokeProperty, "Brush.Accent");
        else DropDash.Stroke = (Brush)FindResource(hover ? "Brush.TextMuted" : "Brush.Stroke");
        JarIcon.Stroke = (Brush)FindResource(over || hover ? "Brush.Text" : "Brush.TextDim");

        Animate(JarLift, TranslateTransform.YProperty, over ? -3 : 0, 260, Motion.Back);
        Animate(IdleText, OpacityProperty, over ? 0 : 1, 120);
        Animate(OverText, OpacityProperty, over ? 1 : 0, 120);
        if (over) OverText.Text = count == 1 ? "Release to add 1 mod jar" : $"Release to add {count} mod jars";
    }

    private static void Animate(IAnimatable target, DependencyProperty property, double to, int ms, IEasingFunction? ease = null)
    {
        if (Motion.Off)
        {
            target.BeginAnimation(property, null);
            ((DependencyObject)target).SetValue(property, to);
            return;
        }
        target.BeginAnimation(property, Motion.To(to, ms, ease ?? Motion.Out), HandoffBehavior.SnapshotAndReplace);
    }

    private void DropZone_MouseEnter(object sender, MouseEventArgs e)
    {
        if (!_dragOver && _version != null) SetZone(over: false, hover: true, count: 0);
    }

    private void DropZone_MouseLeave(object sender, MouseEventArgs e)
    {
        if (!_dragOver) SetZone(over: false, hover: false, count: 0);
    }

    private void DropZone_Click(object sender, MouseButtonEventArgs e) => Browse();

    private void DropZone_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key is not (Key.Enter or Key.Space)) return;
        Browse();
        e.Handled = true;
    }

    private void DropZone_DragEnter(object sender, DragEventArgs e)
    {
        var jars = _version != null && !_adding ? Jars(e) : 0;
        e.Effects = jars > 0 ? DragDropEffects.Copy : DragDropEffects.None;
        if (jars > 0 && !_dragOver) SetZone(over: true, hover: false, count: jars);
        e.Handled = true;
    }

    private void DropZone_DragLeave(object sender, DragEventArgs e)
    {
        // DragLeave also fires when the pointer crosses onto a child: only leave
        // for real when it is outside the zone.
        var p = e.GetPosition(DropZone);
        if (p.X > 0 && p.Y > 0 && p.X < DropZone.ActualWidth && p.Y < DropZone.ActualHeight) return;
        SetZone(over: false, hover: false, count: 0);
    }

    private void DropZone_Drop(object sender, DragEventArgs e)
    {
        SetZone(over: false, hover: true, count: 0);
        if (_version == null || Jars(e) == 0) return;
        AddFiles((string[])e.Data.GetData(DataFormats.FileDrop));
        e.Handled = true;
    }

    private static int Jars(DragEventArgs e)
    {
        if (!e.Data.GetDataPresent(DataFormats.FileDrop)) return 0;
        return e.Data.GetData(DataFormats.FileDrop) is string[] files
            ? files.Count(f => f.EndsWith(ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase))
            : 0;
    }

    // One strip under the zone. A failure reads in the danger colour (dot + text); a note (adding, added,
    // some skipped) reads in the normal text with the accent dot.
    private void ShowStatus(string? message, bool error)
    {
        StatusText.Text = message ?? "Something went wrong.";
        StatusText.SetResourceReference(TextBlock.ForegroundProperty, error ? "Brush.DangerText" : "Brush.Text");
        StatusDot.SetResourceReference(Border.BackgroundProperty, error ? "Brush.Danger" : "Brush.Accent");
        StatusBox.SetResourceReference(Border.BackgroundProperty, error ? "Brush.DangerSoft" : "Brush.Card");
        StatusBox.Visibility = Visibility.Visible;
    }

    private void HideStatus()
    {
        StatusBox.Visibility = Visibility.Collapsed;
    }

    // Render mode only (UI/RenderShots.cs): made-up rows, optionally with a jar
    // "carried" over the zone. Never touches the disk or the settings.
    internal void TourShowDemo(IReadOnlyList<ModEntry> mods, int carrying)
    {
        if (!OriginPaths.IsScratchRoot) return;
        _demo = mods;
        HideStatus();
        // carrying == -1: the "just dropped" shot, the first of the player's mods lands.
        var first = carrying < 0 ? mods.FirstOrDefault(m => !m.IsManaged) : null;
        Refresh(first == null ? null : new HashSet<string> { first.FileName });
        SetZone(over: carrying > 0, hover: false, count: Math.Max(carrying, 0));
        if (first != null) Pop(JarScale);
    }

    // Render mode only: the status strip in either tone (UI/ModsProfilesShots.cs).
    internal void TourShowStatus(string message, bool error)
    {
        if (!OriginPaths.IsScratchRoot) return;
        ShowStatus(message, error);
    }

    // Render mode only: the "Remove <name>?" confirm on the named demo row.
    internal void TourConfirmRemove(string fileName)
    {
        if (!OriginPaths.IsScratchRoot) return;
        _pendingRemove = fileName;
        Refresh();
    }

    // Render mode only: the page with no version chosen.
    internal void TourNoVersion()
    {
        if (!OriginPaths.IsScratchRoot) return;
        ShowVersion(null);
    }
}

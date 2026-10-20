using System.Collections.Concurrent;
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
// Poster look: a dashed drop zone that behaves like drag and drop should (it
// grows and lights while a jar is over it, says "Release to add", and the new
// rows land below), then your mods as rows, then Origin's built-in mods as tiles.
public partial class ModsPage : UserControl
{
    private string? _version;
    private ModProfile? _profile;      // the selected mod profile when it belongs to _version; else null (normal mods)
    private string? _pendingRemove;   // filename awaiting the inline "Remove?" confirm
    private bool _suppressToggle;      // guards programmatic IsChecked changes
    private bool _dragOver;
    private bool _adding;              // an import is copying (off the UI thread)
    private IReadOnlyList<ModEntry>? _demo; // render shots only: made-up rows instead of the disk

    public ModsPage()
    {
        InitializeComponent();
        SizeChanged += (_, _) => ApplyDensity();
    }

    /// <summary>Point the page at a version and rebuild. Null = nothing
    /// selectable (version load failed / not chosen yet).</summary>
    public void ShowVersion(string? version)
    {
        _version = version;
        _pendingRemove = null;
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

    // Short windows pull the column up under the bar (the drop zone stays on screen).
    private void ApplyDensity()
    {
        var compact = ActualHeight > 0 && ActualHeight < 680;
        Column.Margin = new Thickness(32, compact ? 80 : 100, 32, 40);
        DropZone.Height = compact ? 140 : 168;
    }

    // The YOUR MODS switch mirrors the saved setting; the launch reads the same
    // setting fresh (HomePage.Launch), so flipping it here applies next Play.
    private void SyncYourModsSwitch()
    {
        var on = SettingsStore.Load().PlayWithExternalMods;
        _suppressToggle = true;
        YourModsSwitch.IsChecked = on;
        _suppressToggle = false;
        YourModsState.Text = on ? "On" : "Off";
    }

    private void YourModsSwitch_Changed(object sender, RoutedEventArgs e)
    {
        if (_suppressToggle) return;
        var on = YourModsSwitch.IsChecked == true;
        YourModsState.Text = on ? "On" : "Off";
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
        UserModsPanel.Children.Clear();
        BuiltInPanel.Children.Clear();

        if (_version == null)
        {
            UserEmptyText.Text = "Choose a version first";
            UserEmptyState.Visibility = Visibility.Visible;
            UserCount.Text = "0";
            BuiltInSection.Visibility = Visibility.Collapsed;
            return;
        }

        var mods = _demo ?? ModManager.Enumerate(_version, _profile?.Id);
        var user = mods.Where(m => !m.IsManaged).ToList();
        // A profile folder holds only the player's own mods; Origin's built-in
        // ones come from the version's shared folder and aren't part of it.
        var managed = _profile != null ? new List<ModEntry>() : mods.Where(m => m.IsManaged).ToList();

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
            Width = 18,
            Height = 18,
        };
        // The generated icon is the instant placeholder; the mod's real icon (if any) fades in over it.
        var host = new Grid();
        host.Children.Add(glyph);
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

    // Decoded icons by jar path (or "demo:<file>"), so a re-built row shows its icon at once with no second fade.
    private static readonly ConcurrentDictionary<string, BitmapSource> LoadedIcons = new();

    // Origin Client's own jar ships a placeholder icon, so it keeps the Origin mark.
    private void LoadRealIcon(ModEntry mod, Border well, Path glyph, bool dim)
    {
        if (mod.FileName.StartsWith("originclient", StringComparison.OrdinalIgnoreCase)) return;
        var demo = _demo != null;
        string? jar = null;
        if (!demo)
        {
            if (_version == null) return;
            var name = mod.Enabled || mod.IsManaged ? mod.FileName : mod.FileName + ".disabled";
            jar = System.IO.Path.Combine(ModManager.ModsFolder(_version, _profile?.Id), name);
        }
        var key = demo ? "demo:" + mod.FileName : jar!;

        if (LoadedIcons.TryGetValue(key, out var cached))
        {
            ShowRealIcon(well, glyph, cached, dim, fade: false);
            return;
        }

        var slug = mod.IsManaged ? ModIconSource.BuiltInSlug(mod.FileName) : null;
        var fileName = mod.FileName;
        _ = Task.Run(async () =>
        {
            byte[]? bytes;
            if (demo)
                bytes = ModIconSource.DemoIcons != null && ModIconSource.DemoIcons.TryGetValue(fileName, out var d) ? d : null;
            else
                bytes = await ModIconSource.ResolveAsync(jar!, slug).ConfigureAwait(false);
            if (bytes == null) return;

            var image = DecodeIcon(bytes);
            if (image == null)
            {
                if (!demo) ModIconSource.MarkUndecodable(jar!);
                return;
            }
            LoadedIcons[key] = image;
            await well.Dispatcher.InvokeAsync(() => ShowRealIcon(well, glyph, image, dim, fade: true));
        });
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
    // Small pixel-art icons are drawn at a whole-number multiple with nearest-neighbour so they stay crisp;
    // big icons use high-quality scaling. Clipped to the tile's corner radius either way.
    private static void ShowRealIcon(Border well, Path glyph, BitmapSource source, bool dim, bool fade)
    {
        if (well.Child is not Grid host || host.Children.Count > 1) return;
        var longest = Math.Max(source.PixelWidth, source.PixelHeight);
        var pixelArt = longest <= 64;
        double side = pixelArt && longest <= 40 ? 40 / longest * longest : 40;

        var image = new Image
        {
            Source = source,
            Stretch = Stretch.Uniform,
            Width = side,
            Height = side,
            IsHitTestVisible = false,
            Clip = new RectangleGeometry(new Rect(0, 0, side, side), 4, 4),
        };
        RenderOptions.SetBitmapScalingMode(image, pixelArt ? BitmapScalingMode.NearestNeighbor : BitmapScalingMode.HighQuality);
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

    private static TextBlock NameText(string text, bool dim, double size = 14) => new()
    {
        Text = text,
        FontSize = size,
        FontWeight = FontWeights.SemiBold,
        Foreground = (Brush)Application.Current.FindResource(dim ? "Brush.TextDim" : "Brush.Text"),
        TextTrimming = TextTrimming.CharacterEllipsis,
    };

    private static TextBlock FileText(string text) => new()
    {
        Text = text,
        FontSize = 12,
        Margin = new Thickness(0, 2, 0, 0),
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

        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(14, 0, 12, 0) };
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
            Style = (Style)FindResource("Toggle.Switch"),
            IsChecked = mod.Enabled,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 12, 0),
            ToolTip = mod.Enabled ? "On: loads in-game" : "Off: skipped at launch"
        };
        System.Windows.Automation.AutomationProperties.SetName(toggle, $"Enable {mod.DisplayName}");
        toggle.Checked += (s, _) => OnToggle(mod.FileName, true, (ToggleButton)s);
        toggle.Unchecked += (s, _) => OnToggle(mod.FileName, false, (ToggleButton)s);
        Grid.SetColumn(toggle, 2);
        grid.Children.Add(toggle);

        var remove = IconButton("Icon.Trash", "Remove mod", $"Remove {mod.DisplayName}");
        remove.Click += (_, _) => { _pendingRemove = mod.FileName; Refresh(); };
        Grid.SetColumn(remove, 3);
        grid.Children.Add(remove);

        border.Child = grid;
        if (added) AddLandingLight(grid);
        return border;
    }

    // "ADDED": a Card chip with an accent square, like the mockup's new-row tag.
    private Border AddedTag()
    {
        var label = new TextBlock
        {
            Text = "ADDED",
            FontSize = 10,
            FontWeight = FontWeights.Bold,
            Foreground = (Brush)FindResource("Brush.Text"),
            VerticalAlignment = VerticalAlignment.Center,
        };
        var square = new Border { Width = 6, Height = 6, Margin = new Thickness(0, 0, 6, 0), VerticalAlignment = VerticalAlignment.Center };
        square.SetResourceReference(Border.BackgroundProperty, "Brush.Acc");
        var line = new StackPanel { Orientation = Orientation.Horizontal };
        line.Children.Add(square);
        line.Children.Add(label);
        var tag = new Border
        {
            CornerRadius = (CornerRadius)FindResource("Radius.Knob"),
            Padding = new Thickness(6, 2, 6, 2),
            Margin = new Thickness(8, 0, 0, 0),
            VerticalAlignment = VerticalAlignment.Center,
            Child = line,
        };
        // The version accent, softly, behind the white label (contrast stays on the text).
        tag.SetResourceReference(Border.BackgroundProperty, "Brush.AccSoft");
        return tag;
    }

    // The accent flash a landed row gives once: a tint over the row and a 3 px
    // edge on its left that fade out (opacity only; both ignore the mouse).
    private static void AddLandingLight(Grid grid)
    {
        var tint = new Rectangle { IsHitTestVisible = false, Margin = new Thickness(-12, -10, -10, -10), Opacity = 0.28 };
        var edge = new Rectangle { IsHitTestVisible = false, Width = 3, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(-12, -10, 0, -10) };
        tint.SetResourceReference(Shape.FillProperty, "Brush.Acc");
        edge.SetResourceReference(Shape.FillProperty, "Brush.Acc");
        Grid.SetColumnSpan(tint, 4);
        Grid.SetColumnSpan(edge, 4);
        grid.Children.Insert(0, tint);
        grid.Children.Add(edge);
        if (Motion.Off)
        {
            tint.Opacity = 0;
            return;
        }
        tint.BeginAnimation(OpacityProperty, Motion.FromTo(0.28, 0, 900, Motion.Out));
        edge.BeginAnimation(OpacityProperty, Motion.FromTo(1, 0, 900, Motion.Out, 700));
    }

    // Inline destructive-action confirm (nothing lost by accident): the row
    // flips to "Remove <name>?" with explicit Remove / Cancel, rather than a
    // native dialog that would break the chromeless look.
    private FrameworkElement BuildConfirmRow(ModEntry mod)
    {
        var border = RowShell();
        border.BorderBrush = (Brush)FindResource("Brush.Danger");
        border.BorderThickness = new Thickness(1);
        border.Padding = new Thickness(11, 9, 9, 9);

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        grid.Children.Add(IconWell(true, mod));

        var prompt = NameText($"Remove {mod.DisplayName}?", false);
        prompt.VerticalAlignment = VerticalAlignment.Center;
        prompt.Margin = new Thickness(14, 0, 12, 0);
        Grid.SetColumn(prompt, 1);
        grid.Children.Add(prompt);

        var confirm = new Button
        {
            Content = "Remove",
            Style = (Style)FindResource("Button.Danger"),
            Padding = new Thickness(14, 0, 14, 0),
            Height = 34,
            Margin = new Thickness(0, 0, 8, 0),
            VerticalAlignment = VerticalAlignment.Center
        };
        confirm.Click += (_, _) =>
        {
            var result = ModManager.Remove(_version!, mod.FileName, _profile?.Id);
            _pendingRemove = null;
            if (!result.Ok) ShowStatus(result.Error);
            else HideStatus();
            Refresh();
        };
        Grid.SetColumn(confirm, 2);
        grid.Children.Add(confirm);

        var cancel = new Button
        {
            Content = "Cancel",
            Style = (Style)FindResource("Button.Secondary"),
            Padding = new Thickness(14, 0, 14, 0),
            Height = 34,
            VerticalAlignment = VerticalAlignment.Center
        };
        cancel.Click += (_, _) => { _pendingRemove = null; Refresh(); };
        Grid.SetColumn(cancel, 3);
        grid.Children.Add(cancel);

        border.Child = grid;
        return border;
    }

    // A built-in mod: a read-only tile (Origin installs and updates it).
    private FrameworkElement BuildManagedTile(ModEntry mod)
    {
        var tile = new Border
        {
            Background = (Brush)FindResource("Brush.Panel"),
            CornerRadius = (CornerRadius)FindResource("Radius.Card"),
            Padding = new Thickness(16, 14, 16, 14),
            Margin = new Thickness(0, 0, 4, 4),
        };
        var stack = new StackPanel();
        var well = IconWell(false, mod);
        well.HorizontalAlignment = HorizontalAlignment.Left;
        well.Margin = new Thickness(0, 0, 0, 12);
        stack.Children.Add(well);
        stack.Children.Add(NameText(mod.DisplayName, false));
        stack.Children.Add(FileText(ModIcons.Blurb(mod)));
        tile.Child = stack;
        System.Windows.Automation.AutomationProperties.SetName(tile, $"{mod.DisplayName}, built in");
        return tile;
    }

    private Button IconButton(string iconKey, string tooltip, string automationName)
    {
        var button = new Button
        {
            Style = (Style)FindResource("Button.Chrome"),
            Width = 34,
            Height = 34,
            Padding = new Thickness(0),
            ToolTip = tooltip,
            VerticalAlignment = VerticalAlignment.Center
        };
        var path = new Path
        {
            Data = (Geometry)FindResource(iconKey),
            Style = (Style)FindResource("Icon.Base"),
            Width = 18,
            Height = 18
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
            toggle.ToolTip = enable ? "On: loads in-game" : "Off: skipped at launch";
            HideStatus();
        }
        else
        {
            // Revert the switch to disk truth without re-firing this handler.
            _suppressToggle = true;
            toggle.IsChecked = !enable;
            _suppressToggle = false;
            ShowStatus(result.Error);
        }
    }

    private void OpenFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_version == null) return;
        var result = ModManager.OpenFolder(_version, _profile?.Id);
        if (!result.Ok) ShowStatus(result.Error);
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
        ShowStatus(files.Length == 1 ? "Adding 1 mod..." : $"Adding {files.Length} mods...");

        var result = await Task.Run(() => ModManager.Import(version, files, profileId));
        _adding = false;
        if (_version != version) return; // the page moved on to another version meanwhile

        if (!result.Ok)
        {
            ShowStatus(result.Error);
            Refresh();
            return;
        }
        var after = ModManager.Enumerate(version, profileId);
        var landed = after.Where(m => !before.Contains(m.FileName)).Select(m => m.FileName).ToHashSet(StringComparer.OrdinalIgnoreCase);
        // Re-added jars (already listed) still count as added; only new names land.
        var jarCount = files.Count(f => f.EndsWith(ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase));
        ShowStatus(jarCount == 1 ? "Added 1 mod." : $"Added {jarCount} mods.");
        Refresh(landed);
        Pop(JarScale);
    }

    // The jar well's "landed" pop: a quick overshoot back to rest.
    private static void Pop(ScaleTransform scale)
    {
        if (Motion.Off) return;
        scale.BeginAnimation(ScaleTransform.ScaleXProperty, Motion.FromTo(1.14, 1, 420, Motion.Back));
        scale.BeginAnimation(ScaleTransform.ScaleYProperty, Motion.FromTo(1.14, 1, 420, Motion.Back));
    }

    // ---- the drop zone's states -----------------------------------------

    // idle -> hover (a little brighter) -> over (a jar is being carried in):
    // grows 1.5 %, lights in the family accent, the dashes take the accent,
    // the well lifts and the line reads "Release to add".
    private void SetZone(bool over, bool hover, int count)
    {
        _dragOver = over;
        var ms = 180;
        DropZone.Background = (Brush)FindResource(over ? "Brush.Card" : hover ? "Brush.Card" : "Brush.Panel");
        if (over) DropDash.SetResourceReference(Shape.StrokeProperty, "Brush.Acc");
        else DropDash.Stroke = (Brush)FindResource(hover ? "Brush.TextMuted" : "Brush.Track");
        JarIcon.Stroke = (Brush)FindResource(over || hover ? "Brush.Text" : "Brush.TextDim");

        Animate(DropScale, ScaleTransform.ScaleXProperty, over ? 1.015 : 1, ms);
        Animate(DropScale, ScaleTransform.ScaleYProperty, over ? 1.015 : 1, ms);
        Animate(JarLift, TranslateTransform.YProperty, over ? -4 : 0, 260, Motion.Back);
        Animate(JarScale, ScaleTransform.ScaleXProperty, over ? 1.06 : 1, 260, Motion.Back);
        Animate(JarScale, ScaleTransform.ScaleYProperty, over ? 1.06 : 1, 260, Motion.Back);
        Animate(DropLit, OpacityProperty, over ? 1 : 0, ms);
        Animate(IdleText, OpacityProperty, over ? 0 : 1, 120);
        Animate(OverText, OpacityProperty, over ? 1 : 0, 120);
        Animate(Ghost, OpacityProperty, over ? 1 : 0, ms);
        Animate(GhostTilt, RotateTransform.AngleProperty, over ? -4 : 0, 260, Motion.Back);
        if (over) GhostText.Text = count == 1 ? "1 mod jar" : $"{count} mod jars";
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

    private void ShowStatus(string? message)
    {
        StatusText.Text = message ?? "Something went wrong.";
        StatusText.Visibility = Visibility.Visible;
    }

    private void HideStatus()
    {
        StatusText.Visibility = Visibility.Collapsed;
    }

    // Render mode only (UI/RenderShots.cs): made-up rows, optionally with a jar
    // "carried" over the zone. Never touches the disk or the settings.
    internal void TourShowDemo(IReadOnlyList<ModEntry> mods, int carrying)
    {
        if (!OriginPaths.IsScratchRoot) return;
        _demo = mods;
        // carrying == -1: the "just dropped" shot, the first of the player's mods lands.
        var first = carrying < 0 ? mods.FirstOrDefault(m => !m.IsManaged) : null;
        Refresh(first == null ? null : new HashSet<string> { first.FileName });
        SetZone(over: carrying > 0, hover: false, count: Math.Max(carrying, 0));
        if (first != null) Pop(JarScale);
    }
}

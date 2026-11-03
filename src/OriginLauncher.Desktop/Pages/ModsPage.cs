using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Controls.Shapes;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Media.Transformation;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Mods.Icons;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Pages;

// The per-version mod manager (port of the WPF UI/Pages/ModsPage). It reads and writes the version's mods folder,
// one per version and shared by every account (Core/GameFolders/SharedMods.cs), or the selected profile's own folder
// when that profile belongs to this version. Every file change goes through Core's ModManager, which only adds,
// deletes or rename-swaps (never rewrites a jar another account's running game may hold) and lets the player's own
// copy of a mod win over Origin's.
//
// Workbench look: a dashed drop zone that behaves like drag and drop should (it lights the dashes and says "Release
// to add N mod jars" while jars are over it; the new rows land below), the player's mods as rows, then Origin's
// built-in mods as cards. Status (an add's result or a refusal) is one strip under the zone. Removing asks inline on
// the row (Remove / Cancel), and a jar that is the same mod as an installed one asks inline above the list
// (Replace / Keep old): nothing is lost or overwritten by accident.
public sealed class ModsPage : Panel, IPage
{
    private readonly MainWindow _window;

    private string? _version;
    private ModProfile? _profile;        // the selected profile when it belongs to _version; else null (normal mods)
    private string? _pendingRemove;      // file awaiting the inline "Remove?" confirm
    private readonly List<ReplaceOffer> _pendingReplace = []; // same-mod jars awaiting "Replace?" (Core/Compat/ModImporter)
    private bool _dragOver;
    private bool _adding;                // an import is copying (off the UI thread)
    private IReadOnlyList<ModEntry>? _demo; // render runs only: made-up rows instead of the disk
    // What the rows on screen were last built from (plain rows only): a re-show with the same version, profile and
    // jars keeps them instead of rebuilding every row (a tab switch back to Mods).
    private string? _builtKey;
    private List<ModEntry>? _builtFrom;

    private readonly Border _versionChip;
    private readonly TextBlock _versionChipText;
    private readonly TextBlock _intro = Ui.Body("");
    private readonly Toggle _myMods = new("Load your mods");
    private readonly Pressable _zone;
    private readonly Rectangle _zoneDash = Dash.Outline(Palette.LineStrong);
    private readonly IconView _zoneIcon = Ui.Icon(Icons.Drop, Sizes.IconLg, Palette.TextDim);
    private readonly StackPanel _zoneIdle;
    private readonly TextBlock _zoneOver = Ui.BodyStrong("Release to add", Palette.Text);
    private readonly StatusStrip _status = new();
    private readonly TextBlock _userCount = Ui.Text("0", TypeScale.Label, FontWeight.Bold, Palette.Text);
    private readonly TextBlock _userHint = Ui.Caption("Switch one off to keep it without loading it");
    private readonly Pressable _openFolder;
    private readonly StackPanel _userRows = new();
    private readonly Border _empty;
    private readonly TextBlock _emptyText = Ui.Body("No mods yet. Drop a jar above to add one.");
    private readonly StackPanel _builtIn;
    private readonly TextBlock _builtInCount = Ui.Text("0", TypeScale.Label, FontWeight.Bold, Palette.Text);
    private readonly UniformGrid _builtInGrid = new() { Columns = 3, Margin = new Thickness(0, 0, -Space.S1, 0) };

    /// <summary>The page's scroller (render runs scroll it to the bottom for the scrolled shot).</summary>
    public ScrollViewer Scroller { get; }

    public ModsPage(MainWindow window)
    {
        _window = window;

        // ---- Header: "Mods" + the version chip over the intro; MY MODS + its switch on the right.
        _versionChip = ModsParts.Chip("", out _versionChipText);
        _versionChip.Margin = new Thickness(Space.S3, 0, 0, Space.S1);
        _versionChip.VerticalAlignment = VerticalAlignment.Bottom;
        var title = Ui.Row(0, Ui.Heading("Mods"), _versionChip);
        title.VerticalAlignment = VerticalAlignment.Top;
        _intro.MaxWidth = 500;
        _intro.HorizontalAlignment = HorizontalAlignment.Left;
        _intro.Margin = new Thickness(0, Space.S2, 0, 0);
        var myModsLabel = Ui.Eyebrow("My mods", Palette.TextDim);
        myModsLabel.Cursor = new Cursor(StandardCursorType.Hand);
        myModsLabel.Tapped += (_, _) => _myMods.RaiseClick(); // the word is part of the switch, like the WPF toggle
        _myMods.Toggled += on => LauncherState.Write(s => s.PlayWithExternalMods = on);
        var myMods = Ui.Row(Space.S3, myModsLabel, _myMods);
        myMods.VerticalAlignment = VerticalAlignment.Top;
        myMods.Margin = new Thickness(Space.S6, 6, 0, 0);
        var header = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        header.Children.Add(Ui.Column(0, title, _intro).At(0));
        header.Children.Add(myMods.At(1));

        // ---- Drop zone: content-tall (one icon, two lines, 20 above and below), dashed edge, click / Enter browses.
        _zoneIcon.Margin = new Thickness(0, 0, Space.S3, 0);
        var browse = Ui.Caption("or ");
        browse.Inlines = [new Avalonia.Controls.Documents.Run("or "),
            new Avalonia.Controls.Documents.Run("browse") { FontWeight = FontWeight.SemiBold, Foreground = Palette.Text }];
        browse.Margin = new Thickness(0, Space.S1, 0, 0);
        _zoneIdle = Ui.Column(0, Ui.BodyStrong("Drag and drop mod jars here", Palette.Text), browse);
        _zoneOver.IsVisible = false;
        var words = new Panel { VerticalAlignment = VerticalAlignment.Center };
        words.Children.Add(_zoneIdle);
        words.Children.Add(_zoneOver);
        var zoneRow = Ui.Row(0, _zoneIcon, words);
        zoneRow.HorizontalAlignment = HorizontalAlignment.Center;
        zoneRow.Margin = new Thickness(Space.S6, Space.S5);
        var zoneContent = new Panel { IsHitTestVisible = false }; // the zone alone is the drop target (no child flicker)
        zoneContent.Children.Add(_zoneDash);
        zoneContent.Children.Add(zoneRow);
        _zone = new Pressable(Look.Plain, zoneContent, "Add mods: drag and drop mod jars here, or browse")
        {
            Margin = new Thickness(0, Space.S6, 0, Space.S4),
            CornerRadius = new CornerRadius(Radius.Lg),
            Background = Palette.Panel,
        };
        _zone.StateChanged += p =>
        {
            if (!_dragOver && _version != null) SetZone(over: false, hover: p.IsHovered || p.ForceHover, count: 0);
        };
        _zone.Click += (_, _) => Browse();
        DragDrop.SetAllowDrop(_zone, true);
        _zone.AddHandler(DragDrop.DragEnterEvent, OnDragOver);
        _zone.AddHandler(DragDrop.DragOverEvent, OnDragOver);
        _zone.AddHandler(DragDrop.DragLeaveEvent, OnDragLeave);
        _zone.AddHandler(DragDrop.DropEvent, OnDrop);

        // ---- YOUR MODS header: label + count, the hint, Open folder.
        _openFolder = Ui.Button("Open folder", Look.Secondary, Icons.Folder, Sizes.ControlMd).Named("Open this mods folder");
        _openFolder.Click += (_, _) => OpenFolder();
        _userHint.HorizontalAlignment = HorizontalAlignment.Right;
        _userHint.Margin = new Thickness(Space.S3, 0, Space.S3, 0);
        _userHint.TextTrimming = TextTrimming.CharacterEllipsis;
        var userHead = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), Margin = new Thickness(0, 0, 0, Space.S2) };
        userHead.Children.Add(Ui.Row(Space.S2, Ui.Eyebrow("Your mods"), _userCount).At(0));
        userHead.Children.Add(_userHint.At(1));
        userHead.Children.Add(_openFolder.At(2));

        _empty = new Border
        {
            Background = Palette.Panel, CornerRadius = new CornerRadius(Radius.Md), Padding = new Thickness(Space.S4, 14),
            Child = _emptyText,
        };

        // ---- BUILT IN: Origin's own mods, read-only cards, three across.
        var builtHead = new Grid { Margin = new Thickness(0, 0, 0, Space.S2) };
        builtHead.Children.Add(Ui.Row(Space.S2, Ui.Eyebrow("Built in"), _builtInCount).Align(HorizontalAlignment.Left));
        builtHead.Children.Add(Ui.Caption("Kept up to date by Origin").Align(HorizontalAlignment.Right));
        _builtIn = Ui.Column(0, builtHead, _builtInGrid);
        _builtIn.Margin = new Thickness(0, Space.S8, 0, 0);
        _builtIn.IsVisible = false;

        var stack = Ui.Column(0, header, _zone, _status, userHead, _userRows, _empty, _builtIn);
        Scroller = ModsParts.Column(this, stack, out _);
        Children.Add(Scroller);

        LauncherState.SettingsChanged += SyncMyMods;
    }

    public void Shown()
    {
        var version = LauncherState.SelectedVersion;
        if (version != _version || _demo != null) ShowVersion(version);
        else
        {
            ResolveProfile();
            SyncMyMods();
            Refresh();
        }
    }

    /// <summary>Point the page at a version and rebuild. Null = nothing selectable (no version chosen yet).</summary>
    public void ShowVersion(string? version)
    {
        _version = version;
        _pendingRemove = null;
        _pendingReplace.Clear();
        _demo = null;
        ResolveProfile();
        _versionChipText.Text = version ?? "No version";
        _versionChip.IsVisible = version != null;
        _zone.IsEnabled = version != null;
        _zone.Opacity = version != null ? 1 : 0.5;
        SyncMyMods();
        _status.Hide();
        SetZone(over: false, hover: false, count: 0);
        Refresh();
    }

    // The MY MODS switch mirrors the saved setting (Home's switch writes the same one); setting IsOn from code never
    // writes, so only the player's click here saves.
    private void SyncMyMods() => _myMods.IsOn = LauncherState.Settings.PlayWithExternalMods;

    // A selected profile owns the mods list for ITS version: adds, toggles and removals go to that profile's folder.
    // On any other version the page keeps managing the version's normal mods.
    private void ResolveProfile()
    {
        var settings = LauncherState.Settings;
        _profile = ProfileRules.Active(settings.ModProfiles, settings.SelectedProfileId, _version);
        var selected = settings.ModProfiles.FirstOrDefault(p => p.Id == settings.SelectedProfileId && ProfileRules.IsUsable(p));
        _intro.Text = _profile != null
            ? $"Mods for your profile \"{_profile.Name}\". Only this profile loads them; Origin's built-in mods stay up to date on their own."
            : selected != null && _version != null
                ? $"Your profile \"{selected.Name}\" is for {selected.Version}, so these are your normal mods for this version, shared by all your accounts."
                : "Your own .jar mods for this version, shared by all your accounts. Origin's built-in mods stay up to date on their own.";
    }

    private void Refresh(ICollection<string>? landed = null)
    {
        if (_version == null)
        {
            _builtKey = null;
            _builtFrom = null;
            _userRows.Children.Clear();
            _builtInGrid.Children.Clear();
            _emptyText.Text = "Choose a version first";
            _empty.IsVisible = true;
            _userCount.Text = "0";
            _userHint.IsVisible = false;
            _openFolder.IsEnabled = false;
            _builtIn.IsVisible = false;
            return;
        }

        var mods = _demo ?? ModManager.Enumerate(_version, _profile?.Id);
        var plain = landed == null && _pendingRemove == null && _pendingReplace.Count == 0 && _demo == null;
        var key = $"{_version}|{_profile?.Id}|{_profile?.Name}";
        if (plain && key == _builtKey && _builtFrom != null && mods.SequenceEqual(_builtFrom)) return; // same rows
        _builtKey = plain ? key : null;
        _builtFrom = plain ? mods.ToList() : null;
        _userRows.Children.Clear();
        _builtInGrid.Children.Clear();
        var user = mods.Where(m => !m.IsManaged).ToList();
        // A profile folder holds only the player's own mods; Origin's built-in ones live in the shared folder.
        var managed = _profile != null ? [] : mods.Where(m => m.IsManaged).ToList();

        // A dropped jar that is the same mod as an installed one asks first, above the list.
        if (_pendingReplace.Count > 0) _userRows.Children.Add(BuildReplaceRow(_pendingReplace[0]));

        var delay = 0;
        foreach (var mod in user)
        {
            var isLanded = landed != null && landed.Contains(mod.FileName);
            var row = mod.FileName == _pendingRemove ? BuildConfirmRow(mod) : BuildUserRow(mod, isLanded);
            _userRows.Children.Add(row);
            if (!isLanded) continue;
            Land(row, delay);
            delay += 40;
        }

        _openFolder.IsEnabled = true;
        _userCount.Text = user.Count.ToString(System.Globalization.CultureInfo.InvariantCulture);
        _emptyText.Text = "No mods yet. Drop a jar above to add one.";
        _empty.IsVisible = user.Count == 0;
        _userHint.IsVisible = user.Count > 0;

        foreach (var mod in managed) _builtInGrid.Children.Add(BuildManagedTile(mod));
        _builtInCount.Text = managed.Count.ToString(System.Globalization.CultureInfo.InvariantCulture);
        _builtIn.IsVisible = managed.Count > 0;
    }

    // A just-added row drops in from above and settles, staggered (no motion: it is simply there).
    private static void Land(Control row, int delayMs)
    {
        if (Motion.Off) return;
        var delay = TimeSpan.FromMilliseconds(delayMs);
        row.Opacity = 0;
        row.RenderTransform = TransformOperations.Parse("translateY(-14px)");
        row.Transitions =
        [
            new DoubleTransition { Property = OpacityProperty, Duration = Motion.Ms(220), Delay = delay },
            new TransformOperationsTransition { Property = RenderTransformProperty, Duration = Motion.Ms(420), Delay = delay, Easing = new BackEaseOut() },
        ];
        DispatcherTimer.RunOnce(() =>
        {
            row.Opacity = 1;
            row.RenderTransform = TransformOperations.Parse("translateY(0px)");
        }, TimeSpan.FromMilliseconds(16));
    }

    // ---- rows -------------------------------------------------------------------------------------------------

    // A row: Panel, radius md, 12 / 10 inside, 4 below; it lifts to Card on hover.
    private static Border RowShell() => ModsParts.HoverSurface(Radius.Md, new Thickness(Space.S3, 10)).Margin(0, 0, 0, Space.S1);

    private static Grid RowGrid() => new() { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto,Auto") };

    private static TextBlock NameText(string text, bool dim) =>
        Ui.Ellipsis(Ui.Text(text, TypeScale.Body, FontWeight.SemiBold, dim ? Palette.TextDim : Palette.Text));

    private static TextBlock FileText(string text)
    {
        var t = Ui.Ellipsis(Ui.Text(text, TypeScale.Caption, FontWeight.Normal, Palette.TextMuted));
        t.Margin = new Thickness(0, Space.S1, 0, 0);
        return t;
    }

    private Control BuildUserRow(ModEntry mod, bool added = false)
    {
        var shell = RowShell();
        var grid = RowGrid();
        grid.Children.Add(IconWell(!mod.Enabled, mod).At(0));

        // The name column starts 12 after the icon well; the Replace and Remove prompts use the same gap, so the
        // question lines up with the names above and below it.
        Control name = added
            ? Ui.Row(Space.S2, NameText(mod.DisplayName, !mod.Enabled), AddedTag())
            : NameText(mod.DisplayName, !mod.Enabled);
        var text = Ui.Column(0, name, FileText(mod.FileName));
        text.VerticalAlignment = VerticalAlignment.Center;
        text.Margin = new Thickness(Space.S3, 0, Space.S3, 0);
        grid.Children.Add(text.At(1));

        var toggle = new Toggle($"Enable {mod.DisplayName}") { IsOn = mod.Enabled, Margin = new Thickness(0, 0, Space.S3, 0) };
        toggle.Toggled += on => OnToggle(mod.FileName, on, toggle);
        grid.Children.Add(toggle.At(2));

        var remove = QuietIconButton(Icons.Trash, $"Remove {mod.DisplayName}");
        remove.Click += (_, _) => { _pendingRemove = mod.FileName; Refresh(); };
        grid.Children.Add(remove.At(3));

        if (added) grid.Children.Insert(0, LandingLight());
        shell.Child = grid;
        return shell;
    }

    // An icon button with no hover label (spec 9): the accessible name carries the meaning.
    private static Pressable QuietIconButton(string glyph, string name)
    {
        var b = Ui.IconButton(glyph, name, Sizes.ControlSm);
        ToolTip.SetTip(b, null);
        return b;
    }

    // "ADDED": a capsule badge, a 6 px accent dot and the word, on the soft accent.
    private static Border AddedTag() => Ui.Capsule(new Border
    {
        Background = Palette.AccentSoft,
        Padding = new Thickness(6, 3),
        VerticalAlignment = VerticalAlignment.Center,
        Child = Ui.Row(6, Ui.Dot(Palette.Accent), Ui.Text("ADDED", TypeScale.Label, FontWeight.Bold, Palette.Text)),
    });

    // The flash a landed row gives once: the soft accent over the row, fading out (no edge, no glow). Without motion
    // there is no flash at all (the ADDED tag says it).
    private static Control LandingLight()
    {
        var tint = new Border
        {
            Background = Palette.AccentSoft,
            CornerRadius = new CornerRadius(Radius.Md),
            Margin = new Thickness(-13, -11),
            IsHitTestVisible = false,
            Opacity = Motion.Off ? 0 : 1,
        };
        Grid.SetColumnSpan(tint, 4);
        if (!Motion.Off)
        {
            tint.Transitions = [new DoubleTransition { Property = OpacityProperty, Duration = Motion.Ms(1100), Delay = TimeSpan.FromMilliseconds(300) }];
            DispatcherTimer.RunOnce(() => tint.Opacity = 0, TimeSpan.FromMilliseconds(16));
        }
        return tint;
    }

    // Inline destructive-action confirm: the row flips to "Remove <name>?" with Remove / Cancel.
    private Control BuildConfirmRow(ModEntry mod)
    {
        var shell = RowShell();
        shell.BorderBrush = Palette.Danger;
        var grid = RowGrid();
        grid.Children.Add(IconWell(true, mod).At(0));
        var prompt = NameText($"Remove {mod.DisplayName}?", false);
        prompt.Margin = new Thickness(Space.S3, 0, Space.S2, 0);
        grid.Children.Add(prompt.At(1));

        var confirm = Ui.Button("Remove", Look.Danger, height: Sizes.ControlSm).Margin(0, 0, Space.S2, 0);
        confirm.Click += (_, _) =>
        {
            if (_version == null || _demo != null) return;
            var result = ModManager.Remove(_version, mod.FileName, _profile?.Id);
            _pendingRemove = null;
            if (!result.Ok) _status.Show(result.Error, error: true);
            else _status.Hide();
            Refresh();
        };
        grid.Children.Add(confirm.At(2));
        var cancel = Ui.Button("Cancel", Look.Secondary, height: Sizes.ControlSm);
        cancel.Click += (_, _) => { _pendingRemove = null; Refresh(); };
        grid.Children.Add(cancel.At(3));
        shell.Child = grid;
        return shell;
    }

    // "Litematica is already installed / Replace 0.19.50 -> 0.21.0" with Replace / Keep old: the same inline pattern as
    // the Remove confirm, so nothing is overwritten by accident.
    private Control BuildReplaceRow(ReplaceOffer offer)
    {
        var shell = RowShell();
        shell.BorderBrush = Palette.Accent;
        var grid = RowGrid();
        grid.Children.Add(IconWell(true, new ModEntry(offer.ExistingFileName, offer.ModName, true, false)).At(0));

        // Line 1 is the mod, line 2 the versions as one trimmed caption, never wrapped mid-token; a long name trims.
        var from = offer.ExistingVersion ?? "this version";
        var prompt = Ui.Column(0,
            NameText($"{offer.ModName} is already installed", false),
            FileText(offer.NewVersion == null ? $"Replace {from} with this file?" : $"Replace {from} -> {offer.NewVersion}"));
        prompt.VerticalAlignment = VerticalAlignment.Center;
        prompt.Margin = new Thickness(Space.S3, 0, Space.S2, 0);
        grid.Children.Add(prompt.At(1));

        var replace = Ui.Button("Replace", Look.Primary, height: Sizes.ControlSm).Margin(0, 0, Space.S2, 0);
        replace.Click += (_, _) => AnswerReplace(offer, true);
        grid.Children.Add(replace.At(2));
        var keep = Ui.Button("Keep old", Look.Secondary, height: Sizes.ControlSm);
        keep.Click += (_, _) => AnswerReplace(offer, false);
        grid.Children.Add(keep.At(3));
        shell.Child = grid;
        return shell;
    }

    // A built-in mod: a read-only card (Origin installs and updates it), as tall as a row so the grid stays dense.
    private Control BuildManagedTile(ModEntry mod)
    {
        var tile = ModsParts.HoverSurface(Radius.Lg, new Thickness(Space.S3, 10)).Margin(0, 0, Space.S1, Space.S1);
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        grid.Children.Add(IconWell(false, mod).At(0));
        var text = Ui.Column(0, NameText(mod.DisplayName, false), FileText(ModIcons.Blurb(mod)));
        text.VerticalAlignment = VerticalAlignment.Center;
        text.Margin = new Thickness(Space.S3, 0, 0, 0);
        grid.Children.Add(text.At(1));
        tile.Child = grid;
        tile.Named($"{mod.DisplayName}, built in");
        return tile;
    }

    // ---- icon wells -------------------------------------------------------------------------------------------

    // The square well that leads every row and tile: the mod's glyph in its hue on that hue's soft wash (dimmer when
    // switched off), or the jar glyph on bg for an unknown mod; Origin's own entry wears the logo file. The real icon
    // from inside the jar, when it has one, replaces the drawing as soon as it is read (off the UI thread).
    private Border IconWell(bool dim, ModEntry mod)
    {
        var well = new Border
        {
            Width = Sizes.TileLg, Height = Sizes.TileLg, CornerRadius = new CornerRadius(Radius.Sm),
            Background = Palette.Bg, VerticalAlignment = VerticalAlignment.Center,
        };
        var hue = ModIcons.Hue(mod);
        var ink = hue != null ? Palette.Brush("Hue." + hue) : dim ? Palette.TextMuted : Palette.TextDim;
        Control glyph = ModIcons.For(mod) is { } g
            ? new PathGlyph(g, Sizes.IconLg) { Brush = ink }
            : Ui.Icon(Icons.Jar, Sizes.IconLg, ink);
        glyph.HorizontalAlignment = HorizontalAlignment.Center;
        if (hue != null)
        {
            well.Background = Palette.Brush("HueTile." + hue);
            glyph.Opacity = dim ? 0.55 : 1;
        }
        var host = new Panel();
        host.Children.Add(glyph);
        well.Child = host;
        if (ModIcons.IsOrigin(mod))
        {
            glyph.IsVisible = false;
            var logo = Ui.Logo(Sizes.LogoBar);
            logo.Opacity = dim ? 0.55 : 1;
            host.Children.Add(logo);
            return well;
        }
        LoadRealIcon(mod, well, glyph, dim);
        return well;
    }

    // Decoded icons by jar (path + size + mtime), loaded once and shared: a rebuild gets the finished icon at once.
    private static readonly IconMemo<Bitmap> RealIcons = new();

    private void LoadRealIcon(ModEntry mod, Border well, Control glyph, bool dim)
    {
        if (_demo != null)
        {
            // Render runs: tiny in-memory demo icons, decoded on the spot so the frame is deterministic.
            if (ModIconSource.DemoIcons != null && ModIconSource.DemoIcons.TryGetValue(mod.FileName, out var bytes)
                && DecodeIcon(bytes) is { } demo)
                ShowRealIcon(well, glyph, demo, dim);
            return;
        }
        if (_version == null) return;
        var name = mod.Enabled || mod.IsManaged ? mod.FileName : mod.FileName + ".disabled";
        var jar = System.IO.Path.Combine(ModManager.ModsFolder(_version, _profile?.Id), name);
        var task = RealIcons.GetAsync(jar, path => JarIconReader.ReadIcon(path) is { } b ? DecodeIcon(b) : null);
        if (task.IsCompletedSuccessfully)
        {
            if (task.Result is { } ready) ShowRealIcon(well, glyph, ready, dim);
            return;
        }
        _ = task.ContinueWith(t =>
        {
            if (t.IsCompletedSuccessfully && t.Result is { } image)
                Dispatcher.UIThread.Post(() => ShowRealIcon(well, glyph, image, dim));
        }, TaskScheduler.Default);
    }

    // Decode off the UI thread. Anything that is not a sane image is "no icon"; never keep a 2000 px icon in memory
    // (a tile shows 40 px, 80 physical at 200 %).
    private static Bitmap? DecodeIcon(byte[] bytes)
    {
        try
        {
            using var ms = new System.IO.MemoryStream(bytes);
            var bitmap = new Bitmap(ms);
            var (w, h) = (bitmap.PixelSize.Width, bitmap.PixelSize.Height);
            if (w <= 0 || h <= 0 || w > 4096 || h > 4096)
            {
                bitmap.Dispose();
                return null;
            }
            if (Math.Max(w, h) <= 128) return bitmap;
            bitmap.Dispose();
            ms.Position = 0;
            return w >= h ? Bitmap.DecodeToWidth(ms, 128) : Bitmap.DecodeToHeight(ms, 128);
        }
        catch (Exception)
        {
            return null;
        }
    }

    // Small icons are drawn at a whole-number multiple with nearest-neighbour so they stay crisp (16 -> 32, 32 -> 32,
    // 20 -> 40); everything else fills the 40 px tile with high-quality scaling. Clipped to the tile's corner radius.
    private static void ShowRealIcon(Border well, Control glyph, Bitmap source, bool dim)
    {
        if (well.Child is not Panel host || host.Children.Count > 1) return;
        var longest = Math.Max(source.PixelSize.Width, source.PixelSize.Height);
        var whole = longest <= (int)Sizes.TileLg;
        double side = whole ? (int)Sizes.TileLg / longest * longest : Sizes.TileLg;
        var image = new Image { Source = source, Stretch = Stretch.Uniform, Width = side, Height = side };
        RenderOptions.SetBitmapInterpolationMode(image, whole ? BitmapInterpolationMode.None : BitmapInterpolationMode.HighQuality);
        host.Children.Add(new Border
        {
            Width = side, Height = side, CornerRadius = new CornerRadius(Radius.Sm), ClipToBounds = true,
            Child = image, Opacity = dim ? 0.6 : 1, IsHitTestVisible = false,
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        });
        glyph.IsVisible = false;
        well.Background = Palette.Bg;
    }

    // ---- actions ----------------------------------------------------------------------------------------------

    private void OnToggle(string fileName, bool enable, Toggle toggle)
    {
        if (_version == null || _demo != null) return;
        var result = ModManager.SetEnabled(_version, fileName, enable, _profile?.Id);
        if (result.Ok)
        {
            _status.Hide();
            _builtKey = null; // the rows on screen now differ from what they were built from
            Refresh();
            return;
        }
        toggle.IsOn = !enable; // back to the disk's truth (IsOn from code never re-raises Toggled)
        _status.Show(result.Error, error: true);
    }

    private void OpenFolder()
    {
        if (_version == null || _demo != null) return;
        var result = ModManager.OpenFolder(_version, _profile?.Id);
        if (!result.Ok) _status.Show(result.Error, error: true);
    }

    // Browse: the same add path as a drop, through the system file picker.
    private async void Browse()
    {
        if (_version == null || _adding || _demo != null) return;
        if (TopLevel.GetTopLevel(this) is not { } top) return;
        var files = await top.StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "Add mod jars",
            AllowMultiple = true,
            FileTypeFilter = [new FilePickerFileType("Mod jars") { Patterns = ["*.jar"] }],
        });
        var paths = files.Select(f => f.TryGetLocalPath()).OfType<string>().ToArray();
        if (paths.Length > 0) AddFiles(paths);
    }

    // Copy the jars in off the UI thread (a big pack must not freeze the window), then land the new rows. One add at
    // a time: a second waits its turn.
    private async void AddFiles(string[] files)
    {
        if (_version == null || _adding) return;
        _adding = true;
        var version = _version;
        var profileId = _profile?.Id;
        var before = ModManager.Enumerate(version, profileId).Select(m => m.FileName).ToHashSet(StringComparer.OrdinalIgnoreCase);
        _status.Show(files.Length == 1 ? "Adding 1 mod..." : $"Adding {files.Length} mods...", error: false);
        var report = await Task.Run(() => ModManager.Import(version, files, profileId));
        _adding = false;
        if (_version != version) return; // the page moved on to another version meanwhile
        ApplyImport(report, before, version, profileId);
    }

    // Shows what an add did: the new rows land, a refused jar says why in plain words, and a jar that is the same mod
    // as an installed one waits for the player's yes (never a silent overwrite).
    private void ApplyImport(ImportReport report, HashSet<string> before, string version, string? profileId)
    {
        _pendingReplace.AddRange(report.NeedsReplace);
        if (!report.Ok)
        {
            _status.Show(report.Error, error: true);
            Refresh();
            return;
        }
        var landed = ModManager.Enumerate(version, profileId).Where(m => !before.Contains(m.FileName))
            .Select(m => m.FileName).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var message = report.Message;
        if (message.Length > 0) _status.Show(message, error: false);
        else _status.Hide();
        Refresh(landed);
    }

    // The player answered a "Replace?" row. Yes copies that one jar in (the old file stays, turned off); no drops it.
    private async void AnswerReplace(ReplaceOffer offer, bool replace)
    {
        _pendingReplace.Remove(offer);
        if (!replace || _version == null || _adding || _demo != null)
        {
            Refresh();
            return;
        }
        _adding = true;
        var version = _version;
        var profileId = _profile?.Id;
        var before = ModManager.Enumerate(version, profileId).Select(m => m.FileName).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var confirmed = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { offer.FileName };
        var report = await Task.Run(() => ModManager.Import(version, [offer.SourcePath], profileId, confirmed));
        _adding = false;
        if (_version != version) return;
        ApplyImport(report, before, version, profileId);
    }

    // ---- the drop zone ----------------------------------------------------------------------------------------

    // idle -> hover (a little brighter) -> over (jars are being carried in): the zone lights in the card surface, the
    // dashes take the accent, the arrow lifts 3 px and the line reads "Release to add 2 mod jars". No grow, no glow.
    private void SetZone(bool over, bool hover, int count)
    {
        _dragOver = over;
        _zone.Background = over || hover ? Palette.Card : Palette.Panel;
        _zoneDash.Stroke = over ? Palette.Accent : hover ? Palette.TextMuted : Palette.LineStrong;
        _zoneIcon.Brush = over || hover ? Palette.Text : Palette.TextDim;
        _zoneIcon.RenderTransform = TransformOperations.Parse(over ? "translateY(-3px)" : "translateY(0px)");
        _zoneIdle.IsVisible = !over;
        _zoneOver.IsVisible = over;
        if (over) _zoneOver.Text = count == 1 ? "Release to add 1 mod jar" : $"Release to add {count} mod jars";
    }

    private static string[] JarPaths(DragEventArgs e) =>
        e.DataTransfer.TryGetFiles() is { } items
            ? items.Select(i => i.TryGetLocalPath()).OfType<string>()
                .Where(p => p.EndsWith(ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase)).ToArray()
            : [];

    private void OnDragOver(object? sender, DragEventArgs e)
    {
        var jars = _version != null && !_adding && _demo == null ? JarPaths(e).Length : 0;
        e.DragEffects = jars > 0 ? DragDropEffects.Copy : DragDropEffects.None;
        if (jars > 0 && !_dragOver) SetZone(over: true, hover: false, count: jars);
        e.Handled = true;
    }

    private void OnDragLeave(object? sender, DragEventArgs e) => SetZone(over: false, hover: false, count: 0);

    private void OnDrop(object? sender, DragEventArgs e)
    {
        SetZone(over: false, hover: true, count: 0);
        var jars = _version != null && _demo == null ? JarPaths(e) : [];
        if (jars.Length == 0) return;
        AddFiles(jars);
        e.Handled = true;
    }

    // ---- render runs only (Shots/ModsProfilesShots.cs): never touch the disk or the settings ---------------------

    /// <summary>Made-up rows, optionally with jars "carried" over the zone (carrying -1: the first mod just landed).</summary>
    internal void TourShowDemo(IReadOnlyList<ModEntry> mods, int carrying)
    {
        if (!OriginPaths.IsScratchRoot) return;
        _demo = mods;
        _status.Hide();
        var first = carrying < 0 ? mods.FirstOrDefault(m => !m.IsManaged) : null;
        Refresh(first == null ? null : new HashSet<string> { first.FileName });
        SetZone(over: carrying > 0, hover: false, count: Math.Max(carrying, 0));
    }

    internal void TourShowStatus(string message, bool error)
    {
        if (OriginPaths.IsScratchRoot) _status.Show(message, error);
    }

    internal void TourConfirmRemove(string fileName)
    {
        if (!OriginPaths.IsScratchRoot) return;
        _pendingRemove = fileName;
        Refresh();
    }

    internal void TourOfferReplace(ReplaceOffer offer)
    {
        if (!OriginPaths.IsScratchRoot) return;
        _pendingReplace.Clear();
        _pendingReplace.Add(offer);
        Refresh();
    }

    internal void TourNoVersion()
    {
        if (OriginPaths.IsScratchRoot) ShowVersion(null);
    }

    internal Pressable OpenFolderButton => _openFolder;

    /// <summary>The first row's Remove button (the Windows shot focuses it).</summary>
    internal Pressable? FirstRowButton =>
        _userRows.Children.OfType<Border>().FirstOrDefault()?.Child is Grid g ? g.Children.OfType<Pressable>().LastOrDefault() : null;
}

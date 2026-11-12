using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Layout;
using Avalonia.Media;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Sync;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;
using TileState = OriginLauncher.App.UI.Controls.LauncherState;

namespace OriginLauncher.Desktop.Overlays;

// Step 1 (the launchers found) and the start of step 2: how much comes over, the grid, the single-setup pick list.
internal sealed partial class ImportPage
{
    // ---------------------------------------------------------------- 1. detect

    private void ShowLaunchers()
    {
        ShowPane(Pane.Launchers);
        RenderLaunchers();
    }

    // The launchers found, as tiles (logo, folder, three counts; a press picks one); the ones not found as one slim row;
    // while the scan runs, grey skeleton tiles in the tiles' shape so nothing jumps when the real ones land.
    private void RenderLaunchers()
    {
        if (_pane != Pane.Launchers) return;
        var tiles = _model.Tiles(_scanning);
        var found = tiles.Where(t => t.State == TileState.Found).ToList();
        var missing = tiles.Where(t => t.State is TileState.NotInstalled or TileState.Empty).ToList();
        var nothing = !_scanning && found.Count == 0;

        var page = new StackPanel();
        if (!_scanning && !string.IsNullOrEmpty(_scanNote))
            page.Children.Add(Ui.Wrap(Ui.Body(_scanNote, Palette.TextDim)).Margin(0, 6, 0, 12));
        var grid = new UniformGrid { Columns = 3, Margin = new Thickness(0, 0, -16, 0) };
        if (_scanning && found.Count == 0)
        {
            foreach (var w in new[] { (120, 160), (96, 140), (132, 120) }) grid.Children.Add(SkeletonTile(w.Item1, w.Item2));
            grid.IsHitTestVisible = false;
        }
        foreach (var tile in found) grid.Children.Add(Tile(tile));
        page.Children.Add(grid);

        if (!_scanning && missing.Count > 0) page.Children.Add(MissingBar(missing));
        if (!_scanning)
        {
            var never = ImportVerifier.NeverImported.First(n => n.Name.Contains("ATLauncher", StringComparison.Ordinal));
            page.Children.Add(Ui.Wrap(Ui.Caption($"{never.Name}: {never.Reason}", Palette.TextMuted)).Margin(2, 8, 0, 0));
        }
        SetBody(page);

        var launchers = found.Count(t => t.Kind != SourceKind.Folder);
        var checkedLine = $"Scan complete - {ImportFlowModel.TileOrder.Count} launchers checked";
        if (_scanning)
            SetHeader("Bring it all over", "Looking through your other launchers...", "Looking...", dot: false);
        else if (nothing && _model.EverythingImported)
            SetHeader("Bring it all over", "Everything from your other launchers is already in Origin. It shows up here again if something changes there.", checkedLine);
        else if (nothing)
            SetHeader("Bring it all over", "Nothing found to import. Add a setup's folder, or play once in your other launcher first.", checkedLine);
        else
            SetHeader("Bring it all over",
                (launchers > 0 ? $"Found {ImportLogic.Plural(launchers, "launcher", "launchers")} on {BulkSummary.ThisComputer}. " : "Found the folder you picked. ")
                + "Pick the one to bring over. Each setup with mods becomes its own Origin profile; the other launcher is never changed.",
                checkedLine);

        if (nothing) SetFooter(FooterCopiesNote, null, null, "Add a folder", () => _ = BrowseAsync());
        else SetFooter(FooterCopiesNote, null, null, null, null);
    }

    private static Border SkeletonTile(double titleWidth, double pathWidth)
    {
        var top = Ui.Row(0,
            new Border { Width = 44, Height = 44, Background = Palette.Bg, CornerRadius = new CornerRadius(Radius.Md), Margin = new Thickness(0, 0, 14, 0) },
            Ui.Column(8, ImportParts.Skeleton(titleWidth, 14), ImportParts.Skeleton(pathWidth, 10)).Align(HorizontalAlignment.Left));
        var stats = new Border { Height = 64, Background = Palette.Bg, CornerRadius = new CornerRadius(Radius.Md), Margin = new Thickness(0, 14, 0, 0) };
        return new Border
        {
            Background = Palette.Panel, BorderBrush = Palette.Line, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(Radius.Lg),
            Padding = new Thickness(16), Margin = new Thickness(0, 0, 16, 16), Child = Ui.Column(0, top, stats),
        };
    }

    // One launcher: its logo on a soft-edged well, its name and folder, then setups / worlds / mods ("..." until counted).
    private Pressable Tile(LauncherTile tile)
    {
        var mine = _model.Setups.Where(s => s.Kind == tile.Kind).ToList();
        var counted = _model.Counted(mine);
        var canTick = mine.Any(_model.CanGo);

        var name = Ui.Text(tile.Title, TypeScale.Title, FontWeight.Bold);
        name.TextTrimming = TextTrimming.CharacterEllipsis;
        var path = Ui.Caption(ImportLogic.ShortPath(ImportLogic.CommonFolder(mine.Select(s => s.GameDir).ToList())), Palette.TextDim);
        path.TextTrimming = TextTrimming.CharacterEllipsis;
        path.Margin = new Thickness(0, 2, 0, 0);
        var head = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto") };
        head.Children.Add(ImportParts.Logo(tile.Kind, 44, tile.Title).Margin(0, 0, 14, 0).At(0));
        head.Children.Add(Ui.Column(0, name, path).Align(HorizontalAlignment.Stretch).At(1));
        head.Children.Add(ImportParts.Go().At(2));

        var stats = new UniformGrid { Columns = 3, Margin = new Thickness(0, 14, -8, 0) };
        stats.Children.Add(TileStat(N(mine.Count), SetupsLabel(tile.Kind, mine.Count)));
        stats.Children.Add(TileStat(counted ? N(_model.Total(mine, ImportCategories.Worlds)) : "...", "WORLDS"));
        stats.Children.Add(TileStat(counted ? N(_model.Total(mine, ImportCategories.Mods)) : "...", "MODS"));

        var card = new Pressable(Look.Card, Ui.Column(0, head, stats), tile.Title)
        {
            Name = "tile-" + tile.Kind, Padding = new Thickness(16), Margin = new Thickness(0, 0, 16, 16),
            CornerRadius = new CornerRadius(Radius.Lg), IsEnabled = canTick && !IsApplying,
        };
        ToolTip.SetTip(card, canTick ? "Bring things over from " + tile.Title : "Origin can't run any of these versions yet");
        card.Click += (_, _) => ChooseLauncher(tile.Kind);
        return card;
    }

    private static Border TileStat(string value, string label)
    {
        var stat = ImportParts.Stat(value, label);
        stat.Margin = new Thickness(0, 0, 8, 0);
        stat.Padding = new Thickness(10, 8);
        return stat;
    }

    // The label under the setups count, the WPF tile's SetupsLabel: launchers that call them instances say INSTANCES,
    // the rest SETUPS, singular for 1. (Before, this showed the tile's whole summary, cut to "2 VERSIONS,".)
    private static string SetupsLabel(SourceKind kind, int count)
    {
        var word = kind is SourceKind.CurseForge or SourceKind.Modrinth or SourceKind.Prism or SourceKind.OfficialLauncher
            ? "INSTANCE" : "SETUP";
        return count == 1 ? word : word + "S";
    }

    // "Feather Client, LabyMod and 3 more have nothing to import on this PC", their logos, and Locate folder.
    private Border MissingBar(IReadOnlyList<LauncherTile> missing)
    {
        var logos = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 8, 0) };
        foreach (var t in missing.Take(8))
        {
            var logo = ImportParts.Logo(t.Kind, 28, t.Title);
            logo.Margin = new Thickness(0, 0, 6, 0);
            ToolTip.SetTip(logo, t.Title);
            logos.Children.Add(logo);
        }
        var text = ImportParts.Rich(MissingSentence(missing, _model.Installed != null), Palette.TextDim);
        text.VerticalAlignment = VerticalAlignment.Center;
        var locate = Ui.Button("Locate folder", Look.Secondary, Icons.Folder, Sizes.ControlMd);
        locate.Name = "locate";
        ToolTip.SetTip(locate, "Point Origin at a launcher's folder");
        locate.Click += (_, _) => _ = BrowseAsync();
        locate.Margin = new Thickness(12, 0, 0, 0);
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto") };
        grid.Children.Add(logos.At(0));
        grid.Children.Add(text.At(1));
        grid.Children.Add(locate.At(2));
        return new Border
        {
            Background = Palette.Card, BorderBrush = Palette.Line, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(Radius.Lg),
            Padding = new Thickness(12, 8), Child = grid,
        };
    }

    // "MultiMC wasn't found on this PC" / "Lunar Client, Feather Client and 3 more weren't found on this PC";
    // a launcher that is installed but empty says so instead ("has nothing to import"). (The WPF page's own sentence.)
    internal static string MissingSentence(IReadOnlyList<LauncherTile> missing, bool installedKnown)
    {
        var names = missing.Select(t => "**" + t.Title + "**").ToList();
        var list = ImportFlowModel.JoinFew(names);
        var many = missing.Count > 1;
        var allNot = !installedKnown || missing.All(t => t.State == TileState.NotInstalled);
        var allEmpty = installedKnown && missing.All(t => t.State == TileState.Empty);
        var verb = allNot ? (many ? $"weren't found on {BulkSummary.ThisComputer}" : $"wasn't found on {BulkSummary.ThisComputer}")
            : allEmpty ? (many ? "have nothing to import" : "has nothing to import")
            : $"have nothing to import on {BulkSummary.ThisComputer}";
        return $"{list} {verb}";
    }

    // "17 setups, 54 worlds and 320 mods ready to import": what is going, with **bold** numbers.
    private string ReadyNote(IReadOnlyList<DetectedInstance> going, string verb)
    {
        if (going.Count == 0) return "Nothing is ticked yet";
        static string Part(int n, string one, string many) => $"**{n}** {(n == 1 ? one : many)}";
        var parts = new List<string> { Part(going.Count, "setup", "setups") };
        if (_model.Counted(going))
        {
            var worlds = _model.Total(going, ImportCategories.Worlds);
            var mods = _model.Total(going, ImportCategories.Mods);
            if (worlds > 0) parts.Add(Part(worlds, "world", "worlds"));
            if (mods > 0) parts.Add(Part(mods, "mod", "mods"));
        }
        return ImportLogic.JoinParts(parts) + " " + verb;
    }

    private void ChooseLauncher(SourceKind kind)
    {
        if (_scanning || _model.FastSetups(kind).Count == 0 || IsApplying) return;
        if (_picked != kind) _plan = null; // another launcher: nothing planned for the last one is reused
        _picked = kind;
        ShowScope();
    }

    // ---------------------------------------------------------------- 2. how much of it

    // Two rows: everything from the picked launcher, or the grid over its setups. The plan for "everything" is made in
    // the background while the player reads, so step 3 usually opens with it ready.
    private void ShowScope()
    {
        if (_picked is not { } kind)
        {
            ShowLaunchers();
            return;
        }
        ShowPane(Pane.Scope);
        _model.UseFast(kind);
        RenderScope();
        PrefetchPlan();
    }

    private void RenderScope()
    {
        if (_picked is not { } kind || _pane != Pane.Scope) return;
        var title = ImportFlowModel.LauncherTitle(kind);
        var going = _model.FastSetups(kind);
        SetHeader(title, "How much should come over? Nothing in " + title + " is changed.");
        var versions = going.Select(s => s.McVersion).Distinct().Count();
        var stays = _model.Setups.Count(s => s.Kind == kind && !_model.CanGo(s));
        var note = (stays > 0
                ? $"{ImportLogic.Plural(stays, "setup is", "setups are")} on a Minecraft version Origin can't run, so {(stays == 1 ? "it stays" : "they stay")} behind. "
                : "")
            + "Accounts, launcher app data and mods made for other mod setups never come over; the report names them.";

        var page = new StackPanel();
        var all = ScopeRow("scope-all", "Import everything", Icons.Import, Palette.TileEmerald, Palette.HueEmerald,
            "Every setup Origin can run, with its settings, mods, packs, worlds and servers. Each setup with mods becomes its own profile.",
            ReadyNote(going, "will come over"));
        all.Click += (_, _) => FastImport(kind);
        var choose = ScopeRow("scope-choose", "Choose what to import", Icons.Check, Palette.TileSky, Palette.HueSky,
            $"Tick what to bring from {title}, version by version: settings, mods, packs, worlds and servers.",
            $"**{ImportLogic.Plural(going.Count, "setup", "setups")}** on **{ImportLogic.Plural(versions, "version", "versions")}** to pick from");
        choose.Click += (_, _) => ShowGrid();
        all.IsEnabled = choose.IsEnabled = Allowed && !Busy;
        page.Children.Add(all);
        page.Children.Add(choose);
        page.Children.Add(Ui.Wrap(Ui.Caption(note, Palette.TextMuted)).Margin(2, 4, 0, 0));
        SetBody(page);
        SetFooter(FooterCopiesNote, null, null, null, null);
    }

    // The Settings row look (DESIGN-SYSTEM 2.3): a 28 hue tile labels the row, title and what it brings, the counts, a chevron.
    private static Pressable ScopeRow(string name, string title, string glyph, IBrush tile, IBrush ink, string text, string counts)
    {
        var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        words.Children.Add(Ui.BodyStrong(title));
        words.Children.Add(Ui.Wrap(Ui.Body(text, Palette.TextDim)));
        var countLine = ImportParts.Rich(counts, Palette.TextDim, TypeScale.Caption);
        countLine.Margin = new Thickness(0, 4, 0, 0);
        words.Children.Add(countLine);
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto") };
        var t = Ui.Tile(glyph, tile, ink);
        t.VerticalAlignment = VerticalAlignment.Top;
        t.Margin = new Thickness(0, 0, 12, 0);
        grid.Children.Add(t.At(0));
        grid.Children.Add(words.At(1));
        var go = ImportParts.Go();
        go.Margin = new Thickness(16, 0, 2, 0);
        grid.Children.Add(go.At(2));
        return new Pressable(Look.Card, grid, title)
        {
            Name = name, Padding = new Thickness(16, 12), Margin = new Thickness(0, 0, 0, 8), CornerRadius = new CornerRadius(Radius.Lg),
        };
    }

    // ---------------------------------------------------------------- on to step 3 (Confirm)

    // Every way out of step 2 lands on Review: what comes over, which profiles are made and what stays behind.
    private void ImportNow()
    {
        if (!CanStartImport() || _model.GoingSetups().Count == 0) return;
        _model.UseTicks();
        OpenReview();
    }

    private void FastImport(SourceKind launcher)
    {
        if (!CanStartImport() || _model.FastSetups(launcher).Count == 0) return;
        _model.UseFast(launcher);
        OpenReview();
    }

    private void ImportFromGrid()
    {
        if (!CanStartImport() || _model.GridModel is not { BringsNothing: false }) return;
        _model.UseGrid(_picked);
        OpenReview();
    }

    private bool CanStartImport() =>
        _pane is Pane.Launchers or Pane.Scope or Pane.Grid or Pane.Pick && !_autoStart && !_scanning
        && ImportLogic.CheckStart(IsApplying, Busy ? BusyReason : null) == ImportLogic.StartKind.Start;

    private void ShowChecking()
    {
        _statusText.Text = "Checking what can come over...";
        _statusRow.IsVisible = true;
        _statusDot.IsVisible = false;
        SetPrimaryText("Checking...");
        _primaryIcon.IsVisible = false;
        UpdateFooterEnabled();
    }

    // ---------------------------------------------------------------- 2a. choose what to import: the grid

    private void ShowGrid()
    {
        if (_scanning || _model.Setups.Count == 0 || _autoStart) return;
        _model.UseGrid(_picked);
        ShowPane(Pane.Grid);
        RenderGrid();
        PrefetchPlan();
    }

    // The picked launcher's row, a tick per thing, version tabs when there is more than one version; the heads sit
    // over the ticks (the row's 12 px inset and its 504 px tick block), then the "Pick single setups" link.
    private void RenderGrid()
    {
        if (_model.GridModel is not { } grid || _pane != Pane.Grid) return;
        var page = new StackPanel();

        if (grid.ShowTabs)
        {
            var tabs = new WrapPanel { Margin = new Thickness(0, 0, 0, 6) };
            foreach (var tab in grid.Tabs)
            {
                var selected = tab == grid.Tab;
                var title = ImportGridModel.TabTitle(tab);
                var label = Ui.Text(title, TypeScale.Caption, FontWeight.SemiBold);
                var b = new Pressable(Look.Plain, label, "Minecraft " + title)
                {
                    Name = "tab-" + tab, MinHeight = Sizes.ControlSm, Padding = new Thickness(12, 0), BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(Radius.Md), Margin = new Thickness(0, 0, 8, 8),
                };
                label.VerticalAlignment = VerticalAlignment.Center;
                void Paint(Pressable p)
                {
                    var hot = p.IsHovered || p.ForceHover;
                    p.Background = selected ? Palette.AccentSoft : Palette.Card;
                    p.BorderBrush = selected ? Palette.AccentLine : hot ? Palette.AccentLine : Palette.Line;
                    label.Foreground = selected ? Palette.Accent : hot ? Palette.Text : Palette.TextDim;
                }
                b.StateChanged += Paint;
                Paint(b);
                var key = tab;
                b.Click += (_, _) =>
                {
                    grid.SetTab(key);
                    RenderGrid();
                };
                tabs.Children.Add(b);
            }
            page.Children.Add(tabs);
        }

        var heads = new Grid { ColumnDefinitions = new ColumnDefinitions("*,504"), Margin = new Thickness(12, 0, 12, 4) };
        heads.Children.Add(Ui.Eyebrow("LAUNCHER", Palette.TextDim).Align(HorizontalAlignment.Left, VerticalAlignment.Bottom).At(0));
        var headCells = new UniformGrid { Rows = 1 };
        foreach (var c in ImportGridModel.Columns)
        {
            var h = Ui.Wrap(Ui.Eyebrow(ImportGridModel.ColumnTitle(c).ToUpperInvariant(), Palette.TextDim));
            h.TextAlignment = TextAlignment.Center;
            h.HorizontalAlignment = HorizontalAlignment.Center;
            h.VerticalAlignment = VerticalAlignment.Bottom;
            headCells.Children.Add(h);
        }
        heads.Children.Add(headCells.At(1));
        page.Children.Add(heads);

        var rows = new StackPanel { Spacing = 4 };
        foreach (var kind in grid.Rows())
        {
            var title = ImportFlowModel.LauncherTitle(kind);
            var name = Ui.Text(title, TypeScale.Body, FontWeight.Bold);
            name.TextTrimming = TextTrimming.CharacterEllipsis;
            var caption = Ui.Caption(grid.RowCaption(kind), Palette.TextDim);
            caption.TextTrimming = TextTrimming.CharacterEllipsis;
            var who = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*"), MinWidth = 172 };
            who.Children.Add(ImportParts.Logo(kind, 28, title).Margin(0, 0, 10, 0).At(0));
            who.Children.Add(Ui.Column(0, name, caption).Align(HorizontalAlignment.Stretch).At(1));
            var cells = new UniformGrid { Rows = 1, Width = 504 };
            foreach (var col in ImportGridModel.Columns)
            {
                var state = grid.Cell(kind, col);
                var cellName = $"{title}: {ImportGridModel.ColumnTitle(col)}";
                Control cell;
                if (!state.Enabled)
                {
                    cell = new Border
                    {
                        Width = 12, Height = 2, Background = Palette.TextDim, CornerRadius = new CornerRadius(1),
                        HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
                    };
                    var holder = new Border { Background = Brushes.Transparent, Height = 28, Child = cell };
                    if (state.Tip is { } tip) ToolTip.SetTip(holder, tip);
                    cells.Children.Add(holder);
                    continue;
                }
                var tick = new Tick(cellName) { Name = $"cell-{kind}-{col}", State = state.On, IsEnabled = Allowed && !_autoStart, HorizontalAlignment = HorizontalAlignment.Center };
                if (state.Tip is { } t) ToolTip.SetTip(tick, t);
                var (k, c) = (kind, col);
                tick.Click += (_, _) =>
                {
                    grid.Toggle(k, c);
                    RenderGrid();
                    PrefetchPlan();
                };
                cells.Children.Add(tick);
            }
            var line = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
            line.Children.Add(who.At(0));
            line.Children.Add(cells.At(1));
            rows.Children.Add(new Border
            {
                Background = Palette.Card, CornerRadius = new CornerRadius(Radius.Lg), Padding = new Thickness(12, 6), Child = line,
            });
        }
        page.Children.Add(rows);
        var single = ImportParts.Link("Pick single setups", "Tick setups one by one instead");
        single.Look = Look.Secondary;
        single.Name = "grid-single";
        single.HorizontalAlignment = HorizontalAlignment.Right;
        single.Margin = new Thickness(0, 6, 0, 0);
        single.Click += (_, _) => ShowPick();
        page.Children.Add(single);
        SetBody(page);

        SetHeader("Choose what to import",
            _picked is { } from ? $"Tick what to bring over from {ImportFlowModel.LauncherTitle(from)}." : "Tick what to bring over.", compact: true);
        SetFooter(GridNote(grid), null, null, _autoStart ? "Checking..." : "Next", ImportFromGrid);
        if (_autoStart) ShowChecking();
    }

    private static string GridNote(ImportGridModel grid)
    {
        if (grid.BringsNothing) return "Nothing is ticked yet";
        return grid.SettingsLauncher is { } kind
            ? $"Settings from **{ImportFlowModel.LauncherTitle(kind)}**"
            : "Origin keeps its own settings";
    }

    // ---------------------------------------------------------------- 2b. pick single setups

    private void ShowPick()
    {
        if (_scanning || _model.Setups.Count == 0 || _autoStart) return;
        if (_pane is Pane.Launchers or Pane.Grid) _pickFrom = _pane;
        _model.UseTicks();
        _model.SetScope(_picked); // one source: only the picked launcher's setups are listed and can go
        ShowPane(Pane.Pick);
        RenderPick();
    }

    // A card per launcher: its tick, logo, name and counts; under it a row per setup (tick, name, what it holds, its
    // Minecraft version, the arrow to review it on its own).
    private void RenderPick()
    {
        if (_pane != Pane.Pick) return;
        var groups = _model.Tiles(false).Where(t => t.State == TileState.Found && (_picked == null || t.Kind == _picked)).ToList();
        var page = new StackPanel { Spacing = 16 };
        foreach (var tile in groups)
        {
            var mine = _model.Setups.Where(s => s.Kind == tile.Kind).ToList();
            var parts = new List<string> { $"{mine.Count(_model.IsGoing)} of {mine.Count} selected" };
            if (_model.Counted(mine))
            {
                var worlds = _model.Total(mine, ImportCategories.Worlds);
                var mods = _model.Total(mine, ImportCategories.Mods);
                if (worlds > 0) parts.Add(ImportLogic.Plural(worlds, "world", "worlds"));
                if (mods > 0) parts.Add(ImportLogic.Plural(mods, "mod", "mods"));
            }
            var groupTick = new Tick("Tick or untick every setup")
            {
                Name = "group-" + tile.Kind, State = _model.GoingState(mine), IsEnabled = Allowed && !_autoStart && mine.Any(_model.CanGo),
                Margin = new Thickness(-6, 0, 6, 0),
            };
            ToolTip.SetTip(groupTick, "Tick or untick every setup");
            groupTick.Click += (_, _) =>
            {
                var on = _model.GoingState(mine) != true;
                foreach (var setup in mine) _model.SetGoing(setup, on);
                RenderPick();
                PrefetchPlan();
            };
            var head = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,Auto,*"), Margin = new Thickness(0, 0, 0, 10) };
            head.Children.Add(groupTick.At(0));
            head.Children.Add(ImportParts.Logo(tile.Kind, 36, tile.Title).Margin(0, 0, 12, 0).At(1));
            head.Children.Add(Ui.Column(0, Ui.Text(tile.Title, TypeScale.Body, FontWeight.Bold), Ui.Caption(string.Join(" - ", parts), Palette.TextDim))
                .Align(HorizontalAlignment.Left).At(2));

            var card = new StackPanel { Spacing = 4 };
            card.Children.Add(head);
            foreach (var setup in mine) card.Children.Add(PickRow(setup));
            page.Children.Add(new Border
            {
                Background = Palette.Card, CornerRadius = new CornerRadius(Radius.Lg), Padding = new Thickness(16, 14, 16, 12), Child = card,
            });
        }
        SetBody(page);

        SetHeader("Pick single setups",
            "Tick the setups to bring. Each one goes into Origin's own folder for its Minecraft version, its mods into its own profile. Setups on a version Origin can't run yet are listed, but stay out.");
        SetFooter(ReadyNote(_model.GoingSetups().Where(_model.InScopeOf).ToList(), "selected"), null, null,
            _autoStart ? "Checking..." : "Next", ImportNow);
        if (_autoStart) ShowChecking();
    }

    private Pressable PickRow(DetectedInstance setup)
    {
        var canTick = _model.CanGo(setup);
        var id = setup.BulkId();
        var tick = new Tick("Include in import")
        {
            Name = "pick-" + id, State = _model.IsGoing(setup), IsEnabled = canTick && Allowed && !_autoStart, Margin = new Thickness(-4, 0, 8, 0),
        };
        ToolTip.SetTip(tick, "Include in import");
        tick.Click += (_, _) => TogglePick(setup);

        var name = Ui.Text(setup.Name, TypeScale.Body, FontWeight.Bold, canTick ? Palette.Text : Palette.TextDim);
        name.TextTrimming = TextTrimming.CharacterEllipsis;
        var caption = Ui.Wrap(Ui.Caption(PickCaption(setup), Palette.TextDim));
        var words = Ui.Column(0, name, caption).Align(HorizontalAlignment.Stretch);
        var badge = Ui.Badge(setup.McVersion ?? "?", Palette.Card, Palette.TextDim);
        badge.Opacity = canTick ? 1 : 0.6;
        var review = Ui.IconButton("Chevron", "Review this setup on its own", Sizes.ControlSm);
        if (review.Child is Control glyph) glyph.RenderTransform = new RotateTransform(-90);
        review.Name = "review-" + id;
        review.Click += (_, _) => OpenSingle(setup);

        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto,Auto"), MinHeight = 36 };
        grid.Children.Add(tick.At(0));
        grid.Children.Add(words.At(1));
        grid.Children.Add(badge.Margin(12, 0, 4, 0).At(2));
        grid.Children.Add(review.At(3));
        words.Opacity = canTick ? 1 : 0.6;

        var row = new Pressable(Look.Plain, grid, setup.Name)
        {
            Name = "row-" + id, Padding = new Thickness(12, 6, 6, 6), CornerRadius = new CornerRadius(Radius.Md), BorderThickness = new Thickness(1),
        };
        ToolTip.SetTip(row, canTick ? "Include in import" : "Pick a version for this setup");
        void Paint(Pressable p)
        {
            var hot = p.IsHovered || p.ForceHover;
            p.Background = Palette.Bg;
            p.BorderBrush = hot ? Palette.LineStrong : Brushes.Transparent;
        }
        row.StateChanged += Paint;
        Paint(row);
        row.Click += (_, _) =>
        {
            if (canTick) TogglePick(setup);
            else OpenSingle(setup);
        };
        return row;
    }

    // "23 mods, 3 worlds" once counted; a setup Origin can't run says why instead.
    private string PickCaption(DetectedInstance setup)
    {
        if (!_model.CanGo(setup))
            return ImportFlowModel.KeyOf(setup) == ImportFlowModel.UnknownKey
                ? "Origin couldn't tell which Minecraft version this is"
                : $"Origin can't run {setup.McVersion} yet";
        if (!_model.Counted(new[] { setup })) return "Counting...";
        var line = _model.ContentsLine(new[] { setup });
        return string.IsNullOrEmpty(line) ? "Settings only" : line;
    }

    private void TogglePick(DetectedInstance setup)
    {
        if (!Allowed || _autoStart || !_model.CanGo(setup)) return;
        _model.SetGoing(setup, !_model.IsGoing(setup));
        RenderPick();
        PrefetchPlan();
    }

    // "What to bring" (the header button): what each version brings, and one row per Minecraft version.
    private void Bring()
    {
        if (_scanning || _model.Setups.Count == 0 || _autoStart) return;
        _versionsFrom = _pane;
        OpenLauncher(_picked);
    }

    private void OpenLauncher(SourceKind? kind)
    {
        if (_scanning || _model.Setups.Count == 0) return;
        _model.SetScope(kind);
        ShowVersions();
    }
}

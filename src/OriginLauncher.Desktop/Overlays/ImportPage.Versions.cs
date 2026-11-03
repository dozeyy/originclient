using System.Globalization;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Sync;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// What to bring: every version (Versions), one version (Version), whose settings a version uses (Chooser), and the
// "what to bring" switches every one of those pages shows.
internal sealed partial class ImportPage
{
    // ---------------------------------------------------------------- 2. versions

    private void ShowVersions()
    {
        if (_pane != Pane.Versions) ShowPane(Pane.Versions);
        RenderVersions();
        PrefetchPlan();
    }

    // WHAT TO BRING (the switches for every version), VERSIONS (select-all, then one row per version Origin can run),
    // CAN'T COME OVER (the rest, gold).
    private void RenderVersions()
    {
        if (_pane != Pane.Versions) return;
        var versions = _model.Versions();
        var supported = versions.Where(v => v.Supported).ToList();
        var selected = _model.SelectedVersionCount;
        var page = ImportRows.List();

        page.Children.Add(ImportParts.Section("WHAT TO BRING"));
        page.Children.Add(Bar(AllBar));
        page.Children.Add(ImportParts.Section("VERSIONS", supported.Count));
        if (supported.Count == 0)
        {
            page.Children.Add(ImportRows.Hint("None of these versions can come over as they are. Open one below to pick a version for it."));
        }
        else
        {
            var all = selected == 0 ? false : selected == supported.Count ? true : (bool?)null;
            var caption = selected == supported.Count
                ? (supported.Count == 1 ? "The one version" : $"All {supported.Count} versions")
                : $"{selected} of {supported.Count} versions";
            var tick = VersionTick("tick-all", "Import from all versions", all, ToggleAllVersions);
            var row = ImportRows.Button(ImportRows.Line(ImportRows.Words("Import from all versions", caption), tick), "Import from all versions",
                "Tick or untick every version");
            row.Name = "row-all";
            row.Click += (_, _) => ToggleAllVersions();
            page.Children.Add(row);
            foreach (var version in supported) page.Children.Add(VersionRow(version));
        }

        var cannot = versions.Where(v => !v.Supported).ToList();
        if (cannot.Count > 0)
        {
            page.Children.Add(ImportParts.Section("CAN'T COME OVER", cannot.Count, gold: true));
            foreach (var version in cannot) page.Children.Add(VersionRow(version));
        }
        SetBody(page);

        SetHeader(_model.Scope == null ? "What to bring" : ScopeTitle, _model.Summary(_model.InScope.ToList()));
        SetFooter(supported.Count == 0 ? null : $"{selected} of {ImportLogic.Plural(supported.Count, "version", "versions")}",
            null, null, "Review", () => OpenReview());
    }

    // One Minecraft version: the row opens it (what it brings, its setups); the tick box takes it in or out.
    private Pressable VersionRow(VersionEntry version)
    {
        var caption = _model.VersionCaption(version);
        var contents = _model.ContentsLine(version.Setups);
        Tick? tick = null;
        string? warning;
        if (version.Supported)
        {
            var ticked = _model.IsSelected(version) && _model.Going(version).Count > 0;
            warning = ticked && _model.Winner(version).NeedsChoice ? PickSettings : null;
            var key = version.Key;
            tick = VersionTick("tick-" + key, $"Import Minecraft {version.Title}", ticked, () =>
            {
                if (!Allowed) return;
                var now = _model.Version(key)!;
                SetVersionOn(key, !(_model.IsSelected(now) && _model.Going(now).Count > 0));
                RenderVersions();
                PrefetchPlan();
            });
        }
        else
        {
            warning = version.Reason;
        }
        var words = ImportRows.Words(version.Title, caption, contents, warning, dimmed: !version.Supported);
        var row = ImportRows.Button(ImportRows.Line(words, tick, ImportParts.Go()), $"Minecraft {version.Title}, {caption}",
            version.Supported ? $"Choose what {version.Title} brings" : $"See the setups on {version.Title}");
        row.Name = "row-" + version.Key;
        var k = version.Key;
        row.Click += (_, _) => OpenVersion(k);
        return row;
    }

    private Tick VersionTick(string name, string accessible, bool? state, Action toggle)
    {
        var tick = new Tick(accessible, small: true) { Name = name, State = state, IsEnabled = Allowed };
        ToolTip.SetTip(tick, name == "tick-all" ? "Tick or untick every version" : "Include in import");
        tick.Click += (_, _) => toggle();
        return tick;
    }

    private void ToggleAllVersions()
    {
        if (!Allowed) return;
        var supported = _model.Versions().Count(v => v.Supported);
        var on = _model.SelectedVersionCount == 0 || _model.SelectedVersionCount != supported;
        _model.SetAllVersions(on);
        if (on)
            foreach (var version in _model.Versions().Where(v => v.Supported && _model.Going(v).Count == 0))
                foreach (var setup in version.Setups) _model.SetTicked(setup, true);
        RenderVersions();
        PrefetchPlan();
    }

    // A version in or out. In with none of its setups ticked: all of them come back (a tick means "bring it").
    private void SetVersionOn(string key, bool on)
    {
        _model.SetSelected(key, on);
        if (on && _model.Version(key) is { } version && _model.Going(version).Count == 0)
            foreach (var setup in version.Setups) _model.SetTicked(setup, true);
    }

    // ---------------------------------------------------------------- what to bring (the switches)

    // The switches of one bar: AllBar (every version at once), "v|1.21.1" (the Version page) or "s|1.21.1" (one setup on
    // its own: the same choice as its version's, one choice per version).
    private Border Bar(string id)
    {
        var setups = BarSetups(id);
        var key = BarVersion(id);
        var wrap = new WrapPanel();
        foreach (var category in CategoryFilter.Each)
        {
            var label = CategoryFilter.Name(category);
            var count = _model.CountText(setups, category);
            var enabled = _model.Has(setups, category);
            bool? on = key == null ? _model.BringsAll(category) : (_model.BringFor(key) & category) != 0;
            var lower = label.ToLowerInvariant();
            var tip = !enabled ? $"No {lower} to bring"
                : on == null ? $"Some versions bring {lower}. Press to bring them for all."
                : on == true ? $"Bringing {lower}. Press to leave them out."
                : $"Leaving {lower} out. Press to bring them.";
            var chip = ImportRows.Chip(label, count, on, enabled && Allowed, tip);
            chip.Name = $"chip-{id}-{category}";
            var c = category;
            chip.Click += (_, _) => ChipPressed(id, c, on);
            wrap.Children.Add(chip);
        }
        var card = ImportRows.Static(wrap);
        card.Padding = new Thickness(10, 10, 4, 4);
        return card;
    }

    private static string? BarVersion(string id) => id == AllBar ? null : id[2..];

    private IReadOnlyCollection<DetectedInstance> BarSetups(string id)
    {
        if (id.StartsWith("s|", StringComparison.Ordinal)) return _single == null ? Array.Empty<DetectedInstance>() : new[] { _single };
        if (BarVersion(id) is { } key)
        {
            if (_model.Version(key) is not { } version) return Array.Empty<DetectedInstance>();
            var ticked = version.Setups.Where(_model.IsTicked).ToList();
            return ticked.Count > 0 ? ticked : version.Setups;
        }
        var going = _model.AllGoing();
        return going.Count > 0 ? going : _model.Versions().Where(v => v.Supported).SelectMany(v => v.Setups).ToList();
    }

    private void ChipPressed(string id, ImportCategories category, bool? wasOn)
    {
        if (!Allowed) return;
        var on = wasOn != true; // mixed (some versions) -> on for all
        if (BarVersion(id) is { } key) _model.SetBring(key, category, on);
        else _model.SetBringAll(category, on);
        if (id.StartsWith("s|", StringComparison.Ordinal))
        {
            RenderSingle();
            _ = ReplanSingleAsync();
            return;
        }
        RefreshCurrent();
        PrefetchPlan();
    }

    // ---------------------------------------------------------------- 2b. one version

    private void OpenVersion(string key)
    {
        if (_model.Version(key) is null) return;
        _versionKey = key;
        ShowPane(Pane.Version);
        RenderVersion();
    }

    // Supported: WHAT TO BRING for this version, then SETUPS (a note on whose settings, a row per setup). Not supported:
    // why, then its setups dimmed (a press opens one on its own, where another version can be picked).
    private void RenderVersion()
    {
        if (_versionKey == null || _model.Version(_versionKey) is not { } version || _pane != Pane.Version) return;
        var page = ImportRows.List();
        SetHeader("Minecraft " + version.Title, ScopeTitle);
        if (!version.Supported)
        {
            page.Children.Add(ImportRows.Hint(version.Version == null
                ? "Origin couldn't tell which Minecraft version these setups are. Open one to pick a version for it."
                : $"Origin can't run Minecraft {version.Version}. Open a setup to bring it into a version Origin has."));
            page.Children.Add(ImportParts.Section("SETUPS", version.Setups.Count));
            foreach (var setup in version.Setups)
                page.Children.Add(SetupRow(setup, ImportLogic.Describe(setup, 0), setup.Problem ?? version.Reason, canTick: false, choice: null));
            SetBody(page);
            SetFooter(null, null, null, null, null);
            return;
        }

        var preview = _model.Winner(version);
        var going = _model.Going(version);
        var selected = _model.IsSelected(version);
        page.Children.Add(ImportParts.Section("WHAT TO BRING"));
        page.Children.Add(Bar("v|" + version.Key));
        page.Children.Add(ImportParts.Section("SETUPS", version.Setups.Count));
        page.Children.Add(ImportRows.Hint(!selected ? "Not part of this import. Press INCLUDE to bring it."
            : going.Count == 0 ? "Tick a setup to bring this version."
            : preview.Winner == null ? "These setups bring packs only: Origin's own settings stay."
            : going.Count == 1 ? $"Everything comes from {preview.Winner.Name}."
            : preview.NeedsChoice ? $"Settings from {preview.Winner.Name} (suggested): press CHANGE to pick. Mods and packs from every ticked setup."
            : $"Settings from {preview.Winner.Name}. Mods and packs from every ticked setup."));
        foreach (var setup in version.Setups)
        {
            var lines = new List<string>();
            var isWinner = preview.Winner != null && preview.Winner.BulkId() == setup.BulkId();
            if (version.Version != null && BulkRules.IsPacksOnly(setup, version.Version)) lines.Add(BulkRules.ForgeCaption);
            if (isWinner && preview.NeedsChoice) lines.Add(PickSettings);
            if (NotSureNote(setup) is { } notSure) lines.Add(notSure);
            page.Children.Add(SetupRow(setup, Evidence(setup), lines.Count == 0 ? null : string.Join("\n", lines), canTick: true,
                choice: isWinner && preview.Participants.Count > 1 ? version.Key : null, ticked: _model.IsTicked(setup)));
        }
        SetBody(page);

        var on = selected && going.Count > 0;
        SetFooter(_model.VersionCaption(version), null, null, on ? "Done" : "Include", () =>
        {
            if (on)
            {
                GoBack();
                return;
            }
            SetVersionOn(version.Key, true);
            RenderVersion();
            PrefetchPlan();
        });
    }

    // One setup: the row opens its own review; CHANGE picks whose settings the version uses; the tick takes it in or out.
    // noTick: Review shows the same row without a tick (what the plan brings is decided).
    private Pressable SetupRow(DetectedInstance setup, string caption, string? warning, bool canTick, string? choice,
        bool ticked = false, bool noTick = false, string? title = null, string? key = null)
    {
        var id = setup.BulkId();
        var name = title ?? setup.Name;
        var words = ImportRows.Words(name, caption, null, warning, dimmed: !canTick);
        Control? change = null;
        if (choice != null)
        {
            var b = Ui.Button("Change", Look.Secondary, height: Sizes.ControlSm);
            b.Name = "change-" + id;
            b.IsEnabled = Allowed;
            b.Margin = new Thickness(6, 0, 0, 0);
            ToolTip.SetTip(b, "Pick whose settings to use");
            Avalonia.Automation.AutomationProperties.SetName(b, $"Pick whose settings to use for {name}");
            var version = choice;
            b.Click += (_, _) => OpenChooser(version);
            change = b;
        }
        Control? tickBox = null;
        if (canTick && !noTick)
        {
            var t = new Tick($"Include {setup.SourceLabel}: {name}", small: true)
            {
                Name = "stick-" + id, State = ticked, IsEnabled = Allowed, Margin = new Thickness(6, 0, 0, 0),
            };
            ToolTip.SetTip(t, "Include in import");
            t.Click += (_, _) =>
            {
                if (!Allowed) return;
                var on = !_model.IsTicked(setup);
                _model.SetTicked(setup, on);
                if (on && _versionKey != null) _model.SetSelected(_versionKey, true);
                RenderVersion();
                PrefetchPlan();
            };
            tickBox = t;
        }
        var line = ImportRows.WithAvatar(setup.Name, ImportRows.Line(words, change, tickBox), dimmed: !canTick);
        var row = ImportRows.Button(line, $"{name}, {caption}",
            canTick ? "Review this setup on its own" : "This setup can't be imported as it is. Press to pick a version.");
        row.Name = "setup-" + (key ?? id);
        row.Click += (_, _) => OpenSingle(setup);
        return row;
    }

    // ---------------------------------------------------------------- whose settings

    private void OpenChooser(string version)
    {
        if (_model.Version(version) is not { } entry) return;
        var preview = _model.Winner(entry);
        if (preview.Participants.Count == 0) return;
        _chooserVersion = version;
        if (_pane is Pane.Version or Pane.Review) _chooserFrom = _pane;

        var ids = new HashSet<string>(preview.Participants.Select(p => p.BulkId()), StringComparer.OrdinalIgnoreCase);
        var ordered = preview.Pick.Options.Where(o => ids.Contains(o.BulkId()))
            .Concat(preview.Participants.Where(p => preview.Pick.Options.All(o => o.BulkId() != p.BulkId())))
            .Select(o => preview.Participants.First(p => p.BulkId() == o.BulkId()))
            .ToList();
        var suggested = preview.Pick.Preselected?.BulkId();
        var page = ImportRows.List();
        foreach (var setup in ordered)
        {
            var isSuggested = suggested == setup.BulkId();
            var picked = preview.Winner?.BulkId() == setup.BulkId();
            var words = ImportRows.Words(setup.Name, (isSuggested ? "Suggested - " : "") + Evidence(setup));
            var row = ImportRows.Button(ImportRows.WithAvatar(setup.Name, words), setup.Name);
            row.Name = "choose-" + setup.BulkId();
            if (picked)
            {
                // The picked one has a 1 px accent frame (like SELECTED on Profiles).
                row.BorderBrush = Palette.Accent;
                row.BorderThickness = new Thickness(1);
            }
            var id = setup.BulkId();
            row.Click += (_, _) =>
            {
                if (_chooserVersion is not { } v) return;
                _model.Choices[v] = id;
                GoBack(); // Review re-plans: the choice is part of its key
            };
            page.Children.Add(row);
        }
        ShowPane(Pane.Chooser);
        SetBody(page);
        SetHeader($"{entry.Title} settings", "Use settings from");
        SetFooter(null, null, null, null, null);
    }

    /// <summary>"Lunar - 38 h played - 9 days ago": the evidence for picking one setup's settings.</summary>
    private static string Evidence(DetectedInstance setup)
    {
        var parts = new List<string> { setup.SourceLabel };
        if (setup.PlaytimeSeconds is > 0) parts.Add(Playtime(setup.PlaytimeSeconds.Value));
        if (setup.LastPlayed is { } last && last > DateTimeOffset.MinValue.AddYears(2)) parts.Add(Ago(last));
        return string.Join(" - ", parts);
    }

    private static string Playtime(long seconds)
    {
        if (seconds < 3600) return Math.Max(1, seconds / 60) + " min";
        var hours = seconds / 3600.0;
        return (hours < 10 ? hours.ToString("0.#", CultureInfo.InvariantCulture) : Math.Round(hours).ToString("0", CultureInfo.InvariantCulture)) + " h";
    }

    private static string Ago(DateTimeOffset when)
    {
        var span = DateTimeOffset.UtcNow - when;
        if (span.TotalMinutes < 2) return "just now";
        if (span.TotalHours < 1) return $"{(int)span.TotalMinutes} min ago";
        if (span.TotalDays < 1) return $"{(int)span.TotalHours} h ago";
        if (span.TotalDays < 2) return "yesterday";
        if (span.TotalDays < 60) return $"{(int)span.TotalDays} days ago";
        return $"{(int)(span.TotalDays / 30)} months ago";
    }
}

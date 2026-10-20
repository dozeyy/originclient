using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;

namespace OriginLauncher.App.UI.Controls;

// The import drawer's words and decisions that need no WPF: plurals, the Import all label, whether a start is
// allowed, and what the Done page says. Pure, so it is linked into OriginLauncher.Tests (ImportDrawerLogicTests).

internal static class ImportLogic
{
    /// <summary>"1 file", "2 files": the one place a count is turned into words.</summary>
    public static string Plural(int count, string one, string many) => count == 1 ? $"1 {one}" : $"{count} {many}";

    public static string GroupTitle(string sourceLabel) => sourceLabel switch
    {
        "Minecraft" => "Minecraft launcher",
        "Prism" => "Prism Launcher",
        "Modrinth" => "Modrinth App",
        "Lunar" => "Lunar Client",
        "Feather" => "Feather Client",
        "Badlion" => "Badlion Client",
        "Folder" => "Picked folders",
        _ => sourceLabel,
    };

    /// <summary>"1.21.1 - 12 mods" (ASCII only; the mod count only once the plan knows it). The loader is not a word here.</summary>
    public static string Describe(DetectedInstance setup, int mods)
    {
        var parts = new List<string>();
        if (setup.McVersion != null) parts.Add(setup.McVersion);
        if (mods > 0) parts.Add(Plural(mods, "mod", "mods"));
        return string.Join(" - ", parts);
    }

    /// <summary>The label and enabled state of the big button on the overview.</summary>
    public static (string Label, bool Enabled) ImportAllButton(bool planning, bool planFailed, bool planAllowsApply,
        int importable, bool refused)
    {
        if (planning) return ("Checking...", false);
        if (planFailed) return ("Check again", !refused);
        // The button says what will actually happen: the number the plan will import (not "all" of what was found).
        var label = importable == 0 ? "Import" : importable == 1 ? "Import 1 setup" : $"Import {importable}";
        return (label, planAllowsApply && importable > 0 && !refused);
    }

    /// <summary>
    /// Whether the row controls (tick boxes, CHANGE) are on: off only while Import is refused (a game starting, an update)
    /// or an import is writing. A BLOCKED plan ("Not enough free disk space") leaves them on, so the player can untick
    /// setups until it fits; Import itself follows the plan (<see cref="ImportAllButton"/>), the notice says why.
    /// </summary>
    public static bool RowControlsAllowed(bool refused, bool applying) => !refused && !applying;

    /// <summary>
    /// What a setup brings, in one short line: "Settings, packs, 6 mods". <paramref name="mods"/> null = the count is not
    /// known (a result line), so mods are named without a number.
    /// </summary>
    public static string RoleCaption(SetupRole role, int? mods)
    {
        var count = mods is > 0 ? Plural(mods.Value, "mod", "mods") : null;
        return role switch
        {
            SetupRole.Full => mods == null ? "Settings, mods, packs" : count == null ? "Settings and packs" : $"Settings, packs, {count}",
            SetupRole.ModsAndPacks => mods == null ? "Mods and packs" : count == null ? "Packs" : $"Packs, {count}",
            _ => "Packs only",
        };
    }

    /// <summary>
    /// A plan line shown as a row's status line: one sentence loses its full stop (rows and Friends carry none). Text with
    /// more than one sentence keeps its punctuation, so nothing is run together.
    /// </summary>
    public static string? StatusLine(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return text;
        var t = text.Trim();
        var body = t.EndsWith('.') && !t.EndsWith("...", StringComparison.Ordinal) ? t[..^1] : t;
        return body.Contains(". ", StringComparison.Ordinal) ? t : body;
    }

    /// <summary>A skipped setup's row caption (no full stop): the short form of the plan's sentence, which Details and Done show.</summary>
    public static string SkipCaption(SkipKind kind) => kind switch
    {
        SkipKind.Unsupported => BulkRules.UnsupportedCaption,
        SkipKind.UnknownVersion => "Version unknown",
        SkipKind.FolderMissing => "Folder not found",
        SkipKind.InsideOrigin => "Already part of Origin",
        SkipKind.SourceRunning or SkipKind.TargetRunning => "Close Minecraft first",
        SkipKind.NotSelected => "Not picked",
        _ => "Left out",
    };

    /// <summary>The square avatar's letter: the first letter or digit of the setup's own name.</summary>
    public static string Initial(string name)
    {
        foreach (var c in name)
            if (char.IsLetterOrDigit(c)) return char.ToUpperInvariant(c).ToString();
        return "?";
    }

    // ---------------------------------------------------------------- the Import page's words

    /// <summary>"IMPORT 12" -> "Import 12", "CHECKING..." -> "Checking...": the page's buttons are sentence case, the engine's labels are caps.</summary>
    public static string Sentence(string text)
    {
        if (string.IsNullOrEmpty(text)) return text;
        var letters = text.Where(char.IsLetter).ToList();
        if (letters.Count == 0 || letters.Any(char.IsLower)) return text; // already mixed case: leave it
        return char.ToUpperInvariant(text[0]) + text[1..].ToLowerInvariant();
    }

    /// <summary>"3 setups", "3 setups and 4 worlds", "17 setups, 54 worlds and 320 mods": the parts joined the way a person says them.</summary>
    public static string JoinParts(IReadOnlyList<string> parts) => parts.Count switch
    {
        0 => "",
        1 => parts[0],
        _ => string.Join(", ", parts.Take(parts.Count - 1)) + " and " + parts[^1],
    };

    /// <summary>A folder path as the player would say it: %LocalAppData% and %AppData% for the folders Windows puts them in.</summary>
    public static string ShortPath(string path, string? appData = null, string? localAppData = null)
    {
        if (string.IsNullOrWhiteSpace(path)) return "";
        appData ??= Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        localAppData ??= Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        // LocalAppData first: it is longer, and on some PCs AppData sits inside it.
        foreach (var (root, token) in new[] { (localAppData, "%LocalAppData%"), (appData, "%AppData%") })
        {
            if (string.IsNullOrEmpty(root)) continue;
            var trimmed = root.TrimEnd('\\', '/');
            if (path.Length > trimmed.Length && path.StartsWith(trimmed, StringComparison.OrdinalIgnoreCase)
                && (path[trimmed.Length] == '\\' || path[trimmed.Length] == '/'))
                return token + path[trimmed.Length..];
        }
        return path;
    }

    /// <summary>
    /// Where a launcher keeps its setups: the deepest folder they share ("...\PrismLauncher\instances" for several), or the
    /// one setup's own folder. Folder names are compared without case, like Windows does.
    /// </summary>
    public static string CommonFolder(IReadOnlyList<string> folders)
    {
        var paths = folders.Where(f => !string.IsNullOrWhiteSpace(f)).Select(f => f.TrimEnd('\\', '/')).ToList();
        if (paths.Count == 0) return "";
        if (paths.Count == 1) return paths[0];
        var split = paths.Select(p => p.Split('\\', '/')).ToList();
        var common = new List<string>();
        for (var i = 0; i < split.Min(s => s.Length); i++)
        {
            var part = split[0][i];
            if (split.Any(s => !string.Equals(s[i], part, StringComparison.OrdinalIgnoreCase))) break;
            common.Add(part);
        }
        // Nothing shared past the drive: the first setup's folder is still a true answer.
        return common.Count <= 1 ? paths[0] : string.Join("\\", common);
    }

    /// <summary>What a press on Import does right now: start, or say why not (and leave nothing half-shown).</summary>
    public enum StartKind { Start, AlreadyRunning, Refused }

    public static StartKind CheckStart(bool isApplying, string? refusal) =>
        isApplying ? StartKind.AlreadyRunning : refusal != null ? StartKind.Refused : StartKind.Start;
}

/// <summary>What the Done page says, whichever way the import ran (one setup or many).</summary>
internal sealed class ImportDoneModel
{
    public bool Succeeded { get; init; }
    public bool Cancelled { get; init; }
    public string? Error { get; init; }
    public string Id { get; init; } = "";
    public int Brought { get; set; }
    public List<(string Version, string Text)> VersionLines { get; } = new();
    public List<(string Title, string? Caption, string Reason)> Lost { get; } = new();

    public static ImportDoneModel FromBulk(BulkImportResult r)
    {
        var m = new ImportDoneModel { Succeeded = r.Succeeded, Cancelled = r.Cancelled, Error = r.Error, Id = r.RunId };
        if (!m.Succeeded) return m;
        // One row per setup: setups are told apart by their id (one folder = one setup), never by a name test, so a setup
        // called "Pack" can never hide "Prism: Pack 2".
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var labels = new HashSet<string>(StringComparer.Ordinal); // every way a known setup is named (its name, "Prism: Name")
        void Know(DetectedInstance setup)
        {
            labels.Add(setup.Name);
            labels.Add(setup.BulkLabel());
        }
        void Lose(DetectedInstance setup, string reason)
        {
            Know(setup);
            if (seen.Add(setup.BulkId())) m.Lost.Add((setup.Name, LostCaption(setup), reason));
        }
        foreach (var version in r.Versions)
        {
            var ok = version.Setups.Where(s => s.Kind is SetupResultKind.Imported or SetupResultKind.ImportedPartly).ToList();
            foreach (var s in ok)
            {
                Know(s.Setup);
                seen.Add(s.Setup.BulkId());
            }
            m.Brought += ok.Count;
            foreach (var s in ok) m.LoseItems(s.LeftOut, s.Setup.BulkLabel());
            if (ok.Count > 0)
            {
                var full = ok.FirstOrDefault(s => s.Role == SetupRole.Full);
                var tail = full != null ? $", settings from {full.Setup.Name}" : ok.All(s => s.Role == SetupRole.PacksOnly) ? ", packs only" : "";
                m.VersionLines.Add((version.Version, ImportLogic.Plural(ok.Count, "setup", "setups") + tail));
            }
            foreach (var s in version.Setups.Where(s => s.Kind is SetupResultKind.Skipped or SetupResultKind.Failed))
                Lose(s.Setup, s.Message ?? "Couldn't come over.");
        }
        // The whole setups the plan skipped (unsupported, unticked, its folder gone), structured.
        foreach (var k in r.Skipped) Lose(k.Setup, k.Reason);
        // A whole setup the engine only reports in its "didn't come over" list (by label: exact, never a part of a name).
        foreach (var item in r.DidntComeOver)
        {
            if (labels.Contains(item.SetupLabel) || !seen.Add("label|" + item.SetupLabel)) continue;
            m.Lost.Add((item.SetupLabel, null, item.Reason));
        }
        return m;
    }

    private static string LostCaption(DetectedInstance setup) =>
        ImportLogic.GroupTitle(setup.SourceLabel) + (setup.McVersion == null ? "" : " - " + setup.McVersion);

    public static ImportDoneModel FromSingle(ImportPlan plan, ImportResult r)
    {
        var m = new ImportDoneModel { Succeeded = r.Succeeded, Cancelled = r.Cancelled, Error = r.Error, Id = r.ImportId };
        if (!m.Succeeded) return m;
        m.Brought = 1;
        m.VersionLines.Add((plan.TargetVersion, $"1 setup, settings from {plan.Source.Name}"));
        m.LoseItems(r.LeftOut, plan.Source.BulkLabel());
        return m;
    }

    // Items an import that finished still had to leave out (a mod an antivirus held, a world deleted since the plan):
    // one row each under the setup they belong to, so a profile missing fabric-api.jar is never a silent surprise.
    private void LoseItems(IReadOnlyList<SummaryLine>? leftOut, string setupLabel)
    {
        foreach (var line in leftOut ?? Array.Empty<SummaryLine>())
            Lost.Add((line.Name, setupLabel, line.Reason ?? "Left out."));
    }
}

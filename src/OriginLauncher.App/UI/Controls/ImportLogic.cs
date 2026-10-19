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
        if (planning) return ("CHECKING...", false);
        if (planFailed) return ("CHECK AGAIN", !refused);
        // The button says what will actually happen: the number the plan will import (not "all" of what was found).
        var label = importable == 0 ? "IMPORT" : importable == 1 ? "IMPORT 1 SETUP" : $"IMPORT {importable}";
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
        return m;
    }
}

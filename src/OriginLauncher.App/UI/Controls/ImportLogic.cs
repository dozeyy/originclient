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
        int importable, bool everythingTicked, bool refused)
    {
        if (planning) return ("CHECKING...", false);
        if (planFailed) return ("CHECK AGAIN", !refused);
        // The button says what will actually happen: the number the plan will import (not "all" of what was found).
        var label = importable == 0 ? "IMPORT" : importable == 1 ? "IMPORT 1 SETUP" : $"IMPORT {importable}";
        return (label, planAllowsApply && importable > 0 && !refused);
    }

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
        var landed = new List<string>();
        foreach (var version in r.Versions)
        {
            var ok = version.Setups.Where(s => s.Kind is SetupResultKind.Imported or SetupResultKind.ImportedPartly).ToList();
            landed.AddRange(ok.Select(s => s.Setup.Name));
            m.Brought += ok.Count;
            if (ok.Count > 0)
            {
                var full = ok.FirstOrDefault(s => s.Role == SetupRole.Full);
                var tail = full != null ? $", settings from {full.Setup.Name}" : ok.All(s => s.Role == SetupRole.PacksOnly) ? ", packs only" : "";
                m.VersionLines.Add((version.Version, ImportLogic.Plural(ok.Count, "setup", "setups") + tail));
            }
            foreach (var s in version.Setups.Where(s => s.Kind is SetupResultKind.Skipped or SetupResultKind.Failed))
                m.Lost.Add((s.Setup.Name, ImportLogic.GroupTitle(s.Setup.SourceLabel) + (s.Setup.McVersion == null ? "" : " - " + s.Setup.McVersion),
                    s.Message ?? "Couldn't come over."));
        }
        // A whole setup the engine only reports in its "didn't come over" list.
        foreach (var item in r.DidntComeOver)
        {
            if (landed.Any(n => item.SetupLabel.Contains(n, StringComparison.OrdinalIgnoreCase))) continue;
            if (m.Lost.Any(l => item.SetupLabel.Contains(l.Title, StringComparison.OrdinalIgnoreCase)
                                || l.Title.Contains(item.SetupLabel, StringComparison.OrdinalIgnoreCase))) continue;
            m.Lost.Add((item.SetupLabel, null, item.Reason));
        }
        return m;
    }

    public static ImportDoneModel FromSingle(ImportPlan plan, ImportResult r)
    {
        var m = new ImportDoneModel { Succeeded = r.Succeeded, Cancelled = r.Cancelled, Error = r.Error, Id = r.ImportId };
        if (!m.Succeeded) return m;
        m.Brought = 1;
        m.VersionLines.Add((plan.TargetVersion, $"1 setup, settings from {plan.Source.Name}"));
        return m;
    }
}

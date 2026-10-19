using System.Text.Json;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Import.Launcher;

// The `bulk` section of the --import-plan dry run: what "import everything" would do with the setups a scan
// found, grouped by Minecraft version. Until the engine's PlanBulkAsync is connected this is a grouping of the
// SCAN (no plans, no file reads): which setups qualify, which are skipped and why, who would win each version's
// settings. It writes names, launcher labels, versions, roles and plain sentences - never a path, never a file's
// contents. A setup name that is itself a path is cut down to its last segment (ImportErrors.Plain).
public static class ImportBulkReport
{
    public const string UnsupportedVersionFormat = "Origin doesn't support Minecraft {0}, so this setup can't come over.";
    public const string ForgeReason = "Forge mods can't come over. Origin runs Fabric, except on 1.8.9.";
    public const string UnknownVersionReason = "Origin couldn't tell which Minecraft version this setup uses, so it can't come over.";

    private const string LegacyForgeVersion = "1.8.9";

    public sealed record SetupLine(string Name, string Launcher, SetupRole Role, string? Note);

    public sealed record GroupLine(string Version, IReadOnlyList<SetupLine> Setups, SetupLine Winner, bool NeedsChoice);

    public sealed record SkipLine(string Name, string Launcher, string? Minecraft, string Reason);

    public sealed record Report(IReadOnlyList<GroupLine> Groups, IReadOnlyList<SkipLine> Skipped);

    /// <summary>Forge and NeoForge cannot run on Origin's Fabric (the legacy 1.8.9 path aside).</summary>
    public static bool IsForgeLike(DetectedInstance setup) =>
        setup.Loader is LoaderKind.Forge or LoaderKind.NeoForge && setup.McVersion != LegacyForgeVersion;

    /// <summary>Groups the scanned setups by version the way the bulk plan will (winner by the version picker), from the scan alone.</summary>
    public static Report Group(IReadOnlyList<DetectedInstance> setups, IReadOnlyCollection<string> supportedTargets)
    {
        var skipped = new List<SkipLine>();
        var qualifying = new List<DetectedInstance>();
        foreach (var s in setups)
        {
            if (string.IsNullOrWhiteSpace(s.McVersion))
                skipped.Add(new SkipLine(Clean(s.Name), s.SourceLabel, null, UnknownVersionReason));
            else if (!supportedTargets.Contains(s.McVersion))
                skipped.Add(new SkipLine(Clean(s.Name), s.SourceLabel, s.McVersion, string.Format(UnsupportedVersionFormat, s.McVersion)));
            else
                qualifying.Add(s);
        }

        var groups = new List<GroupLine>();
        foreach (var byVersion in qualifying
                     .GroupBy(s => s.McVersion!, StringComparer.Ordinal)
                     .OrderByDescending(g => g.Key, Comparer<string>.Create(McVersion.Compare)))
        {
            var members = byVersion.ToList();
            var pick = VersionPicker.Pick(members);
            var winner = pick.Preselected ?? members[0];

            var lines = new List<SetupLine> { Line(winner, SetupRole.Full) };
            foreach (var other in members.Where(m => !ReferenceEquals(m, winner))
                         .OrderByDescending(m => m.LastPlayed ?? DateTimeOffset.MinValue)
                         .ThenBy(m => m.Name, StringComparer.OrdinalIgnoreCase))
                lines.Add(Line(other, IsForgeLike(other) ? SetupRole.PacksOnly : SetupRole.ModsAndPacks));

            groups.Add(new GroupLine(byVersion.Key, lines, lines[0], pick.Mode == PickMode.Choose));
        }
        return new Report(groups, skipped);
    }

    private static SetupLine Line(DetectedInstance s, SetupRole role) =>
        new(Clean(s.Name), s.SourceLabel, role, IsForgeLike(s) ? ForgeReason : null);

    private static string Clean(string name) => ImportErrors.Plain(name);

    /// <summary>Writes the section as a JSON object value named "bulk".</summary>
    public static void Write(Utf8JsonWriter w, Report report)
    {
        w.WriteStartObject("bulk");
        w.WriteString("source", "scan-grouping");
        w.WriteString("note", "Grouped from the scan alone (nothing planned): who would win each version and what is skipped.");

        w.WriteStartArray("groups");
        foreach (var g in report.Groups)
        {
            w.WriteStartObject();
            w.WriteString("version", g.Version);
            w.WriteStartArray("setups");
            foreach (var s in g.Setups)
            {
                w.WriteStartObject();
                w.WriteString("name", s.Name);
                w.WriteString("launcher", s.Launcher);
                w.WriteString("role", s.Role.ToString());
                if (s.Note != null) w.WriteString("note", s.Note);
                w.WriteEndObject();
            }
            w.WriteEndArray();
            w.WriteStartObject("winner");
            w.WriteString("name", g.Winner.Name);
            w.WriteString("launcher", g.Winner.Launcher);
            w.WriteEndObject();
            w.WriteBoolean("needsChoice", g.NeedsChoice);
            w.WriteEndObject();
        }
        w.WriteEndArray();

        w.WriteStartArray("skipped");
        foreach (var s in report.Skipped)
        {
            w.WriteStartObject();
            w.WriteString("name", s.Name);
            w.WriteString("launcher", s.Launcher);
            if (s.Minecraft != null) w.WriteString("minecraft", s.Minecraft);
            w.WriteString("reason", s.Reason);
            w.WriteEndObject();
        }
        w.WriteEndArray();
        w.WriteEndObject();
    }

    /// <summary>
    /// The file written when the dry run fails: the plain reason only (paths cut to names by ImportErrors.Plain),
    /// never the raw exception message, never a stack.
    /// </summary>
    public static string ErrorJson(Exception ex) =>
        JsonSerializer.Serialize(new { error = ImportErrors.Plain(ex) });
}

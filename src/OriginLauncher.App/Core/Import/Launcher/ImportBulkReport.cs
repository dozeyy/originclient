using System.Text.Json;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Import.Launcher;

// The `bulk` section of the --import-plan dry run: what "import everything" would do with the setups a scan
// found, grouped by Minecraft version. Normally the ENGINE's plan (WriteEngine: ImportEngine.PlanBulkAsync, the
// drawer's own call); if that fails, a grouping of the SCAN (Group + Write: no plans, no file reads). It writes
// names, launcher labels, versions, roles, counts and plain sentences - never a path, never a file's contents.
// A setup name that is itself a path is cut down to its last segment (ImportErrors.Plain).
public static class ImportBulkReport
{
    // The sentences are BulkRules' (one source per message): the report says exactly what the plan and summary say.
    public const string ForgeReason = BulkRules.ForgeNote;
    public const string UnknownVersionReason = BulkRules.UnknownVersionReason;

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
                skipped.Add(new SkipLine(Clean(s.Name), s.SourceLabel, s.McVersion, BulkRules.UnsupportedReason(s.McVersion)));
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

    /// <summary>
    /// Writes the engine's bulk plan as the JSON object "bulk": per version its target, winner, whether the player is
    /// asked to pick, each setup's role, note, version certainty and counts; then every skipped setup with its kind and
    /// reason, the run's blockers and notes. Names pass through ImportErrors.Plain; denied file names never appear.
    /// </summary>
    public static void WriteEngine(Utf8JsonWriter w, BulkImportPlan plan, CredentialGuard guard)
    {
        w.WriteStartObject("bulk");
        w.WriteString("source", "engine");
        w.WriteString("note", "ImportEngine.PlanBulkAsync for every setup found (selection: all), placeholder account. Nothing applied.");
        w.WriteBoolean("canApply", plan.CanApply);
        w.WriteNumber("bytesToCopy", plan.BytesToCopy);
        w.WriteStartArray("blocked");
        foreach (var b in plan.Blocked) w.WriteStringValue(Clean(b));
        w.WriteEndArray();
        w.WriteStartArray("notes");
        foreach (var n in plan.Notes) w.WriteStringValue(Clean(n));
        w.WriteEndArray();

        w.WriteStartArray("groups");
        foreach (var g in plan.Groups)
        {
            w.WriteStartObject();
            w.WriteString("version", g.Version);
            w.WriteStartObject("winner");
            w.WriteString("name", Clean(g.Winner.Name));
            w.WriteString("launcher", g.Winner.SourceLabel);
            w.WriteString("setupId", g.Winner.SetupId);
            w.WriteEndObject();
            w.WriteBoolean("needsChoice", g.NeedsChoice);
            w.WriteBoolean("carriesSettings", g.CarriesSettings);
            if (g.GameFiles is { } files)
            {
                w.WriteStartObject("gameFiles");
                w.WriteNumber("reused", files.ReusedCount);
                w.WriteNumber("toDownload", files.ToDownloadCount);
                w.WriteBoolean("metadataUnavailable", files.MetadataUnavailable);
                w.WriteEndObject();
            }
            w.WriteStartArray("setups");
            foreach (var s in g.Setups)
            {
                w.WriteStartObject();
                w.WriteString("name", Clean(s.Setup.Name));
                w.WriteString("launcher", s.Setup.SourceLabel);
                w.WriteString("setupId", s.Setup.SetupId);
                w.WriteString("role", s.Role.ToString());
                if (s.Note != null) w.WriteString("note", Clean(s.Note));
                w.WriteBoolean("versionUncertain", s.VersionUncertain);
                w.WriteNumber("mods", s.Plan.Mods.Count(m => m.IsCopied));
                w.WriteNumber("ops", s.Plan.Ops.Count(o => !o.Fallback));
                w.WriteNumber("bytesToCopy", s.Plan.BytesToCopy);
                w.WriteStartArray("needsAttention");
                foreach (var l in s.Plan.Lines.Where(l => l.Section == SummarySection.NeedsAttention && !guard.IsDenied(l.Name)))
                    w.WriteStringValue(Clean(l.Reason == null ? l.Name : $"{l.Name}: {l.Reason}"));
                w.WriteEndArray();
                w.WriteEndObject();
            }
            w.WriteEndArray();
            w.WriteEndObject();
        }
        w.WriteEndArray();

        w.WriteStartArray("skipped");
        foreach (var k in plan.Skipped)
        {
            w.WriteStartObject();
            w.WriteString("name", Clean(k.Setup.Name));
            w.WriteString("launcher", k.Setup.SourceLabel);
            w.WriteString("setupId", k.Setup.SetupId);
            if (k.Setup.McVersion != null) w.WriteString("minecraft", k.Setup.McVersion);
            w.WriteString("kind", k.Kind.ToString());
            w.WriteString("reason", Clean(k.Reason));
            w.WriteEndObject();
        }
        w.WriteEndArray();
        w.WriteEndObject();
    }

    /// <summary>Writes the scan-only section as a JSON object value named "bulk" (the fallback when the engine's plan failed).</summary>
    public static void Write(Utf8JsonWriter w, Report report, string? engineError = null)
    {
        w.WriteStartObject("bulk");
        w.WriteString("source", "scan-grouping");
        w.WriteString("note", "Grouped from the scan alone (nothing planned): who would win each version and what is skipped.");
        if (engineError != null) w.WriteString("engineError", engineError);

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

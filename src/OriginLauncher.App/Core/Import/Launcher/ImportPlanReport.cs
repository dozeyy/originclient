using System.IO;
using System.Text;
using System.Text.Json;

namespace OriginLauncher.App.Core.Import.Launcher;

// `OriginLauncher.App.exe --import-plan <out.json>`: a read-only dry run of the import against this PC.
// No window, no EnsureScaffold, no Origin folder is created or written: it scans the other launchers,
// lets the version picker choose, plans the picked instance for a placeholder account (the accounts file
// is never opened) and writes ONLY the one JSON file. Plan never applies.
//
// What goes in the file: instances found (name, launcher, Minecraft version, loader, how certain the detector is and
// why not, the other launcher names for the same folder, the folder-only setup id - a hash, never the path), the
// picker's choice, counts and statuses of the plan, the plan's lines (name + reason), the mods' outcomes, and the
// engine's real bulk plan ("import everything": groups, winners, roles, skipped + why).
// What never does: file contents, folder paths, and any file whose NAME the credential guard denies -
// those appear once, by name only, under skippedForSafety.
public static class ImportPlanReport
{
    /// <summary>Exit codes: 0 written, 2 refused (the file would land inside Origin's data folder), 1 failed.</summary>
    public static int Run(string outPath)
    {
        // The one checked path: every write below (the report AND a failure note) goes to this, never to the raw argument.
        string full;
        try
        {
            full = Path.GetFullPath(outPath);
        }
        catch (Exception)
        {
            return 1;
        }
        var originRoot = Path.TrimEndingDirectorySeparator(OriginPaths.Root) + Path.DirectorySeparatorChar;
        if (full.StartsWith(originRoot, StringComparison.OrdinalIgnoreCase)) return 2;

        try
        {
            var json = Task.Run(() => BuildAsync(CancellationToken.None)).GetAwaiter().GetResult();
            var dir = Path.GetDirectoryName(full);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            File.WriteAllText(full, json, new UTF8Encoding(false));
            return 0;
        }
        catch (Exception ex)
        {
            // The plain reason only (paths cut to names): never the raw exception message.
            try { File.WriteAllText(full, ImportBulkReport.ErrorJson(ex), new UTF8Encoding(false)); }
            catch (Exception) { /* nothing more to do */ }
            return 1;
        }
    }

    internal static async Task<string> BuildAsync(CancellationToken ct)
    {
        var backend = new LauncherImportBackend(dryRun: true);
        var scan = await backend.ScanAsync(null, ct).ConfigureAwait(false);
        var targets = await backend.GetSupportedTargetsAsync(ct).ConfigureAwait(false);
        var guard = CredentialGuard.Default;
        var pick = VersionPicker.Pick(scan.Instances);

        ImportPlan? plan = null;
        if (pick.Preselected is { } chosen)
            plan = await backend.PlanAsync(chosen, null, new ImportOptions(), null, ct).ConfigureAwait(false);
        var others = new List<ImportPlan>();
        foreach (var instance in scan.Instances
                     .Where(i => !ReferenceEquals(i, pick.Preselected) && i.McVersion != null && targets.Contains(i.McVersion))
                     .Take(3))
            others.Add(await backend.PlanAsync(instance, null, new ImportOptions(), null, ct).ConfigureAwait(false));

        // "Import everything" through the engine itself (the same PlanBulkAsync the drawer calls), for the placeholder
        // account. If it fails, the section falls back to the scan-only grouping and says why.
        Bulk.BulkImportPlan? bulk = null;
        string? bulkError = null;
        try
        {
            bulk = await backend.PlanBulkAsync(Bulk.BulkSelection.All(), new ImportOptions(), null, null, ct).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            bulkError = ImportErrors.Plain(ex);
        }

        using var stream = new MemoryStream();
        using (var w = new Utf8JsonWriter(stream, new JsonWriterOptions { Indented = true }))
        {
            w.WriteStartObject();
            w.WriteString("note", "Dry run: nothing was changed. Planned for a placeholder account; the accounts file was not opened.");
            w.WriteString("generatedUtc", DateTime.UtcNow.ToString("o"));
            w.WriteNumber("scanMilliseconds", (long)scan.Elapsed.TotalMilliseconds);

            w.WriteStartArray("scanNotes");
            foreach (var note in scan.Notes) w.WriteStringValue(note);
            w.WriteEndArray();

            w.WriteStartArray("instances");
            foreach (var i in scan.Instances)
            {
                w.WriteStartObject();
                w.WriteString("launcher", i.SourceLabel);
                w.WriteString("name", i.Name);
                w.WriteString("minecraft", i.McVersion);
                w.WriteString("loader", i.Loader.ToString());
                w.WriteString("confidence", i.Confidence.ToString());
                w.WriteBoolean("pinned", i.Pinned);
                if (i.PlaytimeSeconds is { } secs) w.WriteNumber("playtimeSeconds", secs);
                if (i.LastPlayed is { } last) w.WriteString("lastPlayedUtc", last.UtcDateTime.ToString("o"));
                if (i.Problem != null) w.WriteString("problem", i.Problem);
                w.WriteString("setupId", i.SetupId);
                w.WriteBoolean("versionCertain", i.VersionCertain);
                w.WriteBoolean("loaderCertain", i.LoaderCertain);
                if (i.UncertainReason != null) w.WriteString("uncertainReason", ImportErrors.Plain(i.UncertainReason));
                w.WriteStartArray("aliases");
                foreach (var alias in i.Aliases) w.WriteStringValue(ImportErrors.Plain(alias));
                w.WriteEndArray();
                w.WriteEndObject();
            }
            w.WriteEndArray();

            w.WriteStartObject("pick");
            w.WriteString("mode", pick.Mode.ToString());
            if (pick.Preselected is { } p)
            {
                w.WriteString("launcher", p.SourceLabel);
                w.WriteString("name", p.Name);
                w.WriteString("minecraft", p.McVersion);
            }
            w.WriteEndObject();

            if (plan != null)
            {
                w.WriteStartObject("plan");
                WritePlan(w, plan, guard);
                w.WriteEndObject();
            }

            // The other instances Origin could import as they are (a supported Minecraft version), planned the
            // same way, so one run shows what a typical player would get. At most three.
            w.WriteStartArray("otherPlans");
            foreach (var other in others)
            {
                w.WriteStartObject();
                w.WriteString("instance", other.Source.Name);
                WritePlan(w, other, guard);
                w.WriteEndObject();
            }
            w.WriteEndArray();

            // "Import everything": the engine's bulk plan (groups, winner, roles, skipped + why); scan-only if it failed.
            if (bulk != null) ImportBulkReport.WriteEngine(w, bulk, guard);
            else ImportBulkReport.Write(w, ImportBulkReport.Group(scan.Instances, targets), bulkError);
            w.WriteEndObject();
        }
        return Encoding.UTF8.GetString(stream.ToArray());
    }

    private static void WritePlan(Utf8JsonWriter w, ImportPlan plan, CredentialGuard guard)
    {
        w.WriteString("targetVersion", plan.TargetVersion);
        w.WriteBoolean("canApply", plan.CanApply);
        w.WriteStartArray("blocked");
        foreach (var b in plan.Blocked) w.WriteStringValue(b);
        w.WriteEndArray();

        w.WriteStartObject("counts");
        w.WriteNumber("ops", plan.Ops.Count);
        w.WriteNumber("bytesToCopy", plan.BytesToCopy);
        w.WriteNumber("settingsFilesNew", plan.Created);
        w.WriteNumber("settingsFilesReplaced", plan.Replaced);
        w.WriteNumber("settingsFilesKept", plan.Kept);
        w.WriteNumber("settingsFilesUnchanged", plan.Unchanged);
        foreach (var group in plan.Ops.GroupBy(o => o.Item).OrderBy(g => g.Key))
            w.WriteNumber("ops" + group.Key, group.Count());
        foreach (var group in plan.Mods.GroupBy(m => m.Outcome).OrderBy(g => g.Key))
            w.WriteNumber("mods" + group.Key, group.Count());
        w.WriteEndObject();

        if (plan.GameFiles is { } g)
        {
            w.WriteStartObject("gameFiles");
            w.WriteNumber("reused", g.ReusedCount);
            w.WriteNumber("reusedBytes", g.ReusedBytes);
            w.WriteNumber("toDownload", g.ToDownloadCount);
            w.WriteNumber("toDownloadBytes", g.ToDownloadBytes);
            w.WriteNumber("alreadyPresent", g.AlreadyPresentCount);
            w.WriteBoolean("metadataUnavailable", g.MetadataUnavailable);
            w.WriteEndObject();
        }

        w.WriteStartArray("caches");
        foreach (var c in plan.Caches)
        {
            w.WriteStartObject();
            w.WriteString("kind", c.Kind.ToString());
            w.WriteString("scope", c.Scope.ToString());
            w.WriteString("dimension", c.Dimension);
            w.WriteBoolean("willImport", c.WillImport);
            w.WriteString("reason", c.Reason);
            w.WriteNumber("bytes", c.Bytes);
            w.WriteEndObject();
        }
        w.WriteEndArray();

        w.WriteStartArray("mods");
        foreach (var m in plan.Mods.Where(m => !guard.IsDenied(m.FileName)))
        {
            w.WriteStartObject();
            w.WriteString("file", m.FileName);
            w.WriteString("outcome", m.Outcome.ToString());
            w.WriteString("reason", m.Reason);
            w.WriteEndObject();
        }
        w.WriteEndArray();

        w.WriteStartArray("lines");
        foreach (var l in plan.Lines.Where(l => !guard.IsDenied(l.Name) && !IsSafetyLine(l)))
        {
            w.WriteStartObject();
            w.WriteString("section", l.Section.ToString());
            w.WriteString("kind", l.Kind.ToString());
            w.WriteString("name", l.Name);
            w.WriteString("reason", l.Reason);
            if (l.Bytes > 0) w.WriteNumber("bytes", l.Bytes);
            w.WriteEndObject();
        }
        w.WriteEndArray();

        // Names only, never a path and never a value: files left alone because they look like logins.
        w.WriteStartArray("skippedForSafety");
        foreach (var name in plan.SkippedForSafety) w.WriteStringValue(name);
        w.WriteEndArray();
    }

    private static bool IsSafetyLine(SummaryLine l) =>
        l.Reason != null && l.Reason.StartsWith("Skipped for safety", StringComparison.Ordinal);
}

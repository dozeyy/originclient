using System.IO;
using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.App.Core.Compat;

/// <summary>What Fix Compatibility asks the executor to build: a NEW profile that is the source with the plan applied.</summary>
/// <param name="Version">The Minecraft version of the source and the copy.</param>
/// <param name="Copy">The copy as it will be registered (a fresh id; its name is made unique when it is registered).</param>
/// <param name="SourceModsFolder">Where the player's mods are now (a profile's mods folder, or the version's shared folder).</param>
/// <param name="OnlyFiles">For the shared folder: the file names that are the player's own mods (Origin's jars stay
/// Origin's). Null = every file of the source folder (a profile).</param>
/// <param name="Plan">The planner's steps, against the SOURCE paths; the executor points them at the copy.</param>
/// <param name="Platform">What the copy is checked against.</param>
/// <param name="Notes">Plain lines written to the copy's repair.txt (why each mod is off, what could not be fixed).</param>
public sealed record RepairCopyRequest(
    string Version, ModProfile Copy, string SourceModsFolder, IReadOnlySet<string>? OnlyFiles, RepairPlan Plan,
    CompatPlatform Platform, IReadOnlyList<string> Notes);

public sealed record RepairCopyResult(string? ProfileId, string? ProfileName, string? Error, RepairResult? Steps)
{
    public bool Ok => Error == null;
}

/// <summary>
/// Fix Compatibility's writer. It never changes the player's profile or folder: it builds a COPY and only reads the
/// original (File.Copy), so the original stays byte for byte as it was. Journaled at
/// <c>OriginPaths.RepairJournals/&lt;new id&gt;.json</c>; the order is
///   1. journal "copying"; 2. copy the mods into profiles/&lt;new id&gt;.repairing and apply the plan there;
///   3. rename that folder to profiles/&lt;new id&gt;; 4. journal "moved"; 5. add the profile to settings (and select it);
///   6. remove the journal.
/// A crash anywhere leaves either nothing the launcher knows about (a staging or finished folder no setting names,
/// removed by <see cref="RecoverCopies"/>) or the finished, registered profile. All-or-nothing: if any step fails
/// (offline, a checksum mismatch), nothing is registered and the staging copy is removed.
/// </summary>
public static partial class RepairExecutor
{
    public const string StagingSuffix = ".repairing";
    public const string NotesFile = "repair.txt";

    internal sealed record CopyJournal(string Version, string ProfileId, string Stage);

    /// <param name="profilesDir">profiles folder of <see cref="RepairCopyRequest.Version"/> (OriginPaths.ProfilesDir).</param>
    /// <param name="journalsDir">OriginPaths.RepairJournals.</param>
    /// <param name="update">SettingsStore.Update: the copy is registered through it, nothing else writes settings here.</param>
    public static RepairCopyResult ApplyToCopy(
        RepairCopyRequest request, string profilesDir, string journalsDir,
        Action<Action<LauncherSettings>> update, IDependencyFetcher? fetcher)
    {
        var id = request.Copy.Id;
        if (!ProfileRules.IsValidId(id)) return Failed("The fixed copy has no valid id.");
        var final = Path.Combine(profilesDir, id);
        var staging = final + StagingSuffix;
        var journal = Path.Combine(journalsDir, id + ".json");
        if (Directory.Exists(final) || Directory.Exists(staging)) return Failed("A profile with that id is already there.");

        try
        {
            WriteJournal(journal, new CopyJournal(request.Version, id, "copying"));
            var stagingMods = Path.Combine(staging, "mods");
            var copyError = CopySource(request, stagingMods);
            if (copyError != null) return Abandon(journal, staging, copyError);

            var context = new RepairContext(request.Platform, stagingMods, new[] { stagingMods }, AddedRole: ModRole.Profile);
            var steps = Retarget(request.Plan.Steps, request.SourceModsFolder, stagingMods);
            if (steps == null) return Abandon(journal, staging, "The fix names a mod outside this profile, so nothing was changed.");
            var result = Apply(new RepairPlan(steps, request.Plan.Unfixable), context, fetcher);
            if (!result.AllOk)
                return Abandon(journal, staging, result.Failed.First().Error ?? "A step of the fix failed.");

            File.WriteAllText(Path.Combine(staging, NotesFile), Notes(request, result), new UTF8Encoding(false));
            Directory.Move(staging, final);
            WriteJournal(journal, new CopyJournal(request.Version, id, "moved"));

            string? name = null;
            update(s =>
            {
                if (s.ModProfiles.Any(p => p.Id == id)) return;
                request.Copy.Name = UniqueFixedName(request.Copy.Name, request.Version, s.ModProfiles);
                name = request.Copy.Name;
                s.ModProfiles.Add(request.Copy);
                s.SelectedProfileId = id;
            });
            TryDelete(journal);
            return new RepairCopyResult(id, name ?? request.Copy.Name, null, result);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Where it stopped is in the journal: RecoverCopies (run before the next fix) keeps the copy if settings
            // name it and removes the folders otherwise. Only the staging folder is cleared now.
            ProfileFolders.Delete(staging);
            return Failed("Couldn't make the fixed copy - " + ex.Message);
        }
    }

    /// <summary>
    /// Finishes what an interrupted <see cref="ApplyToCopy"/> left: a copy settings already name is kept (its journal
    /// is removed); any other staging or finished folder from a journal is removed with it. Paths are rebuilt from the
    /// journal's version and id (both validated), never read from it. Never throws for an I/O problem.
    /// </summary>
    /// <returns>How many interrupted repairs were cleaned up.</returns>
    public static int RecoverCopies(string journalsDir, Func<string, string> profilesDirFor, Func<string, bool> isRegistered)
    {
        var cleaned = 0;
        string[] files;
        try { files = Directory.Exists(journalsDir) ? Directory.GetFiles(journalsDir, "*.json") : Array.Empty<string>(); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return 0; }

        foreach (var file in files)
        {
            CopyJournal? entry = null;
            try { entry = JsonSerializer.Deserialize<CopyJournal>(File.ReadAllText(file)); }
            catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException or NotSupportedException) { }

            var id = Path.GetFileNameWithoutExtension(file);
            if (entry == null || entry.ProfileId != id || !ProfileRules.IsValidId(id) || !ProfileRules.IsValidVersion(entry.Version))
            {
                TryDelete(file); // unreadable: whatever folder it named is unnamed by settings, so it is inert
                continue;
            }
            if (!isRegistered(id))
            {
                var final = Path.Combine(profilesDirFor(entry.Version), id);
                if (ProfileFolders.Delete(final + StagingSuffix) != null || ProfileFolders.Delete(final) != null)
                    continue; // still held: try again next time, journal kept
            }
            TryDelete(file);
            cleaned++;
        }
        return cleaned;
    }

    // Points each step at the copy. Null when a step names a file that is not in the source folder.
    private static List<RepairStep>? Retarget(IReadOnlyList<RepairStep> steps, string sourceFolder, string stagingMods)
    {
        var source = Path.GetFullPath(sourceFolder).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var list = new List<RepairStep>(steps.Count);
        foreach (var step in steps)
        {
            switch (step)
            {
                case DisableModStep d:
                    var dir = Path.GetFullPath(Path.GetDirectoryName(d.Mod.Path) ?? "")
                        .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
                    if (!string.Equals(dir, source, StringComparison.OrdinalIgnoreCase)) return null;
                    list.Add(d with { Mod = d.Mod with { Path = Path.Combine(stagingMods, d.Mod.FileName) } });
                    break;
                case AddModStep a:
                    list.Add(a with { DestinationPath = Path.Combine(stagingMods, Path.GetFileName(a.DestinationPath)) });
                    break;
                default:
                    return null;
            }
        }
        return list;
    }

    // Reads the source only. A profile's folder is copied whole (configs inside it too); the shared folder gives only
    // the player's own mod files.
    private static string? CopySource(RepairCopyRequest request, string stagingMods)
    {
        if (request.OnlyFiles == null) return ProfileFolders.CopyMods(request.SourceModsFolder, stagingMods);
        try
        {
            Directory.CreateDirectory(stagingMods);
            foreach (var name in request.OnlyFiles)
            {
                if (Path.GetFileName(name) != name) return "A mod file name was not a plain name, so nothing was copied.";
                var from = Path.Combine(request.SourceModsFolder, name);
                if (!File.Exists(from)) continue;
                var to = Path.Combine(stagingMods, name);
                File.Copy(from, to, overwrite: false);
                File.SetLastWriteTimeUtc(to, File.GetLastWriteTimeUtc(from));
            }
            return null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return "Couldn't copy the mods: " + ex.Message;
        }
    }

    private static string Notes(RepairCopyRequest request, RepairResult result)
    {
        var text = new StringBuilder();
        text.Append("Fixed copy made by Origin's Fix Compatibility on ").Append(DateTime.Now.ToString("yyyy-MM-dd HH:mm"))
            .Append(", Minecraft ").Append(request.Version).Append(".\n");
        text.Append("The profile it was copied from was not changed.\n\n");
        foreach (var done in result.Done)
        {
            text.Append(done.Step switch
            {
                DisableModStep d => $"Turned off {d.Mod.FileName}: {d.Why}",
                AddModStep a => $"Added {a.Offer.FileName} ({a.Offer.Id} {a.Offer.Version}): {a.Why}",
                _ => done.Step.Why,
            }).Append('\n');
        }
        foreach (var line in request.Notes) text.Append(line).Append('\n');
        return text.ToString();
    }

    /// <summary>"Hypixel PvP fixed", then "Hypixel PvP fixed 2"... the first one free for the version.</summary>
    public static string UniqueFixedName(string name, string version, IEnumerable<ModProfile> existing)
    {
        var all = existing.ToList();
        var baseName = name.Trim();
        if (ProfileRules.ValidateName(baseName, version, all) == null) return baseName;
        for (var n = 2; ; n++)
        {
            var suffix = $" {n}";
            var room = Math.Max(1, ProfileRules.MaxNameLength - suffix.Length);
            var candidate = (baseName.Length > room ? baseName[..room].TrimEnd() : baseName) + suffix;
            if (ProfileRules.ValidateName(candidate, version, all) == null) return candidate;
        }
    }

    private static void WriteJournal(string path, CopyJournal entry)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temp = path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(entry));
        File.Move(temp, path, overwrite: true);
    }

    // Removes this repair's own staging folder and journal; the error (when given) is returned to the caller.
    private static RepairCopyResult Abandon(string journal, string staging, string? error)
    {
        if (ProfileFolders.Delete(staging) == null) TryDelete(journal); // else the journal stays for RecoverCopies
        return Failed(error ?? "Couldn't make the fixed copy.");
    }

    private static RepairCopyResult Failed(string error) => new(null, null, error, null);

    private static void TryDelete(string file)
    {
        try { if (File.Exists(file)) File.Delete(file); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* removed next time */ }
    }
}

using System.IO;
using System.IO.Compression;
using System.Text.Json;

namespace OriginLauncher.App.Core.Launch;

// The file-reading half of crash handling (the deciding half is pure: CrashFactsParser + CrashRules + CrashEngine).
// Reads what a stopped game left behind into a CrashEvidence: the launch log the launcher captured, the game's own
// logs/latest.log, the newest crash-reports/crash-*.txt, the JVM's hs_err_pid*.log, and the launch mod set (every jar
// in the folder the game loaded mods from, with its mod id; unreadable jars are kept by file name, since a damaged jar
// must still be matchable). Nothing here writes, uploads or deletes: crash data stays on the PC.
//
// Two loaders: Fabric (every version but 1.8.9, fabric.mod.json) and Forge 1.8.9 (mcmod.info).
public static class CrashAnalyzer
{
    public const int LaunchLogTailBytes = 512 * 1024;
    public const int LatestLogTailBytes = 512 * 1024;
    public const int CrashReportTailBytes = 256 * 1024;
    public const int HsErrTailBytes = 64 * 1024;

    /// <param name="instanceDir">The crashed game's folder (one account's game folder for the version).</param>
    /// <param name="startedUtc">When this game started: only files written since then count (a file from an earlier
    /// session must not explain this one). Null: the last 5 minutes.</param>
    public static CrashEvidence ReadEvidence(string instanceDir, string? launchLogPath, int exitCode,
        bool stoppedByPlayer, bool crashedWhileStarting, int ramMb, int totalPhysicalMb, DateTime? startedUtc = null)
    {
        return new CrashEvidence(
            exitCode, stoppedByPlayer, crashedWhileStarting,
            LaunchLog: TryReadTail(launchLogPath, LaunchLogTailBytes),
            LatestLog: TryReadTail(FreshLatestLog(instanceDir, startedUtc), LatestLogTailBytes),
            CrashReport: TryReadTail(NewestCrashReport(instanceDir, startedUtc), CrashReportTailBytes, fromStart: true),
            HsErr: TryReadTail(NewestHsErr(instanceDir, startedUtc), HsErrTailBytes),
            Mods: ReadMods(instanceDir),
            RamMb: ramMb,
            TotalPhysicalMb: totalPhysicalMb);
    }

    /// <summary>
    /// The launch mod set: exactly the folder this game's launch loaded (GameFolders.LaunchedModsFolder: the shared
    /// folder, mods-shared, mods-origin-only, mods-profile[-id], or 1.8.9's mirrored mods/), so an Origin-only launch
    /// has no added mods to leave out and a profile's own jars can be turned off. A game this launcher run didn't
    /// start (unknown folder) falls back to the version's shared folder plus this account's mods-profile copy.
    /// </summary>
    public static List<CrashModEntry> ReadMods(string instanceDir) =>
        GameFolders.LaunchedModsFolder.For(instanceDir) is { } loaded
            ? ReadModsFrom(loaded)
            : ReadModsFrom(GameFolders.InstanceLayout.ModsDirForGameDir(instanceDir),
                Path.Combine(instanceDir, Profiles.LaunchModFolder.ProfileFolderName));

    // Nested jars (Fabric's jar-in-jar, META-INF/jars/*.jar) are read this many levels deep: Origin's jar -> the
    // mods it carries (1.21.1: Sodium, Iris, Mod Menu, JEI, Simple Voice Chat, ...) -> their own nested modules.
    private const int NestedDepth = 2;

    public static List<CrashModEntry> ReadModsFrom(params string[] modsFolders)
    {
        var list = new List<CrashModEntry>();
        var nested = new List<CrashModEntry>();
        IEnumerable<string> jars;
        try
        {
            jars = modsFolders.Where(Directory.Exists)
                .SelectMany(f => Directory.EnumerateFiles(f, "*.jar")).ToList();
        }
        catch { return list; /* folder unreadable: no mod set, rules degrade to ids only */ }

        foreach (var jar in jars)
        {
            var name = Path.GetFileName(jar);
            var external = IsExternalJar(jar);
            try
            {
                using var zip = ZipFile.OpenRead(jar);
                var ids = new List<string>();
                if (zip.GetEntry("fabric.mod.json") is { } fabric)
                {
                    if (FabricId(fabric) is { } id) ids.Add(id);
                    // The mods inside this jar belong to it: a crash in Origin's built-in Sodium is Origin's (never
                    // "a mod you added" with nothing to turn off), one inside a player's jar is that jar's.
                    foreach (var inner in NestedFabricIds(zip, NestedDepth))
                        nested.Add(new CrashModEntry(inner, name, external));
                }
                else if (zip.GetEntry("mcmod.info") is { } forge)
                {
                    ids.AddRange(ForgeModIds(forge));
                }
                // Code packages only for added mods: Origin's own jars run on every launch, blaming them by stack is noise.
                var packages = external ? CodePackages(zip) : null;
                if (ids.Count == 0) list.Add(new CrashModEntry("", name, external, packages));
                foreach (var id in ids) list.Add(new CrashModEntry(id, name, external, packages));
            }
            catch
            {
                // Corrupt or locked jar: kept by file name so a "damaged file" finding can still name it.
                list.Add(new CrashModEntry("", name, external));
            }
        }
        // After every top-level jar, and only for ids no top-level jar has: a player's own Sodium beside Origin's
        // built-in one is the copy that loaded (DECISIONS 2026-10-25), so lookups by id must find the player's file.
        var known = new HashSet<string>(list.Select(m => m.ModId).Where(id => id.Length > 0), StringComparer.OrdinalIgnoreCase);
        foreach (var entry in nested)
            if (known.Add(entry.ModId)) list.Add(entry);
        return list;
    }

    private static string? FabricId(ZipArchiveEntry fabricModJson)
    {
        using var doc = JsonDocument.Parse(fabricModJson.Open(), new JsonDocumentOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip });
        return doc.RootElement.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.String && id.GetString() is { Length: > 0 } s
            ? s : null;
    }

    // The fabric.mod.json ids of the jars nested in this one, depth levels down. A nested jar that can't be read is
    // skipped (only its own id is lost; the host jar is still listed).
    private static List<string> NestedFabricIds(ZipArchive zip, int depth)
    {
        var ids = new List<string>();
        if (depth <= 0) return ids;
        foreach (var entry in zip.Entries)
        {
            if (!entry.FullName.StartsWith("META-INF/jars/", StringComparison.Ordinal)
                || !entry.FullName.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) continue;
            try
            {
                using var buffer = new MemoryStream();
                using (var source = entry.Open()) source.CopyTo(buffer);
                buffer.Position = 0;
                using var inner = new ZipArchive(buffer, ZipArchiveMode.Read);
                if (inner.GetEntry("fabric.mod.json") is not { } fabric) continue;
                if (FabricId(fabric) is { } id) ids.Add(id);
                ids.AddRange(NestedFabricIds(inner, depth - 1));
            }
            catch (Exception e) when (e is InvalidDataException or JsonException or IOException)
            {
                // damaged nested jar or metadata: skip it
            }
        }
        return ids;
    }

    /// <summary>A jar the player added or may switch off: not one of Origin's own built-in jars, decided by the file
    /// (name shape AND bytes, ModManager.IsManagedFile), so a player's own Sodium is theirs and Simple Voice Chat
    /// can be turned off, as on the Mods page. The same rule CrashRepair.DisableBlamed is given, so a fix is only
    /// ever offered for a jar it can turn off.</summary>
    public static bool IsExternalJar(string jarPath) => !Mods.ModManager.IsManagedFile(jarPath);

    private static HashSet<string> CodePackages(ZipArchive zip) =>
        zip.Entries
            .Where(e => e.FullName.EndsWith(".class", StringComparison.Ordinal) && e.FullName.Contains('/'))
            .Select(e => string.Join('.', e.FullName.Split('/').SkipLast(1)))
            .Where(p => p.Length > 0 && !p.StartsWith("META-INF", StringComparison.Ordinal))
            .ToHashSet(StringComparer.Ordinal);

    // mcmod.info comes in two shapes - a bare array of mod entries, or {"modListVersion": 2, "modList": [...]} - and
    // is hand-written by mod authors, so trailing commas and comments are tolerated.
    private static IEnumerable<string> ForgeModIds(ZipArchiveEntry mcmodInfo)
    {
        using var doc = JsonDocument.Parse(mcmodInfo.Open(), new JsonDocumentOptions
        {
            AllowTrailingCommas = true,
            CommentHandling = JsonCommentHandling.Skip,
        });
        var list = doc.RootElement.ValueKind == JsonValueKind.Object
                   && doc.RootElement.TryGetProperty("modList", out var modList)
            ? modList
            : doc.RootElement;
        if (list.ValueKind != JsonValueKind.Array) return [];
        return list.EnumerateArray()
            .Where(e => e.ValueKind == JsonValueKind.Object
                        && e.TryGetProperty("modid", out var id) && id.ValueKind == JsonValueKind.String)
            .Select(e => e.GetProperty("modid").GetString()!)
            .Where(id => id.Length > 0)
            .ToList();
    }

    internal static string? NewestCrashReport(string instanceDir, DateTime? sinceUtc = null) =>
        NewestRecent(Path.Combine(instanceDir, "crash-reports"), "crash-*.txt", sinceUtc);

    // The JVM's own native-crash dump (hs_err_pid*.log) lands in the game's working folder.
    internal static string? NewestHsErr(string instanceDir, DateTime? sinceUtc = null) =>
        NewestRecent(instanceDir, "hs_err_pid*.log", sinceUtc);

    // logs/latest.log (Minecraft rewrites it all game long, so a live one is always recent).
    internal static string? FreshLatestLog(string instanceDir, DateTime? sinceUtc = null) =>
        NewestRecent(Path.Combine(instanceDir, "logs"), "latest.log", sinceUtc);

    // Only trust a file this game wrote: newer than its start (2 s slack for file-time rounding), or, with no start
    // time, from the last 5 minutes. An older one belongs to a previous session.
    private static string? NewestRecent(string dir, string pattern, DateTime? sinceUtc)
    {
        try
        {
            if (!Directory.Exists(dir)) return null;
            var newest = Directory.EnumerateFiles(dir, pattern)
                .OrderByDescending(File.GetLastWriteTimeUtc)
                .FirstOrDefault();
            if (newest == null) return null;
            var written = File.GetLastWriteTimeUtc(newest);
            var fresh = sinceUtc is { } since
                ? written >= since.AddSeconds(-2)
                : DateTime.UtcNow - written < TimeSpan.FromMinutes(5);
            return fresh ? newest : null;
        }
        catch { return null; }
    }

    // The end of a file, at most maxBytes: a game that ran for hours has a huge log, and the crash is at its end.
    // A crash report is the other way round (the exception and its cause come first), so it is read from the start.
    internal static string? TryReadTail(string? path, int maxBytes, bool fromStart = false)
    {
        try
        {
            if (path == null || !File.Exists(path)) return null;
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            if (stream.Length > maxBytes && !fromStart) stream.Seek(-maxBytes, SeekOrigin.End);
            using var reader = new StreamReader(stream);
            if (!fromStart || stream.Length <= maxBytes) return reader.ReadToEnd();
            var buffer = new char[maxBytes];
            return new string(buffer, 0, reader.ReadBlock(buffer, 0, maxBytes));
        }
        catch { return null; }
    }
}

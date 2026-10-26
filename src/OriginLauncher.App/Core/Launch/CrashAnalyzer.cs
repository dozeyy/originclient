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
            CrashReport: TryReadTail(NewestCrashReport(instanceDir, startedUtc), CrashReportTailBytes),
            HsErr: TryReadTail(NewestHsErr(instanceDir, startedUtc), HsErrTailBytes),
            Mods: ReadMods(instanceDir),
            RamMb: ramMb,
            TotalPhysicalMb: totalPhysicalMb);
    }

    /// <summary>
    /// The launch mod set. Mods live in the version's shared folder (SharedMods), except a mod-profile launch, whose
    /// jars the game loaded from this account's own copy (Core/Profiles/LaunchModFolder): both are read.
    /// </summary>
    public static List<CrashModEntry> ReadMods(string instanceDir) =>
        ReadModsFrom(GameFolders.InstanceLayout.ModsDirForGameDir(instanceDir),
            Path.Combine(instanceDir, Profiles.LaunchModFolder.ProfileFolderName));

    public static List<CrashModEntry> ReadModsFrom(params string[] modsFolders)
    {
        var list = new List<CrashModEntry>();
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
            var external = IsExternalJar(name);
            try
            {
                using var zip = ZipFile.OpenRead(jar);
                var ids = new List<string>();
                if (zip.GetEntry("fabric.mod.json") is { } fabric)
                {
                    using var doc = JsonDocument.Parse(fabric.Open(), new JsonDocumentOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip });
                    if (doc.RootElement.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.String)
                        ids.Add(id.GetString()!);
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
        return list;
    }

    /// <summary>A jar the player added (not Origin's own, not the managed stack it installs). Matches what
    /// Core/Compat/CrashRepair.DisableBlamed refuses to touch, so a fix is only ever offered for a jar it can turn off.</summary>
    public static bool IsExternalJar(string jarFileName) =>
        !Mods.ModManager.IsOriginProvisioned(jarFileName)
        && !jarFileName.Equals("originclient.jar", StringComparison.OrdinalIgnoreCase);

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
    internal static string? TryReadTail(string? path, int maxBytes)
    {
        try
        {
            if (path == null || !File.Exists(path)) return null;
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            if (stream.Length > maxBytes) stream.Seek(-maxBytes, SeekOrigin.End);
            using var reader = new StreamReader(stream);
            return reader.ReadToEnd();
        }
        catch { return null; }
    }
}

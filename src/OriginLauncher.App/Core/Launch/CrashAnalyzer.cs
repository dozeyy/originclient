using System.IO;
using System.IO.Compression;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Launch;

/// <summary>One mod implicated in a crash: its mod id (Fabric's, or Forge's
/// on 1.8.9) and, when it could be mapped, the jar file in the instance's mods
/// folder.</summary>
public sealed record CrashCulprit(string ModId, string? JarFileName, bool IsExternal);

/// <summary>What the analyzer concluded. Culprits is empty when no mod could
/// be blamed with confidence — the UI must then say "unknown" plainly rather
/// than guessing.</summary>
public sealed record CrashAnalysis(
    IReadOnlyList<CrashCulprit> Culprits,
    string Reason,
    string? DetailExcerpt);

// Post-mortem crash blame for a Minecraft boot that died (non-zero exit).
// Pure parsing over evidence the launcher already has: the per-launch
// stdout/stderr log (StartWithLifecycleCapture) and the game's own
// crash-reports/*.txt. No runtime instrumentation. Parsers are ordered from
// most to least reliable, and the honest fallback is "unknown" — a wrong
// accusation would send the player deleting the wrong mod.
//
// Two loaders: Fabric (every version but 1.8.9) and Forge 1.8.9 (FML 8.0.x).
// The Forge parsers read FML's own wording — its mod state table in the crash
// report ("UCHE  somemod{1.0} [Some Mod] (somemod.jar)", E = errored), its
// "Found a duplicate mod" and "requires mods ... to be available" lines — and
// Forge mods are indexed by their mcmod.info. The steps never collide: each
// loader's wording only ever appears in its own logs.
public static class CrashAnalyzer
{
    /// <param name="instanceDir">The crashed game's folder (one account's game folder for the version).</param>
    public static CrashAnalysis Analyze(string instanceDir, string? launchLogPath)
    {
        // Mods live in the version's shared folder, not the account's (SharedMods)
        // — except a mod-profile launch, whose jars the game loaded from this
        // account's own "mods-profile" copy (Core/Profiles/LaunchModFolder).
        var modIndex = BuildModIndex(
            GameFolders.InstanceLayout.ModsDirForGameDir(instanceDir),
            Path.Combine(instanceDir, Profiles.LaunchModFolder.ProfileFolderName));

        var logText = TryRead(launchLogPath);
        var crashReportText = TryRead(NewestCrashReport(instanceDir));
        // The launch log is the primary source (Fabric prints resolution and
        // mixin failures to stderr before a crash report even exists); the
        // crash report supplements it for in-boot crashes past mod resolution.
        var combined = (logText ?? "") + "\n" + (crashReportText ?? "");
        if (string.IsNullOrWhiteSpace(combined))
            return new CrashAnalysis([], "Minecraft crashed while starting.", null);

        // 1. Fabric mod-resolution failure: names exact mod ids, e.g.
        //    "Mod 'Some Mod' (somemod) 1.2.3 requires version 1.21 of minecraft"
        //    under "Incompatible mods found!" / "Mod resolution encountered ...".
        if (Regex.IsMatch(combined, @"Incompatible mods? (found|set)|Mod resolution (encountered|failed)", RegexOptions.IgnoreCase))
        {
            var ids = Regex.Matches(combined, @"[Mm]od '[^']*' \(([a-z0-9_\-]+)\)")
                .Select(m => m.Groups[1].Value)
                .Where(id => id != "minecraft" && id != "java" && id != "fabricloader")
                .Distinct().ToList();
            if (ids.Count > 0)
                return Blame(ids, "Minecraft couldn't start because of an incompatible mod.", combined, modIndex,
                             excerptAround: "ncompatible mod");
        }

        // 2. Duplicate mod id — two jars claiming the same id hard-fail the boot.
        var dup = Regex.Match(combined, @"[Dd]uplicate[^\n]*?\b(?:mod id|mods?)\b[^\n]*?['""]([a-z0-9_\-]+)['""]");
        if (dup.Success)
            return Blame([dup.Groups[1].Value],
                         "Two copies of the same mod are installed — Minecraft refuses to start.",
                         combined, modIndex, excerptAround: dup.Value);

        // 2b. Forge 1.8.9: the errored mod(s) in FML's mod state table — the
        //     line names the jar too. FML puts this table in every crash
        //     report from mod loading on, so it is the strongest Forge signal.
        //     FML's own entries (mcp, FML, Forge) are the loader, never blamed.
        if (crashReportText != null)
        {
            var errored = Regex.Matches(crashReportText,
                    @"^\s*[ULCHIJAD]*E[ULCHIJADE]*\s+([A-Za-z0-9_\-\.]+)\{[^}\n]*\}\s+\[[^\]\n]*\]\s+\(([^)\n]+)\)",
                    RegexOptions.Multiline)
                .Where(m => !ForgeLoaderIds.Contains(m.Groups[1].Value))
                .ToList();
            if (errored.Count > 0)
                return Blame(errored.Select(m => m.Groups[1].Value).Distinct().ToList(),
                             "Forge stopped loading because a mod failed.", crashReportText, modIndex,
                             excerptAround: errored[0].Value.Trim(),
                             jarNames: errored
                                 .GroupBy(m => m.Groups[1].Value, StringComparer.OrdinalIgnoreCase)
                                 .ToDictionary(g => g.Key, g => g.First().Groups[2].Value.Trim(),
                                               StringComparer.OrdinalIgnoreCase));
        }

        // 2c. Forge 1.8.9: two jars with one mod id, or a mod missing a
        //     dependency — FML names the mod id in both messages.
        var forgeDup = Regex.Match(combined, @"Found a duplicate mod ([A-Za-z0-9_\-\.]+) at");
        if (forgeDup.Success)
            return Blame([forgeDup.Groups[1].Value],
                         "Two copies of the same mod are installed — Minecraft refuses to start.",
                         combined, modIndex, excerptAround: forgeDup.Value);
        var forgeMissing = Regex.Matches(combined, @"The mod ([A-Za-z0-9_\-\.]+) \([^)\n]*\) requires mods? ")
            .Select(m => m.Groups[1].Value).Distinct().ToList();
        if (forgeMissing.Count > 0)
            return Blame(forgeMissing, "Minecraft couldn't start because a mod is missing something it needs.",
                         combined, modIndex, excerptAround: "requires mod");

        // 3. Mixin apply failure names the owning config, which names the mod:
        //    "Mixin apply failed somemod.mixins.json:..." or
        //    "... in config [somemod.mixins.json]" / "from mod somemod".
        var mixinIds = Regex.Matches(combined, @"Mixin apply(?: for mod ([a-z0-9_\-]+))? failed(?:\s+([a-z0-9_\-\.]+)\.mixins\.json)?", RegexOptions.IgnoreCase)
            .SelectMany(m => new[] { m.Groups[1].Value, m.Groups[2].Value })
            .Concat(Regex.Matches(combined, @"from mod ([a-z0-9_\-]+)\]").Select(m => m.Groups[1].Value))
            .Where(s => !string.IsNullOrEmpty(s) && s != "minecraft")
            .Distinct().ToList();
        if (mixinIds.Count > 0)
            return Blame(mixinIds, "A mod failed to hook into this Minecraft version (mixin apply failure).",
                         combined, modIndex, excerptAround: "Mixin apply");

        // 4. Fabric crash reports carry an explicit blame line when the loader
        //    can attribute the crash: "Suspected Mod(s): Some Mod (somemod)".
        if (crashReportText != null)
        {
            var suspected = Regex.Matches(crashReportText, @"Suspected Mods?[^\n]*?\(([a-z0-9_\-]+)\)")
                .Select(m => m.Groups[1].Value)
                .Where(id => id != "minecraft" && id != "fabricloader")
                .Distinct().ToList();
            if (suspected.Count > 0)
                return Blame(suspected, "Minecraft's crash report points at a mod.",
                             crashReportText, modIndex, excerptAround: "Suspected Mod");
        }

        // 5. Last resort: match exception stack frames against the code
        //    packages inside each installed jar. Only trust it when exactly
        //    one non-Origin mod's packages appear in the failing stack.
        var packageHits = BlameByStackPackages(combined, modIndex);
        if (packageHits.Count == 1)
            return Blame(packageHits, "A mod's code appears in the crash stack.",
                         combined, modIndex, excerptAround: "Exception");

        return new CrashAnalysis([],
            "Minecraft crashed while starting, and no single mod could be identified with confidence.",
            Excerpt(combined, "Exception"));
    }

    // FML's own entries in its mod state table: the loader itself.
    private static readonly HashSet<string> ForgeLoaderIds = new(StringComparer.OrdinalIgnoreCase)
    {
        "mcp", "FML", "Forge",
    };

    // jarNames: the jar file per mod id when the evidence names it (Forge's
    // state table); otherwise the jar is looked up in the mods-folder index.
    private static CrashAnalysis Blame(IReadOnlyList<string> modIds, string reason, string sourceText,
        IReadOnlyDictionary<string, string> modIndex, string excerptAround,
        IReadOnlyDictionary<string, string>? jarNames = null)
    {
        var culprits = modIds.Select(id =>
        {
            var jarName = jarNames != null && jarNames.TryGetValue(id, out var named) ? named
                : modIndex.TryGetValue(id, out var jarPath) ? Path.GetFileName(jarPath) : null;
            var external = jarName == null
                || (!Mods.ModManager.IsManaged(jarName)
                    && !jarName.Equals("originclient.jar", StringComparison.OrdinalIgnoreCase));
            return new CrashCulprit(id, jarName, external);
        }).ToList();
        return new CrashAnalysis(culprits, reason, Excerpt(sourceText, excerptAround));
    }

    // mod id -> full jar path, built by reading fabric.mod.json (Fabric) or
    // mcmod.info (Forge 1.8.9) inside every enabled jar in the instance's mods
    // folder. Best-effort per jar: one unreadable jar must not blank the whole
    // index.
    private static Dictionary<string, string> BuildModIndex(params string[] modsFolders)
    {
        var index = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            foreach (var jar in modsFolders.Where(Directory.Exists).SelectMany(f => Directory.EnumerateFiles(f, "*.jar")))
            {
                try
                {
                    using var zip = ZipFile.OpenRead(jar);
                    if (zip.GetEntry("fabric.mod.json") is { } fabric)
                    {
                        using var doc = JsonDocument.Parse(fabric.Open());
                        if (doc.RootElement.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.String)
                            index[id.GetString()!] = jar;
                    }
                    else if (zip.GetEntry("mcmod.info") is { } forge)
                    {
                        foreach (var modId in ForgeModIds(forge))
                            index[modId] = jar;
                    }
                }
                catch { /* corrupt/locked jar — skip it */ }
            }
        }
        catch { /* folder unreadable — empty index, analyzer degrades to ids only */ }
        return index;
    }

    // mcmod.info comes in two shapes — a bare array of mod entries, or
    // {"modListVersion": 2, "modList": [...]} — and is hand-written by mod
    // authors, so trailing commas and comments are tolerated.
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

    // Match "at com.example.foo.Bar.baz(...)" stack frames against the code
    // packages inside each installed jar. Origin's own jar and the managed perf
    // stack are excluded — they run on every launch; blaming them is noise.
    private static List<string> BlameByStackPackages(string text, IReadOnlyDictionary<string, string> modIndex)
    {
        var frames = Regex.Matches(text, @"^\s*at ([a-z][a-z0-9_]*(?:\.[a-z0-9_]+){2,})\.[A-Z]", RegexOptions.Multiline)
            .Select(m => m.Groups[1].Value).Distinct().ToList();
        if (frames.Count == 0) return [];

        var hits = new List<string>();
        foreach (var (modId, jarPath) in modIndex)
        {
            var jarName = Path.GetFileName(jarPath);
            if (modId == "originclient" || Mods.ModManager.IsManaged(jarName)) continue;
            try
            {
                using var zip = ZipFile.OpenRead(jarPath);
                var packages = zip.Entries
                    .Where(e => e.FullName.EndsWith(".class") && e.FullName.Contains('/'))
                    .Select(e => string.Join('.', e.FullName.Split('/').SkipLast(1)))
                    .Where(p => p.Length > 0 && !p.StartsWith("META-INF"))
                    .Distinct().ToHashSet();
                if (frames.Any(f => packages.Any(p => f.StartsWith(p + ".", StringComparison.Ordinal) || f == p)))
                    hits.Add(modId);
            }
            catch { /* unreadable jar — skip */ }
        }
        return hits;
    }

    private static string? NewestCrashReport(string instanceDir)
    {
        try
        {
            var dir = Path.Combine(instanceDir, "crash-reports");
            if (!Directory.Exists(dir)) return null;
            var newest = Directory.EnumerateFiles(dir, "crash-*.txt")
                .OrderByDescending(File.GetLastWriteTimeUtc)
                .FirstOrDefault();
            // Only trust a report from the last few minutes — an old file
            // belongs to some previous session's crash, not this launch.
            return newest != null && DateTime.UtcNow - File.GetLastWriteTimeUtc(newest) < TimeSpan.FromMinutes(5)
                ? newest
                : null;
        }
        catch { return null; }
    }

    private static string? TryRead(string? path)
    {
        try
        {
            return path != null && File.Exists(path) ? File.ReadAllText(path) : null;
        }
        catch { return null; }
    }

    // A short window of the evidence around the interesting line, for the
    // details box — enough to act on, not the whole log.
    private static string? Excerpt(string text, string around)
    {
        var idx = text.IndexOf(around, StringComparison.OrdinalIgnoreCase);
        if (idx < 0) return null;
        var start = text.LastIndexOf('\n', Math.Max(0, idx - 400)) + 1;
        var end = Math.Min(text.Length, idx + 1200);
        return text[start..end].Trim();
    }
}

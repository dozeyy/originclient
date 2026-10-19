using System.IO;
using System.Text.RegularExpressions;
using OriginLauncher.App.Core.Import.Mods;

namespace OriginLauncher.App.Core.Import.Sources;

/// <summary>A Minecraft version and loader worked out from evidence rather than read from a launcher's own data.</summary>
public sealed record InferredVersion(string? McVersion, LoaderKind Loader, string? LoaderVersion);

// D5: when a launcher's metadata can't tell the version + loader (Modrinth's
// database is never opened; a profile may be damaged; Browse has no metadata at
// all), look for evidence in the instance itself, in this order:
//   1. logs/latest.log: Fabric Loader prints "Loading Minecraft X with Fabric Loader Y"
//   2. the mods' own fabric.mod.json: depends.minecraft
// Whatever comes out of this is Confidence.Inferred — the player is shown it as
// a guess, never as fact.
public static class LatestLogSniffer
{
    private const int HeaderBytes = 256 * 1024; // the loader line is one of the first

    private static readonly Regex LoaderLine = new(
        @"Loading Minecraft (?<mc>\S+) with (?<loader>Fabric|Quilt) Loader (?<version>\S+)",
        RegexOptions.CultureInvariant | RegexOptions.Compiled);

    /// <summary>The version + loader a log's text names, or null when it has no such line.</summary>
    public static InferredVersion? Sniff(string? logText)
    {
        if (string.IsNullOrEmpty(logText)) return null;
        var match = LoaderLine.Match(logText);
        if (!match.Success) return null;
        return new InferredVersion(
            match.Groups["mc"].Value,
            match.Groups["loader"].Value == "Quilt" ? LoaderKind.Quilt : LoaderKind.Fabric,
            match.Groups["version"].Value);
    }

    /// <summary>Reads <paramref name="gameDir"/>/logs/latest.log (shared read: a running game keeps it open).</summary>
    public static InferredVersion? SniffGameDir(string gameDir, SourceFs fs) =>
        Sniff(fs.ReadHeadText(Path.Combine(gameDir, "logs", "latest.log"), HeaderBytes));
}

// D5, second step: the Minecraft version the installed mods were built for.
public static class ModsVersionInference
{
    /// <summary>
    /// Votes over the mods' depends.minecraft requirements; the most common
    /// concrete version wins, the higher one on a tie. Looks at most
    /// <paramref name="maxJars"/> jars so a 400-mod pack is still a cheap scan.
    /// </summary>
    public static InferredVersion? FromMods(string gameDir, SourceFs fs, CancellationToken ct, int maxJars = 60)
    {
        var votes = new Dictionary<string, int>(StringComparer.Ordinal);
        var loader = LoaderKind.None;
        var looked = 0;
        foreach (var path in fs.EnumerateFiles(Path.Combine(gameDir, "mods"), recursive: false))
        {
            ct.ThrowIfCancellationRequested();
            var name = Path.GetFileName(path);
            if (!name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) continue;
            if (++looked > maxJars) break;
            using var stream = fs.OpenRead(path);
            if (stream == null) continue;
            var info = ModMetadataReader.Read(stream, name, readNested: false);
            if (info.ReadFailed) continue;
            if (info.Loader == ModLoader.Fabric) loader = LoaderKind.Fabric;
            else if (loader == LoaderKind.None && info.Loader == ModLoader.Quilt) loader = LoaderKind.Quilt;
            else if (loader == LoaderKind.None && info.Loader is ModLoader.Forge or ModLoader.LegacyForge) loader = LoaderKind.Forge;
            else if (loader == LoaderKind.None && info.Loader == ModLoader.NeoForge) loader = LoaderKind.NeoForge;
            if (info.MinecraftRange?.Anchor is { } anchor) votes[anchor] = votes.GetValueOrDefault(anchor) + 1;
        }
        if (votes.Count == 0) return loader == LoaderKind.None ? null : new InferredVersion(null, loader, null);
        var best = votes
            .OrderByDescending(v => v.Value)
            .ThenByDescending(v => v.Key, Comparer<string>.Create((a, b) => FabricVersion.Compare(a, b)))
            .First().Key;
        return new InferredVersion(best, loader, null);
    }
}

public static class InstanceInference
{
    /// <summary>
    /// Fills a missing Minecraft version / loader from the log, then the mods (D5).
    /// Whatever it fills in marks the instance Inferred; nothing found marks it
    /// "Couldn't read details".
    /// </summary>
    public static DetectedInstance Complete(DetectedInstance instance, SourceFs fs, CancellationToken ct)
    {
        if (instance.McVersion != null) return instance;
        var found = LatestLogSniffer.SniffGameDir(instance.GameDir, fs);
        if (found?.McVersion == null)
        {
            var fromMods = ModsVersionInference.FromMods(instance.GameDir, fs, ct);
            if (fromMods?.McVersion != null) found = new InferredVersion(fromMods.McVersion, fromMods.Loader, found?.LoaderVersion);
            else if (found == null && fromMods != null) found = fromMods;
        }
        if (found?.McVersion == null)
            return instance with { Problem = DetectedInstance.CouldntRead, Confidence = Confidence.Inferred };
        return instance with
        {
            McVersion = found.McVersion,
            Loader = found.Loader != LoaderKind.None ? found.Loader : instance.Loader,
            LoaderVersion = found.LoaderVersion ?? instance.LoaderVersion,
            Confidence = Confidence.Inferred,
            Problem = null,
        };
    }
}

using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using OriginLauncher.App.Core.Import.Mods;

namespace OriginLauncher.App.Core.Import.Detection;

// What a game folder itself says about the version it runs, independent of any
// launcher's record: its last log and its mods. Read once per folder, lazily and
// capped (a 256 KB log head, at most 64 jars' metadata), through SourceFs so a
// credential file is never opened. Pure: every path comes from the caller.

/// <summary>One folder = one setup. The key two setups are compared by, and the stable setup id.</summary>
public static class SetupKey
{
    private static readonly StringComparison PathCompare =
        OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;

    public static StringComparer Comparer { get; } =
        OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal;

    /// <summary>
    /// The folder's full path, tidied: Path.GetFullPath (so "a\..\b", "a/b" and "a\b\" agree) without following
    /// links or junctions. Compared case-insensitively on Windows (use <see cref="Comparer"/>).
    /// </summary>
    public static string Folder(string gameDir)
    {
        try
        {
            return Path.TrimEndingDirectorySeparator(Path.GetFullPath(gameDir.Trim()));
        }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return gameDir.Trim();
        }
    }

    public static bool SameFolder(string a, string b) => string.Equals(Folder(a), Folder(b), PathCompare);

    /// <summary>"prism-1a2b3c4d5e6f7a8b": deterministic from (kind, full folder path) and nothing else.</summary>
    public static string StableId(SourceKind kind, string gameDir)
    {
        var folder = Folder(gameDir);
        if (OperatingSystem.IsWindows()) folder = folder.ToUpperInvariant();
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(kind + "|" + folder));
        return kind.ToString().ToLowerInvariant() + "-" + Convert.ToHexString(hash, 0, 8).ToLowerInvariant();
    }
}

/// <summary>What logs/latest.log says the game last ran. Any field may be null (the log didn't say).</summary>
public sealed record LogEvidence(string? McVersion, LoaderKind? Loader, string? LoaderVersion, DateTimeOffset ModifiedUtc)
{
    public VersionEvidence ToEvidence() =>
        new(EvidenceSource.LatestLog, McVersion, Loader, LoaderVersion, ModifiedUtc) { From = "latest.log" };
}

public static class LogParser
{
    public const int HeadBytes = 256 * 1024; // every loader prints its banner in the first few KB

    private const RegexOptions Opts = RegexOptions.CultureInvariant | RegexOptions.Compiled;

    // Fabric / Quilt: "Loading Minecraft 1.21.1 with Fabric Loader 0.16.10"
    private static readonly Regex FabricLine = new(@"Loading Minecraft (?<mc>\S+) with (?<loader>Fabric|Quilt) Loader (?<v>[^\s,]+)", Opts);

    // Forge / NeoForge (1.13+): "ModLauncher running: args [... --fml.forgeVersion, 47.2.0, --fml.mcVersion, 1.20.1 ...]"
    // or "--fml.neoForgeVersion, 21.1.77"; and "NeoForge mod loading, version 21.1.77, for MC 1.21.1" / "Forge mod loading, ...".
    private static readonly Regex FmlMc = new(@"--fml\.mcVersion,\s*(?<mc>[^,\s\]]+)", Opts);
    private static readonly Regex FmlForge = new(@"--fml\.forgeVersion,\s*(?<v>[^,\s\]]+)", Opts);
    private static readonly Regex FmlNeo = new(@"--fml\.neoForgeVersion,\s*(?<v>[^,\s\]]+)", Opts);
    private static readonly Regex ModLoading = new(@"(?<neo>NeoForge|Forge) mod loading, version (?<v>[^,\s]+), for MC (?<mc>[^,\s]+)", Opts);
    private static readonly Regex ModLauncher = new(@"ModLauncher running", Opts);

    // Legacy Forge (1.12 and older): "Forge Mod Loader version 14.23.5.2859 for Minecraft 1.12.2 loading", "MinecraftForge v14.23.5.2859 Initialized".
    private static readonly Regex LegacyFml = new(@"Forge Mod Loader version (?<v>\S+) for Minecraft (?<mc>\S+) loading", Opts);
    private static readonly Regex LegacyForge = new(@"MinecraftForge v(?<v>\S+) Initialized", Opts);

    // Vanilla / crash-report style: "Minecraft Version: 1.20.1" (says nothing about the loader).
    private static readonly Regex VersionLine = new(@"Minecraft Version: (?<mc>[^\s,]+)", Opts);

    /// <summary>The version + loader a log's text names (null fields when it doesn't say); null when it names neither.</summary>
    public static (string? Mc, LoaderKind? Loader, string? LoaderVersion)? Parse(string? text)
    {
        if (string.IsNullOrEmpty(text)) return null;

        var m = FabricLine.Match(text);
        if (m.Success)
            return (m.Groups["mc"].Value, m.Groups["loader"].Value == "Quilt" ? LoaderKind.Quilt : LoaderKind.Fabric, m.Groups["v"].Value);

        string? mc = null, version = null;
        LoaderKind? loader = null;
        if ((m = ModLoading.Match(text)).Success)
        {
            loader = m.Groups["neo"].Value == "NeoForge" ? LoaderKind.NeoForge : LoaderKind.Forge;
            version = m.Groups["v"].Value;
            mc = m.Groups["mc"].Value;
        }
        if ((m = FmlNeo.Match(text)).Success) { loader = LoaderKind.NeoForge; version ??= m.Groups["v"].Value; }
        else if ((m = FmlForge.Match(text)).Success) { loader ??= LoaderKind.Forge; version ??= m.Groups["v"].Value; }
        if (mc == null && (m = FmlMc.Match(text)).Success) mc = m.Groups["mc"].Value;
        if (loader == null && ModLauncher.IsMatch(text))
            loader = text.Contains("neoforge", StringComparison.OrdinalIgnoreCase) ? LoaderKind.NeoForge : LoaderKind.Forge;

        if (loader == null)
        {
            if ((m = LegacyFml.Match(text)).Success) { loader = LoaderKind.Forge; version = m.Groups["v"].Value; mc = m.Groups["mc"].Value; }
            else if ((m = LegacyForge.Match(text)).Success) { loader = LoaderKind.Forge; version = m.Groups["v"].Value; }
        }
        if (mc == null && (m = VersionLine.Match(text)).Success) mc = m.Groups["mc"].Value;

        return mc == null && loader == null ? null : (mc, loader, version);
    }

    /// <summary>Reads the head of <paramref name="gameDir"/>/logs/latest.log (shared read: a running game keeps it open).</summary>
    public static LogEvidence? Read(string gameDir, SourceFs fs)
    {
        var path = Path.Combine(gameDir, "logs", "latest.log");
        if (!File.Exists(path)) return null;
        var parsed = Parse(fs.ReadHeadText(path, HeadBytes));
        if (parsed == null) return null;
        DateTimeOffset modified;
        try { modified = new DateTimeOffset(File.GetLastWriteTimeUtc(path), TimeSpan.Zero); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return null; }
        return new LogEvidence(parsed.Value.Mc, parsed.Value.Loader, parsed.Value.LoaderVersion, modified);
    }
}

/// <summary>
/// What the mods folder says: the depends.minecraft ranges of the jars that declare one, and how many jars are of
/// each loader type. A version "fits" when at least 80 % of the declaring jars accept it.
/// </summary>
public sealed class ModsEvidence
{
    public const int MaxJars = 64;

    private readonly List<VersionPredicate> _ranges;
    private readonly Dictionary<ModLoader, int> _loaders;

    public ModsEvidence(IEnumerable<VersionPredicate> ranges, IReadOnlyDictionary<ModLoader, int> loaders, int jarsRead)
    {
        _ranges = ranges.Where(r => !r.MatchesAnything).ToList();
        _loaders = loaders.ToDictionary(kv => kv.Key, kv => kv.Value);
        JarsRead = jarsRead;
        BestVersion = PickBest();
        MajorityLoader = PickMajority();
    }

    public int JarsRead { get; }

    /// <summary>How many jars declare a real (not "*") Minecraft requirement.</summary>
    public int Declaring => _ranges.Count;
    public bool HasRanges => _ranges.Count > 0;

    public int Accepting(string version) => _ranges.Count(r => r.IsSatisfiedBy(version));

    /// <summary>At least 80 % of the declaring jars accept <paramref name="version"/>.</summary>
    public bool Fits(string? version) => version != null && HasRanges && Accepting(version) * 5 >= _ranges.Count * 4;

    /// <summary>At least 80 % of the declaring jars reject <paramref name="version"/>.</summary>
    public bool Rejects(string version) => HasRanges && (_ranges.Count - Accepting(version)) * 5 >= _ranges.Count * 4;

    /// <summary>The concrete version most declaring jars accept (the higher one on a tie); null when none declare.</summary>
    public string? BestVersion { get; }

    /// <summary><see cref="BestVersion"/> when it fits (the 80 % consensus), else null (the mods are split).</summary>
    public string? ConsensusVersion => BestVersion != null && Fits(BestVersion) ? BestVersion : null;

    /// <summary>The loader more than half of the loader-typed jars are for; null when none dominates.</summary>
    public LoaderKind? MajorityLoader { get; }

    public int TypedJars => _loaders.Where(kv => kv.Key != ModLoader.Unknown).Sum(kv => kv.Value);

    /// <summary>More than half of the loader-typed jars can't run on <paramref name="loader"/>.</summary>
    public bool MostlyIncompatibleWith(LoaderKind loader)
    {
        if (loader == LoaderKind.Unknown) return false;
        var typed = TypedJars;
        if (typed == 0) return false;
        var bad = _loaders.Where(kv => kv.Key != ModLoader.Unknown && !Runs(kv.Key, loader)).Sum(kv => kv.Value);
        return bad * 2 > typed;
    }

    /// <summary>Can a jar of type <paramref name="jar"/> run on <paramref name="setup"/>? (Quilt runs Fabric mods; 1.20.1 NeoForge runs Forge mods.)</summary>
    public static bool Runs(ModLoader jar, LoaderKind setup) => setup switch
    {
        LoaderKind.Fabric => jar == ModLoader.Fabric,
        LoaderKind.Quilt => jar is ModLoader.Fabric or ModLoader.Quilt,
        LoaderKind.Forge => jar is ModLoader.Forge or ModLoader.LegacyForge,
        LoaderKind.NeoForge => jar is ModLoader.NeoForge or ModLoader.Forge,
        _ => false, // vanilla runs no mods
    };

    public static bool Compatible(LoaderKind setup, LoaderKind jarsAreFor) => jarsAreFor switch
    {
        LoaderKind.Fabric => Runs(ModLoader.Fabric, setup),
        LoaderKind.Quilt => Runs(ModLoader.Quilt, setup),
        LoaderKind.Forge => Runs(ModLoader.Forge, setup),
        LoaderKind.NeoForge => Runs(ModLoader.NeoForge, setup),
        _ => setup == jarsAreFor,
    };

    public VersionEvidence ToEvidence() =>
        new(EvidenceSource.ModDepends, BestVersion, MajorityLoader, null, null) { From = "mods" };

    private string? PickBest()
    {
        if (_ranges.Count == 0) return null;
        var candidates = _ranges.Select(r => r.Anchor).Where(a => a != null).Distinct(StringComparer.Ordinal).ToList();
        if (candidates.Count == 0) return null;
        return candidates
            .Select(c => (Version: c!, Score: Accepting(c!)))
            .OrderByDescending(x => x.Score)
            .ThenByDescending(x => x.Version, Comparer<string>.Create((a, b) => FabricVersion.Compare(a, b)))
            .First().Version;
    }

    private LoaderKind? PickMajority()
    {
        var typed = TypedJars;
        if (typed == 0) return null;
        int Count(params ModLoader[] kinds) => kinds.Sum(k => _loaders.GetValueOrDefault(k));
        foreach (var (kind, count) in new[]
                 {
                     (LoaderKind.Fabric, Count(ModLoader.Fabric)),
                     (LoaderKind.Quilt, Count(ModLoader.Quilt)),
                     (LoaderKind.Forge, Count(ModLoader.Forge, ModLoader.LegacyForge)),
                     (LoaderKind.NeoForge, Count(ModLoader.NeoForge)),
                 })
            if (count * 2 > typed) return kind;
        return null;
    }

    /// <summary>
    /// Reads at most <see cref="MaxJars"/> jars of <paramref name="gameDir"/>/mods (smallest first: cheapest to open,
    /// and a 400-mod pack is still a quick scan). Disabled jars are not counted. Null when there is no mods folder or no jar.
    /// </summary>
    public static ModsEvidence? Read(string gameDir, SourceFs fs, CancellationToken ct)
    {
        var mods = Path.Combine(gameDir, "mods");
        if (!Directory.Exists(mods)) return null;
        var jars = new List<(string Path, long Size)>();
        foreach (var path in fs.EnumerateFiles(mods, recursive: false))
        {
            if (!path.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) continue;
            long size;
            try { size = new FileInfo(path).Length; }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { continue; }
            jars.Add((path, size));
        }
        if (jars.Count == 0) return null;

        var ranges = new List<VersionPredicate>();
        var loaders = new Dictionary<ModLoader, int>();
        var read = 0;
        foreach (var (path, _) in jars.OrderBy(j => j.Size).ThenBy(j => j.Path, StringComparer.Ordinal).Take(MaxJars))
        {
            ct.ThrowIfCancellationRequested();
            using var stream = fs.OpenRead(path);
            if (stream == null) continue;
            var info = ModMetadataReader.Read(stream, Path.GetFileName(path), readNested: false);
            if (info.ReadFailed) continue;
            read++;
            loaders[info.Loader] = loaders.GetValueOrDefault(info.Loader) + 1;
            if (info.MinecraftRange is { } range) ranges.Add(range);
        }
        return read == 0 ? null : new ModsEvidence(ranges, loaders, read);
    }
}

/// <summary>Everything one game folder says about itself, read once per folder.</summary>
public sealed record FolderEvidence(LogEvidence? Log, ModsEvidence? Mods, bool HasGameData)
{
    public static readonly FolderEvidence None = new(null, null, false);

    public static FolderEvidence Read(string gameDir, SourceFs fs, CancellationToken ct)
    {
        if (!Directory.Exists(gameDir)) return None;
        return new FolderEvidence(LogParser.Read(gameDir, fs), ModsEvidence.Read(gameDir, fs, ct), HasData(gameDir));
    }

    /// <summary>The folder holds something a player made: a mods folder, settings, or worlds.</summary>
    public static bool HasData(string gameDir) =>
        Directory.Exists(Path.Combine(gameDir, "mods"))
        || File.Exists(Path.Combine(gameDir, "options.txt"))
        || Directory.Exists(Path.Combine(gameDir, "saves"));
}

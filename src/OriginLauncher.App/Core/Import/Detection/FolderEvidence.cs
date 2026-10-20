using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using OriginLauncher.App.Core.Import.Mods;

namespace OriginLauncher.App.Core.Import.Detection;

// What a game folder itself says about the version it runs, independent of any
// launcher's record: its last log and its mods. Read once per folder, lazily and
// capped (a 256 KB log head, at most 32 jars' metadata), through SourceFs so a
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

    /// <summary>
    /// The key of one setup: its game folder, plus its own mods folder when it has one (a PvP client's per-version
    /// mods next to a shared .minecraft are a setup of their own, never merged into the .minecraft one).
    /// Unchanged from <see cref="Folder"/> for every setup that reads &lt;GameDir&gt;/mods, so existing ids stay put.
    /// </summary>
    public static string Of(string gameDir, string? modsDir) =>
        modsDir == null ? Folder(gameDir) : Folder(gameDir) + "|mods=" + Folder(modsDir);

    public static string Of(DetectedInstance setup) => Of(setup.GameDir, setup.ModsDir);

    /// <summary>
    /// "setup-1a2b3c4d5e6f7a8b": deterministic from the full folder path and NOTHING else — one folder is one setup, so
    /// the id must not change when another launcher's entry wins the name, or when Browse finds the same folder.
    /// </summary>
    public static string StableId(string gameDir, string? modsDir = null)
    {
        var folder = Of(gameDir, modsDir);
        if (OperatingSystem.IsWindows()) folder = folder.ToUpperInvariant();
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(folder));
        return "setup-" + Convert.ToHexString(hash, 0, 8).ToLowerInvariant();
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
    // Measured 2026-10-18 (warm cache): 200 setups x 64 jars read at a 64 cap = 2.3 s, too close to the 5 s scan budget
    // once a cold disk / antivirus is in play; 32 still gives a solid 80 % consensus at half the cost.
    public const int MaxJars = 32;

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
    public static ModsEvidence? Read(string gameDir, SourceFs fs, CancellationToken ct) =>
        ReadFolder(Path.Combine(gameDir, "mods"), fs, ct);

    /// <summary>As <see cref="Read"/>, for a mods folder given directly (a PvP client's own per-version folder).</summary>
    public static ModsEvidence? ReadFolder(string mods, SourceFs fs, CancellationToken ct)
    {
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

/// <summary>
/// options.txt's `version:` line: the DataVersion of the game that last wrote the file, i.e. what last ran in the
/// folder, whatever the loader. Mapped to a Minecraft version; an unknown DataVersion is no evidence.
/// </summary>
public sealed record OptionsEvidence(string? McVersion, int DataVersion, DateTimeOffset ModifiedUtc)
{
    // Mirrors Core/Sync/OptionsTxtFormat.DataVersions (the vanilla data version of each Origin-supported version,
    // from each version's own version.json); keep the two in step. Nothing here is guessed: a DataVersion not in
    // this table (a snapshot, an unsupported version) gives no version evidence.
    private static readonly Dictionary<int, string> VersionByDataVersion = new()
    {
        [2586] = "1.16.5", [2730] = "1.17.1", [2975] = "1.18.2", [3120] = "1.19.2",
        [3218] = "1.19.3", [3337] = "1.19.4", [3463] = "1.20", [3465] = "1.20.1",
        [3578] = "1.20.2", [3700] = "1.20.4", [3953] = "1.21", [3955] = "1.21.1",
        [4080] = "1.21.2", [4082] = "1.21.3", [4189] = "1.21.4", [4435] = "1.21.6",
        [4438] = "1.21.7", [4440] = "1.21.8", [4556] = "1.21.10", [4671] = "1.21.11",
        [4903] = "26.2", [5023] = "26.3",
    };

    public static string? VersionFor(int dataVersion) => VersionByDataVersion.GetValueOrDefault(dataVersion);

    public VersionEvidence ToEvidence() =>
        new(EvidenceSource.OptionsFile, McVersion, null, null, ModifiedUtc) { From = "options.txt" };

    /// <summary>The `version:` of <paramref name="gameDir"/>/options.txt (only the first 64 KB is read); null when absent or unknown.</summary>
    public static OptionsEvidence? Read(string gameDir, SourceFs fs)
    {
        var path = Path.Combine(gameDir, "options.txt");
        if (!File.Exists(path)) return null;
        var text = fs.ReadHeadText(path, 64 * 1024);
        if (text == null) return null;
        foreach (var raw in text.Split('\n'))
        {
            var line = raw.Trim();
            if (!line.StartsWith("version:", StringComparison.Ordinal)) continue;
            if (!int.TryParse(line["version:".Length..], System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out var dataVersion)) return null;
            if (VersionFor(dataVersion) is not { } mc) return null;
            try { return new OptionsEvidence(mc, dataVersion, new DateTimeOffset(File.GetLastWriteTimeUtc(path), TimeSpan.Zero)); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return null; }
        }
        return null;
    }
}

/// <summary>Everything one game folder says about itself, read once per folder.</summary>
public sealed record FolderEvidence(LogEvidence? Log, ModsEvidence? Mods, bool HasGameData, OptionsEvidence? Options = null)
{
    public static readonly FolderEvidence None = new(null, null, false);

    public static FolderEvidence Read(string gameDir, SourceFs fs, CancellationToken ct)
    {
        if (!Directory.Exists(gameDir)) return None;
        return new FolderEvidence(LogParser.Read(gameDir, fs), ModsEvidence.Read(gameDir, fs, ct), HasData(gameDir), OptionsEvidence.Read(gameDir, fs));
    }

    /// <summary>
    /// A setup whose mods live in a folder of their own (<see cref="DetectedInstance.ModsDir"/>): only those mods
    /// speak for it. The shared game folder's latest.log and options.txt describe whatever game last ran there (the
    /// official launcher's, another client's), not this setup, so they are not evidence for it.
    /// </summary>
    public static FolderEvidence ReadOwnMods(string modsDir, SourceFs fs, CancellationToken ct) =>
        !Directory.Exists(modsDir) ? None : new FolderEvidence(null, ModsEvidence.ReadFolder(modsDir, fs, ct), true, null);

    public static FolderEvidence For(DetectedInstance setup, SourceFs fs, CancellationToken ct) =>
        setup.ModsDir != null ? ReadOwnMods(setup.ModsDir, fs, ct) : Read(setup.GameDir, fs, ct);

    /// <summary>The folder holds something a player made: a mods folder, settings, or worlds.</summary>
    public static bool HasData(string gameDir) =>
        Directory.Exists(Path.Combine(gameDir, "mods"))
        || File.Exists(Path.Combine(gameDir, "options.txt"))
        || Directory.Exists(Path.Combine(gameDir, "saves"));
}

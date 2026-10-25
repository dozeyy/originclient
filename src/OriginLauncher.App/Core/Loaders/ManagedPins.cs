namespace OriginLauncher.App.Core.Loaders;

// Integrity pins for every jar Origin puts in a mods folder that is NOT in PerformanceModCatalog's perf stack:
// Fabric API and Simple Voice Chat (one exact file per Minecraft version, ManagedPins.Data.cs), the vetted
// Sodium/Iris alternates the component store serves (Core/Components/ConfigurationCatalog), and the legacy
// Forge pins (SHA-256, the only hash measured for them: LegacyStackInstaller + LegacyForgeBuilds).
// Same policy as the perf catalog: a download that does not hash to its pin is refused, never installed.
// "Is this jar Origin's?" (ModManager.IsManagedFile) asks the known-hash sets below first.
public static partial class ManagedPins
{
    /// <summary>The pinned Fabric API file for this exact Minecraft version, or null (live lookup, still SHA-512 checked).</summary>
    public static PerfMod? FabricApi(string mcVersion) => FabricApiPins.GetValueOrDefault(mcVersion);

    /// <summary>The pinned Simple Voice Chat file for this exact Minecraft version, or null.</summary>
    public static PerfMod? VoiceChat(string mcVersion) => VoiceChatPins.GetValueOrDefault(mcVersion);

    /// <summary>One jar of a vetted Sodium/Iris alternate pair: <paramref name="family"/> is "sodium" or "iris".</summary>
    public static PerfMod? Alternate(string mcVersion, string family, string pairId) =>
        AlternatePins.GetValueOrDefault($"{mcVersion}:{family}-{pairId}");

    /// <summary>Every pinned version key, for the data tests.</summary>
    public static IReadOnlyCollection<string> FabricApiVersions => FabricApiPins.Keys;
    public static IReadOnlyCollection<string> VoiceChatVersions => VoiceChatPins.Keys;
    public static IEnumerable<KeyValuePair<string, PerfMod>> AlternateRows => AlternatePins;

    private static readonly Lazy<HashSet<string>> Sha512Set = new(() =>
    {
        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var mod in PerformanceModCatalog.AllPins()) Add(set, mod.Sha512);
        foreach (var mod in FabricApiPins.Values) Add(set, mod.Sha512);
        foreach (var mod in VoiceChatPins.Values) Add(set, mod.Sha512);
        foreach (var mod in AlternatePins.Values) Add(set, mod.Sha512);
        return set;
    });

    private static readonly Lazy<HashSet<string>> Sha256Set = new(() =>
    {
        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var sha in LegacyStackInstaller.Sha256Pins()) Add(set, sha);
        foreach (var build in LegacyForgeBuilds.Builds.Values) Add(set, build.UniversalSha256);
        return set;
    });

    private static void Add(HashSet<string> set, string? hex)
    {
        if (!string.IsNullOrEmpty(hex)) set.Add(hex);
    }

    /// <summary>True when this SHA-512 (hex, any case) is a file Origin pins anywhere.</summary>
    public static bool IsKnownSha512(string? hex) => hex != null && Sha512Set.Value.Contains(hex);

    /// <summary>True when this SHA-256 (hex, any case) is a legacy Forge file Origin pins.</summary>
    public static bool IsKnownSha256(string? hex) => hex != null && Sha256Set.Value.Contains(hex);
}

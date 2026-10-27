using OriginLauncher.App.Core.Loaders;

namespace OriginLauncher.App.Core.Compat;

/// <summary>
/// Fix Compatibility's FIRST source for a missing dependency: the files Origin itself pins for this exact Minecraft
/// version (Fabric API and Simple Voice Chat in ManagedPins, the performance stack in PerformanceModCatalog), each
/// with the SHA-512 Modrinth published for it. Pure data, no network: a missing mod Origin pins is never asked about
/// on Modrinth at all. The fetcher (ModrinthDependencyFetcher) downloads it from cdn.modrinth.com and the executor
/// refuses a file whose hash or own mod id is not the pinned one.
/// </summary>
public sealed class OriginDependencyCatalog : IDependencyCatalog
{
    public static readonly OriginDependencyCatalog Instance = new();

    /// <summary>The pinned file for <paramref name="id"/> on <paramref name="mcVersion"/>, by the mod id the jar declares.</summary>
    public static PerfMod? PinFor(string id, string mcVersion)
    {
        var perf = PerformanceModCatalog.TryGet(mcVersion);
        return id switch
        {
            // "fabric" is Fabric API's id on the old builds and an alias the newer ones provide.
            "fabric-api" or "fabric" => ManagedPins.FabricApi(mcVersion),
            "voicechat" => ManagedPins.VoiceChat(mcVersion),
            "sodium" => perf?.Sodium,
            "iris" => perf?.Iris,
            "indium" => perf?.Indium,
            "lithium" => perf?.Lithium,
            "ferritecore" => perf?.Ferrite,
            "krypton" => perf?.Krypton,
            "immediatelyfast" => perf?.ImmediatelyFast,
            "modernfix" => perf?.ModernFix,
            "modmenu" => perf?.ModMenu,
            "entityculling" => perf?.EntityCulling,
            _ => null,
        };
    }

    public DependencyOffer? Find(string id, VersionPredicate range, CompatPlatform platform)
    {
        if (platform.Loader != LoaderFamily.Fabric) return null;
        var pin = PinFor(id, platform.McVersion);
        // Only a pin that carries its hash and is served the way the fetcher accepts (https, cdn.modrinth.com, a plain
        // .jar name): anything else is no offer, never an unchecked download.
        if (pin == null || ModrinthFileGuard.Check(pin.Url, pin.FileName, pin.Sha512) != ModrinthDownloadResult.Installed)
            return null;
        if (!range.MatchesAnything && !range.IsSatisfiedBy(pin.Version)) return null;
        var info = new ModInfo { FileName = pin.FileName, Loader = ModLoader.Fabric, Id = id, Version = pin.Version, Name = id };
        return new DependencyOffer(id, pin.Version, pin.FileName, info, pin.Url, Sha512: pin.Sha512, FromOrigin: true);
    }
}

/// <summary>Asks each catalog in turn and takes the first offer: Origin's own pins, then Modrinth.</summary>
public sealed class LayeredDependencyCatalog : IDependencyCatalog
{
    private readonly IDependencyCatalog?[] _layers;

    public LayeredDependencyCatalog(params IDependencyCatalog?[] layers) => _layers = layers;

    public DependencyOffer? Find(string id, VersionPredicate range, CompatPlatform platform)
    {
        foreach (var layer in _layers)
            if (layer?.Find(id, range, platform) is { } offer) return offer;
        return null;
    }
}

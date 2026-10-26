using System.IO;

namespace OriginLauncher.App.Core.Compat;

// The vocabulary of repairs. A plan is plain data: RepairPlanner builds it from a CompatReport without touching disk,
// RepairExecutor is the only thing that applies it. A repair never deletes a user file: a mod is turned off by
// renaming foo.jar to foo.jar.disabled, and a missing dependency is a NEW file added beside the others. A jar is
// never rewritten in place (another account's running game may hold it).

/// <summary>A mod file a catalog can supply to meet a requirement, with the metadata it will have once it is there.</summary>
/// <param name="Info">What the file says about itself, known up front so the planner can check the repair would work.</param>
/// <param name="Source">Where the executor's fetcher gets it (a path or URL the fetcher understands).</param>
/// <param name="Sha256">When known, the file is verified against it before it is put in place.</param>
/// <param name="Sha512">The same for a SHA-512 (what Modrinth publishes; Fix Compatibility's offers always carry one).</param>
public sealed record DependencyOffer(
    string Id, string Version, string FileName, ModInfo Info, string Source, string? Sha256 = null, string? Sha512 = null);

/// <summary>Pure lookup of a file that would satisfy a missing requirement; null when there is none.</summary>
public interface IDependencyCatalog
{
    DependencyOffer? Find(string id, VersionPredicate range, CompatPlatform platform);
}

/// <summary>Brings an offered file to <paramref name="destinationPath"/> (a temporary path the executor then validates and moves into place).</summary>
public interface IDependencyFetcher
{
    bool Fetch(DependencyOffer offer, string destinationPath, out string? error);
}

/// <summary>What a plan may touch and with what help.</summary>
/// <param name="Platform">What the repaired set is checked against.</param>
/// <param name="DestinationFolder">Where new files go (the folder the set lives in).</param>
/// <param name="AllowedFolders">The only folders a step may change: the executor refuses a path anywhere else.</param>
/// <param name="IsProtected">Mods never to touch (Origin's own, anything else the caller names). Managed mods are always protected.</param>
/// <param name="Catalog">Where missing dependencies can come from; null = nothing is ever added.</param>
/// <param name="AddedRole">How an added file counts when the plan is simulated.</param>
public sealed record RepairContext(
    CompatPlatform Platform, string DestinationFolder, IReadOnlyList<string> AllowedFolders,
    Func<ModFile, bool>? IsProtected = null, IDependencyCatalog? Catalog = null, ModRole AddedRole = ModRole.SharedLoose)
{
    /// <summary>True when a missing dependency can be added at all.</summary>
    public bool CanFetch => Catalog != null;

    /// <summary>The version's shared mods folder (<see cref="OriginPaths.ModsDir"/>).</summary>
    public static RepairContext ForShared(CompatPlatform platform, string version,
        Func<ModFile, bool>? isProtected = null, IDependencyCatalog? catalog = null) =>
        new(platform, OriginPaths.ModsDir(version), new[] { OriginPaths.ModsDir(version) }, isProtected, catalog, ModRole.SharedLoose);

    /// <summary>One profile's own mods folder (<see cref="OriginPaths.ProfileModsDir"/>).</summary>
    public static RepairContext ForProfile(CompatPlatform platform, string version, string profileId,
        Func<ModFile, bool>? isProtected = null, IDependencyCatalog? catalog = null) =>
        new(platform, OriginPaths.ProfileModsDir(version, profileId), new[] { OriginPaths.ProfileModsDir(version, profileId) },
            isProtected, catalog, ModRole.Profile);
}

public abstract record RepairStep(string Why);

/// <summary>Turn a mod off: foo.jar becomes foo.jar.disabled (reversible by renaming back).</summary>
public sealed record DisableModStep(ModFile Mod, string Why, Certainty Certainty) : RepairStep(Why)
{
    public string From => Mod.Path;
    public string To => Mod.Path + ".disabled";
}

/// <summary>Add a missing dependency as a new file.</summary>
public sealed record AddModStep(DependencyOffer Offer, string DestinationPath, string Why) : RepairStep(Why);

/// <summary>A blocking finding the plan can't fix (Origin's own mod, a protected mod, nothing to add), with the plain reason.</summary>
public sealed record UnfixableFinding(CompatFinding Finding, string Reason);

/// <summary>What to do, in order, and what is left that this plan can't fix.</summary>
public sealed record RepairPlan(IReadOnlyList<RepairStep> Steps, IReadOnlyList<UnfixableFinding> Unfixable)
{
    public static readonly RepairPlan Empty = new(Array.Empty<RepairStep>(), Array.Empty<UnfixableFinding>());

    public bool IsEmpty => Steps.Count == 0;
}

/// <summary>One step as the executor handled it.</summary>
/// <param name="From">Disable: the file as it was. Add: null.</param>
/// <param name="To">Disable: the turned-off name. Add: the new file.</param>
public sealed record AppliedStep(RepairStep Step, bool Ok, string? Error, string? From, string? To);

public sealed record RepairResult(IReadOnlyList<AppliedStep> Steps)
{
    public bool AllOk => Steps.All(s => s.Ok);
    public IEnumerable<AppliedStep> Done => Steps.Where(s => s.Ok);
    public IEnumerable<AppliedStep> Failed => Steps.Where(s => !s.Ok);
}

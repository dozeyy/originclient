namespace OriginLauncher.App.Core.Compat;

public enum ModLoader { Unknown, Fabric, Quilt, Forge, NeoForge, LegacyForge }

/// <summary>Where a mod says it runs (fabric.mod.json "environment"). Fabric simply doesn't load a mod on the other side.</summary>
public enum ModEnvironment { Both, Client, Server }

/// <summary>
/// One "depends" / "breaks" / "recommends" / "conflicts" entry: a mod id and the version requirements (any one may
/// hold). A class (not a record) so the parsed <see cref="Predicate"/> is built once and the equality stays "same id,
/// same requirement text".
/// </summary>
public sealed class ModDependency : IEquatable<ModDependency>
{
    private VersionPredicate? _predicate;

    public ModDependency(string id, IReadOnlyList<string> ranges)
    {
        Id = id;
        Ranges = ranges;
    }

    public string Id { get; }
    public IReadOnlyList<string> Ranges { get; }

    public VersionPredicate Predicate => _predicate ??= VersionPredicate.Parse(Ranges);

    /// <summary>
    /// Whether <paramref name="version"/> meets the requirement. A null version (the provider states none) meets any
    /// requirement: with nothing known there is nothing to contradict.
    /// </summary>
    public bool IsSatisfiedBy(string? version) => version == null || Predicate.IsSatisfiedBy(version);

    public bool Equals(ModDependency? other) =>
        other != null && Id == other.Id && Ranges.SequenceEqual(other.Ranges);

    public override bool Equals(object? obj) => Equals(obj as ModDependency);
    public override int GetHashCode() => HashCode.Combine(Id, Ranges.Count);
    public override string ToString() => $"{Id} {Predicate.Display}";
}

/// <summary>A mod jar nested inside another (jar-in-jar): its own id, version, name and the ids it also answers to.</summary>
public sealed record NestedMod(string Id, string? Version, string? Name)
{
    public IReadOnlyList<string> Provides { get; init; } = Array.Empty<string>();
}

/// <summary>What a mod jar says about itself. Identity comes from metadata, never from the file name.</summary>
public sealed record ModInfo
{
    public required string FileName { get; init; }
    public ModLoader Loader { get; init; }
    public string? Id { get; init; }
    public string? Version { get; init; }
    public string? Name { get; init; }

    /// <summary>mcmod.info's "mcversion" (legacy Forge only).</summary>
    public string? McVersion { get; init; }
    public IReadOnlyList<ModDependency> Depends { get; init; } = Array.Empty<ModDependency>();
    public IReadOnlyList<ModDependency> Breaks { get; init; } = Array.Empty<ModDependency>();

    /// <summary>"recommends": the loader only warns when one is missing.</summary>
    public IReadOnlyList<ModDependency> Recommends { get; init; } = Array.Empty<ModDependency>();

    /// <summary>"conflicts": the loader only warns when one is present.</summary>
    public IReadOnlyList<ModDependency> Conflicts { get; init; } = Array.Empty<ModDependency>();
    public IReadOnlyList<string> Provides { get; init; } = Array.Empty<string>();

    /// <summary>A provided id that states its own version ("provides": [{"id": .., "version": ..}]); the mod's version otherwise.</summary>
    public IReadOnlyDictionary<string, string> ProvidedVersions { get; init; } = EmptyVersions;
    public IReadOnlyList<NestedMod> Nested { get; init; } = Array.Empty<NestedMod>();
    public ModEnvironment Environment { get; init; }

    /// <summary>The file is not a readable zip/jar at all.</summary>
    public bool ReadFailed { get; init; }

    private static readonly IReadOnlyDictionary<string, string> EmptyVersions = new Dictionary<string, string>();

    /// <summary>The requirement on Minecraft ("depends": {"minecraft": ...}), or null when the mod names none.</summary>
    public VersionPredicate? MinecraftRange =>
        Depends.FirstOrDefault(d => d.Id == "minecraft") is { } d ? d.Predicate : null;

    /// <summary>The version a provided id carries: the one it states, else this mod's own.</summary>
    public string? VersionProvided(string id) => ProvidedVersions.TryGetValue(id, out var v) ? v : Version;
}

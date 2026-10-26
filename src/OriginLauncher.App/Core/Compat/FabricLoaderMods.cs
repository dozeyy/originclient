namespace OriginLauncher.App.Core.Compat;

/// <summary>
/// The mods Fabric Loader itself puts into the mod set, besides <c>fabricloader</c>, <c>minecraft</c> and <c>java</c>.
/// Read from the real loader jars on disk (not recalled): since 0.15 the loader jar carries
/// <c>META-INF/jars/mixinextras-fabric-X.jar</c>, listed under <c>"jars"</c> in the loader's own fabric.mod.json, and that
/// nested jar's fabric.mod.json says <c>"id": "mixinextras"</c>, <c>"provides": ["com_github_llamalad7_mixinextras"]</c>.
/// A mod that depends on <c>mixinextras</c> without bundling it therefore starts fine, so it must never be reported as
/// missing a dependency.
///
/// Checked jars (gradle cache, 2026-10-25): fabric-loader-0.19.3.jar -> mixinextras-fabric-0.5.4.jar,
/// fabric-loader-0.19.5.jar -> mixinextras-fabric-0.5.5.jar, fabric-loader-0.20.1.jar -> mixinextras-fabric-0.5.5.jar
/// (its "jars" entry and the nested jar's own fabric.mod.json, read 2026-10-25). Add a row only after reading the next loader jar.
/// </summary>
internal static class FabricLoaderMods
{
    /// <param name="LoaderVersion">The Fabric Loader release the jar was read from.</param>
    /// <param name="MixinExtras">The version of the mixinextras jar nested in it.</param>
    internal sealed record Row(string LoaderVersion, string MixinExtras);

    /// <summary>Oldest to newest.</summary>
    internal static readonly IReadOnlyList<Row> Verified = new[]
    {
        new Row("0.19.3", "0.5.4"),
        new Row("0.19.5", "0.5.5"),
        new Row("0.20.1", "0.5.5"),
    };

    /// <summary>The ids mixinextras answers to (its own id plus the one it <c>provides</c>).</summary>
    internal static readonly IReadOnlyList<string> MixinExtrasIds = new[] { "mixinextras", "com_github_llamalad7_mixinextras" };

    /// <summary>
    /// The mods a Fabric Loader of this version provides. A loader version that is unknown (the pre-launch check does not
    /// know which one CmlLib installed: always the newest at install time) or newer than the table gets the newest verified
    /// row, so a mod is never blocked over something the loader really provides; an older one gets the oldest row.
    /// </summary>
    internal static IEnumerable<(string Id, string Version)> For(string? loaderVersion)
    {
        var row = Verified[^1];
        if (Version.TryParse(loaderVersion, out var wanted))
        {
            row = Verified[0];
            foreach (var candidate in Verified)
                if (Version.TryParse(candidate.LoaderVersion, out var v) && v <= wanted) row = candidate;
        }
        foreach (var id in MixinExtrasIds) yield return (id, row.MixinExtras);
    }
}

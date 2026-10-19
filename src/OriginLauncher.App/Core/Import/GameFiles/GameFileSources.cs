using System.IO;

namespace OriginLauncher.App.Core.Import.GameFiles;

/// <summary>
/// Where the other launchers on this PC keep what Minecraft needs. Every root is passed in (the engine
/// asks the OS for nothing); a null root is a launcher that isn't there.
/// </summary>
public sealed record GameFileRoots
{
    /// <summary>instances/ — Origin's own folders: the target is written here, the other versions' folders are read as sources.</summary>
    public required string InstancesRoot { get; init; }

    /// <summary>The official launcher's .minecraft (versions/, libraries/, assets/ and a legacy runtime/).</summary>
    public string? OfficialMinecraftDir { get; init; }

    /// <summary>
    /// Folders holding the official launcher's Java runtimes as runtime\&lt;component&gt;\windows-x64\&lt;component&gt;\ —
    /// the Store launcher's (%LOCALAPPDATA%\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime)
    /// and the legacy one (%APPDATA%\.minecraft\runtime).
    /// </summary>
    public IReadOnlyList<string> OfficialRuntimeRoots { get; init; } = Array.Empty<string>();

    /// <summary>Prism's data folder (libraries/ and assets/; its client jar sits in libraries/com/mojang/minecraft/).</summary>
    public string? PrismDataDir { get; init; }

    /// <summary>Modrinth app's meta folder (versions/, libraries/, assets/).</summary>
    public string? ModrinthMetaDir { get; init; }

    /// <summary>CurseForge's minecraft folder (Install/versions, libraries, assets, runtime).</summary>
    public string? CurseForgeRoot { get; init; }
}

/// <summary>
/// One place a required file may already exist, with the layout rules of that launcher. Read-only:
/// the engine copies out of it and never writes, links or references it (CmlLib would download
/// into whatever folder it is pointed at, and a hardlink would share bytes with the source).
/// </summary>
public sealed class GameFileSource
{
    public required string Name { get; init; }
    public string? VersionsDir { get; init; }
    public string? LibrariesDir { get; init; }
    public string? AssetsDir { get; init; }

    /// <summary>Prism keeps the client jar at libraries/com/mojang/minecraft/&lt;v&gt;/minecraft-&lt;v&gt;-client.jar.</summary>
    public bool PrismClientJar { get; init; }

    /// <summary>Official layout: &lt;root&gt;/&lt;component&gt;/windows-x64/&lt;component&gt;/&lt;path&gt;.</summary>
    public IReadOnlyList<string> OfficialRuntimeRoots { get; init; } = Array.Empty<string>();

    /// <summary>Origin / CmlLib layout: &lt;dir&gt;/&lt;component&gt;/&lt;path&gt; (dir = instances/&lt;v&gt;/runtime/windows-x64).</summary>
    public string? OriginRuntimeDir { get; init; }

    /// <summary>The places <paramref name="file"/> could be in this source (existing or not), best first.</summary>
    public IEnumerable<string> Candidates(RequiredFile file, string mcVersion)
    {
        switch (file.Kind)
        {
            case RequiredKind.ClientJar:
                if (VersionsDir != null) yield return Path.Combine(VersionsDir, mcVersion, mcVersion + ".jar");
                if (PrismClientJar && LibrariesDir != null)
                    yield return Path.Combine(LibrariesDir, "com", "mojang", "minecraft", mcVersion, $"minecraft-{mcVersion}-client.jar");
                break;
            case RequiredKind.Library:
            case RequiredKind.Native:
                if (LibrariesDir != null) yield return Path.Combine(LibrariesDir, file.Id!.Replace('/', Path.DirectorySeparatorChar));
                break;
            case RequiredKind.AssetIndex:
                if (AssetsDir != null) yield return Path.Combine(AssetsDir, "indexes", file.Id + ".json");
                break;
            case RequiredKind.Asset:
                if (AssetsDir != null) yield return Path.Combine(AssetsDir, "objects", file.Id![..2], file.Id);
                break;
            case RequiredKind.Runtime:
                var sub = file.SubPath!.Replace('/', Path.DirectorySeparatorChar);
                foreach (var root in OfficialRuntimeRoots)
                    yield return Path.Combine(root, file.Component!, "windows-x64", file.Component!, sub);
                if (OriginRuntimeDir != null) yield return Path.Combine(OriginRuntimeDir, file.Component!, sub);
                break;
        }
    }

    /// <summary>Every source the roots describe for <paramref name="targetVersion"/> (Origin's own other versions included, the target itself not).</summary>
    public static List<GameFileSource> Build(GameFileRoots roots, string targetVersion)
    {
        var list = new List<GameFileSource>();
        if (Present(roots.OfficialMinecraftDir) is { } mc)
        {
            var runtimes = roots.OfficialRuntimeRoots.Where(Directory.Exists).ToList();
            var legacy = Path.Combine(mc, "runtime");
            if (Directory.Exists(legacy) && !runtimes.Contains(legacy, StringComparer.OrdinalIgnoreCase)) runtimes.Add(legacy);
            list.Add(new GameFileSource
            {
                Name = "Minecraft launcher", VersionsDir = Path.Combine(mc, "versions"), LibrariesDir = Path.Combine(mc, "libraries"),
                AssetsDir = Path.Combine(mc, "assets"), OfficialRuntimeRoots = runtimes,
            });
        }
        else if (roots.OfficialRuntimeRoots.Where(Directory.Exists).ToList() is { Count: > 0 } onlyRuntimes)
        {
            list.Add(new GameFileSource { Name = "Minecraft launcher (Java)", OfficialRuntimeRoots = onlyRuntimes });
        }
        if (Present(roots.PrismDataDir) is { } prism)
            list.Add(new GameFileSource
            {
                Name = "Prism", LibrariesDir = Path.Combine(prism, "libraries"), AssetsDir = Path.Combine(prism, "assets"),
                PrismClientJar = true,
            });
        if (Present(roots.ModrinthMetaDir) is { } meta)
            list.Add(new GameFileSource
            {
                Name = "Modrinth", VersionsDir = Path.Combine(meta, "versions"), LibrariesDir = Path.Combine(meta, "libraries"),
                AssetsDir = Path.Combine(meta, "assets"),
            });
        if (Present(roots.CurseForgeRoot) is { } cf)
        {
            var install = Path.Combine(cf, "Install");
            list.Add(new GameFileSource
            {
                Name = "CurseForge", VersionsDir = Path.Combine(install, "versions"), LibrariesDir = Path.Combine(install, "libraries"),
                AssetsDir = Path.Combine(install, "assets"), OfficialRuntimeRoots = new[] { Path.Combine(install, "runtime") },
            });
        }
        if (Directory.Exists(roots.InstancesRoot))
        {
            IEnumerable<string> versions;
            try { versions = Directory.EnumerateDirectories(roots.InstancesRoot).ToList(); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { versions = Array.Empty<string>(); }
            foreach (var versionRoot in versions.OrderBy(v => v, StringComparer.OrdinalIgnoreCase))
            {
                if (string.Equals(Path.GetFileName(versionRoot), targetVersion, StringComparison.OrdinalIgnoreCase)) continue;
                list.Add(new GameFileSource
                {
                    Name = "Origin " + Path.GetFileName(versionRoot), VersionsDir = Path.Combine(versionRoot, "versions"),
                    LibrariesDir = Path.Combine(versionRoot, "libraries"), AssetsDir = Path.Combine(versionRoot, "assets"),
                    OriginRuntimeDir = Path.Combine(versionRoot, "runtime", "windows-x64"),
                });
            }
        }
        return list;
    }

    private static string? Present(string? dir) => !string.IsNullOrWhiteSpace(dir) && Directory.Exists(dir) ? dir : null;
}

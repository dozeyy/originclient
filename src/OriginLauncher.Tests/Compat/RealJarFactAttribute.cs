using OriginLauncher.App.Core.Compat;
using Xunit;

namespace OriginLauncher.Tests.Compat;

/// <summary>Which real jars a test reads. They exist only on a machine that built the mod (never on a CI runner).</summary>
public enum RealJarSet
{
    /// <summary>Fabric API 0.116.x in the gradle cache and the built 1.21.1 originclient jar.</summary>
    FabricApiAndOrigin,
    /// <summary>At least one of the Fabric Loader jars the <see cref="FabricLoaderMods"/> table was read from.</summary>
    FabricLoader,
}

/// <summary>
/// A [Fact] that is SKIPPED, visibly, when the real jars it needs are not on this machine. A test that returns early
/// when its input is missing reports green having checked nothing; a skip reports what it did not run.
/// </summary>
[AttributeUsage(AttributeTargets.Method)]
public sealed class RealJarFactAttribute : FactAttribute
{
    public RealJarFactAttribute(RealJarSet need)
    {
        var missing = RealJars.Missing(need);
        if (missing != null) Skip = "needs real jars that are not on this machine: " + missing;
    }
}

internal static class RealJars
{
    public static string GradleFiles { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".gradle", "caches", "modules-2", "files-2.1");

    public static string? FabricApi() =>
        Find(Path.Combine(GradleFiles, "net.fabricmc.fabric-api", "fabric-api"), "fabric-api-0.116");

    public static string? OriginMod() =>
        RepoRoot() is { } root ? Find(Path.Combine(root, "src", "mods", "versions", "1.21.1", "build", "libs"), "originclient-") : null;

    public static string? FabricLoader(string loaderVersion) =>
        Find(Path.Combine(GradleFiles, "net.fabricmc", "fabric-loader", loaderVersion), $"fabric-loader-{loaderVersion}.jar", "");

    /// <summary>What is missing for <paramref name="need"/>, or null when everything is there.</summary>
    public static string? Missing(RealJarSet need) => need switch
    {
        RealJarSet.FabricApiAndOrigin =>
            FabricApi() == null ? "Fabric API 0.116 in the gradle cache"
            : OriginMod() == null ? "the built 1.21.1 originclient jar" : null,
        RealJarSet.FabricLoader =>
            FabricLoaderMods.Verified.Any(r => FabricLoader(r.LoaderVersion) != null) ? null : "a Fabric Loader jar in the gradle cache",
        _ => null,
    };

    public static string? Find(string folder, string prefix, string suffix = ".jar")
    {
        if (!Directory.Exists(folder)) return null;
        return Directory.GetFiles(folder, prefix + "*" + suffix, SearchOption.AllDirectories)
            .Where(f => !f.EndsWith("-sources.jar", StringComparison.OrdinalIgnoreCase) && !f.EndsWith("-dev.jar", StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(f => f, StringComparer.OrdinalIgnoreCase).FirstOrDefault();
    }

    /// <summary>The repo checkout, or the main checkout when this is a git worktree.</summary>
    public static string? RepoRoot()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir != null; dir = dir.Parent)
        {
            var git = Path.Combine(dir.FullName, ".git");
            if (Directory.Exists(git)) return dir.FullName;
            if (File.Exists(git)) // a worktree: ".git" is a file naming <main>/.git/worktrees/<name>
            {
                var line = File.ReadAllText(git).Trim();
                if (line.StartsWith("gitdir:", StringComparison.Ordinal))
                {
                    var gitdir = line["gitdir:".Length..].Trim().Replace('/', Path.DirectorySeparatorChar);
                    var marker = Path.DirectorySeparatorChar + ".git" + Path.DirectorySeparatorChar;
                    var at = gitdir.IndexOf(marker, StringComparison.Ordinal);
                    if (at > 0) return gitdir[..at];
                }
                return dir.FullName;
            }
        }
        return null;
    }
}

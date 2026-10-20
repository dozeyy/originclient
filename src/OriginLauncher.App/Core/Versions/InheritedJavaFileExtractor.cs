using System.Net.Http;
using CmlLib.Core;
using CmlLib.Core.FileExtractors;
using CmlLib.Core.Files;
using CmlLib.Core.Rules;
using CmlLib.Core.Version;

namespace OriginLauncher.App.Core.Versions;

/// <summary>
/// CmlLib's Java download, minus the Java a launch never runs.
///
/// CmlLib installs files for EVERY version in the inheritance chain, and its
/// JavaFileExtractor treats a version that names no Java as "needs jre-legacy"
/// (Java 8). A Fabric loader profile (and the 1.8.9 Forge profile) names no Java,
/// so every launch of a Fabric version also downloaded Mojang's Java 8 next to
/// the Java the game actually runs: on a first 1.21.1 launch that was 192 extra
/// files / 150 MB (measured, launch-time run 39361931276), plus two extra
/// manifest fetches and 192 extra file checks on every later launch.
///
/// The game's Java comes from the nearest version in the chain that names one
/// (MinecraftLauncher.GetJavaPath -> GetInheritedProperty(JavaVersion)), so a
/// version that names none while an ancestor does contributes nothing that is
/// ever run: it is skipped here. Every other case goes to CmlLib unchanged, so a
/// chain where nobody names a Java still gets jre-legacy exactly as before, and
/// the ancestor that does name one still downloads it.
/// </summary>
public sealed class InheritedJavaFileExtractor : IFileExtractor
{
    private readonly IFileExtractor _inner;

    public InheritedJavaFileExtractor(IFileExtractor inner) => _inner = inner;

    public ValueTask<IEnumerable<GameFile>> Extract(
        MinecraftPath path, IVersion version, RulesEvaluatorContext rulesContext, CancellationToken cancellationToken)
    {
        if (string.IsNullOrEmpty(version.JavaVersion?.Component) && AncestorNamesJava(version))
            return ValueTask.FromResult(Enumerable.Empty<GameFile>());
        return _inner.Extract(path, version, rulesContext, cancellationToken);
    }

    private static bool AncestorNamesJava(IVersion version)
    {
        for (var v = version.ParentVersion; v != null; v = v.ParentVersion)
            if (!string.IsNullOrEmpty(v.JavaVersion?.Component)) return true;
        return false;
    }

    /// <summary>
    /// Swaps CmlLib's Java extractor in the launcher's list for this one, fetching
    /// Java's metadata through <paramref name="javaMetaHttp"/> (MojangMetaCache).
    /// </summary>
    public static void Install(MinecraftLauncherParameters parameters, HttpClient javaMetaHttp)
    {
        var list = parameters.FileExtractors;
        if (list == null || parameters.JavaPathResolver == null) return;
        var java = list.FirstOrDefault(e => e is JavaFileExtractor);
        if (java == null) return;
        list.Remove(java);
        list.Add(new InheritedJavaFileExtractor(new JavaFileExtractor(javaMetaHttp, parameters.JavaPathResolver)));
    }
}

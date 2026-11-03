using System.Runtime.InteropServices;

namespace OriginLauncher.App.Core.Updates;

/// <summary>
/// Which self-update zip a Mac launcher takes from a release. Each Mac build is one architecture
/// (Apple Silicon and Intel are separate, self-contained builds), so an Apple Silicon launcher only
/// ever updates to the next Apple Silicon zip and an Intel one to the next Intel zip. The zip's
/// SHA-256 sits next to it as zip name + ".sha256", exactly like Windows' win-x64 zip.
///
/// These names must match what .github/workflows/launcher-release.yml publishes;
/// tools/release/check_consistency.py (check_mac_release) fails CI when they disagree.
/// Pure (no OS calls), so the Windows-mode test run checks the name choice too.
/// </summary>
public static class MacReleaseAssets
{
    public const string AppleSiliconZipPrefix = "OriginLauncher-osx-arm64";
    public const string IntelZipPrefix = "OriginLauncher-osx-x64";

    /// <summary>
    /// The zip name without ".zip" for a process architecture. An architecture no Mac build exists
    /// for gets a name no release publishes, so it is never offered an update (never a wrong-arch one).
    /// </summary>
    public static string ZipPrefixFor(Architecture arch) => arch switch
    {
        Architecture.Arm64 => AppleSiliconZipPrefix,
        Architecture.X64 => IntelZipPrefix,
        _ => "OriginLauncher-osx-unsupported-" + arch.ToString().ToLowerInvariant(),
    };

    public static string ZipNameFor(Architecture arch) => ZipPrefixFor(arch) + ".zip";

    public static string Sha256NameFor(Architecture arch) => ZipNameFor(arch) + ".sha256";
}

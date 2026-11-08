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

    /// <summary>The disk images the website offers (launcher-release.yml; check_consistency.py MAC_DMGS).</summary>
    public const string AppleSiliconDmg = "Origin-Mac-AppleSilicon.dmg";
    public const string IntelDmg = "Origin-Mac-Intel.dmg";

    /// <summary>
    /// The newest release's Apple Silicon disk image. The launcher already reads its updates from these GitHub
    /// releases (UpdateService.FeedUrl); "latest/download" always names the newest one, so the link never goes stale.
    /// </summary>
    public static readonly Uri AppleSiliconDownload = new($"https://github.com/dozeyy/originclient/releases/latest/download/{AppleSiliconDmg}");

    /// <summary>
    /// Which zip a self-update takes: the one for the MACHINE's CPU, not this process's. They differ only for the
    /// Intel build running under Rosetta on an Apple Silicon Mac (the player clicked the Intel download, or Migration
    /// Assistant carried the app over), where the process reports X64 but the machine is Arm64. That build crashes
    /// in the sign-in web view under Rosetta, so its next update installs the Apple Silicon app instead of the Intel
    /// one again (the swap replaces the whole .app, so changing CPU on the way is safe).
    /// </summary>
    public static string ZipPrefixForUpdate(Architecture process, Architecture machine) =>
        ZipPrefixFor(machine == Architecture.Arm64 ? Architecture.Arm64 : process);

    /// <summary>
    /// The one-time notice for the Intel build under Rosetta on an Apple Silicon Mac, or null when the build already
    /// matches the machine. Title, message and the button that opens <see cref="AppleSiliconDownload"/>.
    /// </summary>
    public static (string Title, string Message, string Download, string Later)? NativeBuildNotice(Architecture process, Architecture machine) =>
        process == Architecture.X64 && machine == Architecture.Arm64
            ? ("Get Origin for Apple Silicon",
               "This copy of Origin is the Intel version, running through Rosetta on your Apple Silicon Mac. "
               + "Microsoft sign-in can crash it there. The Apple Silicon version is faster and signs in normally: "
               + "download it, drag it to Applications and replace this one. Your accounts and worlds stay.",
               "Download", "Not now")
            : null;
}

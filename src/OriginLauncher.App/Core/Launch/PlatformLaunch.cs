using CmlLib.Core;
using CmlLib.Core.ProcessBuilder;

namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// The per-OS parts of building a game launch, called by VersionManager at three points. Windows
/// (PlatformLaunch.Windows.cs) does nothing at any of them: CmlLib's defaults are the Windows launch.
/// The Mac (PlatformLaunch.Mac.cs) applies MacLaunchPolicy: the Java/natives arch, the Rosetta check and
/// the LWJGL 2 argument fix.
/// </summary>
public static partial class PlatformLaunch
{
    /// <summary>Before anything is downloaded. Throws <see cref="RosettaRequiredException"/> when the version can't run on this Mac yet.</summary>
    public static partial void EnsureCanRun(string version);

    /// <summary>Right after the CmlLib launcher is built: the OS and arch its library rules, natives and Java resolve as.</summary>
    public static partial void Configure(MinecraftLauncher launcher, string version);

    /// <summary>Just before the process is built: OS-specific JVM arguments.</summary>
    public static partial void AdjustOption(string version, MLaunchOption option);

    /// <summary>
    /// After the process is built, before it starts: Windows hands Java the data folder's short name when Java can't
    /// read its real one (JavaPathEncoding). Throws <see cref="JavaPathUnreadableException"/> when it can't be helped.
    /// Mac and Linux pass UTF-8 and do nothing.
    /// </summary>
    public static void AfterBuild(System.Diagnostics.Process built) => AfterBuildCore(built);

    static partial void AfterBuildCore(System.Diagnostics.Process built);
}

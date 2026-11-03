using System.Runtime.InteropServices;
using CmlLib.Core;
using CmlLib.Core.ProcessBuilder;

namespace OriginLauncher.App.Core.Launch;

// Mac: MacLaunchPolicy decides the arch per version and CPU (the rules and their evidence live there).
public static partial class PlatformLaunch
{
    /// <summary>
    /// This Mac's CPU. hw.optional.arm64 is 1 on Apple Silicon even when the asking process runs under
    /// Rosetta, so an x64 launcher build still sees the real machine; an Intel Mac has no such name.
    /// </summary>
    public static Architecture HostArch { get; } =
        SystemInfo.SysctlInt64("hw.optional.arm64") == 1 ? Architecture.Arm64 : Architecture.X64;

    // The words the crash report uses for this computer (CrashRules). A Mac's graphics drivers ship inside macOS, so
    // "update your driver" means a macOS update.
    public static string SystemName => "macOS";
    public static string MachineName => "Mac";
    public static string DriverUpdateAdvice => "Update macOS (it carries the Mac's graphics drivers)";

    /// <summary>How <paramref name="version"/> runs on this Mac.</summary>
    public static MacLaunchShape ShapeFor(string version) => MacLaunchPolicy.For(version, HostArch);

    public static partial void EnsureCanRun(string version)
    {
        if (ShapeFor(version).UnderRosetta && !MacRosetta.IsInstalled())
            throw new RosettaRequiredException(version);
    }

    // CmlLib's default context is LauncherOSRule.Current (osx + this process's arch). Replacing only the OS
    // keeps its features; the same context drives the library rules, the natives and the Java folder.
    public static partial void Configure(MinecraftLauncher launcher, string version) =>
        launcher.RulesContext = launcher.RulesContext with
        {
            OS = MacLaunchPolicy.RulesOs(ShapeFor(version), Environment.OSVersion.Version.ToString()),
        };

    public static partial void AdjustOption(string version, MLaunchOption option)
    {
        // The Dock and the app switcher show "Minecraft", not "java". Never via CmlLib's DockName/DockIcon: CmlLib
        // 4.0.6 writes them as TWO arguments ("-Xdock:name" "Minecraft"), and java takes the second one as the main
        // class, so no version started. One "-Xdock:name=Minecraft" argument is what the JDK reads.
        if (option.ExtraJvmArguments?.Any(a => a.Values?.Contains(MacLaunchPolicy.DockNameArgument) == true) != true)
            option.ExtraJvmArguments = (option.ExtraJvmArguments ?? Enumerable.Empty<MArgument>())
                .Append(new MArgument(MacLaunchPolicy.DockNameArgument)).ToList();

        // LWJGL 2 (1.8.9): its legacy JSON has no jvm arguments, so CmlLib would add its
        // DefaultJvmArguments, whose only macOS entry is -XstartOnFirstThread (LWJGL 3 only, see
        // MacLaunchPolicy). JvmArgumentOverrides replaces those defaults AND the extra list, so the extra
        // list is carried over as the overrides; -cp, natives, -Xmx/-Xms and the main class are still added.
        if (ShapeFor(version).Lwjgl2)
            option.JvmArgumentOverrides = (option.ExtraJvmArguments ?? Enumerable.Empty<MArgument>())
                .Where(a => a.Values?.Contains(MacLaunchPolicy.StartOnFirstThread) != true)
                .ToList();
    }
}

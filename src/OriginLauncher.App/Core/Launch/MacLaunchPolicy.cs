using System.Runtime.InteropServices;
using CmlLib.Core.Rules;

namespace OriginLauncher.App.Core.Launch;

/// <summary>Which CPU a Mac launch's Java (and its native libraries) is built for.</summary>
public enum MacJavaArch { Arm64, X64 }

/// <summary>
/// How one Minecraft version runs on one Mac: the Java and natives arch, whether that means Rosetta 2
/// on this Mac, and whether the version is on LWJGL 2. Pure (no files, no processes), so the Windows
/// test suite pins it too; the Mac launch path reads it in Core/Launch/PlatformLaunch.Mac.cs.
/// </summary>
public sealed record MacLaunchShape(string Version, MacJavaArch JavaArch, bool UnderRosetta, bool Lwjgl2);

/// <summary>
/// The Mac launch rules, one place (2026-11-02, design pass + macos-15 runner probes):
///  * Apple Silicon runs a version natively only when Mojang ships BOTH halves for arm64: an arm64 Java
///    in its runtime manifest (mac-os-arm64) and arm64 LWJGL natives. That starts at 1.19 (LWJGL 3.3.1,
///    natives-macos-arm64; Java 17 "gamma" and later). 1.16.5 / 1.17.1 / 1.18.2 (LWJGL 3.2.x, x64-only
///    natives; jre-legacy / alpha / beta have no mac-os-arm64 build) run x64 Java under Rosetta 2, and
///    CmlLib must resolve them as an x64 Mac, or it looks for Java in mac-os-arm64 only and finds none.
///  * 1.8.9 (and 1.12.2, pulled) is LWJGL 2.9.2 with x64 natives and Temurin 8u504 x64 (Adoptium has no
///    aarch64 Java 8 for macOS): Rosetta on Apple Silicon too.
///  * An Intel Mac runs everything x64, no Rosetta.
///  * -XstartOnFirstThread is LWJGL 3 only. Mojang's own JSON carries it for 1.13+ (arguments.jvm, osx
///    rule); a legacy JSON has no jvm arguments, so CmlLib falls back to its DefaultJvmArguments, which add
///    it for every osx launch. LWJGL 2 creates its Cocoa window from AppKit's thread and does not want the
///    Java main thread to be it: Prism Launcher adds the flag only to LWJGL 3 versions
///    (PrismLauncher/meta, meta/run/generate_mojang.py: "if it uses LWJGL 3, add the trait that enables
///    starting on first thread on macOS"). So an LWJGL 2 Mac launch drops it.
/// </summary>
public static class MacLaunchPolicy
{
    /// <summary>The JVM flag LWJGL 3 needs on macOS (GLFW must own the process's first thread).</summary>
    public const string StartOnFirstThread = "-XstartOnFirstThread";

    /// <summary>The shape of <paramref name="version"/> on a Mac whose CPU is <paramref name="host"/>.</summary>
    public static MacLaunchShape For(string version, Architecture host)
    {
        var appleSilicon = host == Architecture.Arm64;
        var arch = appleSilicon && HasArm64Build(version) ? MacJavaArch.Arm64 : MacJavaArch.X64;
        return new MacLaunchShape(version, arch, UnderRosetta: appleSilicon && arch == MacJavaArch.X64,
            Lwjgl2: UsesLwjgl2(version));
    }

    /// <summary>True when Mojang ships an arm64 Java and arm64 LWJGL natives for the version (1.19+, every 26.x).</summary>
    public static bool HasArm64Build(string version) =>
        Parse(version) is var (major, minor) && (major >= 2 || (major == 1 && minor >= 19));

    /// <summary>Pre-1.13 versions run LWJGL 2 (1.8.9, 1.12.2): no -XstartOnFirstThread.</summary>
    public static bool UsesLwjgl2(string version) =>
        Parse(version) is var (major, minor) && major == 1 && minor < 13;

    /// <summary>
    /// The OS CmlLib resolves this launch as: library rules, natives and the Java runtime folder
    /// (MinecraftJavaManifestResolver.GetOSNameForJava: "64" = mac-os, "arm64" = mac-os-arm64).
    /// </summary>
    public static LauncherOSRule RulesOs(MacLaunchShape shape, string osVersion) =>
        new(LauncherOSRule.OSX, shape.JavaArch == MacJavaArch.Arm64 ? LauncherOSRule.Arm64 : LauncherOSRule.X64, osVersion);

    // "1.21.11" -> (1, 21), "26.3" -> (26, 3); anything else (a snapshot name) -> (0, 0), which counts as old:
    // x64 under Rosetta is the choice that runs everywhere a Mac can run anything.
    private static (int Major, int Minor) Parse(string version)
    {
        var parts = version.Split('.');
        return parts.Length >= 2 && int.TryParse(parts[0], out var major) && int.TryParse(parts[1], out var minor)
            ? (major, minor)
            : (0, 0);
    }
}

/// <summary>
/// The version needs Rosetta 2 on this Apple Silicon Mac and Rosetta is not installed. Thrown before any
/// download, so nothing is half-installed. The launch screen shows <see cref="PlayFailure"/>'s Rosetta
/// message with an "Install Rosetta" button; only that click runs MacRosetta.InstallAsync.
/// </summary>
public sealed class RosettaRequiredException : Exception
{
    public RosettaRequiredException(string version)
        : base($"Minecraft {version} needs Rosetta 2 on this Mac, and Rosetta is not installed.")
    {
        Version = version;
    }

    public string Version { get; }
}

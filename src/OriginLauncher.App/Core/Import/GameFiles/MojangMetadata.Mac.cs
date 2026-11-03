using System.Text.Json;
using OriginLauncher.App.Core.Launch;

namespace OriginLauncher.App.Core.Import.GameFiles;

// Mac: Mojang calls macOS "osx" in library rules and natives maps (1.8.9's "natives-osx", the
// natives-macos / natives-macos-arm64 libraries of LWJGL 3 versions both carry an osx rule). The Java
// runtime is per VERSION, not per machine: MacLaunchPolicy runs 1.19+ native on Apple Silicon
// ("mac-os-arm64") and everything older x64 under Rosetta ("mac-os"; jre-legacy has no arm64 build),
// so the import fetches and copies exactly the Java that Play will launch. The CPU comes from
// PlatformLaunch.HostArch (hw.optional.arm64: the real machine, even for the x64 build under Rosetta).
public sealed partial class MojangMetadata
{
    internal static string RuntimePlatformFor(string mcVersion) =>
        MacLaunchPolicy.JavaRuntimePlatform(MacLaunchPolicy.For(mcVersion, PlatformLaunch.HostArch));

    private const string RuleOsName = "osx";

    // An arch-named rule applies only to the arch this version runs as; none name a Mac arch today.
    private static bool ArchApplies(JsonElement os, string mcVersion) =>
        !os.TryGetProperty("arch", out var arch) || arch.ValueKind != JsonValueKind.String
        || string.Equals(arch.GetString(), MacLaunchPolicy.RuleArch(MacLaunchPolicy.For(mcVersion, PlatformLaunch.HostArch)), StringComparison.Ordinal);
}

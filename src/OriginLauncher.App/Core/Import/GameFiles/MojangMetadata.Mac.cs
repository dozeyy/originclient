using System.Runtime.InteropServices;
using System.Text.Json;

namespace OriginLauncher.App.Core.Import.GameFiles;

// Mac: Mojang calls macOS "osx" in library rules and natives maps (1.8.9's "natives-osx", the
// natives-macos / natives-macos-arm64 libraries of LWJGL 3 versions both carry an osx rule), and
// its Java runtime list has "mac-os-arm64" for Apple Silicon and "mac-os" for Intel. The machine
// decides, not this process: the x64 build running under Rosetta (sysctl.proc_translated = 1) is
// still on Apple Silicon, so it gets the native arm64 Java too.
public sealed partial class MojangMetadata
{
    private static readonly bool AppleSilicon =
        RuntimeInformation.OSArchitecture == Architecture.Arm64 || SystemInfo.SysctlInt64("sysctl.proc_translated") == 1;

    internal static readonly string RuntimePlatform = AppleSilicon ? "mac-os-arm64" : "mac-os";

    private const string RuleOsName = "osx";

    // An arch-named rule (Mojang has used "x86") applies only to that arch; none name a Mac arch today.
    private static bool ArchApplies(JsonElement os) =>
        !os.TryGetProperty("arch", out var arch) || arch.ValueKind != JsonValueKind.String
        || string.Equals(arch.GetString(), AppleSilicon ? "arm64" : "x86_64", StringComparison.Ordinal);
}

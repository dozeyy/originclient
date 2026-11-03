using System.Runtime.InteropServices;
using System.Text.Json;

namespace OriginLauncher.App.Core.Import.GameFiles;

// Linux: Mojang's rule and natives name is "linux" (1.8.9's "natives-linux", LWJGL 3's
// natives-linux libraries all carry {"os": {"name": "linux"}}), and its Java runtime list calls
// x64 Linux "linux". Mojang publishes no arm64 Linux Java ("linux-arm64" is not in
// java-runtime/all.json, checked 2026-11-02) and no natives-linux-arm64 LWJGL either, so on arm64
// the runtime part of the list is skipped (JavaSkipped) instead of pointing at x64 files.
// 1.8.9 needs no special case: its LWJGL 2.9.4 entries are allowed on every OS but osx, so Linux
// keeps 2.9.4 and never sees the osx-only 2.9.2 (LibraryRulesTests pins that).
public sealed partial class MojangMetadata
{
    private static readonly bool Arm64 = RuntimeInformation.OSArchitecture == Architecture.Arm64;

    internal static readonly string RuntimePlatform = Arm64 ? "linux-arm64" : "linux";

    // The same runtime for every version on Linux (the per-version choice is a Mac thing: Rosetta for old LWJGL).
    internal static string RuntimePlatformFor(string mcVersion) => RuntimePlatform;

    private const string RuleOsName = "linux";

    // An arch-named rule applies only to that arch: Mojang has used "x86" (32-bit, never this
    // launcher) and "arm64"; "x86_64"/"amd64"/"aarch64" are accepted as their usual synonyms.
    private static bool ArchApplies(JsonElement os, string mcVersion) => ArchApplies(os);

    private static bool ArchApplies(JsonElement os)
    {
        if (!os.TryGetProperty("arch", out var arch) || arch.ValueKind != JsonValueKind.String) return true;
        return arch.GetString() switch
        {
            "arm64" or "aarch64" => Arm64,
            "x64" or "x86_64" or "amd64" => !Arm64,
            _ => false,
        };
    }
}

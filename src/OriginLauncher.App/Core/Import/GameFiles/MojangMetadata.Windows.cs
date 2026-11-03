using System.Text.Json;

namespace OriginLauncher.App.Core.Import.GameFiles;

// Windows x64: Mojang's rule name "windows", runtime folder "windows-x64".
public sealed partial class MojangMetadata
{
    internal const string RuntimePlatform = "windows-x64";
    private const string RuleOsName = "windows";

    // Mojang only names x86 / arm64 for the non-default builds; this launcher runs x64.
    private static bool ArchApplies(JsonElement os) => !os.TryGetProperty("arch", out _);
}

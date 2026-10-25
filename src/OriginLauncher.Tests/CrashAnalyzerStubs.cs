// CrashAnalyzer is linked into this project (CrashAnalyzerTests) but the real ModManager drags in the WPF-side
// loader installers. This stand-in keeps the same rule CrashAnalyzer needs: Origin's own jars are "managed".
namespace OriginLauncher.App.Core.Mods;

internal static class ModManager
{
    public static bool IsManaged(string jarName)
    {
        var n = jarName.ToLowerInvariant();
        return n.StartsWith("originclient") || n.StartsWith("fabric-api-");
    }
}

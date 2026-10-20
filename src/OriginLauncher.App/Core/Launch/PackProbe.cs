using System.IO;
using System.Linq;
namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// Looks at the folders that make an instance "pack-heavy" (HeapPlan.IsHeavy). Does file
/// system reads, so callers run it off the UI thread. Any problem reading = "not heavy".
/// </summary>
public static class PackProbe
{
    public static bool IsHeavy(string modsDir, string gameDir)
    {
        try
        {
            var mods = CountFiles(modsDir, "*.jar", skipPrefix: "originclient");
            var shaders = Directory.Exists(Path.Combine(gameDir, "shaderpacks"))
                ? Directory.EnumerateFileSystemEntries(Path.Combine(gameDir, "shaderpacks")).Count()
                : 0;
            long packBytes = 0;
            var packs = Path.Combine(gameDir, "resourcepacks");
            if (Directory.Exists(packs))
                foreach (var f in new DirectoryInfo(packs).EnumerateFiles("*.zip"))
                    packBytes += f.Length;
            return HeapPlan.IsHeavy(mods, shaders, packBytes);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static int CountFiles(string dir, string pattern, string skipPrefix) =>
        Directory.Exists(dir)
            ? Directory.EnumerateFiles(dir, pattern)
                .Count(f => !Path.GetFileName(f).StartsWith(skipPrefix, StringComparison.OrdinalIgnoreCase))
            : 0;
}

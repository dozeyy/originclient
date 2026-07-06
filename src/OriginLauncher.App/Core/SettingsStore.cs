using System.IO;
using System.Text.Json;
using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.Core;

public static class SettingsStore
{
    private static readonly string FilePath = Path.Combine(OriginPaths.Root, "settings.json");

    public static LauncherSettings Load()
    {
        if (!File.Exists(FilePath))
            return new LauncherSettings { RamMb = AutoDetectRamMb() };

        try
        {
            var json = File.ReadAllText(FilePath);
            return JsonSerializer.Deserialize<LauncherSettings>(json) ?? new LauncherSettings { RamMb = AutoDetectRamMb() };
        }
        catch (JsonException)
        {
            return new LauncherSettings { RamMb = AutoDetectRamMb() };
        }
    }

    // Half of total physical RAM, leaving headroom for the OS — scales with
    // real system specs instead of a fixed ceiling (a 32GB machine should get
    // more than an 8GB one). Floor of 2048 covers low-memory systems.
    private static int AutoDetectRamMb()
    {
        var totalMb = SystemInfo.GetTotalPhysicalMemoryMb();
        return Math.Max(2048, totalMb / 2);
    }

    public static void Save(LauncherSettings settings)
    {
        Directory.CreateDirectory(OriginPaths.Root);
        var json = JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(FilePath, json);
    }
}

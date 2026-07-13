using System.IO;
using System.Text.Json;
using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.Core;

// The ONLY way to persist a setting is Update(mutate): load fresh -> mutate ->
// save, under a lock. HomePage and SettingsPage are long-lived and each holds
// its own LauncherSettings snapshot for initial UI state; writing a whole
// cached snapshot back (the old public Save) meant one page's stale copy
// silently reverted fields the other page had just changed. Update writes only
// what the mutation touches, against what's actually on disk right now.
public static class SettingsStore
{
    private static readonly string FilePath = Path.Combine(OriginPaths.Root, "settings.json");
    private static readonly object Gate = new();

    public static void Update(Action<LauncherSettings> mutate)
    {
        lock (Gate)
        {
            var settings = Load();
            mutate(settings);
            Save(settings);
        }
    }

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

    private static void Save(LauncherSettings settings)
    {
        Directory.CreateDirectory(OriginPaths.Root);
        var json = JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(FilePath, json);
    }
}

using System.IO;
using System.Text.Json;
using OriginLauncher.App.Core.Launch;
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
        var settings = ReadFile();
        if (settings.RamManual == null)
        {
            // A settings.json from before Auto/Manual memory: settle it once and save the answer, so
            // a later change of RAM can never flip an old auto number into a "manual" one (RamPolicy.Migrate).
            lock (Gate)
            {
                settings = ReadFile();
                if (RamPolicy.Migrate(settings, SystemInfo.GetTotalPhysicalMemoryMb()))
                {
                    try { Save(settings); }
                    catch (Exception e) when (e is IOException or UnauthorizedAccessException) { /* retried on the next Load */ }
                }
            }
        }
        return settings;
    }

    private static LauncherSettings ReadFile()
    {
        if (!File.Exists(FilePath)) return Fresh();

        try
        {
            var json = File.ReadAllText(FilePath);
            return JsonSerializer.Deserialize<LauncherSettings>(json) ?? Fresh();
        }
        catch (Exception e) when (e is JsonException or IOException or UnauthorizedAccessException)
        {
            return Fresh();
        }
    }

    // New installs (and an unreadable file) start on Auto; RamMb holds the suggestion the Settings
    // slider shows. The rule lives in RamAdvice, shared with the installer's memory slider.
    private static LauncherSettings Fresh() => RamPolicy.Fresh(SystemInfo.GetTotalPhysicalMemoryMb());

    private static void Save(LauncherSettings settings)
    {
        Directory.CreateDirectory(OriginPaths.Root);
        var json = JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true });
        var temporaryPath = Path.Combine(
            OriginPaths.Root,
            $"settings.{Environment.ProcessId}.{Guid.NewGuid():N}.tmp");

        try
        {
            File.WriteAllText(temporaryPath, json);
            // Same-directory rename is atomic on Windows: a crash can leave
            // either complete version, never a half-written JSON document.
            File.Move(temporaryPath, FilePath, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }
}

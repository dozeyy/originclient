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
//
// Fail closed (like AccountStore): Update never saves defaults over a
// settings.json it could not read. A file another program holds for a moment
// (antivirus, backup, indexer) is retried briefly, then the write is refused
// with an IOException and the file stays exactly as it was. A file that reads
// but is not valid JSON can never be repaired by waiting: it is kept beside the
// real one as settings.json.damaged-<time> before a fresh one replaces it.
// Before 2026-11-03 either case silently reset every setting (profiles, memory,
// Java arguments, Settings Sync) on the next click.
public static class SettingsStore
{
    // The real file. Tests point it at a temporary file (and swap the memory
    // probe below), so nothing ever touches the player's real settings.
    internal static string FilePath { get; set; } = Path.Combine(OriginPaths.Root, "settings.json");

    internal static Func<int> TotalMemoryMb { get; set; } = SystemInfo.GetTotalPhysicalMemoryMb;

    // Load takes it too: an in-process read never holds the file while Update
    // renames the new copy over it (Windows refuses that rename).
    private static readonly object Gate = new();

    // A read another program blocks: three short waits (~0.2 s) before giving up.
    private static readonly int[] ReadWaitsMs = { 25, 60, 120 };

    private enum ReadState { Ok, Missing, Damaged, Unreadable }

    public static void Update(Action<LauncherSettings> mutate)
    {
        lock (Gate)
        {
            var (settings, state) = Read();
            if (state == ReadState.Unreadable)
                throw new IOException("settings.json is busy or can't be read right now; the change was not saved and the file was left as it was.");
            if (state == ReadState.Damaged) KeepDamagedCopy();
            RamPolicy.Migrate(settings, TotalMemoryMb());
            mutate(settings);
            Save(settings);
        }
    }

    /// <summary>
    /// The settings on disk now. A missing, damaged or unreadable file reads as a new install's defaults (for
    /// display only: Update re-reads and never writes those defaults over a file it couldn't read).
    /// </summary>
    public static LauncherSettings Load()
    {
        lock (Gate)
        {
            var (settings, state) = Read();
            // A settings.json from before Auto/Manual memory: settle it once and save the answer, so a later change
            // of RAM can never flip an old auto number into a "manual" one (RamPolicy.Migrate).
            if (state == ReadState.Ok && RamPolicy.Migrate(settings, TotalMemoryMb()))
            {
                try { Save(settings); }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException) { /* retried on the next Load */ }
            }
            return settings;
        }
    }

    private static (LauncherSettings Settings, ReadState State) Read()
    {
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                if (!File.Exists(FilePath)) return (Fresh(), ReadState.Missing);
                // Share everything: this read must never be the handle that makes another writer's rename fail.
                string json;
                using (var stream = new FileStream(FilePath, FileMode.Open, FileAccess.Read,
                           FileShare.ReadWrite | FileShare.Delete))
                using (var reader = new StreamReader(stream))
                    json = reader.ReadToEnd();
                var settings = JsonSerializer.Deserialize<LauncherSettings>(json);
                return settings == null ? (Fresh(), ReadState.Damaged) : (settings, ReadState.Ok);
            }
            catch (JsonException)
            {
                return (Fresh(), ReadState.Damaged);
            }
            catch (FileNotFoundException)
            {
                return (Fresh(), ReadState.Missing); // deleted between the check and the open
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                if (attempt >= ReadWaitsMs.Length) return (Fresh(), ReadState.Unreadable);
                Thread.Sleep(ReadWaitsMs[attempt]);
            }
        }
    }

    // Renamed aside, never deleted: the player (or support) can still recover it. Throws when it can't be moved,
    // which refuses the write rather than lose the file.
    private static void KeepDamagedCopy()
    {
        var aside = $"{FilePath}.damaged-{DateTime.Now:yyyyMMdd-HHmmss}";
        for (var n = 2; File.Exists(aside); n++) aside = $"{FilePath}.damaged-{DateTime.Now:yyyyMMdd-HHmmss}-{n}";
        File.Move(FilePath, aside);
    }

    // New installs (and an unreadable file, for display) start on Auto; RamMb holds the suggestion the Settings
    // slider shows. The rule lives in RamAdvice, shared with the installer's memory slider.
    private static LauncherSettings Fresh() => RamPolicy.Fresh(TotalMemoryMb());

    // Temp file + same-folder rename (a crash leaves either complete version), waiting out a reader that holds the
    // file for a moment instead of failing the write (Sync/AtomicFile).
    private static void Save(LauncherSettings settings) =>
        Sync.AtomicFile.WriteAllText(FilePath,
            JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true }));
}

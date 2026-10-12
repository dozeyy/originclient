using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using OriginLauncher.App.Core.GameFolders.Launcher;
using OriginLauncher.App.Core.Sync;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.Core;

// Settings Sync (Settings -> Origin Client -> "Sync settings across versions",
// LauncherSettings.SettingsSyncEnabled, off by default): the player's keybinds,
// video settings, HUD layout, mod settings and server list follow them to
// every Minecraft version. Every version already has its own isolated instance,
// so the launcher simply carries the settings files between instances around
// each launch — all versions get it with zero mod changes.
//
// Per ACCOUNT (2026-10-12): each account has its own game folder per version
// (Core/GameFolders), and its own store (OriginPaths.SettingsSyncStoreFor) — an
// account's folders sync among themselves and never with another account's.
// Inside one store everything is exactly as before: one instance per version.
//
// This class is the WHEN and WHERE; the merge rules are in
// Core/Sync/SettingsSyncEngine.cs and what syncs is in Core/Sync/SyncedFiles.cs.
//
// Hard rules (mandate 4, never broken):
//  * every entry point swallows and logs its own errors (logs/settings-sync.log)
//    — a sync problem means an unsynced launch, never a failed one;
//  * nothing is ever written into an instance whose game is running, and a
//    running instance is not read either (its files can be mid-write) — it is
//    harvested when its game exits;
//  * one lock: seeding, launches and exits never interleave their file work.
public static class SettingsSync
{
    private static readonly object Gate = new();

    // Games this launcher started, by game folder. Also backed by a file-lock
    // probe (IsGameRunning) for a game started before the launcher was restarted.
    private static readonly ConcurrentDictionary<string, Process> RunningGames = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>The switch was turned on: rebuild every account's store from its instances (newest file wins per key).</summary>
    public static void Seed()
    {
        foreach (var account in AccountFolders.AllKeys())
            Run($"seed {account}", () =>
            {
                var engine = new SettingsSyncEngine(new SyncStoreData(), Log);
                engine.Seed(Instances(account, skipRunning: true));
                SyncStoreFile.Save(OriginPaths.SettingsSyncStoreFor(account), engine.Store);
                Log($"{account}: seeded the store from every instance");
            });
    }

    /// <summary>
    /// Just before <paramref name="account"/>'s <paramref name="version"/> game
    /// starts: take in what the player changed in that account's other
    /// versions, then write it all into this one.
    /// </summary>
    public static void BeforeLaunch(string account, string version) => Run($"launch {account} {version}", () =>
    {
        var storePath = OriginPaths.SettingsSyncStoreFor(account);
        var store = SyncStoreFile.Load(storePath);
        var engine = new SettingsSyncEngine(store ?? new SyncStoreData(), Log);
        var instances = Instances(account, skipRunning: true);
        if (store == null)
        {
            // No store (first launch after the switch went on raced the seed,
            // or the file was lost): seed now — loses nothing.
            engine.Seed(instances);
        }
        else
        {
            foreach (var instance in instances)
                engine.Harvest(instance);
        }

        var target = instances.FirstOrDefault(i => i.Version == version);
        if (target != null)
            engine.Push(target);
        else
            Log($"{version}: not pushed (its game is already running, or the instance is missing)");

        SyncStoreFile.Save(storePath, engine.Store);
    });

    /// <summary>The launched game process started: remember it so its instance is left alone until it exits.</summary>
    public static void TrackGame(string account, string version, Process process) =>
        RunningGames[OriginPaths.GameDir(version, account)] = process;

    /// <summary>The game exited: take in what the player changed during that session.</summary>
    public static void AfterExit(string account, string version) => Run($"exit {account} {version}", () =>
    {
        RunningGames.TryRemove(OriginPaths.GameDir(version, account), out _);
        if (!SettingsStore.Load().SettingsSyncEnabled) return;
        var storePath = OriginPaths.SettingsSyncStoreFor(account);
        var store = SyncStoreFile.Load(storePath);
        if (store == null) return; // the next launch seeds
        var instance = Instances(account, skipRunning: true).FirstOrDefault(i => i.Version == version);
        if (instance == null) return;
        var engine = new SettingsSyncEngine(store, Log);
        engine.Harvest(instance);
        SyncStoreFile.Save(storePath, engine.Store);
    });

    // Every Origin-supported version for which this account has a game folder.
    private static List<SyncInstance> Instances(string account, bool skipRunning)
    {
        var instances = new List<SyncInstance>();
        foreach (var version in VersionManager.OriginSupportedVersions)
        {
            var directory = OriginPaths.GameDir(version, account);
            if (!Directory.Exists(directory)) continue;
            if (skipRunning && IsGameRunning(directory))
            {
                Log($"{account} {version}: game is running — left alone");
                continue;
            }
            instances.Add(new SyncInstance(version, directory));
        }
        return instances;
    }

    private static bool IsGameRunning(string instanceDirectory)
    {
        if (RunningGames.TryGetValue(instanceDirectory, out var process))
        {
            try
            {
                if (!process.HasExited) return true;
            }
            catch (Exception)
            {
                // handle gone: fall through to the lock probe
            }
            RunningGames.TryRemove(instanceDirectory, out _);
        }

        // A running game keeps logs/latest.log open; asking for it with no
        // sharing fails while any other process holds it. Catches a game the
        // launcher didn't start this session (e.g. the launcher was restarted).
        var latestLog = Path.Combine(instanceDirectory, "logs", "latest.log");
        if (!File.Exists(latestLog)) return false;
        try
        {
            using var probe = new FileStream(latestLog, FileMode.Open, FileAccess.Read, FileShare.None);
            return false;
        }
        catch (IOException)
        {
            return true;
        }
        catch (UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static void Run(string what, Action action)
    {
        try
        {
            lock (Gate)
                action();
        }
        catch (Exception ex)
        {
            Log($"{what} failed: {ex}");
        }
    }

    private static readonly object LogGate = new();

    private static void Log(string message)
    {
        try
        {
            lock (LogGate)
            {
                Directory.CreateDirectory(OriginPaths.Logs);
                var path = Path.Combine(OriginPaths.Logs, "settings-sync.log");
                // Keep it small: start over past 1 MB.
                if (File.Exists(path) && new FileInfo(path).Length > 1024 * 1024)
                    File.Delete(path);
                File.AppendAllText(path, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {message}{Environment.NewLine}");
            }
        }
        catch
        {
            // logging is best-effort; it must never throw into a launch
        }
    }
}

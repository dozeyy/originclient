using System.IO;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.Core.GameFolders.Launcher;

// The launcher side of per-account game folders: which account is selected,
// running the one-time migration, and creating (seeding) an account's folder
// the first time it plays a version. The rules themselves are the pure classes
// one folder up (InstanceLayout / InstanceMigration / AccountSeed), which the
// tests cover; this class only wires them to the launcher's real state.
public static class AccountFolders
{
    private static readonly object Gate = new();
    private static bool _migrationDone;

    /// <summary>
    /// The selected account's folder key; offline test mode's when no account is
    /// selected and that mode is on; null when nobody can play.
    /// </summary>
    public static string? SelectedKey()
    {
        var account = AccountStore.GetSelected(AccountStore.Load());
        if (account != null) return InstanceLayout.KeyFor(account.Id);
        return SettingsStore.Load().OfflineTestMode ? InstanceLayout.OfflineKey : null;
    }

    /// <summary>
    /// The game folder pages like Mods show for a version: the selected
    /// account's (offline test mode's when nobody is signed in).
    /// </summary>
    public static string SelectedGameDir(string version) =>
        OriginPaths.GameDir(version, SelectedKey() ?? InstanceLayout.OfflineKey);

    /// <summary>Every account game folder that exists for a version.</summary>
    public static IReadOnlyList<(string Key, string Dir)> GameDirs(string version)
    {
        var accounts = InstanceLayout.AccountsDir(OriginPaths.Instances, version);
        try
        {
            if (!Directory.Exists(accounts)) return Array.Empty<(string, string)>();
            return Directory.EnumerateDirectories(accounts)
                .Select(dir => (Key: Path.GetFileName(dir), Dir: dir))
                .Where(entry => InstanceLayout.IsAccountKey(entry.Key))
                .ToList();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return Array.Empty<(string, string)>();
        }
    }

    /// <summary>Every game folder of every Origin version, for settings that apply to all of them.</summary>
    public static IEnumerable<string> AllGameDirs() =>
        VersionManager.OriginSupportedVersions.SelectMany(v => GameDirs(v).Select(e => e.Dir));

    /// <summary>Every account that has a game folder for any Origin version.</summary>
    public static IReadOnlyList<string> AllKeys() =>
        VersionManager.OriginSupportedVersions
            .SelectMany(v => GameDirs(v).Select(e => e.Key))
            .Distinct(StringComparer.Ordinal)
            .ToList();

    /// <summary>
    /// The version whose game is open in one of this account's folders without
    /// this launcher having started it (the launcher was restarted while
    /// playing), or null. A running Minecraft holds its logs/latest.log open.
    /// </summary>
    public static string? VersionPlayingOutsideLauncher(string accountKey) =>
        VersionManager.OriginSupportedVersions.FirstOrDefault(version =>
            InstanceMigration.IsGameRunningIn(OriginPaths.GameDir(version, accountKey)));

    /// <summary>
    /// Moves the old one-folder-per-version game data into the account that
    /// inherits it: the one recorded in the marker, or — the first time —
    /// <paramref name="candidateKey"/> (the account selected right now). With
    /// no marker and no candidate (nobody signed in yet) it waits for the first
    /// account. Fast after the first complete pass; never throws.
    /// </summary>
    public static void EnsureMigrated(string? candidateKey)
    {
        lock (Gate)
        {
            if (_migrationDone) return;
            try
            {
                var key = InstanceMigration.ReadMarker(OriginPaths.AccountMigrationMarker);
                if (key == null)
                {
                    if (candidateKey == null) return;
                    key = candidateKey;
                    Directory.CreateDirectory(OriginPaths.Instances);
                    InstanceMigration.WriteMarker(OriginPaths.AccountMigrationMarker, key);
                    Log($"old game folders go to account {key}");
                }

                var complete = true;
                foreach (var version in VersionManager.OriginSupportedVersions)
                    complete &= InstanceMigration.MigrateVersion(OriginPaths.VersionRoot(version), key, Log);

                MoveLegacySyncStore(key);
                _migrationDone = complete;
            }
            catch (Exception ex)
            {
                Log($"migration paused: {ex}");
            }
        }
    }

    /// <summary>
    /// The account's game folder for a version, ready to launch into: migrated,
    /// and — the first time this account plays this version — seeded with the
    /// settings of the account that played it most recently. Never throws for
    /// a seeding problem (an unseeded folder still launches).
    /// </summary>
    public static string PrepareGameDir(string version, string accountKey)
    {
        EnsureMigrated(accountKey);
        lock (Gate)
        {
            var dir = OriginPaths.GameDir(version, accountKey);
            if (!Directory.Exists(dir))
            {
                var candidates = GameDirs(version).Where(e => e.Key != accountKey).Select(e => e.Dir).ToList();
                // Old-layout data not moved yet (its game was still running at
                // the last pass) still holds the player's latest settings.
                var root = OriginPaths.VersionRoot(version);
                if (InstanceMigration.NeedsMigration(root)) candidates.Add(root);
                var source = AccountSeed.PickSource(candidates);
                AccountSeed.Seed(dir, source, Log);
                Directory.CreateDirectory(dir);
                OriginClientConfigBridge.ApplyToNewFolder(dir);
                if (source != null) CopyOriginsOwnMods(source, dir);
            }
            return dir;
        }
    }

    // Origin's own jars (Fabric API and the perf/shader stack — never the
    // Origin client jar, which comes from the launcher itself, and never
    // the player's own mods) are copied from the seed source so a second
    // account's first launch needn't download them again. Launch provisioning
    // still checks and heals the set exactly as for any folder.
    private static void CopyOriginsOwnMods(string sourceGameDir, string gameDir)
    {
        try
        {
            var sourceMods = Path.Combine(sourceGameDir, "mods");
            if (!Directory.Exists(sourceMods)) return;
            var targetMods = Path.Combine(gameDir, "mods");
            Directory.CreateDirectory(targetMods);
            foreach (var jar in Directory.EnumerateFiles(sourceMods))
            {
                var name = Path.GetFileName(jar);
                if (!name.EndsWith(Mods.ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase)) continue;
                if (!Mods.ModManager.IsManaged(name)
                    || name.StartsWith("originclient", StringComparison.OrdinalIgnoreCase)) continue;
                var target = Path.Combine(targetMods, name);
                try
                {
                    if (!File.Exists(target)) File.Copy(jar, target);
                }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                {
                    // the launch downloads it instead
                }
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Log($"mods not copied: {ex.Message}");
        }
    }

    // The single pre-accounts Settings Sync store belongs to the account that
    // inherited the folders it describes. Renamed, never overwritten.
    private static void MoveLegacySyncStore(string key)
    {
        try
        {
            var legacy = OriginPaths.LegacySettingsSyncStore;
            var target = OriginPaths.SettingsSyncStoreFor(key);
            if (!File.Exists(legacy) || File.Exists(target)) return;
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Move(legacy, target);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Log($"settings-sync store not moved yet: {ex.Message}");
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
                var path = Path.Combine(OriginPaths.Logs, "game-folders.log");
                if (File.Exists(path) && new FileInfo(path).Length > 512 * 1024)
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

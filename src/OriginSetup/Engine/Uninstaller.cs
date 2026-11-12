using OriginLauncher.App.Core.Install;

namespace OriginSetup.Engine;

// Removes Origin: shortcuts, Start-with-Windows, the Add/Remove Programs
// entry and Origin's own files (UninstallPlan decides which) — and, only when
// the player ticks it, their Origin data (accounts, settings, game instances
// with their worlds).
public static class Uninstaller
{
    /// <summary>What was left behind (it is logged); <see cref="UninstallResult.Ok"/> when nothing.</summary>
    /// <param name="dataRoot">Origin's data folder; null = the real one. Tests pass their own.</param>
    /// <param name="runningExe">This uninstaller when it runs from inside the folder (the temp hand-off failed): it
    /// can't delete itself while running, so it is left for the exit script and not counted as left over.</param>
    public static UninstallResult Run(string dir, bool removeUserData, Action<double> report, string? dataRoot = null,
                                      string? runningExe = null)
    {
        dataRoot ??= Handoff.DataRoot;
        SetupLog.Write($"uninstall {dir} (removeUserData={removeUserData})");
        report(0.05);
        LauncherProcess.Close(TimeSpan.FromSeconds(5));

        report(0.2);
        Shortcuts.Delete(Shortcuts.StartMenuPath);
        Shortcuts.Delete(Shortcuts.DesktopPath);
        Shortcuts.DeleteLegacyGroup();
        try { InstallRegistry.SetStartWithWindows(false, ""); }
        catch (Exception ex) when (ex is UnauthorizedAccessException || ex is System.Security.SecurityException) { }
        // A registry refusal must not stop the files going (it used to throw out of here before anything was removed,
        // and the window then said "mostly removed" over an untouched install).
        try { Registration.Remove(); }
        catch (Exception ex) when (ex is UnauthorizedAccessException || ex is System.Security.SecurityException || ex is IOException)
        {
            SetupLog.Write("could not remove the Installed apps entry: " + ex.Message);
        }

        report(0.4);
        var plan = UninstallPlan.For(dir);
        SetupLog.Write($"install folder: {plan.Scope} ({plan.Reason})");
        bool ok = plan.Execute(runningExe);

        report(0.75);
        bool dataOk = true, dataUnchecked = false;
        IReadOnlyList<string> gamesOpen = Array.Empty<string>();
        if (removeUserData)
        {
            // A game still open would be writing into the worlds being deleted, and Windows stops the delete at the
            // first file it holds: some worlds half gone. Keep the data whole instead and say why (the window asks
            // the player to close the game before it ever gets here; this covers the silent uninstall and a game
            // started in between).
            var games = RunningGames.Check(dataRoot);
            gamesOpen = games.Open;
            if (games.KeepData)
            {
                SetupLog.Write(games.Open.Count > 0
                    ? "data kept: Minecraft still open (" + string.Join(", ", games.Open) + ")"
                    : "data kept: couldn't check every game folder for a running game");
                dataOk = false;
                dataUnchecked = games.Open.Count == 0;
            }
            else
            {
                dataOk = FileOps.TryDeleteTree(dataRoot);
            }
        }

        var result = new UninstallResult(installFolderLeft: !ok, dataLeft: !dataOk, gamesOpen) { DataUnchecked = dataUnchecked };
        SetupLog.Write(result.Ok ? "uninstall complete" : "uninstall finished with leftovers");
        report(1);
        return result;
    }
}

/// <summary>What Uninstaller.Run could not remove, so the Problem page names the right folder.</summary>
public sealed class UninstallResult
{
    public UninstallResult(bool installFolderLeft, bool dataLeft, IReadOnlyList<string>? gamesOpen = null)
    {
        InstallFolderLeft = installFolderLeft;
        DataLeft = dataLeft;
        GamesOpen = gamesOpen ?? Array.Empty<string>();
    }

    /// <summary>Some of Origin's own files (the install folder) are still there.</summary>
    public bool InstallFolderLeft { get; }
    /// <summary>The player asked to delete their data and some or all of it is still there.</summary>
    public bool DataLeft { get; }
    /// <summary>The versions whose game was open, so the data was not touched at all (empty otherwise).</summary>
    public IReadOnlyList<string> GamesOpen { get; }
    /// <summary>The data was not touched because some game folder couldn't be checked for a running game.</summary>
    public bool DataUnchecked { get; set; }
    public bool Ok => !InstallFolderLeft && !DataLeft;

    /// <summary>
    /// The Problem page's body: exactly what was left, in the player's words. The folder line under it is the data
    /// folder whenever <see cref="DataLeft"/>, otherwise the install folder.
    /// </summary>
    public string ProblemText(string installDir)
    {
        if (GamesOpen.Count > 0)
            return $"Origin is removed, but your worlds, settings and accounts were kept because Minecraft {string.Join(", ", GamesOpen)} was still open."
                   + " Close the game, then delete this folder yourself if you still want them gone:";
        if (DataUnchecked)
            return "Origin is removed, but your worlds, settings and accounts were kept: Origin couldn't check whether Minecraft is still open."
                   + " Close every Minecraft window, then delete this folder yourself if you still want them gone:";
        if (DataLeft && InstallFolderLeft)
            return $"Some files were in use and couldn't be deleted (Minecraft may still be running). Origin's own folder ({installDir}) and part of your data are left. Your data folder:";
        if (DataLeft)
            return "Origin is removed, but some of your data was in use and couldn't be deleted (Minecraft may still be running). You can delete the rest yourself:";
        return "Some files were in use and couldn't be deleted (Minecraft may still be running). You can delete the folder yourself:";
    }
}

public enum UninstallScope
{
    /// <summary>Not provably Origin's: nothing in the folder is touched.</summary>
    Nothing,
    /// <summary>Only the files the manifest lists (then folders left empty).</summary>
    ListedFiles,
    /// <summary>The folder is Origin's own: all of it.</summary>
    WholeFolder,
}

// What uninstall may delete from the install folder. Pure rules, unit-tested (UninstallTests).
// The proof is origin-install.json (InstallManifest), never a file or folder name another program
// could share. Never the data folder, a folder around it or inside it, a drive root or a
// well-known folder (Documents, Downloads, AppData, ...), whatever the manifest says.
public sealed class UninstallPlan
{
    private UninstallPlan(string dir, UninstallScope scope, IReadOnlyList<string> files, string reason)
    {
        Dir = dir;
        Scope = scope;
        Files = files;
        Reason = reason;
    }

    public string Dir { get; }
    public UninstallScope Scope { get; }
    /// <summary>Full paths, all inside <see cref="Dir"/> (ListedFiles only).</summary>
    public IReadOnlyList<string> Files { get; }
    public string Reason { get; }

    /// <param name="dataRoot">Origin's data folder; null = the real one. Tests pass their own.</param>
    public static UninstallPlan For(string dir, string? dataRoot = null)
    {
        UninstallPlan Keep(string why) => new UninstallPlan(dir, UninstallScope.Nothing, Array.Empty<string>(), why);
        if (string.IsNullOrWhiteSpace(dir) || !Directory.Exists(dir)) return Keep("no such folder");
        if (InstallLocation.IsWellKnownFolder(dir)) return Keep("a drive or personal/system folder, never Origin's own");
        bool aroundData = InstallLocation.Overlaps(dir, dataRoot ?? Handoff.DataRoot);

        var manifest = InstallManifest.Read(dir);
        if (manifest == null)
        {
            // Installed before Setup wrote manifests (Setups before origin-install.json, or the old Inno installer).
            // The one shape those always made themselves: a folder named OriginLauncher with the launcher in it.
            return !aroundData && IsLegacyOwnFolder(dir)
                ? new UninstallPlan(dir, UninstallScope.WholeFolder, Array.Empty<string>(), "an older Setup's own OriginLauncher folder")
                : Keep(aroundData ? "this is (or holds) the data folder, and there is no install record"
                                  : "no install record, and not a folder an older Setup made for itself");
        }
        if (manifest.OwnsFolder && !aroundData)
            return new UninstallPlan(dir, UninstallScope.WholeFolder, Array.Empty<string>(), "Setup created this folder");
        var files = manifest.Files.Select(f => InstallManifest.PathIn(dir, f)).Where(p => p != null).Select(p => p!).ToList();
        return new UninstallPlan(dir, UninstallScope.ListedFiles, files, $"{files.Count} files Setup put here");
    }

    /// <summary>
    /// What older Setups (and the Inno installer) always made for themselves: a folder named
    /// OriginLauncher that holds the launcher. (Picking a busy folder made them create one.)
    /// </summary>
    public static bool IsLegacyOwnFolder(string dir) =>
        string.Equals(Path.GetFileName(dir.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)),
                      InstallLocation.FolderName, StringComparison.OrdinalIgnoreCase)
        && File.Exists(Path.Combine(dir, InstallLocation.LauncherExe));

    /// <summary>Deletes what the plan covers. False when something could not be removed (logged).</summary>
    /// <param name="keep">The running uninstaller when it sits inside <see cref="Dir"/>: left in place (the exit
    /// script removes it and the then-empty folder) and not a failure.</param>
    public bool Execute(string? keep = null)
    {
        keep = keep != null && IsInside(keep, Dir) ? Path.GetFullPath(keep) : null;
        switch (Scope)
        {
            case UninstallScope.WholeFolder:
                return keep == null ? FileOps.TryDeleteTree(Dir) : DeleteTreeExcept(Dir, keep);
            case UninstallScope.ListedFiles:
                bool ok = true;
                foreach (var file in Files.Concat(new[] { Path.Combine(Dir, InstallManifest.FileName) }))
                    if (keep == null || !SamePath(file, keep)) ok &= TryDeleteFile(file);
                ok &= FileOps.TryDeleteTree(Path.Combine(Dir, Installer.StagingFolder));
                // Then the folders those files were in, deepest first, if nothing else is left in them.
                var root = Path.GetFullPath(Dir).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
                var folders = Files.Select(f => Path.GetDirectoryName(f)).Where(d => d != null)
                    .SelectMany(d => Ancestors(d!, root)).Distinct(StringComparer.OrdinalIgnoreCase)
                    .OrderByDescending(d => d.Length);
                foreach (var folder in folders) FileOps.DeleteIfEmpty(folder);
                FileOps.DeleteIfEmpty(Dir);
                return ok;
            default:
                return true;
        }
    }

    // Everything under dir except the one file keep (and the folders leading to it).
    private static bool DeleteTreeExcept(string dir, string keep)
    {
        try
        {
            bool ok = true;
            foreach (var sub in Directory.GetDirectories(dir))
                ok &= IsInside(keep, sub) ? DeleteTreeExcept(sub, keep) : FileOps.TryDeleteTree(sub);
            foreach (var file in Directory.GetFiles(dir))
                if (!SamePath(file, keep)) ok &= TryDeleteFile(file);
            return ok;
        }
        catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException)
        {
            SetupLog.Write($"could not fully remove {dir}: {ex.Message}");
            return false;
        }
    }

    private static bool SamePath(string a, string b) =>
        string.Equals(Path.GetFullPath(a), Path.GetFullPath(b), StringComparison.OrdinalIgnoreCase);

    private static bool IsInside(string path, string folder)
    {
        var root = Path.GetFullPath(folder).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) + Path.DirectorySeparatorChar;
        return Path.GetFullPath(path).StartsWith(root, StringComparison.OrdinalIgnoreCase);
    }

    // The folder and its parents, up to (not including) the install folder.
    private static IEnumerable<string> Ancestors(string folder, string root)
    {
        for (var d = folder; d != null && d.Length > root.Length
             && d.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase); d = Path.GetDirectoryName(d))
            yield return d;
    }

    private static bool TryDeleteFile(string path)
    {
        try
        {
            FileOps.Retry(() =>
            {
                if (!File.Exists(path)) return;
                File.SetAttributes(path, FileAttributes.Normal);
                File.Delete(path);
            }, attempts: 5);
            return true;
        }
        catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException)
        {
            SetupLog.Write($"could not remove {path}: {ex.Message}");
            return false;
        }
    }
}

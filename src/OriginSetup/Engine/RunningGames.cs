namespace OriginSetup.Engine;

// "Is a Minecraft game still open on Origin's data?" for the uninstaller's "Also delete my worlds" box.
// Closing the launcher never closes a game (DECISIONS 2026-07-09), so a game can still be writing into its world
// while uninstall deletes the data folder: Windows then stops the delete at the first file the game holds, and
// some worlds are left half deleted. Asked first, the player can close the game; the data stays whole either way.
//
// The test is the launcher's own (Core/GameFolders/GameRunningProbe, Windows path): a running game keeps
// logs/latest.log open, so opening it with FileShare.None fails. Game folders are
// instances/<version>/accounts/<account>/ (Core/GameFolders/InstanceLayout), plus instances/<version>/ itself from
// before per-account folders.
public static class RunningGames
{
    /// <summary>What the check found: the versions with a game open, and the folders it could not look into.</summary>
    public sealed class Result
    {
        public Result(IReadOnlyList<string> open, IReadOnlyList<string> unreadable)
        {
            Open = open;
            Unchecked = unreadable;
        }

        /// <summary>The Minecraft versions with a game open, each once, in name order.</summary>
        public IReadOnlyList<string> Open { get; }

        /// <summary>Folders that couldn't be listed or probed: a game may be running there.</summary>
        public IReadOnlyList<string> Unchecked { get; }

        /// <summary>
        /// The data must stay: a game is open, or one might be (a folder that couldn't be checked). Failing open here
        /// would delete worlds under a running game, the very thing this check exists to stop.
        /// </summary>
        public bool KeepData => Open.Count > 0 || Unchecked.Count > 0;
    }

    /// <summary>The Minecraft versions with a game open in <paramref name="dataRoot"/>, each once, in name order.</summary>
    public static IReadOnlyList<string> In(string dataRoot) => Check(dataRoot).Open;

    /// <summary>
    /// Looks into every game folder under <paramref name="dataRoot"/>. Each folder is checked on its own: one that
    /// can't be listed is recorded in <see cref="Result.Unchecked"/> and the scan goes on with the rest.
    /// </summary>
    public static Result Check(string dataRoot)
    {
        var open = new SortedSet<string>(StringComparer.OrdinalIgnoreCase);
        var unreadable = new List<string>();
        var instances = Path.Combine(dataRoot, "instances");
        if (!Directory.Exists(instances)) return new Result(Array.Empty<string>(), Array.Empty<string>());

        var versionDirs = ListOrNote(instances, unreadable);
        foreach (var versionDir in versionDirs)
        {
            var version = Path.GetFileName(versionDir);
            if (LatestLogIsHeld(versionDir)) open.Add(version);
            var accounts = Path.Combine(versionDir, "accounts");
            if (!Directory.Exists(accounts)) continue;
            foreach (var gameDir in ListOrNote(accounts, unreadable))
                if (LatestLogIsHeld(gameDir)) open.Add(version);
        }
        foreach (var folder in unreadable) SetupLog.Write("running game check couldn't look into " + folder);
        return new Result(open.ToList(), unreadable);
    }

    // The subfolders of one folder, or none (and the folder noted as unreadable) when it can't be listed.
    private static List<string> ListOrNote(string folder, List<string> unreadable)
    {
        try
        {
            return Directory.EnumerateDirectories(folder).ToList();
        }
        catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException || ex is System.Security.SecurityException)
        {
            unreadable.Add(folder);
            return new List<string>();
        }
    }

    /// <summary>"Close Minecraft (1.21.1 and 1.20.1) first" in plain words.</summary>
    public static string CloseFirstMessage(IReadOnlyList<string> versions) =>
        versions.Count == 0 ? ""
        : versions.Count == 1
            ? $"Minecraft {versions[0]} is still open. Close the game first, so none of your worlds is deleted while it's saving."
            : $"Minecraft {Join(versions)} are still open. Close the games first, so none of your worlds is deleted while it's saving.";

    private static string Join(IReadOnlyList<string> v) =>
        v.Count == 1 ? v[0] : string.Join(", ", v.Take(v.Count - 1)) + " and " + v[v.Count - 1];

    private static bool LatestLogIsHeld(string gameDir)
    {
        var latestLog = Path.Combine(gameDir, "logs", "latest.log");
        if (!File.Exists(latestLog)) return false;
        try
        {
            using (new FileStream(latestLog, FileMode.Open, FileAccess.Read, FileShare.None)) return false;
        }
        catch (Exception ex) when (ex is FileNotFoundException || ex is DirectoryNotFoundException)
        {
            return false; // gone between the check and the open: the game just closed
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
}

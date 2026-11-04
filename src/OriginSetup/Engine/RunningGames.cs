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
    /// <summary>The Minecraft versions with a game open in <paramref name="dataRoot"/>, each once, in name order.</summary>
    public static IReadOnlyList<string> In(string dataRoot)
    {
        var open = new SortedSet<string>(StringComparer.OrdinalIgnoreCase);
        var instances = Path.Combine(dataRoot, "instances");
        try
        {
            if (!Directory.Exists(instances)) return Array.Empty<string>();
            foreach (var versionDir in Directory.EnumerateDirectories(instances))
            {
                var version = Path.GetFileName(versionDir);
                if (LatestLogIsHeld(versionDir)) open.Add(version);
                var accounts = Path.Combine(versionDir, "accounts");
                if (!Directory.Exists(accounts)) continue;
                foreach (var gameDir in Directory.EnumerateDirectories(accounts))
                    if (LatestLogIsHeld(gameDir)) open.Add(version);
            }
        }
        catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException)
        {
            // A folder that can't even be listed: say nothing is open; the delete then keeps whatever it can't
            // remove and the Problem page names the data folder (Uninstaller.Run).
            SetupLog.Write("running game check failed: " + ex.Message);
        }
        return open.ToList();
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

namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// Which of the game's console lines are timing marks for the launch-time harness (LaunchTimingHarness,
/// launch-time.yml). Pure so it is unit tested and so Fabric and Forge versions are read by one rule set:
///   Fabric  "Loading Minecraft 1.21.1 with Fabric Loader 0.17.3"  -> game-fabric-start
///           "Loading 9 mods:"                                      -> game-mods-listed
///   Forge   "Forge Mod Loader version 11.15.1.2318 for Minecraft 1.8.9 loading" -> game-forge-start
///           "Forge Mod Loader has identified 5 mods to load"       -> game-mods-listed
/// 1.8.9 runs Forge and never prints Fabric's lines, so before the Forge rows a 1.8.9 launch showed no game mark at
/// all and "did the game reach its mod list" could not be asked of it. game-mods-listed means the same on both.
/// </summary>
public static class GameLogMarks
{
    private const string BootPrefix = "[OriginBoot] mark ";

    /// <summary>The marks this console line is, in order; empty for a line that is none.</summary>
    public static IReadOnlyList<string> Of(string? line)
    {
        if (string.IsNullOrEmpty(line)) return [];
        var marks = new List<string>(2);
        if (line.Contains("Loading Minecraft") && line.Contains("Fabric Loader")) marks.Add("game-fabric-start");
        if (line.Contains("Forge Mod Loader version")) marks.Add("game-forge-start");
        if ((line.Contains("Loading ") && line.Contains(" mods:"))
            || (line.Contains("Forge Mod Loader has identified") && line.Contains(" mods to load")))
            marks.Add("game-mods-listed");
        var boot = line.IndexOf(BootPrefix, StringComparison.Ordinal);
        if (boot >= 0) marks.Add("game-" + line[(boot + BootPrefix.Length)..].Split(' ')[0]);
        if (line.Contains("Backend library:")) marks.Add(WindowMark);
        return marks;
    }

    /// <summary>The line the game prints once its window and sound backend are up: the harness stops waiting here.</summary>
    public const string WindowMark = "game-window";
}

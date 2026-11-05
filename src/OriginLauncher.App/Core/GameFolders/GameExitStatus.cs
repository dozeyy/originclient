namespace OriginLauncher.App.Core.GameFolders;

/// <summary>
/// The account's Home status line after its game stops (all three launchers use this one table).
/// A non-zero exit code is NOT a crash by itself: Minecraft quits with odd codes at shutdown, Task Manager kills it,
/// and CrashEngine.Decide counts "Minecraft logged a normal quit" and "stopped in game with no sign of a crash" as
/// normal closes. So the moment a game that was up exits the line goes neutral ("Ready", no code), and only the
/// crash verdict may turn it red. The exit code lives in the launch log, never on the line.
/// </summary>
public static class GameExitStatus
{
    public static readonly LaunchNote Ready = new("Ready", LaunchTone.Neutral);
    public static readonly LaunchNote CrashedInGame = new("Minecraft crashed", LaunchTone.Error);
    // Plain words only: the crash window that opens with it carries the exit code, the log and "Open logs folder"
    // (bug run 5, JL-9: the old line printed "exit code -1073740791 - log saved to C:\Users\..." under Play).
    public static readonly LaunchNote CrashedWhileStarting = new("Minecraft crashed while starting", LaunchTone.Error);

    /// <summary>The line the moment a game that reached its window exits, before any verdict.</summary>
    public static LaunchNote OnExit() => Ready;

    /// <summary>
    /// The line once CrashEngine decided. Never an exit code or a path: the crash window shows those.
    /// </summary>
    public static LaunchNote AfterVerdict(bool isCrash, bool crashedWhileStarting) =>
        !isCrash ? Ready
        : crashedWhileStarting ? CrashedWhileStarting
        : CrashedInGame;
}

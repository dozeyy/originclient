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

    /// <summary>The line the moment a game that reached its window exits, before any verdict.</summary>
    public static LaunchNote OnExit() => Ready;

    /// <summary>
    /// The line once CrashEngine decided. Null = leave the line alone: a crash while starting keeps the boot watcher's
    /// own crash line, which names the log.
    /// </summary>
    public static LaunchNote? AfterVerdict(bool isCrash, bool crashedWhileStarting) =>
        !isCrash ? Ready
        : crashedWhileStarting ? null
        : CrashedInGame;
}

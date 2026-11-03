namespace OriginLauncher.App.Core.Updates;

// When the self-update may start a background download, and which versions' files must stay on disk. Pure (versions,
// flags and the clock in; a decision out) so the tests can drive the races; UpdateService asks it under lock (Gate).
//
// The rules, and why:
//  - Installing: once the badge click has taken (or joined) a version, nothing else downloads and no stage is touched
//    until that click fails. A second fetch of the same version would delete and rebuild the very stage the click is
//    verifying or the swap script is copying; a newer release's fetch would clean that stage away as stale.
//  - A game this launcher started is running (or starting): no background download, so it never costs ping or frames.
//    The poll after the game closes starts it. The click path is never held back by this.
//  - Ready or already downloading: nothing to do. One download at a time: an older background download is superseded
//    by a newer release; a download the click waits on, or one of a newer release, is never replaced.
//  - An ordinary background failure backs off (BackoffVersion until BackoffUntilUtc); the quiet retry after a failed
//    check has the gate's own back-off instead.
public static class UpdateFetchRules
{
    public enum Decision
    {
        /// <summary>This version is already ready, downloading or being installed.</summary>
        AlreadyHandled,
        /// <summary>Not now (a click is installing, a game is running, a newer or clicked download runs, back-off).</summary>
        NotNow,
        /// <summary>Start it (cancelling <see cref="State.Running"/> when there is one: it is older and nobody waits on it).</summary>
        Start
    }

    public sealed record State(
        Version? Installing,
        Version? Ready,
        Version? Running,
        bool RunningJoined,
        Version? BackoffVersion,
        DateTime BackoffUntilUtc);

    public static Decision Background(Version wanted, bool quiet, bool gameRunning, State s, DateTime nowUtc)
    {
        if (s.Installing != null) return Same(s.Installing, wanted) ? Decision.AlreadyHandled : Decision.NotNow;
        if (Same(s.Ready, wanted) || Same(s.Running, wanted)) return Decision.AlreadyHandled;
        if (gameRunning) return Decision.NotNow;
        if (s.Running != null && (s.RunningJoined || UpdateGate.Normalize(s.Running) > UpdateGate.Normalize(wanted))) return Decision.NotNow;
        if (!quiet && Same(s.BackoffVersion, wanted) && nowUtc < s.BackoffUntilUtc) return Decision.NotNow;
        return Decision.Start;
    }

    /// <summary>The versions whose downloads and stages a cleanup must leave alone.</summary>
    public static Version?[] Keep(Version? fetching, Version? offered, State s) =>
        [fetching, offered, s.Running, s.Ready, s.Installing];

    public static bool Same(Version? a, Version? b) =>
        a != null && b != null && UpdateGate.Normalize(a) == UpdateGate.Normalize(b);
}

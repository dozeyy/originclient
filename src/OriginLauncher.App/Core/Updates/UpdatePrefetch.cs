namespace OriginLauncher.App.Core.Updates;

// The background download's state machine (DECISIONS 2026-11-03: "Launcher updates download in the background the
// moment they appear"). Pure: versions, flags and the clock in, a decision out, so the tests can drive every race.
// UpdateService owns one and asks it under lock (Gate); the downloading, staging and swapping stay in UpdateService.
//
//   Idle --TryStart--> Downloading --Staged--> Staged --BeginInstall--> (installing) --swap--> process gone
//                         |  Failed: back to Idle with a back-off (2 min doubling to 30 min); Cancelled: Idle, no back-off
//
// The rules, and why:
//  - Installing: once the Update click (or the restart prompt) takes a version, nothing else downloads and no cleanup
//    touches that version's files until the click fails. A second fetch would delete and rebuild the very stage the
//    swap script is about to copy; a newer release's cleanup would remove it as stale.
//  - Not while a game this launcher started is starting or running (no download may cost ping or frames), not offline,
//    not on a metered connection: the next poll simply asks again. The click is never held back by any of these.
//  - One background download at a time, for the offered version only: a newer offer supersedes an older one's download
//    or stage (the caller cancels/deletes it), unless the click is installing.
//  - A click while the same version downloads JOINS that download (never a second one); a click once it is staged only
//    re-checks the stage and swaps. Anything else (no background download, it failed, the stage changed) is the click's
//    own download, exactly as before: delta first, full verified zip as the fallback.
//  - The swap itself waits while a game this launcher started is running (closing the launcher would leave that game
//    without its crash window and settings sync).
public sealed class UpdatePrefetch
{
    public static readonly TimeSpan FirstRetry = TimeSpan.FromMinutes(2);
    public static readonly TimeSpan MaxRetry = TimeSpan.FromMinutes(30);

    public enum Phase { Idle, Downloading, Staged }

    /// <summary>What the machine says about a background download of the offered version.</summary>
    public enum Start
    {
        /// <summary>Start it now (the phase is Downloading for that version).</summary>
        Go,
        /// <summary>Not now: installing another version, a game is running, offline, metered, or backing off.</summary>
        NotNow,
        /// <summary>It is already downloading, staged or being installed.</summary>
        AlreadyHandled
    }

    /// <summary>What the Update click does for its version.</summary>
    public enum Click
    {
        /// <summary>A background download staged it: re-check the stage, then swap.</summary>
        SwapStaged,
        /// <summary>A background download of it is running: wait for that one (with its progress), never start a second.</summary>
        JoinDownload,
        /// <summary>Nothing usable in the background: the click downloads it itself (delta first, then the full zip).</summary>
        DownloadNow
    }

    public enum SwapStep { Swap, WaitForGame }

    /// <summary>The network and game state a background start depends on (read by the caller outside any lock).</summary>
    public readonly record struct Conditions(bool GameRunning, bool Online, bool Metered);

    public Phase Current { get; private set; }

    /// <summary>The version downloading or staged (null when Idle).</summary>
    public Version? Version { get; private set; }

    /// <summary>The version the click took, from BeginInstall until InstallFailed (a success quits the process).</summary>
    public Version? Installing { get; private set; }

    /// <summary>True when the click waits on the running background download (it then owns the outcome).</summary>
    public bool Joined { get; private set; }

    private Version? _failedVersion;
    private int _failures;
    private DateTime _retryAtUtc;

    /// <summary>
    /// A poll found <paramref name="offered"/> on offer: may its background download start? <paramref name="dropped"/> is
    /// an older version's download or stage this offer supersedes; the caller cancels that download and deletes its stage.
    /// </summary>
    public Start TryStart(Version offered, Conditions c, DateTime nowUtc, out Version? dropped)
    {
        dropped = null;
        if (Installing != null) return Same(Installing, offered) ? Start.AlreadyHandled : Start.NotNow;
        if (Current != Phase.Idle)
        {
            if (Same(Version, offered)) return Start.AlreadyHandled;
            if (UpdateGate.Normalize(Version!) > UpdateGate.Normalize(offered)) return Start.NotNow; // never step backwards
            dropped = Version;
            Reset();
        }
        if (c.GameRunning || !c.Online || c.Metered) return Start.NotNow;
        if (Same(_failedVersion, offered) && nowUtc < _retryAtUtc) return Start.NotNow;
        Current = Phase.Downloading;
        Version = offered;
        Joined = false;
        return Start.Go;
    }

    /// <summary>
    /// The background download of <paramref name="version"/> finished and its stage is verified. False when it is no
    /// longer wanted (superseded meanwhile): the caller deletes that stage.
    /// </summary>
    public bool Staged(Version version)
    {
        if (Current != Phase.Downloading || !Same(Version, version)) return false;
        Current = Phase.Staged;
        Joined = false;
        if (Same(_failedVersion, version)) { _failedVersion = null; _failures = 0; }
        return true;
    }

    /// <summary>
    /// The background download of <paramref name="version"/> failed (<paramref name="cancelled"/>: stopped on purpose,
    /// for exit, a game start or a newer release). Back to Idle; an ordinary failure backs off and returns the wait.
    /// A failure the click had joined is the click's to count, so it never backs the background off.
    /// </summary>
    public TimeSpan? Failed(Version version, bool cancelled, DateTime nowUtc)
    {
        var joined = Joined && Same(Version, version);
        if (Current == Phase.Downloading && Same(Version, version)) Reset();
        if (cancelled || joined) return null;
        _failures = Same(_failedVersion, version) ? _failures + 1 : 1;
        _failedVersion = version;
        var wait = TimeSpan.FromMinutes(Math.Min(FirstRetry.TotalMinutes * Math.Pow(2, _failures - 1), MaxRetry.TotalMinutes));
        _retryAtUtc = nowUtc + wait;
        return wait;
    }

    /// <summary>
    /// The click (or the restart prompt) takes <paramref name="version"/>. From now on no background download starts
    /// and no cleanup touches it. <paramref name="dropped"/> is another version's download/stage the caller cancels.
    /// </summary>
    public Click BeginInstall(Version version, out Version? dropped)
    {
        dropped = null;
        Installing = version;
        if (Current != Phase.Idle && !Same(Version, version))
        {
            dropped = Version;
            Reset();
        }
        switch (Current)
        {
            case Phase.Staged:
                return Click.SwapStaged;
            case Phase.Downloading:
                Joined = true;
                return Click.JoinDownload;
            default:
                return Click.DownloadNow;
        }
    }

    /// <summary>
    /// The click's stage check failed or the joined download failed: the click stages it itself. The machine forgets
    /// the background stage (the click's own download rebuilds that folder).
    /// </summary>
    public void FallBackToClickDownload(Version version)
    {
        if (Same(Version, version)) Reset();
    }

    /// <summary>The last step before the swap: wait while a game this launcher started is starting or running.</summary>
    public static SwapStep BeforeSwap(bool gameRunning) => gameRunning ? SwapStep.WaitForGame : SwapStep.Swap;

    public enum PreSwap { Swap, Restage, Fail }

    /// <summary>How many times the click stages before it gives up on a stage that keeps changing on disk.</summary>
    public const int MaxStageAttempts = 2;

    /// <summary>
    /// After the wait for games, right before the swap, the stage is hashed again (<paramref name="stageMatches"/>). A
    /// match swaps. A mismatch makes the click download and stage it afresh, once; a second mismatch in a row is an
    /// ordinary failure (the gate counts it; it never blocks Play for good).
    /// </summary>
    public static PreSwap CheckBeforeSwap(bool stageMatches, int attempt) =>
        stageMatches ? PreSwap.Swap : attempt < MaxStageAttempts ? PreSwap.Restage : PreSwap.Fail;

    /// <summary>
    /// May an unwanted stage of <paramref name="version"/> be deleted now? Asked again, under the stage's own lock, right
    /// before the delete: a click (or a newer background download of it) may have taken that version since the delete
    /// was decided, and its stage must then stay.
    /// </summary>
    public bool MayDeleteStage(Version version) => !Keep().Any(v => Same(v, version));

    /// <summary>
    /// The player pressed Cancel while the swap waited for Minecraft to close: the click lets go of
    /// <paramref name="version"/>, and its verified stage stays as if the background download had staged it, so the next
    /// click (or the restart prompt) only re-checks it and swaps. Never a failure: no back-off, nothing deleted.
    /// </summary>
    public void InstallPostponed(Version version)
    {
        if (Same(Installing, version)) Installing = null;
        Current = Phase.Staged;
        Version = version;
        Joined = false;
    }

    /// <summary>The click failed (it did not quit): back to Idle so a later poll or click can try again.</summary>
    public void InstallFailed(Version version)
    {
        if (Same(Installing, version)) Installing = null;
        if (Same(Version, version)) Reset();
    }

    /// <summary>
    /// The offer of <paramref name="version"/> was withdrawn (its check failed): forget its background download or
    /// stage unless the click is installing it. True when the caller should delete that stage.
    /// </summary>
    public bool Withdrawn(Version version)
    {
        if (Same(Installing, version) || !Same(Version, version) || Current == Phase.Idle) return false;
        Reset();
        return true;
    }

    /// <summary>The versions whose downloads and stages a cleanup must leave alone.</summary>
    public Version?[] Keep() => [Version, Installing];

    private void Reset()
    {
        Current = Phase.Idle;
        Version = null;
        Joined = false;
    }

    public static bool Same(Version? a, Version? b) =>
        a != null && b != null && UpdateGate.Normalize(a) == UpdateGate.Normalize(b);

    // ---- metered connections (each OS reads its own; these are the pure halves) ----

    // NLM_CONNECTION_COST flags (Windows netlistmgr.h): FIXED 0x2 and VARIABLE 0x4 are a metered plan, OVERDATALIMIT
    // 0x10000 and ROAMING 0x40000 always cost. UNRESTRICTED 0x1 (and UNKNOWN 0) are free to use.
    private const uint CostFixed = 0x2, CostVariable = 0x4, CostOverDataLimit = 0x10000, CostRoaming = 0x40000;

    /// <summary>Windows: is a connection with this INetworkCostManager cost metered?</summary>
    public static bool IsMeteredCost(uint cost) => (cost & (CostFixed | CostVariable | CostOverDataLimit | CostRoaming)) != 0;

    /// <summary>
    /// Linux: NetworkManager's Metered property as `gdbus call ... Properties.Get` prints it, e.g. "(&lt;uint32 4&gt;,)".
    /// NMMetered: 1 yes, 3 guess-yes (metered); 0 unknown, 2 no, 4 guess-no (not). Anything unreadable = not metered.
    /// </summary>
    public static bool IsNetworkManagerMetered(string? gdbusOutput)
    {
        if (string.IsNullOrWhiteSpace(gdbusOutput)) return false;
        var at = gdbusOutput.IndexOf("uint32", StringComparison.Ordinal);
        if (at < 0) return false;
        var digits = new string(gdbusOutput[(at + "uint32".Length)..].SkipWhile(char.IsWhiteSpace).TakeWhile(char.IsDigit).ToArray());
        return uint.TryParse(digits, out var value) && value is 1 or 3;
    }
}
